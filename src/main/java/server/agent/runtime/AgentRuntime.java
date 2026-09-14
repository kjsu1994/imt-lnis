package server.agent.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import server.agent.codec.NativeAfsCodec;
import server.agent.config.AgentConfig;
import server.agent.gnss.SerialCaptureService;
import server.agent.transport.AfsSessionService;
import server.shared.model.AgentProtocol.*;
import server.shared.model.LnisModels.AgentState;

/**
 * 서버 명령을 GNSS 수집 또는 Sender/Receiver AFS 작업으로 분배하는 Agent 핵심 런타임이다.
 *
 * <p>WebSocket transport와 장치/시험 구현을 분리한다. 세션별 GRAW를 조립한 뒤 AFS frame을 관리 채널로 전달하고,
 * 작업 완료 callback에서 RoleResult를 서버로 보내 Agent 상태를 READY로 복원한다.
 */
public final class AgentRuntime implements AutoCloseable {
  private final AgentConfig config;
  private final NativeAfsCodec codec;
  private final SerialCaptureService capture = new SerialCaptureService();
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.READY);
  private final AfsSessionService afs;
  private final Object afsStateLock = new Object();
  private UUID activeAfsSession;
  private final server.agent.dtn.DtnWorker dtn;
  private final Map<UUID, ByteArrayOutputStream> sessionInputs = new ConcurrentHashMap<>();
  private volatile Consumer<Envelope> outbound = ignored -> {};

  public AgentRuntime(AgentConfig config, NativeAfsCodec codec) {
    this.config = config;
    this.codec = codec;
    this.afs = new AfsSessionService(codec);
    this.dtn = new server.agent.dtn.DtnWorker(
        new server.agent.dtn.DtnProcessor(codec, config.nativeDirectory()),
        json, config.role(), state, (id, payload) -> send(MessageType.DTN_DATA, id, payload));
    json.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  public void outbound(Consumer<Envelope> outbound) {
    this.outbound = outbound;
  }

  public AgentState state() {
    dtn.expire();
    return state.get();
  }

  public int codecAbiVersion() {
    return codec.abiVersion();
  }

  /** protocol version과 메시지 종류를 확인한 뒤 입력 또는 명령 처리 흐름으로 분기한다. */
  public void handle(Envelope envelope) {
    if (envelope.protocolVersion() != server.shared.model.AgentProtocol.PROTOCOL_VERSION) {
      return;
    }
    try {
      if (envelope.type() == MessageType.INPUT_CHUNK) {
        receiveInputChunk(envelope);
        return;
      }
      if (envelope.type() == MessageType.INPUT_COMPLETE) {
        int bytes =
            sessionInputs.getOrDefault(envelope.sessionId(), new ByteArrayOutputStream()).size();
        status(
            envelope.sessionId(),
            EventType.TX_STATUS,
            10,
            "InputReady",
            "Input transfer completed",
            Map.of("bytes", bytes));
        return;
      }
      if ((envelope.type() == MessageType.AFS_TRANSFER_START
          || envelope.type() == MessageType.AFS_TRANSFER_BATCH
          || envelope.type() == MessageType.AFS_TRANSFER_COMPLETE)
          && !isActiveAfs(envelope.sessionId())) return;
      if (envelope.type() == MessageType.AFS_TRANSFER_START) {
        afs.receiveStart(envelope.sessionId(),
            json.treeToValue(envelope.payload(), AfsTransferStart.class));
        return;
      }
      if (envelope.type() == MessageType.AFS_TRANSFER_BATCH) {
        afs.receiveBatch(envelope.sessionId(),
            json.treeToValue(envelope.payload(), AfsTransferBatch.class));
        return;
      }
      if (envelope.type() == MessageType.AFS_TRANSFER_COMPLETE) {
        afs.receiveComplete(envelope.sessionId(),
            json.treeToValue(envelope.payload(), AfsTransferComplete.class));
        return;
      }
      if (envelope.type() != MessageType.COMMAND) {
        return;
      }
      Command command = json.treeToValue(envelope.payload(), Command.class);
      if (dtn.active() && command.command() != CommandType.DTN_PROCESS && command.command() != CommandType.LIST_PORTS)
        throw new IllegalStateException("DTN 작업 중에는 다른 시험 명령을 실행할 수 없습니다.");
      if ((command.command() == CommandType.START_CAPTURE || command.command() == CommandType.ARM_RECEIVER
          || command.command() == CommandType.START_SENDER) && state.get() == AgentState.BUSY)
        throw new IllegalStateException("Agent가 다른 작업을 수행 중입니다.");
      switch (command.command()) {
        case DTN_PROCESS -> dtn.accept(envelope.sessionId(), command.arguments());
        case DTN_STOP_CAPTURE -> {
          capture.stopAndAwait();
          state.set(AgentState.READY);
          status(envelope.sessionId(), EventType.GNSS_STATUS, 100, "Stopped", "수집 종료 및 청크 전달 완료", Map.of());
        }
        case LIST_PORTS ->
            send(
                MessageType.PORT_LIST,
                envelope.sessionId(),
                json.valueToTree(
                    new PortList(
                        capture.portNames().stream()
                            .map(name -> new PortDescriptor(name, name))
                            .toList())));
        case START_CAPTURE -> startCapture(envelope.sessionId(), command.arguments());
        case STOP_CAPTURE -> stopCapture(envelope.sessionId());
        case CANCEL_SESSION -> cancel(envelope.sessionId());
        case ARM_RECEIVER -> startReceiver(envelope.sessionId(), command.arguments());
        case START_SENDER -> startSender(envelope.sessionId(), command.arguments());
      }
      ack(envelope, true, "Accepted");
    } catch (Exception error) {
      ack(envelope, false, error.getMessage());
      status(envelope.sessionId(), EventType.ERROR, 0, "Failed", safe(error), Map.of());
      boolean singleCapture = "START_CAPTURE".equals(envelope.payload().path("command").asText())
          && envelope.payload().path("arguments").path("singleEpoch").asBoolean();
      if (state.get() != AgentState.BUSY && !singleCapture) state.set(AgentState.ERROR);
    }
  }

  private void receiveInputChunk(Envelope envelope) {
    byte[] data = Base64.getDecoder().decode(envelope.payload().path("dataBase64").asText());
    sessionInputs
        .computeIfAbsent(envelope.sessionId(), ignored -> new ByteArrayOutputStream())
        .writeBytes(data);
  }

  private void stopCapture(UUID sessionId) {
    capture.stop();
    state.set(AgentState.READY);
    status(sessionId, EventType.GNSS_STATUS, 100, "Stopped", "GNSS capture stopped", Map.of());
  }

  private void cancel(UUID sessionId) {
    synchronized (afsStateLock) {
      // A delayed cancellation must not stop a newer trial.
      if (activeAfsSession != null && !activeAfsSession.equals(sessionId)) return;
      capture.stop();
      activeAfsSession = null;
      afs.cancel();
      state.set(AgentState.READY);
    }
    sessionInputs.remove(sessionId);
    status(sessionId, EventType.SESSION_STATUS, 0, "Cancelled", "Operation cancelled", Map.of());
  }

  /** Receiver 역할을 확인하고 관리 채널의 AFS frame 수신 상태를 준비한다. */
  private void startReceiver(UUID sessionId, JsonNode args) throws Exception {
    if (config.role() != server.shared.model.LnisModels.AgentRole.RECEIVER) {
      throw new IllegalStateException("Only RECEIVER can arm AFS reception");
    }
    var command = json.treeToValue(args, AfsSessionService.SessionCommand.class);
    beginAfs(sessionId);
    try {
      afs.arm(
          sessionId,
          command,
          (type, payload) -> event(sessionId, type, payload),
          evidence -> frameEvidence(sessionId, evidence),
          result -> completeRole(sessionId, result));
    } catch (RuntimeException error) {
      abandonAfs(sessionId);
      throw error;
    }
  }

  /** 전달 완료된 GRAW 입력을 꺼내 AFS frame 생성 및 관리 채널 전송을 시작한다. */
  private void startSender(UUID sessionId, JsonNode args) throws Exception {
    if (config.role() != server.shared.model.LnisModels.AgentRole.SENDER) {
      throw new IllegalStateException("Only SENDER can start AFS transmission");
    }
    ByteArrayOutputStream input = sessionInputs.remove(sessionId);
    if (input == null || input.size() == 0) {
      throw new IllegalStateException("No GRAW input was transferred");
    }
    var command = json.treeToValue(args, AfsSessionService.SessionCommand.class);
    beginAfs(sessionId);
    try {
      afs.send(
          sessionId,
          command,
          input.toByteArray(),
          (type, payload) -> event(sessionId, type, payload),
          evidence -> frameEvidence(sessionId, evidence),
          result -> completeRole(sessionId, result),
          new AfsSessionService.TransferSink() {
            @Override public void start(AfsTransferStart start) {
              send(MessageType.AFS_TRANSFER_START, sessionId, json.valueToTree(start));
            }
            @Override public void batch(AfsTransferBatch batch) {
              send(MessageType.AFS_TRANSFER_BATCH, sessionId, json.valueToTree(batch));
            }
            @Override public void complete(AfsTransferComplete complete) {
              send(MessageType.AFS_TRANSFER_COMPLETE, sessionId, json.valueToTree(complete));
            }
          });
    } catch (RuntimeException error) {
      abandonAfs(sessionId);
      throw error;
    }
  }

  /** AFS 원문 증거는 전용 메시지로 중앙 서버에 보낸다. */
  private void frameEvidence(UUID sessionId, FrameEvidenceMessage evidence) {
    send(MessageType.FRAME_EVIDENCE, sessionId, json.valueToTree(evidence));
  }

  private boolean isActiveAfs(UUID sessionId) {
    synchronized (afsStateLock) {
      return sessionId != null && sessionId.equals(activeAfsSession);
    }
  }

  private void beginAfs(UUID sessionId) {
    synchronized (afsStateLock) {
      if (activeAfsSession != null) throw new IllegalStateException("AFS operation already running");
      activeAfsSession = sessionId;
      state.set(AgentState.BUSY);
    }
  }

  private boolean abandonAfs(UUID sessionId) {
    synchronized (afsStateLock) {
      if (!sessionId.equals(activeAfsSession)) return false;
      activeAfsSession = null;
      state.set(AgentState.READY);
      return true;
    }
  }

  private void completeRole(UUID sessionId, Object result) {
    if (abandonAfs(sessionId))
      send(MessageType.ROLE_RESULT, sessionId, json.valueToTree(result));
  }

  /** COM 포트 설정을 역직렬화하고 canonical GRAW 청크 callback을 등록한다. */
  private void startCapture(UUID sessionId, JsonNode args) throws Exception {
    if (config.role() != server.shared.model.LnisModels.AgentRole.SENDER) {
      throw new IllegalStateException("Only SENDER can capture GNSS");
    }
    var settings = json.treeToValue(args, SerialCaptureService.Settings.class);
    var capturedPvt = new AtomicReference<List<server.shared.model.DtnModels.Pvt>>();
    var selection = settings.singleEpoch() ? new server.agent.gnss.SingleEpochCapture(records -> {
      try (var pvt = new server.agent.codec.NativePvtCodec(config.nativeDirectory())) {
        var results = pvt.calculate(records);
        var result = results.getFirst();
        if (!result.isPositionValid() || !result.isVelocityValid()) return false;
        capturedPvt.set(results);
        return true;
      }
    }) : null;
    state.set(AgentState.BUSY);
    try {
    capture.start(
        settings,
        chunk -> publishCaptureChunk(sessionId, chunk),
        error -> {
          state.set(settings.singleEpoch() ? AgentState.READY : AgentState.ERROR);
          status(sessionId, EventType.ERROR, 0, "CaptureFailed", safe(error), Map.of());
        }, selection, () -> {
          state.set(AgentState.READY);
          status(sessionId, EventType.GNSS_STATUS, 100, "SingleEpochComplete", "한 시점 수집·지구 PVT 검증 완료", Map.of("pvt", capturedPvt.get()));
        });
    } catch (Exception error) {
      state.set(AgentState.READY);
      throw error;
    }
  }

  private void publishCaptureChunk(UUID sessionId, SerialCaptureService.CaptureChunk chunk) {
    var payload =
        JsonNodeFactory.instance
            .objectNode()
            .put("chunkIndex", chunk.index())
            .put("bytesRead", chunk.bytesRead())
            .put("records", chunk.records())
            .put("rawBase64", Base64.getEncoder().encodeToString(chunk.rawSerial()))
            .put("canonicalBase64", Base64.getEncoder().encodeToString(chunk.canonical()));
    send(MessageType.INPUT_CHUNK, sessionId, payload);
    status(
        sessionId,
        EventType.GNSS_STATUS,
        0,
        "Capturing",
        chunk.bytesRead() + " bytes",
        Map.of("records", chunk.records()));
  }

  private void event(UUID sessionId, EventType type, Object payload) {
    if (payload instanceof Map<?, ?> map) {
      int percent = map.get("percent") instanceof Number number ? number.intValue() : 0;
      Object stageValue = map.containsKey("stage") ? map.get("stage") : "Running";
      Object messageValue = map.containsKey("message") ? map.get("message") : "";
      Map<String, Object> values = new LinkedHashMap<>();
      map.forEach((key, value) -> values.put(String.valueOf(key), value));
      status(
          sessionId,
          type,
          percent,
          String.valueOf(stageValue),
          String.valueOf(messageValue),
          values);
    } else {
      send(MessageType.STATUS, sessionId, json.valueToTree(payload));
    }
  }

  private void ack(Envelope original, boolean accepted, String message) {
    outbound.accept(
        new Envelope(
            server.shared.model.AgentProtocol.PROTOCOL_VERSION,
            MessageType.COMMAND_ACK,
            UUID.randomUUID(),
            original.messageId(),
            config.agentId(),
            config.role(),
            original.sessionId(),
            java.time.Instant.now(),
            json.valueToTree(new CommandAck(accepted, message))));
  }

  private void send(MessageType type, UUID sessionId, JsonNode payload) {
    outbound.accept(Envelope.of(type, config.agentId(), config.role(), sessionId, payload));
  }

  public void status(
      UUID sessionId,
      EventType type,
      int percent,
      String stage,
      String message,
      Map<String, Object> counters) {
    send(
        MessageType.STATUS,
        sessionId,
        json.valueToTree(new Progress(type, percent, stage, message, counters)));
  }

  private static String safe(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  @Override
  public void close() {
    capture.close();
    afs.close();
    codec.close();
  }
}
