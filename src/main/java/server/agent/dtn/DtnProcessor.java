package server.agent.dtn;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import server.agent.codec.NativeAfsCodec;
import server.shared.codec.NativePvtCodec;
import server.agent.afs.*;
import server.shared.codec.GrawCodec;
import server.shared.codec.DtnDelay;
import server.shared.codec.Hashing;
import server.shared.model.DtnModels;
import server.shared.model.DtnModels.*;

/** AFS 생성/복원기를 재사용하는 DTN 계산 작업이다. */
public final class DtnProcessor {
  private final NativeAfsCodec afs;
  private final Path nativeDirectory;
  public DtnProcessor(NativeAfsCodec afs, Path nativeDirectory) {
    this.afs = afs;
    this.nativeDirectory = nativeDirectory;
  }

  public AgentResult prepare(UUID id, byte[] source) {
    return prepare(id, source, false);
  }

  public AgentResult prepare(UUID id, byte[] source, boolean raw) {
    return prepare(id,source,raw,(stage,message)->{});
  }
  public AgentResult prepare(UUID id, byte[] source, boolean raw, java.util.function.BiConsumer<String,String> progress) {
    if (source.length == 0 || source.length > DtnModels.MAX_INPUT_BYTES)
      throw new IllegalArgumentException("DTN 수집 입력은 1 MiB 이하로 제한됩니다.");
    var records = GrawCodec.splitLengthPrefixed(source);
    AgentResult result = calculate(records, progress);
    Transfer transfer = new Transfer();
    transfer.setTestId(id);
    transfer.setSourceSha256(Hashing.hex(Hashing.sha256Digest().digest(source)));
    transfer.setRecordCount(records.size());
    if (raw) {
      transfer.setFormat("LNIS-GRAW-RAW-v1");
      transfer.setTestType("GNSS_RAW");
      transfer.setGrawBase64(Base64.getEncoder().encodeToString(source));
      progress.accept("JSON 준비","GNSS RAW Base64 준비 완료 · "+source.length+" bytes · "+records.size()+" records · SHA-256 "+transfer.getSourceSha256());
      result.setTransfer(transfer);
      return result;
    }
    progress.accept("AFS 변환", "Epoch별 GPS 관측 → SB2 항법정보 · SB3 보충정보 · SB4 원본 관측값");
    AfsPvtTransferCodec.prepare(transfer, records, afs);
    int count = transfer.getSatellites().stream().mapToInt(s -> s.frames().size()).sum();
    progress.accept("AFS 변환", "인코딩 완료 · " + count + " frames · 각 6000 bits/750 bytes"
        + " · SB3 517/846 bits · SB4 606/846 bits · 원본 메타데이터 별도 보존");
    progress.accept("JSON 준비", "AFS v4 · PVT 계산 입력은 프레임, 원본 RAW는 metadata로 보존");
    result.setTransfer(transfer);
    return result;
  }

  public AgentResult receive(UUID id, Transfer transfer) {
    return receive(id,transfer,(stage,message)->{});
  }
  public AgentResult receive(UUID id, Transfer transfer, java.util.function.BiConsumer<String,String> progress) {
    return receive(id, transfer, progress, null);
  }
  public AgentResult receive(UUID id, Transfer transfer, java.util.function.BiConsumer<String,String> progress, DtnDelay.Timing timing) {
    if (transfer != null && server.shared.codec.AfsPvtFrameCodec.FORMAT.equals(transfer.getFormat())) {
      if (!id.equals(transfer.getTestId())) throw new IllegalArgumentException("시험 ID 불일치");
      progress.accept("AFS 복원", "SB2·SB3·SB4 복호화 → 프레임 계산 입력 복원 · 원본 metadata 별도 대조");
      var restored = AfsPvtTransferCodec.restore(transfer, afs);
      progress.accept("AFS 복원", "프레임/원본 검증 완료 · " + restored.frameCount()
          + " frames · PVT 입력 출처: AFS 프레임 · metadata 계산 대체 없음");
      AgentResult result = calculate(restored.calculation(), progress, timing);
      result.setObservations(server.shared.model.DtnObservationView.fromRecords(restored.source())
          .withFrameInput(restored.calculation(), restored.frameCount()));
      if (timing != null) {
        // Display all original signals in their original order, independently of the GPS-only solver input.
        var originalEvidence = DtnDelay.convert(restored.source(), timing).evidence();
        String error = result.getDelayEvidence().error();
        result.setDelayEvidence(new DtnDelay.Evidence(originalEvidence.timing(), originalEvidence.delaySeconds(),
            originalEvidence.addedMeters(), originalEvidence.originalTime(), originalEvidence.shiftedTime(),
            originalEvidence.satellites(), error));
      }
      return result;
    }
    if (transfer != null && (AfsMetadataCodec.FORMAT.equals(transfer.getFormat())
        || AfsMetadataCodec.GROUPED_FORMAT.equals(transfer.getFormat()))) {
      if (!id.equals(transfer.getTestId())) throw new IllegalArgumentException("시험 ID 불일치");
      progress.accept("AFS 복원","CRC·원본 0101 패턴 검사 → SB2 항법정보와 관측 metadata 결합");
      var records = AfsMetadataCodec.restore(transfer, afs);
      progress.accept("AFS 복원","GRAW 복원·SHA-256 검증 완료 · "+records.size()+" records");
      return calculate(records, progress, timing);
    }
    if (transfer != null && "LNIS-GRAW-RAW-v1".equals(transfer.getFormat()))
      return receiveRaw(id, transfer,progress,timing);
    if (transfer == null || !id.equals(transfer.getTestId()) || transfer.getSchemaVersion() != 1
        || !DtnModels.PROFILE.equals(transfer.getProfile()) || !"LNIS-GRAW-AFS-v1".equals(transfer.getFormat())
        || !"AFS_METADATA".equals(transfer.getTestType()) || transfer.getGrawBase64() != null
        || transfer.getPrn() != 1 || transfer.getFrames() == null || transfer.getFrames().isEmpty()
        || transfer.getFrames().size() > 20000 || transfer.getRecordCount() < 1)
      throw new IllegalArgumentException("지원하지 않는 DTN payload입니다.");
    progress.accept("AFS 복원","복호화·CRC·순서·시간 메타데이터 검사 시작 · "+transfer.getFrames().size()+" frames");
    AfsReassembler reassembler = new AfsReassembler();
    int index = 0;
    for (var frame : transfer.getFrames()) {
      if (frame.getIndex() != index++) throw new IllegalArgumentException("AFS frame 순서 오류");
      var decoded = afs.decode(frame.getToi(), Base64.getDecoder().decode(frame.getFrameBase64()));
      if (!decoded.sb2Valid() || !decoded.sb3Valid() || !decoded.sb4Valid())
        throw new IllegalArgumentException("AFS CRC 검사 실패");
      var sb2 = Sb2PayloadCodec.decode(decoded.sb2(), transfer.getPrn(), frame.getWeek(), frame.getAfsItow());
      if (!sb2.headerMatchesPacket()) throw new IllegalArgumentException("AFS 시간 metadata 불일치");
      reassembler.add(AfsRawFragmentCodec.decode(AfsRawFragmentCodec.fromSbBits(decoded.sb3())));
      reassembler.add(AfsRawFragmentCodec.decode(AfsRawFragmentCodec.fromSbBits(decoded.sb4())));
    }
    progress.accept("AFS 복원","전체 프레임 복호화·CRC·시간 정보 검사 통과");
    var records = reassembler.completeRecords();
    if (reassembler.incompleteCount() != 0 || records.size() != transfer.getRecordCount())
      throw new IllegalArgumentException("수신 GRAW 레코드가 부족합니다.");
    ByteArrayOutputStream source = new ByteArrayOutputStream();
    for (byte[] record : records) {
      if ((long)source.size()+4+record.length > DtnModels.MAX_INPUT_BYTES)
        throw new IllegalArgumentException("복원 입력 크기 초과");
      source.writeBytes(ByteBuffer.allocate(4).putInt(record.length).array());
      source.writeBytes(record);
    }
    if (!Hashing.hex(Hashing.sha256Digest().digest(source.toByteArray())).equals(transfer.getSourceSha256()))
      throw new IllegalArgumentException("복원 데이터 SHA-256 불일치");
    progress.accept("AFS 복원","GRAW 복원·SHA-256 검증 완료 · "+records.size()+" records · "+source.size()+" bytes");
    return calculate(records, progress, timing);
  }

  private AgentResult receiveRaw(UUID id, Transfer transfer, java.util.function.BiConsumer<String,String> progress, DtnDelay.Timing timing) {
    progress.accept("RAW 복원","Base64 복원·크기·SHA-256·레코드 수 확인 시작");
    if (!id.equals(transfer.getTestId()) || transfer.getSchemaVersion() != 1
        || !"GNSS_RAW".equals(transfer.getTestType())
        || !DtnModels.PROFILE.equals(transfer.getProfile()) || transfer.getFrames() != null
        || transfer.getGrawBase64() == null
        || transfer.getGrawBase64().length() > ((DtnModels.MAX_INPUT_BYTES + 2) / 3) * 4)
      throw new IllegalArgumentException("지원하지 않는 RAW payload입니다.");
    byte[] source = Base64.getDecoder().decode(transfer.getGrawBase64());
    if (source.length == 0 || source.length > DtnModels.MAX_INPUT_BYTES
        || !Hashing.hex(Hashing.sha256Digest().digest(source)).equals(transfer.getSourceSha256()))
      throw new IllegalArgumentException("RAW 입력 크기 또는 SHA-256 불일치");
    var records = GrawCodec.splitLengthPrefixed(source);
    if (records.size() != transfer.getRecordCount()) throw new IllegalArgumentException("RAW 레코드 수 불일치");
    progress.accept("RAW 복원","크기·SHA-256·레코드 수 검증 통과 · "+records.size()+" records · "+source.length+" bytes");
    return calculate(records, progress, timing);
  }

  private AgentResult calculate(List<byte[]> records, java.util.function.BiConsumer<String,String> progress) {
    return calculate(records, progress, null);
  }
  private AgentResult calculate(List<byte[]> records, java.util.function.BiConsumer<String,String> progress, DtnDelay.Timing timing) {
    AgentResult result = new AgentResult();
    result.setObservations(server.shared.model.DtnObservationView.fromRecords(records));
    if (timing != null) {
      var converted = DtnDelay.convert(records, timing);
      result.setDelayEvidence(converted.evidence());
      if (converted.evidence().error() != null) {
        Pvt unavailable = new Pvt();
        unavailable.setWeek(converted.evidence().originalTime().week());
        unavailable.setTowSeconds(converted.evidence().originalTime().towSeconds());
        unavailable.setMessage(converted.evidence().error());
        result.setPvt(List.of(unavailable));
        return result;
      }
      records = converted.records();
    }
    progress.accept("PVT","지구 PVT 계산 시작 · "+records.size()+" records");
    long started=System.nanoTime();
    try (var pvt = new NativePvtCodec(nativeDirectory)) {
      result.setPvt(pvt.calculate(records));
    } catch (RuntimeException error) {
      if (timing == null) {
        throw error;
      }
      var evidence = result.getDelayEvidence();
      String message = "지연 반영 PVT 계산 불가 · " + error.getMessage();
      result.setDelayEvidence(new DtnDelay.Evidence(
          evidence.timing(), evidence.delaySeconds(), evidence.addedMeters(),
          evidence.originalTime(), evidence.shiftedTime(), evidence.satellites(), message));
      Pvt unavailable = new Pvt();
      unavailable.setWeek(evidence.shiftedTime().week());
      unavailable.setTowSeconds(evidence.shiftedTime().towSeconds());
      unavailable.setMessage(message);
      result.setPvt(List.of(unavailable));
    }
    progress.accept("PVT","계산 요청 처리 종료 · "+((System.nanoTime()-started)/1_000_000)+" ms · 유효성은 결과에서 확인");
    return result;
  }
}
