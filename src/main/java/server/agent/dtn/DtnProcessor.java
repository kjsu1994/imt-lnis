package server.agent.dtn;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import server.agent.codec.NativeAfsCodec;
import server.agent.codec.NativePvtCodec;
import server.agent.afs.*;
import server.shared.codec.GrawCodec;
import server.shared.codec.Hashing;
import server.shared.model.DtnModels;
import server.shared.model.DtnModels.*;
import server.shared.model.LnisModels.*;

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
    if (source.length == 0 || source.length > DtnModels.MAX_INPUT_BYTES)
      throw new IllegalArgumentException("DTN 수집 입력은 1 MiB 이하로 제한됩니다.");
    var records = GrawCodec.splitLengthPrefixed(source);
    AgentResult result = new AgentResult();
    result.setObservations(server.shared.model.DtnObservationView.fromRecords(records));
    try (var pvt = new NativePvtCodec(nativeDirectory)) { result.setPvt(pvt.calculate(records)); }
    Transfer transfer = new Transfer();
    transfer.setTestId(id);
    transfer.setSourceSha256(Hashing.hex(Hashing.sha256Digest().digest(source)));
    transfer.setRecordCount(records.size());
    if (raw) {
      transfer.setFormat("LNIS-GRAW-RAW-v1");
      transfer.setTestType("GNSS_RAW");
      transfer.setGrawBase64(Base64.getEncoder().encodeToString(source));
      result.setTransfer(transfer);
      return result;
    }
    var frames = new AfsFrameBuilder(afs).prepare(records,
        new TestOptions(TestType.TEST_A_NORMAL, 0, 0, 0, Map.of()), 1).frames();
    List<DtnModels.Frame> output = new ArrayList<>();
    for (var frame : frames) {
      DtnModels.Frame item = new DtnModels.Frame();
      item.setIndex(output.size());
      item.setWeek(frame.week());
      item.setAfsItow(frame.intervalOfWeek());
      item.setToi(frame.timeOfInterval());
      item.setFrameBase64(Base64.getEncoder().encodeToString(frame.payload()));
      output.add(item);
    }
    transfer.setFrames(output);
    result.setTransfer(transfer);
    return result;
  }

  public AgentResult receive(UUID id, Transfer transfer) {
    if (transfer != null && "LNIS-GRAW-RAW-v1".equals(transfer.getFormat()))
      return receiveRaw(id, transfer);
    if (transfer == null || !id.equals(transfer.getTestId()) || transfer.getSchemaVersion() != 1
        || !DtnModels.PROFILE.equals(transfer.getProfile()) || !"LNIS-GRAW-AFS-v1".equals(transfer.getFormat())
        || !"AFS_METADATA".equals(transfer.getTestType()) || transfer.getGrawBase64() != null
        || transfer.getPrn() != 1 || transfer.getFrames() == null || transfer.getFrames().isEmpty()
        || transfer.getFrames().size() > 20000 || transfer.getRecordCount() < 1)
      throw new IllegalArgumentException("지원하지 않는 DTN payload입니다.");
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
    AgentResult result = new AgentResult();
    result.setObservations(server.shared.model.DtnObservationView.fromRecords(records));
    try (var pvt = new NativePvtCodec(nativeDirectory)) { result.setPvt(pvt.calculate(records)); }
    return result;
  }

  private AgentResult receiveRaw(UUID id, Transfer transfer) {
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
    AgentResult result = new AgentResult();
    result.setObservations(server.shared.model.DtnObservationView.fromRecords(records));
    try (var pvt = new NativePvtCodec(nativeDirectory)) { result.setPvt(pvt.calculate(records)); }
    return result;
  }
}
