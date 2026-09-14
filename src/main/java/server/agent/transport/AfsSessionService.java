package server.agent.transport;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import server.agent.afs.AfsFrameBuilder;
import server.agent.afs.AfsRawFragmentCodec;
import server.agent.afs.AfsReassembler;
import server.agent.afs.Sb2PayloadCodec;
import server.agent.codec.NativeAfsCodec;
import server.shared.codec.GrawCodec;
import server.shared.codec.Hashing;
import server.shared.model.AgentProtocol.AfsTransferBatch;
import server.shared.model.AgentProtocol.AfsTransferComplete;
import server.shared.model.AgentProtocol.AfsTransferFrame;
import server.shared.model.AgentProtocol.AfsTransferStart;
import server.shared.model.AgentProtocol.EventType;
import server.shared.model.AgentProtocol.FrameEvidenceMessage;
import server.shared.model.LnisModels.AfsCounters;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.IntegrityResult;
import server.shared.model.LnisModels.Metric;
import server.shared.model.LnisModels.MetricCategory;
import server.shared.model.LnisModels.MetricStatus;
import server.shared.model.LnisModels.ResourceSample;
import server.shared.model.LnisModels.RoleResult;
import server.shared.model.LnisModels.Sb2EphemerisResult;
import server.shared.model.LnisModels.TestOptions;
import server.shared.model.LnisModels.TestType;
import server.shared.model.LnisModels.Verdict;

/** AFS 프레임 생성과 Receiver 복호화를 담당하며 전송 자체는 신뢰성 있는 관리 채널에 맡긴다. */
public final class AfsSessionService implements AutoCloseable {
  private static final int MAX_EVIDENCE_FRAMES = 500;
  public static final int FRAMES_PER_BATCH = 32;

  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class SessionCommand {
    String senderAgentId;
    String receiverAgentId;
    UUID inputId;
    server.shared.model.LnisModels.AfsSettings afs;
    TestOptions options;

    public SessionCommand(String senderAgentId, String receiverAgentId, UUID inputId,
        server.shared.model.LnisModels.AfsSettings afs, TestOptions options) {
      this.senderAgentId = senderAgentId;
      this.receiverAgentId = receiverAgentId;
      this.inputId = inputId;
      this.afs = afs == null ? new server.shared.model.LnisModels.AfsSettings(1) : afs;
      this.options = options;
    }
  }

  /** Sender가 만든 start/batch/complete 경계를 기존 연결 계층에 전달한다. */
  public interface TransferSink {
    void start(AfsTransferStart start);
    void batch(AfsTransferBatch batch);
    void complete(AfsTransferComplete complete);
  }

  private record ReceivedFrame(int index, int prn, int week, int intervalOfWeek,
      int timeOfInterval, byte[] payload) {}

  private static final class Operation {
    volatile boolean cancelled;
    Future<?> future;

    void check() {
      if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    void cancel() {
      cancelled = true;
      if (future != null) future.cancel(true);
    }
  }

  private static final class ReceiverContext {
    final UUID sessionId;
    final SessionCommand command;
    final Instant started = Instant.now();
    final BiConsumer<EventType, Object> event;
    final Consumer<FrameEvidenceMessage> evidence;
    final Consumer<RoleResult> result;
    final List<ReceivedFrame> frames = new ArrayList<>();
    AfsTransferStart manifest;
    final Operation operation = new Operation();
    boolean evaluating;

    ReceiverContext(UUID sessionId, SessionCommand command, BiConsumer<EventType, Object> event,
        Consumer<FrameEvidenceMessage> evidence, Consumer<RoleResult> result) {
      this.sessionId = sessionId;
      this.command = command;
      this.event = event;
      this.evidence = evidence;
      this.result = result;
    }
  }

  private final NativeAfsCodec codec;
  private final ExecutorService executor;
  private ReceiverContext receiver;
  private Operation sender;

  public AfsSessionService(NativeAfsCodec codec) {
    this(codec, Executors.newVirtualThreadPerTaskExecutor());
  }

  AfsSessionService(NativeAfsCodec codec, ExecutorService executor) {
    this.codec = codec;
    this.executor = executor;
  }

  /** Receiver를 소켓 없이 준비하고 start/batch/complete 메시지를 기다린다. */
  public synchronized void arm(UUID sessionId, SessionCommand command,
      BiConsumer<EventType, Object> event, Consumer<FrameEvidenceMessage> evidence,
      Consumer<RoleResult> result) {
    if (receiver != null) {
      throw new IllegalStateException("Receiver가 이미 다른 AFS 시험을 처리 중입니다.");
    }
    receiver = new ReceiverContext(sessionId, command, event, evidence, result);
    event.accept(EventType.RX_STATUS, Map.of("percent", 0, "stage", "Armed",
        "message", "AFS 관리 채널 수신 준비 완료"));
  }

  /** GRAW를 AFS frame으로 변환해 작은 batch로 전달하고 Sender 결과를 생성한다. */
  public synchronized void send(UUID sessionId, SessionCommand command, byte[] source,
      BiConsumer<EventType, Object> event, Consumer<FrameEvidenceMessage> evidence,
      Consumer<RoleResult> result, TransferSink transfer) {
    if (sender != null) throw new IllegalStateException("AFS sender is already running");
    Operation operation = new Operation();
    sender = operation;
    operation.future = executor.submit(() -> {
      try { runSender(operation, sessionId, command, source, event, evidence, result, transfer); }
      finally { finishSender(operation); }
    });
  }

  private void runSender(Operation operation, UUID id, SessionCommand command, byte[] source,
      BiConsumer<EventType, Object> event, Consumer<FrameEvidenceMessage> evidence,
      Consumer<RoleResult> result, TransferSink transfer) {
    Instant started = Instant.now();
    try {
      operation.check();
      List<byte[]> records = GrawCodec.splitLengthPrefixed(source);
      AfsFrameBuilder.Prepared prepared =
          new AfsFrameBuilder(codec).prepare(records, command.options(), command.afs().prn(), operation::check);
      String sourceHash = Hashing.hex(Hashing.sha256Digest().digest(source));
      AfsTransferStart manifest = new AfsTransferStart(command.senderAgentId(),
          command.receiverAgentId(), source.length, sourceHash, records.size(),
          prepared.frames().size(), command.afs().prn(), command.options().testType(),
          command.options().errorCount(), command.options().errorSeed(),
          command.options().syncDamageInterval(), prepared.injectedFrameCount());
      operation.check();
      transfer.start(manifest);
      operation.check();

      Map<Integer, AfsFrameBuilder.InjectionDetail> injections = new HashMap<>();
      prepared.injections().forEach(value -> injections.put(value.frameIndex(), value));
      event.accept(EventType.TX_STATUS, Map.of("percent", 30, "stage", "Prepared",
          "message", "AFS frame 전송 준비 완료", "testType", command.options().testType(),
          "afsPrn", command.afs().prn(), "sourceBytes", source.length,
          "recordCount", records.size(), "totalFrames", prepared.frames().size(),
          "injectedFrameCount", prepared.injectedFrameCount()));

      for (int offset = 0; offset < prepared.frames().size(); offset += FRAMES_PER_BATCH) {
        operation.check();
        int end = Math.min(prepared.frames().size(), offset + FRAMES_PER_BATCH);
        List<AfsTransferFrame> frames = new ArrayList<>(end - offset);
        for (int index = offset; index < end; index++) {
          operation.check();
          AfsFrameBuilder.Frame frame = prepared.frames().get(index);
          frames.add(new AfsTransferFrame(index, command.afs().prn(), frame.week(),
              frame.intervalOfWeek(), frame.timeOfInterval(), frame.payload()));
          if (shouldKeepEvidence(index, prepared.frames().size())) {
            AfsFrameBuilder.InjectionDetail injection = injections.get(index);
            evidence.accept(new FrameEvidenceMessage(index,
                prepared.referenceFrames().get(index).payload(), frame.payload(), null, null,
                injection == null ? List.of() : injection.bitPositions(), false, false, false,
                false, false, 0, 0, 0, false, null, null,
                injection == null ? "오류가 주입되지 않은 Sender 프레임"
                    : "Sender가 시험 조건에 따라 비트를 반전한 프레임"));
          }
        }
        operation.check();
        transfer.batch(new AfsTransferBatch(command.senderAgentId(), command.receiverAgentId(), frames));
        operation.check();
        event.accept(EventType.TX_STATUS, Map.of("percent",
            35 + (int) (45.0 * end / prepared.frames().size()), "stage", "Transferring",
            "message", "AFS frame " + end + "/" + prepared.frames().size() + " 전달",
            "transferredFrames", end, "totalFrames", prepared.frames().size()));
      }
      operation.check();
      transfer.complete(new AfsTransferComplete(command.senderAgentId(),
          command.receiverAgentId(), prepared.frames().size()));

      IntegrityResult integrity = new IntegrityResult(true, source.length, source.length,
          sourceHash, sourceHash, records.size(), records.size(), "AFS frame 생성 및 전달 완료");
      long injectedBits = (long) command.options().errorCount() * prepared.injectedFrameCount();
      operation.check();
      finishSender(operation);
      result.accept(new RoleResult(2, id, AgentRole.SENDER, Verdict.PASS, Instant.now(),
          integrity, List.of(metric("GeneratedFrames", prepared.frames().size(), "frame")),
          new AfsCounters(prepared.frames().size(), prepared.frames().size(), 0, source.length,
              Duration.between(started, Instant.now()), 0, injectedBits, 0),
          List.of(resourceSample()), null));
    } catch (CancellationException ignored) {
      // Cancellation has no role result.
    } catch (Exception error) {
      if (operation.cancelled) return;
      finishSender(operation);
      result.accept(failed(id, AgentRole.SENDER, error));
      event.accept(EventType.ERROR, Map.of("message", safe(error)));
    }
  }

  public synchronized void receiveStart(UUID sessionId, AfsTransferStart start) {
    ReceiverContext context = requireReceiver(sessionId);
    if (context.manifest != null || !context.command.senderAgentId().equals(start.senderAgentId())
        || !context.command.receiverAgentId().equals(start.receiverAgentId())
        || context.command.afs().prn() != start.prn()
        || context.command.options().testType() != start.testType()) {
      throw new IllegalArgumentException("AFS transfer manifest가 준비 명령과 일치하지 않습니다.");
    }
    if (start.frameCount() < 1 || start.sourceLength() < 1 || start.sourceSha256() == null
        || !start.sourceSha256().matches("[0-9A-Fa-f]{64}")) {
      throw new IllegalArgumentException("AFS transfer manifest가 올바르지 않습니다.");
    }
    context.manifest = start;
    context.event.accept(EventType.RX_STATUS, Map.of("percent", 5, "stage", "Receiving",
        "message", start.testType() + " AFS frame 수신 시작", "testType", start.testType(),
        "sourceBytes", start.sourceLength(), "recordCount", start.recordCount(),
        "expectedFrames", start.frameCount(), "injectedFrameCount", start.injectedFrameCount()));
  }

  public synchronized void receiveBatch(UUID sessionId, AfsTransferBatch batch) {
    ReceiverContext context = requireReceiver(sessionId);
    AfsTransferStart manifest = requireManifest(context);
    if (!manifest.senderAgentId().equals(batch.senderAgentId())
        || !manifest.receiverAgentId().equals(batch.receiverAgentId())
        || batch.frames().isEmpty() || batch.frames().size() > FRAMES_PER_BATCH) {
      throw new IllegalArgumentException("AFS frame batch가 올바르지 않습니다.");
    }
    for (AfsTransferFrame frame : batch.frames()) {
      if (frame.index() != context.frames.size() || frame.prn() != manifest.prn()
          || frame.payload().length != 750 || context.frames.size() >= manifest.frameCount()) {
        throw new IllegalArgumentException("AFS frame 순서, PRN 또는 크기가 올바르지 않습니다.");
      }
      context.frames.add(new ReceivedFrame(frame.index(), frame.prn(), frame.week(),
          frame.intervalOfWeek(), frame.timeOfInterval(), frame.payload().clone()));
    }
    context.event.accept(EventType.RX_STATUS, Map.of("percent",
        10 + (int) (70.0 * context.frames.size() / manifest.frameCount()),
        "stage", "Receiving", "message", "AFS frame " + context.frames.size() + "/"
            + manifest.frameCount() + " 수신", "receivedFrames", context.frames.size(),
        "expectedFrames", manifest.frameCount()));
  }

  public synchronized void receiveComplete(UUID sessionId, AfsTransferComplete complete) {
    ReceiverContext context = requireReceiver(sessionId);
    AfsTransferStart manifest = requireManifest(context);
    if (!manifest.senderAgentId().equals(complete.senderAgentId())
        || !manifest.receiverAgentId().equals(complete.receiverAgentId())
        || complete.frameCount() != manifest.frameCount()
        || context.frames.size() != manifest.frameCount()) {
      throw new IllegalArgumentException("AFS transfer 완료 경계와 수신 프레임 수가 일치하지 않습니다.");
    }
    context.evaluating = true;
    context.operation.future = executor.submit(() -> {
      try { runReceiver(context); }
      finally { finishReceiver(context); }
    });
  }

  private void runReceiver(ReceiverContext context) {
    AfsTransferStart manifest = context.manifest;
    try {
      context.operation.check();
      context.event.accept(EventType.RX_STATUS, Map.of("percent", 85, "stage", "Evaluating",
          "message", "AFS frame 복호화 및 GRAW 무결성 검증", "receivedFrames",
          context.frames.size(), "expectedFrames", manifest.frameCount()));
      DecodeAggregate aggregate = decodeFrames(context.frames, manifest.testType(), context.operation);
      for (ReceivedFrame frame : context.frames) {
        context.operation.check();
        if (!shouldKeepEvidence(frame.index(), manifest.frameCount())) continue;
        FrameDecodeDiagnostic diagnostic = aggregate.diagnostics.get(frame.index());
        context.evidence.accept(new FrameEvidenceMessage(frame.index(), null, null, frame.payload(),
            aggregate.reencodedFrames.get(frame.index()), List.of(),
            diagnostic != null && diagnostic.fullyDecoded(),
            diagnostic != null && diagnostic.decoderCompleted(),
            diagnostic != null && diagnostic.sb2Valid(),
            diagnostic != null && diagnostic.sb3Valid(),
            diagnostic != null && diagnostic.sb4Valid(),
            diagnostic == null ? 0 : diagnostic.sb2DecisionChanges(),
            diagnostic == null ? 0 : diagnostic.sb3DecisionChanges(),
            diagnostic == null ? 0 : diagnostic.sb4DecisionChanges(),
            diagnostic != null && diagnostic.usedForGrawReassembly(),
            aggregate.sb2Ephemeris.get(frame.index()),
            diagnostic == null ? "동기 패턴을 찾지 못해 복호화 대상에서 제외됐습니다."
                : diagnostic.failureReason(),
            diagnostic == null || !diagnostic.decoderCompleted()
                ? "복호화에 성공하지 못해 재인코딩 검증 프레임이 없습니다."
                : "복호화 출력의 진단용 재인코딩 프레임"));
      }
      List<byte[]> records = aggregate.reassembler.completeRecords();
      ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
      for (byte[] record : records) {
        context.operation.check();
        reconstructed.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(record.length).array());
        reconstructed.writeBytes(record);
      }
      byte[] raw = reconstructed.toByteArray();
      String hash = Hashing.hex(Hashing.sha256Digest().digest(raw));
      boolean integrityOk = raw.length == manifest.sourceLength()
          && hash.equalsIgnoreCase(manifest.sourceSha256())
          && records.size() == manifest.recordCount()
          && aggregate.reassembler.incompleteCount() == 0;
      IntegrityResult integrity = new IntegrityResult(integrityOk, manifest.sourceLength(), raw.length,
          manifest.sourceSha256(), hash, manifest.recordCount(), records.size(),
          integrityOk ? "GRAW 무결성 검증 완료" : "GRAW 무결성 검증 실패");
      long expectedRecovered = manifest.frameCount() - manifest.injectedFrameCount();
      boolean passed = manifest.testType() == TestType.TEST_D_SYNC_RECOVERY
          ? aggregate.recoveredSyncFrames == expectedRecovered
              && aggregate.decodedFrames == expectedRecovered
              && aggregate.fullyDecodedFrames == expectedRecovered
          : integrityOk;
      long rejected = manifest.testType() == TestType.TEST_D_SYNC_RECOVERY
          ? manifest.frameCount() - aggregate.recoveredSyncFrames : 0;
      long injectedBits = (long) manifest.errorCount() * manifest.injectedFrameCount();
      RoleResult result = new RoleResult(2, context.sessionId, AgentRole.RECEIVER,
          passed ? Verdict.PASS : Verdict.FAIL, Instant.now(), integrity, metrics(aggregate),
          new AfsCounters(manifest.frameCount(), manifest.frameCount(), context.frames.size(),
              raw.length, Duration.between(context.started, Instant.now()),
              aggregate.decodeFailedFrames, injectedBits, rejected),
          List.of(resourceSample()), passed ? null : integrity.detail());
      context.operation.check();
      context.event.accept(EventType.RX_STATUS, Map.of("percent", 100, "stage", "Verified",
          "message", "AFS frame 및 GRAW 검증 완료", "integritySuccess", integrity.success(),
          "processedFrames", context.frames.size(), "fullyDecodedFrames",
          aggregate.fullyDecodedFrames));
      context.operation.check();
      finishReceiver(context);
      context.result.accept(result);
    } catch (CancellationException ignored) {
      // Cancellation has no role result.
    } catch (Exception error) {
      if (context.operation.cancelled) return;
      finishReceiver(context);
      context.result.accept(failed(context.sessionId, AgentRole.RECEIVER, error));
      context.event.accept(EventType.ERROR, Map.of("message", safe(error)));
    }
  }

  private DecodeAggregate decodeFrames(Collection<ReceivedFrame> input, TestType type, Operation operation) {
    DecodeAggregate total = new DecodeAggregate();
    if (type == TestType.TEST_D_SYNC_RECOVERY) {
      ByteArrayOutputStream joined = new ByteArrayOutputStream();
      input.forEach(frame -> { operation.check(); joined.writeBytes(frame.payload()); });
      byte[] bytes = joined.toByteArray();
      List<Long> offsets = findConfirmedSyncOffsets(bytes, operation::check);
      List<ReceivedFrame> ordered = new ArrayList<>(input);
      total.recoveredSyncFrames = offsets.size();
      for (long offset : offsets) {
        operation.check();
        int source = (int) (offset / 6000);
        if (source < ordered.size()) decodeOne(extract(bytes, offset), ordered.get(source), total, operation);
      }
      return total;
    }
    input.forEach(frame -> decodeOne(frame.payload(), frame, total, operation));
    return total;
  }

  private void decodeOne(byte[] frame, ReceivedFrame source, DecodeAggregate total, Operation operation) {
    operation.check();
    try {
      NativeAfsCodec.Decoded decoded = codec.decode(source.timeOfInterval(), frame);
      operation.check();
      total.decodedFrames++;
      total.reencodedFrames.put(source.index(), codec.encode(source.timeOfInterval(), decoded.sb2(),
          decoded.sb3(), decoded.sb4()));
      operation.check();
      if (decoded.sb2Valid()) {
        total.sb2Valid++;
        total.sb2Ephemeris.put(source.index(), Sb2PayloadCodec.decode(decoded.sb2(), source.prn(),
            source.week(), source.intervalOfWeek()));
      }
      if (decoded.sb3Valid()) total.sb3Valid++;
      if (decoded.sb4Valid()) total.sb4Valid++;
      total.corrected += Math.max(0, decoded.sb2Corrections())
          + Math.max(0, decoded.sb3Corrections()) + Math.max(0, decoded.sb4Corrections());
      boolean full = decoded.sb2Valid() && decoded.sb3Valid() && decoded.sb4Valid();
      boolean used = decoded.sb3Valid() && decoded.sb4Valid();
      if (full) total.fullyDecodedFrames++;
      if (!full) total.decodeFailedFrames++;
      total.diagnostics.put(source.index(), new FrameDecodeDiagnostic(true, full,
          decoded.sb2Valid(), decoded.sb3Valid(), decoded.sb4Valid(),
          Math.max(0, decoded.sb2Corrections()), Math.max(0, decoded.sb3Corrections()),
          Math.max(0, decoded.sb4Corrections()), used, failureReason(decoded)));
      if (used) {
        total.reassembler.add(AfsRawFragmentCodec.decode(AfsRawFragmentCodec.fromSbBits(decoded.sb3())));
        total.reassembler.add(AfsRawFragmentCodec.decode(AfsRawFragmentCodec.fromSbBits(decoded.sb4())));
      }
    } catch (CancellationException cancelled) {
      throw cancelled;
    } catch (Exception error) {
      operation.check();
      total.decodeFailedFrames++;
      total.diagnostics.put(source.index(), new FrameDecodeDiagnostic(false, false, false, false,
          false, 0, 0, 0, false, safe(error)));
    }
  }

  private synchronized ReceiverContext requireReceiver(UUID sessionId) {
    if (receiver == null || !receiver.sessionId.equals(sessionId) || receiver.operation.cancelled || receiver.evaluating) {
      throw new IllegalStateException("준비된 Receiver AFS 세션이 없습니다.");
    }
    return receiver;
  }

  private static AfsTransferStart requireManifest(ReceiverContext context) {
    if (context.manifest == null) throw new IllegalStateException("AFS transfer start가 필요합니다.");
    return context.manifest;
  }

  private synchronized void finishSender(Operation operation) {
    if (sender == operation) sender = null;
  }

  private synchronized void finishReceiver(ReceiverContext context) {
    if (receiver == context) receiver = null;
  }

  public synchronized void cancel() {
    if (sender != null) sender.cancel();
    if (receiver != null) receiver.operation.cancel();
    sender = null;
    receiver = null;
  }

  static List<Long> findConfirmedSyncOffsets(byte[] bytes) {
    return findConfirmedSyncOffsets(bytes, () -> {});
  }

  private static List<Long> findConfirmedSyncOffsets(byte[] bytes, Runnable checkpoint) {
    byte[] sync = {(byte) 0xCC, 0x63, (byte) 0xF7, 0x45, 0x36, (byte) 0xF4,
        (byte) 0x9E, 0x04, (byte) 0xA0};
    List<Long> candidates = new ArrayList<>();
    for (long offset = 0; offset + 6000 <= (long) bytes.length * 8; offset++) {
      if ((offset & 4095) == 0) checkpoint.run();
      boolean matched = true;
      for (int index = 0; index < 68; index++) {
        if (bit(bytes, offset + index) != bit(sync, index)) { matched = false; break; }
      }
      if (matched) { candidates.add(offset); offset += 67; }
    }
    java.util.Set<Long> set = new java.util.HashSet<>(candidates);
    return candidates.stream().filter(offset -> set.contains(offset - 6000)
        || set.contains(offset + 6000)).toList();
  }

  private static byte[] extract(byte[] bytes, long offset) {
    byte[] output = new byte[750];
    for (int index = 0; index < 6000; index++) {
      if (bit(bytes, offset + index) != 0) output[index >>> 3] |= (byte) (1 << (7 - (index & 7)));
    }
    return output;
  }

  private static int bit(byte[] bytes, long bit) {
    return (bytes[(int) (bit >>> 3)] >>> (7 - (bit & 7))) & 1;
  }

  private static boolean shouldKeepEvidence(int index, int total) {
    if (total <= MAX_EVIDENCE_FRAMES) return true;
    int half = MAX_EVIDENCE_FRAMES / 2;
    return index < half || index >= total - half;
  }

  private static List<Metric> metrics(DecodeAggregate value) {
    List<Metric> result = new ArrayList<>();
    result.add(metric("DecodedFrames", value.decodedFrames, "frame"));
    result.add(metric("FullyDecodedFrames", value.fullyDecodedFrames, "frame"));
    result.add(metric("Sb2CrcValidFrames", value.sb2Valid, "frame"));
    result.add(metric("Sb3CrcValidFrames", value.sb3Valid, "frame"));
    result.add(metric("Sb4CrcValidFrames", value.sb4Valid, "frame"));
    result.add(new Metric(MetricCategory.DATA_INTEGRITY, "CorrectedSymbols",
        "LDPC decoder internal decision changes; not the injected error count", "bit",
        (double) value.corrected, MetricStatus.MEASURED, null, null));
    if (value.recoveredSyncFrames > 0)
      result.add(metric("RecoveredSyncFrames", value.recoveredSyncFrames, "frame"));
    return List.copyOf(result);
  }

  private static Metric metric(String name, long value, String unit) {
    return new Metric(MetricCategory.DATA_INTEGRITY, name, name, unit, (double) value,
        MetricStatus.MEASURED, null, null);
  }

  private static ResourceSample resourceSample() {
    long memory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    double cpu = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    return new ResourceSample(Instant.now(), Math.max(0, cpu), memory);
  }

  private static RoleResult failed(UUID id, AgentRole role, Exception error) {
    IntegrityResult integrity = new IntegrityResult(false, 0, 0, "", "", 0, 0, safe(error));
    return new RoleResult(2, id, role, Verdict.INCONCLUSIVE, Instant.now(), integrity, List.of(),
        new AfsCounters(0, 0, 0, 0, Duration.ZERO, 0, 0, 0), List.of(), safe(error));
  }

  private static String failureReason(NativeAfsCodec.Decoded decoded) {
    List<String> failed = new ArrayList<>();
    if (!decoded.sb2Valid()) failed.add("SB2 CRC 실패");
    if (!decoded.sb3Valid()) failed.add("SB3 CRC 실패");
    if (!decoded.sb4Valid()) failed.add("SB4 CRC 실패");
    return failed.isEmpty() ? null : String.join(", ", failed);
  }

  private static String safe(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  @Override
  public void close() {
    cancel();
    executor.shutdownNow();
  }

  private static final class DecodeAggregate {
    final AfsReassembler reassembler = new AfsReassembler();
    final Map<Integer, byte[]> reencodedFrames = new HashMap<>();
    final Map<Integer, FrameDecodeDiagnostic> diagnostics = new HashMap<>();
    final Map<Integer, Sb2EphemerisResult> sb2Ephemeris = new HashMap<>();
    long decodedFrames;
    long fullyDecodedFrames;
    long sb2Valid;
    long sb3Valid;
    long sb4Valid;
    long corrected;
    long recoveredSyncFrames;
    long decodeFailedFrames;
  }

  private record FrameDecodeDiagnostic(boolean decoderCompleted, boolean fullyDecoded,
      boolean sb2Valid, boolean sb3Valid, boolean sb4Valid, int sb2DecisionChanges,
      int sb3DecisionChanges, int sb4DecisionChanges, boolean usedForGrawReassembly,
      String failureReason) {}
}
