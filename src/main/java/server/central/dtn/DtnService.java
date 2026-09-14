package server.central.dtn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


import server.central.agent.AgentCommandService;
import server.central.agent.AgentConnectionRegistry;
import server.central.agent.AgentEntity;
import server.central.agent.AgentRepository;
import server.central.input.InputBufferEntity;
import server.central.input.InputBufferService;
import server.shared.codec.DtnChunks;
import server.shared.model.AgentProtocol.*;
import server.shared.model.DtnModels;
import server.shared.model.DtnModels.*;
import server.shared.model.LnisModels.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.Map;

/** 외부 REST 전달과 별도 Receiver PC의 계산을 조정하는 DTN 전용 서비스다. */
@RequiredArgsConstructor
@Service
public class DtnService {
    private static final List<String> ACTIVE =
            List.of("PREPARING", "WAITING_DTN", "WAITING_RECEIVER", "CALCULATING");
    private final DtnRepository dtnRepository;
    private final AgentCommandService agentCommandService;
    private final AgentRepository agentRepository;
    private final AgentConnectionRegistry agentConnectionRegistry;
    private final InputBufferService inputBufferService;
    private final ObjectMapper objectMapper;
    private final Map<UUID, DtnChunks> chunks = new HashMap<>();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).build();

    @Value("${lnis.dtn.send-url:}")
    private String sendUrl;

    @Value("${lnis.dtn.receive-url:}")
    private String receiveUrl;

    @Value("${lnis.dtn.send-token:}")
    private String sendToken;

    @Value("${lnis.dtn.receive-token:}")
    private String receiveToken;

    @Value("${lnis.dtn.example-enabled:false}")
    private boolean exampleEnabled;

    @Value("${lnis.dtn.sender-adapter-control-url:}")
    private String senderAdapterControlUrl;
    @Value("${lnis.dtn.receiver-adapter-control-url:}")
    private String receiverAdapterControlUrl;

    private DtnNodeLink nodeLink;

    /** 기존 중앙 서버 모드는 그대로 두고 독립 노드 모드에서만 관리 통신을 연결한다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNodeLink(DtnNodeLink nodeLink)
    {
        this.nodeLink = nodeLink;
    }

    private boolean sendingNode()
    {
        return nodeLink != null && nodeLink.sender();
    }

    /* 외부 연동 준비 여부와 지원 규격 조회 */
    public Map<String, Object> configuration()
    {
        return Map.ofEntries(
                Map.entry("exampleEnabled", exampleEnabled),
                Map.entry("adapterControlConfigured",
                        !senderAdapterControlUrl.isBlank() && !receiverAdapterControlUrl.isBlank()),
                Map.entry("configured",
                        !sendUrl.isBlank() && (sendingNode() || !receiveToken.isBlank())),
                Map.entry("defaultSendUrl", sendUrl),
                Map.entry("defaultReceiveUrl", receiveUrl.isBlank() ? sendUrl : receiveUrl),
                Map.entry("receiveConfigured", !receiveToken.isBlank()),
                Map.entry("sendReady", sendingNode() || !receiveToken.isBlank()),
                Map.entry("nodeRole",
                        nodeLink == null ? "CENTRAL" : (sendingNode() ? "SENDER" : "RECEIVER")),
                Map.entry("defaultSendTokenConfigured", !sendToken.isBlank()),
                Map.entry("profile", DtnModels.PROFILE),
                Map.entry("maximumInputBytes", DtnModels.MAX_INPUT_BYTES));
    }
    /* 입력과 Agent를 확인한 뒤 기준 PVT 계산 및 AFS 생성을 요청한다. */
    public synchronized DtnJob create(UUID inputId, String sender, String receiver)
    {
        return create(inputId, sender, receiver, null);
    }

    /** 기존 API는 환경 설정을 기본값으로 사용하고, 새 화면의 URL은 시험마다 별도로 확정한다. */
    public synchronized DtnJob create(UUID inputId, String sender, String receiver, String requestedUrl)
    {
        URI destination = DtnDestination.resolve(requestedUrl, sendUrl);
        if (nodeLink != null) {
            if (!nodeLink.sender()) {
                throw new IllegalStateException("시험 전송은 송신 노드에서 시작하세요.");
            }
            nodeLink.validateParticipants(sender, receiver);
        }
        if (!sendingNode() && receiveToken.isBlank()) {
            throw new IllegalStateException("DTN 수신 인증 토큰을 설정하세요.");
        }
        if (!dtnRepository.findByStateIn(ACTIVE).isEmpty()) {
            throw new IllegalStateException("다른 DTN 시험 진행 중");
        }
        requireAgent(sender, AgentRole.SENDER);
        AgentEntity rx =
                agentRepository
                        .find(receiver)
                        .orElseThrow(() -> new IllegalArgumentException("Receiver를 선택하세요."));
        if (rx.role() != AgentRole.RECEIVER) {
            throw new IllegalArgumentException("Receiver 역할 오류");
        }
        InputBufferEntity input = inputBufferService.get(inputId);
        if (!input.complete()
                || input.receivedSize() <= 0
                || input.receivedSize() > DtnModels.MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("수집 종료 후 1 MiB 이하의 입력을 확정하세요.");
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (long i = 0; i < input.chunkCount(); i++) {
            data.writeBytes(inputBufferService.chunk(inputId, i));
        }
        if (data.size() != input.receivedSize()) {
            throw new IllegalStateException("입력 크기 불일치");
        }
        DtnJob job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setSendUrl(destination.toString());
        job.setInputId(inputId);
        job.setSenderAgentId(sender);
        job.setReceiverAgentId(receiver);
        job.setCreatedAt(Instant.now());
        update(job, "PREPARING", "기준 PVT 계산 및 AFS 생성 중");
        try {
            sendChunks(job, sender, "PREPARE", data.toByteArray());
        } catch (RuntimeException e) {
            fail(job, e);
        }
        return job;
    }

    /** 브라우저 저장소에 의존하지 않아 별도 수신 PC에서도 최근 시험을 볼 수 있다. */
    public List<DtnJob> recent()
    {
        return dtnRepository.findTop50ByOrderByCreatedAtDesc();
    }

    /* 저장된 시험 조회: 존재하지 않으면 기존 조회 오류를 전달한다. */
    public DtnJob get(UUID id)
    {
        return dtnRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DTN 시험을 찾을 수 없습니다."));
    }

    /* 입력 존재 여부 확인 후 Sender 수집 종료 요청 */
    public UUID stopCapture(UUID id, String sender)
    {
        inputBufferService.get(id);
        return agentCommandService.command(sender, id, CommandType.DTN_STOP_CAPTURE, null);
    }

    /** 인증 및 동일성 검증 후 H2 저장이 끝나야 callback 접수를 완료한다. */
    public synchronized DtnJob receive(String authorization, byte[] body) throws Exception
    {
        authenticate(authorization);
        if (sendingNode()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DTN callback은 수신 노드로 보내세요.");
        }
        if (body.length > DtnModels.MAX_JSON_BYTES) {
            throw new IllegalArgumentException("DTN JSON 크기 초과");
        }
        JsonNode received = objectMapper.readTree(body);
        UUID id = UUID.fromString(received.path("testId").asText());
        DtnJob job = get(id);
        boolean samePayload = nodeLink == null
                ? job.getSentJson() != null && received.equals(objectMapper.readTree(job.getSentJson()))
                : DtnPayloadDigest.sha256(objectMapper, received).equals(job.getExpectedPayloadSha256());
        if (!samePayload) {
            throw new IllegalArgumentException("전송 JSON과 수신 JSON이 다릅니다.");
        }
        if (job.getReceivedJson() != null) {
            return job;
        }
        if (!"WAITING_DTN".equals(job.getState())) {
            throw new IllegalStateException("수신 대기 상태가 아닙니다.");
        }
        // 내부 계산용 JSON과 사용자 확인용 원문을 분리한다. 중복 callback은 최초 원문을 덮어쓰지 않는다.
        String receivedOriginal;
        try {
            receivedOriginal = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("DTN JSON 본문은 올바른 UTF-8이어야 합니다.", error);
        }
        job.setReceivedRawJson(receivedOriginal);
        job.setReceivedJson(objectMapper.writeValueAsString(received));
        job.setReceivedAt(Instant.now());
        update(job, "WAITING_RECEIVER", "DTN 수신 완료 / Receiver 연결 대기");
        return job;
    }

    /* 외부 DTN의 Bearer 토큰을 일정 시간 비교 방식으로 확인한다. */
    public void authenticate(String authorization)
    {
        if (receiveToken.isBlank()
                || authorization == null
                || !MessageDigest.isEqual(
                        ("Bearer " + receiveToken).getBytes(StandardCharsets.UTF_8),
                        authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @lombok.Value
    public static class PayloadResponse {
        byte[] body;
        String representation;
    }

    /** 헤더/토큰이 아닌 실제 JSON 본문만 조회한다. 과거 수신 자료는 정규화된 저장본임을 표시한다. */
    public PayloadResponse payload(UUID id, String direction)
    {
        DtnJob job = get(id);
        String body;
        String representation = "original";
        if ("sent".equals(direction)) {
            body = job.getSentJson();
        } else if ("received".equals(direction)) {
            body = job.getReceivedRawJson();
            if (body == null) {
                body = job.getReceivedJson();
                representation = "legacy-normalized";
            }
        } else {
            throw new IllegalArgumentException("JSON 방향은 sent 또는 received여야 합니다.");
        }
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "아직 해당 JSON 본문이 준비되지 않았습니다.");
        }
        return new PayloadResponse(body.getBytes(StandardCharsets.UTF_8), representation);
    }

    /** WebSocket 인증 ID를 기준으로 시험 소유 Agent를 재검증한다. */
    public synchronized void agentData(Envelope envelope) throws Exception
    {
        DtnJob job = get(envelope.sessionId());
        boolean preparing = "PREPARING".equals(job.getState());
        if (!preparing && !"CALCULATING".equals(job.getState())) {
            return;
        }
        String expected = preparing ? job.getSenderAgentId() : job.getReceiverAgentId();
        if (!expected.equals(envelope.agentId())) {
            throw new IllegalArgumentException("DTN 결과 Agent 불일치");
        }
        try {
            JsonNode payload = envelope.payload();
            byte[] bytes =
                    chunks.computeIfAbsent(job.getId(), ignored -> new DtnChunks())
                            .append(
                                    payload.path("index").asInt(-1),
                                    payload.path("last").asBoolean(),
                                    Base64.getDecoder()
                                            .decode(payload.path("dataBase64").asText()));
            if (bytes == null) {
                return;
            }
            chunks.remove(job.getId());
            AgentResult result = objectMapper.readValue(bytes, AgentResult.class);
            if (result.getError() != null) {
                throw new IllegalStateException(result.getError());
            }
            if (result.getPvt() == null || result.getPvt().isEmpty()) {
                throw new IllegalArgumentException("PVT 결과 없음");
            }
            if (result.getObservations() != null)
                job.setObservationsJson(objectMapper.writeValueAsString(result.getObservations()));
            if (preparing) {
                if (result.getTransfer() == null
                        || !job.getId().equals(result.getTransfer().getTestId())) {
                    throw new IllegalArgumentException("송신 payload 식별 오류");
                }
                job.setReferenceJson(objectMapper.writeValueAsString(result.getPvt()));
                job.setSentJson(objectMapper.writeValueAsString(result.getTransfer()));
                update(job, "WAITING_DTN", "외부 DTN 전달 및 수신 대기");
                String packet = job.getSentJson();
                Thread.ofVirtual()
                        .name("dtn-http-send")
                        .start(() -> sendExternal(job.getId(), packet));
            } else {
                job.setReceiverJson(objectMapper.writeValueAsString(result.getPvt()));
                if (nodeLink != null && !nodeLink.sender()) {
                    // 수신 노드에는 기준 PVT가 없다. 비교는 송신 노드에서만 수행한다.
                    update(job, "COMPLETED", "수신 AFS 복호화 및 PVT 계산 완료");
                } else {
                    compare(job, result.getPvt());
                }
            }
        } catch (Exception e) {
            fail(job, e);
        }
    }

    /** ACK 거절은 10분 타임아웃까지 기다리지 않고 즉시 실패로 기록한다. */
    public synchronized void rejected(Envelope envelope)
    {
        if (envelope.sessionId() == null) {
            return;
        }
        dtnRepository
                .findById(envelope.sessionId())
                .ifPresent(
                        job -> {
                            if (ACTIVE.contains(job.getState())
                                    && (job.getSenderAgentId().equals(envelope.agentId())
                                            || job.getReceiverAgentId()
                                                    .equals(envelope.agentId()))) {
                                fail(
                                        job,
                                        new IllegalStateException(
                                                envelope.payload()
                                                        .path("message")
                                                        .asText("Agent 명령 거절")));
                            }
                        });
    }

    /* 시험 제한 시간과 Receiver 연결 상태를 확인한다. */
    @Scheduled(fixedDelay = 3000)
    public synchronized void tick()
    {
        for (DtnJob job : dtnRepository.findByStateIn(ACTIVE)) {
            if (Instant.now().isAfter(job.getCreatedAt().plus(Duration.ofMinutes(10)))) {
                fail(job, new IllegalStateException("DTN 시험 제한 시간 10분 초과"));
                continue;
            }
            if (sendingNode() && "WAITING_DTN".equals(job.getState())) {
                pollReceiver(job);
                continue;
            }
            if ("WAITING_RECEIVER".equals(job.getState())
                    && agentConnectionRegistry.online(job.getReceiverAgentId())
                    && agentRepository
                            .find(job.getReceiverAgentId())
                            .map(a -> a.state() == AgentState.READY)
                            .orElse(false)) {
                try {
                    update(job, "CALCULATING", "Receiver AFS 복호화 및 PVT 계산 중");
                    sendChunks(
                            job,
                            job.getReceiverAgentId(),
                            "RECEIVE",
                            job.getReceivedJson().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    fail(job, e);
                }
            }
        }
    }

    /* 외부 DTN에 JSON 전달: 늦게 도착한 HTTP 실패가 수신 완료를 덮어쓰지 않게 한다. */
    private void sendExternal(UUID id, String packet)
    {
        try {
            if (sendingNode()) {
                // 수신 DB 등록이 성공한 뒤에만 실제 본문을 외부 DTN에 전달한다.
                nodeLink.register(get(id));
            }
            URI destination = DtnDestination.resolve(get(id).getSendUrl(), sendUrl);
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(destination)
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json");
            if (!sendToken.isBlank() && DtnDestination.usesConfiguredToken(destination, sendUrl)) {
                request.header("Authorization", "Bearer " + sendToken);
            }
            int status =
                    httpClient
                            .send(
                                    request.POST(HttpRequest.BodyPublishers.ofString(packet)).build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("외부 DTN HTTP 응답: " + status);
            }
        } catch (Exception e) {
            synchronized (this) {
                DtnJob job = get(id);
                // callback이 먼저 도착했다면 전달 성공 상태를 뒤늦은 HTTP 오류로 되돌리지 않는다.
                if ("WAITING_DTN".equals(job.getState()) && job.getReceivedAt() == null) {
                    fail(job, e);
                }
            }
        }
    }

    /** 관리 연결의 일시 중단은 재전송 없이 다음 상태 조회까지 기다린다. 전체 제한 시간은 유지한다. */
    private void pollReceiver(DtnJob job)
    {
        DtnRemoteResult result;
        try {
            result = nodeLink.result(job.getId());
        } catch (RuntimeException unavailable) {
            return;
        }
        if (result.getReceivedAt() != null && job.getReceivedAt() == null) {
            job.setReceivedAt(result.getReceivedAt());
            update(job, "WAITING_DTN", "수신 노드 접수 완료 / PVT 계산 결과 대기");
        }
        if ("FAILED".equals(result.getState())) {
            fail(job, new IllegalStateException("수신 노드 실패: " + result.getMessage()));
        } else if ("COMPLETED".equals(result.getState())) {
            try {
                if (result.getReceivedAt() == null || result.getPvt() == null || result.getPvt().isEmpty()) {
                    throw new IllegalArgumentException("수신 노드의 완료 결과가 불완전합니다.");
                }
                job.setReceiverJson(objectMapper.writeValueAsString(result.getPvt()));
                compare(job, result.getPvt());
            } catch (Exception error) {
                fail(job, error);
            }
        }
    }

    private void compare(DtnJob job, List<Pvt> receiverPvt) throws Exception
    {
        List<Pvt> reference = objectMapper.readValue(job.getReferenceJson(), new TypeReference<>() {});
        Map<String, Object> comparison = DtnComparison.compare(reference, receiverPvt);
        job.setComparisonJson(objectMapper.writeValueAsString(comparison));
        update(job, "INCONCLUSIVE".equals(comparison.get("verdict")) ? "INCONCLUSIVE" : "COMPLETED",
                "PVT 비교 완료: " + comparison.get("verdict"));
    }

    /* WebSocket 크기 제한에 맞춰 동일한 순서로 청크를 전달한다. */
    private void sendChunks(DtnJob job, String agent, String mode, byte[] bytes)
    {
        for (int offset = 0, index = 0;
                offset < bytes.length;
                offset += DtnChunks.CHUNK_BYTES, index++) {
            int end = Math.min(bytes.length, offset + DtnChunks.CHUNK_BYTES);
            agentCommandService.command(
                    agent,
                    job.getId(),
                    CommandType.DTN_PROCESS,
                    Map.of(
                            "mode",
                            mode,
                            "index",
                            index,
                            "last",
                            end == bytes.length,
                            "dataBase64",
                            Base64.getEncoder()
                                    .encodeToString(Arrays.copyOfRange(bytes, offset, end))));
        }
    }

    /* 지정 역할과 READY 연결 상태 확인 */
    private void requireAgent(String id, AgentRole role)
    {
        AgentEntity agent =
                agentRepository
                        .find(id)
                        .orElseThrow(() -> new IllegalArgumentException("Agent를 선택하세요."));
        if (agent.role() != role
                || agent.state() != AgentState.READY
                || !agentConnectionRegistry.online(id)) {
            throw new IllegalStateException("Agent가 연결된 READY 상태여야 합니다.");
        }
    }

    /* 시험 상태와 갱신 시각을 함께 저장한다. */
    private void update(DtnJob job, String state, String message)
    {
        job.setState(state);
        job.setMessage(message);
        job.setUpdatedAt(Instant.now());
        dtnRepository.save(job);
    }

    /* 미완성 청크를 정리하고 실패 원인을 저장한다. */
    private void fail(DtnJob job, Exception error)
    {
        chunks.remove(job.getId());
        String message =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        update(job, "FAILED", message.substring(0, Math.min(2000, message.length())));
    }
}
