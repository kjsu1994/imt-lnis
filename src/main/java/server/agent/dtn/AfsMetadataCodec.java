package server.agent.dtn;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import server.agent.codec.NativeAfsCodec;
import server.shared.codec.GrawCodec;
import server.shared.codec.GrawCodec.*;
import server.shared.codec.Hashing;
import server.shared.model.DtnModels;
import server.shared.model.DtnModels.*;

/**
 * LNIS 서비스용 AFS/metadata 분리. vendor의 eph2sbf 및 FEC 코드는 변경하지 않는다.
 * SB2 배치·단위는 LANS-AFS-SIM/afs_sim.c:eph2sbf와 같다. SB3/SB4는 원본 0101 패턴이다.
 * GPS LNAV에만 있는 보정항·상태·패리티와 RAWX는 JSON으로 보존한다. 수신 시 SB2를 합쳐
 * 원래 GRAW를 정확히 복원하므로 기존 지구 PVT 계산기와 관측값 화면을 그대로 사용한다.
 */
final class AfsMetadataCodec {
  static final String FORMAT = "LNIS-AFS-GNSS-v2";
  static final String GROUPED_FORMAT = "LNIS-AFS-GNSS-v3";
  // {LNAV subframe index, parity-stripped LNAV bit offset, SB2 bit offset, length}
  // GPS eccentricity is 2^-33, original AFS is 2^-32: its last bit stays in metadata.
  private static final int[][] FIELDS = {
      {1,216,22,16}, {1,136,39,31}, {1,184,70,32}, {2,112,102,32},
      {2,64,134,32}, {2,160,166,32}, {1,88,198,32},
      {0,176,230,16}, {0,216,246,22}, {0,200,268,16}};

  private AfsMetadataCodec() {}

  static void prepare(Transfer transfer, List<byte[]> input, NativeAfsCodec codec) {
    List<Envelope> original = input.stream().map(GrawCodec::decode).toList();
    ObservationEpoch epoch = original.stream().map(Envelope::message)
        .filter(ObservationEpoch.class::isInstance).map(ObservationEpoch.class::cast)
        .findFirst().orElseThrow(() -> invalid("RAWX 관측값이 필요합니다."));
    if (epoch.week() < 0 || epoch.week() > 8191 || !Double.isFinite(epoch.receiverTowSeconds())
        || epoch.receiverTowSeconds() < 0 || epoch.receiverTowSeconds() >= 604800)
      throw invalid("GNSS 관측 시각 오류");
    List<AfsRecord> records = new ArrayList<>(original.stream().map(AfsMetadataCodec::record).toList());
    Map<Integer, int[]> latest = new HashMap<>();
    List<Frame> frames = new ArrayList<>();
    Set<Integer> masked = new HashSet<>();
    for (int i = 0; i < original.size(); i++) {
      checkpoint();
      if (!(original.get(i).message() instanceof NavigationUpdate nav)) continue;
      int sf = subframe(nav);
      if (sf < 1 || sf > 3) continue;
      int[] indices = latest.computeIfAbsent(nav.satelliteId(), key -> new int[]{-1,-1,-1});
      indices[sf - 1] = i;
      if (Arrays.stream(indices).anyMatch(n -> n < 0)) continue;
      List<NavigationUpdate> triple = Arrays.stream(indices)
          .mapToObj(n -> (NavigationUpdate)original.get(n).message()).toList();
      if (!coherent(triple)) continue;
      byte[] sb2 = pattern(1176);
      int itow = (int)(epoch.receiverTowSeconds() / 1200);
      put(sb2, 0, 13, epoch.week()); put(sb2, 13, 9, itow);
      sb2[38] = 0; // GPS e is < 0.5; upper bit of original AFS 2^-32 field is zero.
      for (int[] f : FIELDS) put(sb2, f[2], f[3], read(triple.get(f[0]).words(), f[1], f[3]));
      Frame frame = new Frame();
      frame.setIndex(frames.size()); frame.setWeek(epoch.week()); frame.setAfsItow(itow);
      frame.setToi((int)(epoch.receiverTowSeconds() % 1200 / 12));
      frame.setPrn(nav.satelliteId());
      frame.setNavigationRecordIndices(Arrays.stream(indices).boxed().toList());
      frame.setFrameBase64(Base64.getEncoder().encodeToString(
          codec.encode(frame.getToi(), sb2, pattern(846), pattern(846))));
      frames.add(frame);
      for (int n : indices) masked.add(n);
    }
    if (frames.isEmpty()) throw invalid("GPS LNAV 항법정보(subframe 1·2·3)가 필요합니다. 관측값만 있으면 GNSS RAW 시험을 사용하세요.");
    for (int n : masked) {
      AfsRecord record = records.get(n);
      NavigationUpdate nav = record.navigation();
      List<Long> words = new ArrayList<>(nav.words());
      for (int[] f : FIELDS) if (f[0] == subframe(nav) - 1) write(words, f[1], f[3], 0);
      records.set(n, withNavigation(record, words));
    }
    transfer.setSchemaVersion(2); transfer.setFormat(FORMAT);
    transfer.setMetadata(new AfsMetadata(records)); transfer.setFrames(frames);
  }

  static List<byte[]> restore(Transfer transfer, NativeAfsCodec codec) {
    if (GROUPED_FORMAT.equals(transfer.getFormat())) transfer = ungroup(transfer);
    if (transfer.getSchemaVersion() != 2 || !DtnModels.PROFILE.equals(transfer.getProfile())
        || !"AFS_METADATA".equals(transfer.getTestType()) || transfer.getGrawBase64() != null
        || transfer.getFile() != null || transfer.getSatellites() != null || !(transfer.getMetadata() instanceof AfsMetadata metadata)
        || metadata.records() == null || metadata.records().isEmpty()
        || metadata.records().size() != transfer.getRecordCount() || metadata.records().size() > 15000
        || transfer.getFrames() == null || transfer.getFrames().isEmpty()
        || transfer.getFrames().size() > metadata.records().size())
      throw invalid("전송 형식 오류");
    List<AfsRecord> records = new ArrayList<>(metadata.records());
    for (AfsRecord r : records) {
      if (r == null || r.testId() == null || r.messageId() == null || r.capturedAt() == null
          || (r.observation() == null ? 0 : 1) + (r.navigation() == null ? 0 : 1)
             + (r.receiver() == null ? 0 : 1) != 1) throw invalid("metadata record 오류");
      if (r.navigation() != null && (r.navigation().words() == null
          || r.navigation().words().size() > 255 || r.navigation().words().stream()
              .anyMatch(w -> w == null || w < 0 || w > 0xffffffffL))) throw invalid("항법 word 오류");
      if (r.observation() != null && (r.observation().observations() == null
          || r.observation().observations().size() > 255
          || r.observation().observations().stream().anyMatch(Objects::isNull))) throw invalid("관측값 오류");
    }
    Map<Integer, byte[]> restored = new HashMap<>();
    int index = 0;
    for (Frame frame : transfer.getFrames()) {
      checkpoint();
      if (frame == null || frame.getIndex() != index++ || frame.getPrn() == null
          || frame.getPrn() < 1 || frame.getPrn() > 32 || frame.getWeek() < 0 || frame.getWeek() > 8191
          || frame.getAfsItow() < 0 || frame.getAfsItow() > 503 || frame.getToi() < 0 || frame.getToi() > 99
          || frame.getFrameBase64() == null || frame.getFrameBase64().length() != 1000
          || frame.getNavigationRecordIndices() == null || frame.getNavigationRecordIndices().size() != 3)
        throw invalid("frame header 오류");
      var decoded = codec.decode(frame.getToi(), Base64.getDecoder().decode(frame.getFrameBase64()));
      if (!decoded.sb2Valid() || !decoded.sb3Valid() || !decoded.sb4Valid()) throw invalid("CRC 오류");
      byte[] sb2 = decoded.sb2();
      if (!Arrays.equals(decoded.sb3(), pattern(846)) || !Arrays.equals(decoded.sb4(), pattern(846))
          || sb2[38] != 0 || read(sb2,0,13) != frame.getWeek() || read(sb2,13,9) != frame.getAfsItow())
        throw invalid("원본 AFS 패턴 또는 시간 불일치");
      for (int b = 284; b < sb2.length; b++) if (sb2[b] != b % 2) throw invalid("SB2 예약 영역 오류");
      List<NavigationUpdate> triple = new ArrayList<>();
      for (int sf = 0; sf < 3; sf++) {
        Integer n = frame.getNavigationRecordIndices().get(sf);
        if (n == null || n < 0 || n >= records.size()) throw invalid("항법 record 참조 오류");
        AfsRecord original = metadata.records().get(n);
        NavigationUpdate nav = original.navigation();
        if (nav == null || nav.satelliteId() != frame.getPrn() || subframe(nav) != sf + 1)
          throw invalid("PRN/subframe 참조 불일치");
        List<Long> words = new ArrayList<>(nav.words());
        for (int[] f : FIELDS) if (f[0] == sf) {
          if (read(words, f[1], f[3]) != 0) throw invalid("SB2 필드는 metadata에 중복할 수 없습니다.");
          write(words, f[1], f[3], read(sb2,f[2],f[3]));
        }
        AfsRecord completed = withNavigation(original, words);
        byte[] bytes = GrawCodec.encode(envelope(completed));
        byte[] previous = restored.putIfAbsent(n, bytes);
        if (previous != null && !Arrays.equals(previous, bytes)) throw invalid("동일 항법 record의 SB2 불일치");
        records.set(n, completed); triple.add(completed.navigation());
      }
      if (!coherent(triple)) throw invalid("GPS 항법정보 IODE 불일치");
    }
    List<byte[]> output = new ArrayList<>();
    ByteArrayOutputStream source = new ByteArrayOutputStream();
    for (AfsRecord r : records) {
      checkpoint();
      byte[] bytes = GrawCodec.encode(envelope(r));
      if ((long)source.size() + 4 + bytes.length > DtnModels.MAX_INPUT_BYTES) throw invalid("복원 크기 초과");
      output.add(bytes); source.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array()); source.writeBytes(bytes);
    }
    if (!Hashing.hex(Hashing.sha256Digest().digest(source.toByteArray())).equals(transfer.getSourceSha256()))
      throw invalid("복원 GRAW SHA-256 불일치");
    return output;
  }

  /** JSON만 PRN별로 재배치한다. 원본 AFS 비트·PVT 계산 및 v2 복원 검증은 그대로 재사용한다. */
  static void group(Transfer transfer) {
    List<AfsRecord> records = ((AfsMetadata)transfer.getMetadata()).records();
    Map<String,AfsSatellite> satellites = new LinkedHashMap<>();
    List<AfsIndexedRecord> common = new ArrayList<>();
    for (int i = 0; i < records.size(); i++) {
      AfsRecord r = records.get(i);
      if (r.navigation() != null) {
        var nav = r.navigation();
        satellite(satellites,nav.constellationId(),nav.satelliteId()).metadata()
            .navigationSupplement().add(new AfsIndexedRecord(i,r));
      } else if (r.observation() != null) {
        var epoch = r.observation();
        for (int j = 0; j < epoch.observations().size(); j++) {
          var obs = epoch.observations().get(j);
          satellite(satellites,obs.constellationId(),obs.satelliteId()).metadata().observations()
              .add(new AfsMeasurement(i,j,epoch.week(),epoch.receiverTowSeconds(),obs));
        }
        common.add(new AfsIndexedRecord(i,withObservations(r,List.of())));
      } else common.add(new AfsIndexedRecord(i,r));
    }
    for (Frame frame : transfer.getFrames()) satellite(satellites,0,frame.getPrn()).frames().add(frame);
    transfer.setSatellites(new ArrayList<>(satellites.values()));
    transfer.setMetadata(new AfsGroupedMetadata(common)); transfer.setFrames(null);
    transfer.setSchemaVersion(3); transfer.setFormat(GROUPED_FORMAT);
  }

  /** Restore original record/measurement ordering, not PRN order, before the existing hash/PVT path. */
  static Transfer ungroup(Transfer transfer) {
    if (transfer.getSchemaVersion() != 3 || transfer.getFrames() != null
        || !(transfer.getMetadata() instanceof AfsGroupedMetadata metadata) || metadata.commonRecords() == null
        || transfer.getRecordCount() < 1 || transfer.getRecordCount() > 15000
        || transfer.getSatellites() == null || transfer.getSatellites().isEmpty()
        || transfer.getSatellites().size() > 2048) throw invalid("PRN 묶음 형식 오류");
    AfsRecord[] records = new AfsRecord[transfer.getRecordCount()];
    for (var item : metadata.commonRecords()) {
      assign(records,item);
      if (item.record().navigation() != null || (item.record().observation() != null
          && (item.record().observation().observations() == null
              || !item.record().observation().observations().isEmpty()))) throw invalid("공통 레코드에 위성 데이터 중복");
    }
    Map<Integer,TreeMap<Integer,Observation>> observations = new HashMap<>();
    Set<String> keys = new HashSet<>();
    List<Frame> frames = new ArrayList<>();
    for (var sat : transfer.getSatellites()) {
      checkpoint();
      if (sat == null || sat.constellationId() < 0 || sat.constellationId() > 255 || sat.prn() < 0 || sat.prn() > 255
          || !keys.add(sat.constellationId()+":"+sat.prn()) || sat.frames() == null || sat.metadata() == null
          || sat.metadata().navigationSupplement() == null || sat.metadata().observations() == null)
        throw invalid("위성 식별/metadata 오류");
      for (var frame : sat.frames()) {
        if (frame == null || sat.constellationId() != 0 || !Objects.equals(frame.getPrn(),sat.prn()))
          throw invalid("위성 묶음과 AFS PRN 불일치");
        frames.add(frame);
        if (frames.size() > records.length) throw invalid("프레임 수 초과");
      }
      for (var item : sat.metadata().navigationSupplement()) {
        assign(records,item);
        var nav = item.record().navigation();
        if (nav == null || nav.constellationId() != sat.constellationId() || nav.satelliteId() != sat.prn())
          throw invalid("위성 묶음과 항법정보 불일치");
      }
      for (var item : sat.metadata().observations()) {
        if (item == null || item.recordIndex() < 0 || item.recordIndex() >= records.length
            || item.measurementIndex() < 0 || item.measurementIndex() >= 255 || item.observation() == null)
          throw invalid("관측 참조 오류");
        AfsRecord r = records[item.recordIndex()];
        var obs = item.observation();
        if (r == null || r.observation() == null || item.week() != r.observation().week()
            || Double.compare(item.towSeconds(),r.observation().receiverTowSeconds()) != 0
            || obs.constellationId() != sat.constellationId() || obs.satelliteId() != sat.prn())
          throw invalid("관측 시각/위성 불일치");
        if (observations.computeIfAbsent(item.recordIndex(),n -> new TreeMap<>())
            .putIfAbsent(item.measurementIndex(),obs) != null) throw invalid("중복 관측 참조");
      }
    }
    for (int i = 0; i < records.length; i++) {
      if (records[i] == null) throw invalid("누락 레코드");
      if (records[i].observation() != null) {
        var items = observations.getOrDefault(i,new TreeMap<>());
        int next = 0;
        for (int n : items.keySet()) if (n != next++) throw invalid("누락 관측값");
        records[i] = withObservations(records[i],new ArrayList<>(items.values()));
      }
    }
    frames.sort(Comparator.comparingInt(Frame::getIndex));
    Transfer flat = new Transfer();
    flat.setTestId(transfer.getTestId()); flat.setSchemaVersion(2); flat.setFormat(FORMAT);
    flat.setTestType(transfer.getTestType()); flat.setProfile(transfer.getProfile());
    flat.setFile(transfer.getFile()); flat.setGrawBase64(transfer.getGrawBase64());
    flat.setRecordCount(transfer.getRecordCount()); flat.setSourceSha256(transfer.getSourceSha256());
    flat.setFrames(frames); flat.setMetadata(new AfsMetadata(Arrays.asList(records)));
    return flat;
  }
  private static AfsSatellite satellite(Map<String,AfsSatellite> satellites,int gnss,int prn) {
    return satellites.computeIfAbsent(gnss+":"+prn,key -> new AfsSatellite(gnss,prn,new ArrayList<>(),
        new AfsSatelliteMetadata(new ArrayList<>(),new ArrayList<>())));
  }
  private static void assign(AfsRecord[] records,AfsIndexedRecord item) {
    if (item == null || item.recordIndex() < 0 || item.recordIndex() >= records.length || item.record() == null
        || records[item.recordIndex()] != null) throw invalid("중복/범위 밖 레코드 참조");
    var r = item.record();
    if ((r.observation() == null ? 0 : 1) + (r.navigation() == null ? 0 : 1)
        + (r.receiver() == null ? 0 : 1) != 1) throw invalid("레코드 종류 중복/누락");
    records[item.recordIndex()] = item.record();
  }
  private static AfsRecord withObservations(AfsRecord r,List<Observation> observations) {
    var e = r.observation();
    return new AfsRecord(r.testId(),r.messageId(),r.sequence(),r.capturedAt(),new ObservationEpoch(
        e.receiverTowSeconds(),e.week(),e.leapSeconds(),e.receiverStatus(),e.rawxVersion(),observations),null,null);
  }

  private static AfsRecord record(Envelope e) {
    return new AfsRecord(e.testId(),e.messageId(),e.sequence(),e.capturedAt(),
        e.message() instanceof ObservationEpoch o ? o : null,
        e.message() instanceof NavigationUpdate n ? n : null,
        e.message() instanceof ReceiverMetadata r ? r : null);
  }
  private static Envelope envelope(AfsRecord r) {
    return new Envelope(r.testId(),r.messageId(),r.sequence(),r.capturedAt(),
        r.observation() != null ? r.observation() : r.navigation() != null ? r.navigation() : r.receiver());
  }
  private static AfsRecord withNavigation(AfsRecord r, List<Long> words) {
    NavigationUpdate n = r.navigation();
    return new AfsRecord(r.testId(),r.messageId(),r.sequence(),r.capturedAt(),null,
        new NavigationUpdate(n.constellationId(),n.satelliteId(),n.signalId(),n.frequencyId(),n.sfrbxVersion(),words),null);
  }
  private static int subframe(NavigationUpdate n) {
    if (n.constellationId() != 0 || n.signalId() != 0 || n.satelliteId() < 1 || n.satelliteId() > 32
        || n.words().size() != 10 || read(n.words(),0,8) != 0x8b) return 0;
    return (int)read(n.words(),43,3);
  }
  private static boolean coherent(List<NavigationUpdate> nav) {
    long iode = read(nav.get(1).words(),48,8);
    return iode == read(nav.get(0).words(),168,8) && iode == read(nav.get(2).words(),216,8);
  }
  private static byte[] pattern(int size) {
    byte[] bits = new byte[size];
    for (int i = 0; i < size; i++) bits[i] = (byte)(i % 2);
    return bits;
  }
  private static long read(List<Long> words, int start, int length) {
    long value = 0;
    for (int b = start; b < start + length; b++) value = (value << 1) | ((words.get(b / 24) >>> (29 - b % 24)) & 1);
    return value;
  }
  private static void write(List<Long> words, int start, int length, long value) {
    for (int b = start; b < start + length; b++) {
      long mask = 1L << (29 - b % 24);
      words.set(b / 24, (words.get(b / 24) & ~mask) | (((value >>> (start + length - b - 1)) & 1) * mask));
    }
  }
  private static long read(byte[] bits, int start, int length) {
    long value = 0;
    for (int i = start; i < start + length; i++) value = (value << 1) | bits[i];
    return value;
  }
  private static void put(byte[] bits, int start, int length, long value) {
    for (int i = 0; i < length; i++) bits[start+i] = (byte)((value >>> (length-i-1)) & 1);
  }
  private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("AFS metadata: " + message); }
  private static void checkpoint() {
    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("AFS 작업 취소");
  }
}
