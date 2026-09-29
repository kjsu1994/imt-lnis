package server.node;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import server.afs.NativeAfsCodec;
import server.common.AgentProtocol.*;
import server.common.LnisModels.AgentState;
import server.dtn.DtnProcessor;
import server.dtn.DtnWorker;
import server.gnss.SerialCaptureService;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** GNSS 수집과 DTN 계산을 로컬에서 실행하고 기존 화면 이벤트로 진행을 알린다. */
public final class AgentRuntime implements AutoCloseable {
    private final AgentConfig config;
    private final NativeAfsCodec codec;
    private final AgentMessageService messages;
    private final server.gnss.GnssConnection gnss;
    private final SerialCaptureService capture;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.READY);
    private final DtnWorker dtn;
    private volatile boolean closed;

    public AgentRuntime(AgentConfig config, NativeAfsCodec codec, AgentMessageService messages) {
        this.config = config;
        this.codec = codec;
        this.messages = messages;
        this.gnss = new server.gnss.GnssConnection(config.role() == server.common.LnisModels.AgentRole.SENDER);
        this.capture = new SerialCaptureService(gnss);
        this.dtn =
                new DtnWorker(
                        new DtnProcessor(codec, config.nativeDirectory()),
                        json,
                        config.role(),
                        state);
        json.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public AgentState state() {
        return closed ? AgentState.OFFLINE : state.get();
    }

    public server.gnss.GnssConnection gnss() {
        return gnss;
    }

    public void disconnectGnss(boolean stopCapture) throws InterruptedException {
        if (gnss.status().capturing()) {
            if (!stopCapture) {
                throw new IllegalStateException("수집 중입니다. 수집 중단을 확인한 후 연결을 해제하세요.");
            }
            capture.stopAndAwait();
        }
        gnss.disconnect();
    }

    public int codecAbiVersion() {
        return codec.abiVersion();
    }

    public DtnWorker worker() {
        if (closed) {
            throw new IllegalStateException("실행기가 종료되었습니다.");
        }
        return dtn;
    }

    public synchronized void execute(UUID sessionId, CommandType command, Object arguments) {
        if (closed) {
            throw new IllegalStateException("실행기가 종료되었습니다.");
        }
        try {
            if (dtn.active() && command != CommandType.LIST_PORTS) {
                throw new IllegalStateException("DTN 작업 중에는 다른 시험 명령을 실행할 수 없습니다.");
            }
            if (command == CommandType.START_CAPTURE && state.get() == AgentState.BUSY) {
                throw new IllegalStateException("실행기가 다른 작업을 수행 중입니다.");
            }
            switch (command) {
                case DTN_STOP_CAPTURE -> {
                    capture.stopAndAwait();
                    state.set(AgentState.READY);
                    status(
                            sessionId,
                            EventType.GNSS_STATUS,
                            100,
                            "Stopped",
                            "수집 종료 및 청크 전달 완료",
                            Map.of());
                }
                case LIST_PORTS ->
                        messages.ports(
                                config.agentId(),
                                config.role(),
                                new PortList(
                                        capture.ports().stream()
                                                .map(p -> new PortDescriptor(p.name(), p.description()))
                                                .toList()));
                case START_CAPTURE -> startCapture(sessionId, arguments);
                case STOP_CAPTURE -> stopCapture(sessionId);
            }
        } catch (Exception error) {
            status(sessionId, EventType.ERROR, 0, "Failed", safe(error), Map.of());
            boolean singleCapture =
                    command == CommandType.START_CAPTURE
                            && arguments != null
                            && json.valueToTree(arguments).path("singleEpoch").asBoolean();
            if (state.get() != AgentState.BUSY && !singleCapture) {
                state.set(AgentState.ERROR);
            }
            throw error instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(safe(error), error);
        }
    }

    private void stopCapture(UUID sessionId) {
        capture.stop();
        state.set(AgentState.READY);
        status(sessionId, EventType.GNSS_STATUS, 100, "Stopped", "GNSS capture stopped", Map.of());
    }

    /** COM 포트 설정을 역직렬화하고 canonical GRAW 청크 callback을 등록한다. */
    private void startCapture(UUID sessionId, Object args) throws Exception {
        if (config.role() != server.common.LnisModels.AgentRole.SENDER) {
            throw new IllegalStateException("Only SENDER can capture GNSS");
        }
        var settings = json.convertValue(args, SerialCaptureService.Settings.class);
        var capturedPvt = new AtomicReference<List<server.common.DtnModels.Pvt>>();
        var selection =
                settings.singleEpoch()
                        ? new server.gnss.SingleEpochCapture(
                                records -> {
                                    try (var pvt =
                                            new server.pvt.NativePvtCodec(
                                                    config.nativeDirectory())) {
                                        var results = pvt.calculate(records);
                                        var result = results.getFirst();
                                        capturedPvt.set(results);
                                        if (!result.isPositionValid()
                                                || !result.isVelocityValid()) {
                                            return false;
                                        }
                                        return true;
                                    }
                                })
                        : null;
        AgentState previous = state.get();
        if ((previous != AgentState.READY && previous != AgentState.ERROR)
                || !state.compareAndSet(previous, AgentState.BUSY)) {
            throw new IllegalStateException("다른 작업 진행 중");
        }
        try {
            capture.start(
                    settings,
                    chunk -> publishCaptureChunk(sessionId, chunk),
                    error -> {
                        state.set(settings.singleEpoch() ? AgentState.READY : AgentState.ERROR);
                        status(
                                sessionId,
                                EventType.ERROR,
                                0,
                                "CaptureFailed",
                                safe(error),
                                Map.of());
                    },
                    selection,
                    () -> {
                        state.set(AgentState.READY);
                        status(
                                sessionId,
                                EventType.GNSS_STATUS,
                                100,
                                selection.awaitingDecision() ? "CaptureDecisionRequired" : "SingleEpochComplete",
                                selection.awaitingDecision()
                                        ? "120초 종료 · 관측 데이터 확보 · PVT 조건 미충족 · 사용 여부 선택 대기"
                                        : "한 시점 수집·지구 PVT 검증 완료",
                                Map.of("pvt", capturedPvt.get()));
                    },
                    progress -> status(sessionId, EventType.GNSS_STATUS, 0,
                            progress.stage(), progress.message(), progress.counters()),
                    bytes -> {});
        } catch (Exception error) {
            state.set(AgentState.READY);
            throw error;
        }
    }

    private void publishCaptureChunk(UUID sessionId, SerialCaptureService.CaptureChunk chunk) {
        messages.input(sessionId, chunk.canonical());
        status(
                sessionId,
                EventType.GNSS_STATUS,
                0,
                "Capturing",
                chunk.bytesRead() + " bytes",
                Map.of("records", chunk.records()));
    }

    public void status(
            UUID id,
            EventType type,
            int percent,
            String stage,
            String message,
            Map<String, Object> counters) {
        messages.status(
                config.agentId(),
                config.role(),
                id,
                new Progress(type, percent, stage, message, counters));
    }

    private static String safe(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        capture.close();
        gnss.close();
        dtn.close();
        codec.close();
    }
}
