package server.agent.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import server.agent.codec.NativeAfsCodec;
import server.agent.config.AgentConfig;
import server.agent.gnss.SerialCaptureService;
import server.shared.model.AgentProtocol.*;
import server.shared.model.LnisModels.AgentState;

/** GNSS 수집과 DTN 계산 명령을 실행하고 상태를 보고한다. */
public final class AgentRuntime implements AutoCloseable {
  private final AgentConfig config;
  private final NativeAfsCodec codec;
  private final SerialCaptureService capture = new SerialCaptureService();
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.READY);
  private final server.agent.dtn.DtnWorker dtn;
  private volatile Consumer<Envelope> outbound = ignored -> {};

  public AgentRuntime(AgentConfig config, NativeAfsCodec codec) {
    this.config = config;
    this.codec = codec;
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
      if (envelope.type() != MessageType.COMMAND) {
        return;
      }
      Command command = json.treeToValue(envelope.payload(), Command.class);
      if (dtn.active() && command.command() != CommandType.DTN_PROCESS && command.command() != CommandType.LIST_PORTS)
        throw new IllegalStateException("DTN 작업 중에는 다른 시험 명령을 실행할 수 없습니다.");
      if (command.command() == CommandType.ARM_RECEIVER || command.command() == CommandType.START_SENDER
          || command.command() == CommandType.CANCEL_SESSION) {
        ack(envelope, false, "독립 AFS 시험은 지원하지 않습니다.");
        return;
      }
      if (command.command() == CommandType.START_CAPTURE && state.get() == AgentState.BUSY)
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
        default -> throw new IllegalArgumentException("지원하지 않는 실행 명령입니다.");
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

  private void stopCapture(UUID sessionId) {
    capture.stop();
    state.set(AgentState.READY);
    status(sessionId, EventType.GNSS_STATUS, 100, "Stopped", "GNSS capture stopped", Map.of());
  }

  /** COM 포트 설정을 역직렬화하고 canonical GRAW 청크 callback을 등록한다. */
  private void startCapture(UUID sessionId, JsonNode args) throws Exception {
    if (config.role() != server.shared.model.LnisModels.AgentRole.SENDER) {
      throw new IllegalStateException("Only SENDER can capture GNSS");
    }
    var settings = json.treeToValue(args, SerialCaptureService.Settings.class);
    var capturedPvt = new AtomicReference<List<server.shared.model.DtnModels.Pvt>>();
    var selection = settings.singleEpoch() ? new server.shared.codec.SingleEpochCapture(records -> {
      status(sessionId,EventType.GNSS_STATUS,0,"PvtCalculating","관측값·항법정보 후보 확보 · 지구 PVT 계산 중",Map.of());
      try (var pvt = new server.shared.codec.NativePvtCodec(config.nativeDirectory())) {
        var results = pvt.calculate(records);
        var result = results.getFirst();
        if (!result.isPositionValid() || !result.isVelocityValid()) {
          status(sessionId,EventType.GNSS_STATUS,0,"PvtWaiting","유효한 위치·속도 해 미확보 · 추가 관측값·항법정보 대기",Map.of());
          return false;
        }
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
    codec.close();
  }
}
