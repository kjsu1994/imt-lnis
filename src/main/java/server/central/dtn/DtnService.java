package server.central.dtn;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
    private final Map<UUID, Thread> tasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> cancelRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
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

    @Value("${lnis.dtn.development:false}")
    private boolean development;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DtnLogService logs;
    private void trace(UUID id, String stage, boolean detail, String message) {
        if(logs!=null) logs.add(id,"TEST",stage,detail,message);
    }
    private void beginLog(DtnJob job, UUID source) {
        if(logs==null) return;
        logs.copy(source,job.getId(),"TEST");
        trace(job.getId(),"시험",false,"전송시험 시작 · "+job.getTestType()+" · "+job.getSenderMode()+" → "+job.getReceiverMode());
    }
    private DtnNodeLink nodeLink;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IqService iq;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IqReceiver iqReceiver;

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
                Map.entry("development", development),
                Map.entry("iqEnabled", iq != null && iq.enabled()),
                Map.entry("configured",
                        !sendUrl.isBlank() && (sendingNode() || !receiveToken.isBlank())),
                Map.entry("defaultSendUrl", sendUrl),
                Map.entry("adapterUrl", nodeLink != null && !sendingNode() && !receiveUrl.isBlank() ? receiveUrl : sendUrl),
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
        return create(inputId, sender, receiver, requestedUrl, "AFS_METADATA");
    }

    public synchronized DtnJob create(UUID inputId, String sender, String receiver, String requestedUrl, String testType)
    {
        return create(inputId, sender, receiver, requestedUrl, testType, "DTN", "HDTN");
    }

    public synchronized DtnJob create(UUID inputId, String sender, String receiver, String requestedUrl,
            String testType, String senderMode, String receiverMode)
    {
        return create(inputId, sender, receiver, requestedUrl, testType, senderMode, receiverMode, null);
    }

    public synchronized DtnJob create(UUID inputId, String sender, String receiver, String requestedUrl,
            String testType, String senderMode, String receiverMode, HdtnConfig hdtnConfig)
    {
        if (hdtnConfig != null && !"HDTN".equals(senderMode) && !"HDTN".equals(receiverMode))
            throw new IllegalArgumentException("HDTN 설정은 HDTN이 포함된 전송 경로에서만 사용할 수 있습니다.");
        String hdtnConfigJson = hdtnConfig == null ? null : objectMapper.valueToTree(hdtnConfig).toString();
        URI destination = validateStart(sender, receiver, requestedUrl, testType, senderMode, receiverMode);
        if ("IQ_SAMPLE".equals(testType)) {
            if (iq == null || inputId == null) throw new IllegalArgumentException("생성된 I/Q 파일을 선택하세요.");
            DtnJob job = newJob(sender, receiver, destination, testType, senderMode, receiverMode);
            job.setHdtnConfigJson(hdtnConfigJson);
            job.setIqFileId(inputId);
            beginLog(job, inputId);
            update(job, "PREPARING", "I/Q 파일 무결성 확인 중");
            startTask(job.getId(), "iq-prepare", () -> prepareIq(job, inputId, senderMode, receiverMode));
            return job;
        }
        byte[] data = readInput(inputId);
        DtnJob job = newJob(sender, receiver, destination, testType, senderMode, receiverMode);
        job.setHdtnConfigJson(hdtnConfigJson);
        job.setInputId(inputId);
        beginLog(job,inputId);
        update(job, "PREPARING", "기준 PVT 계산 및 " + testType + " 준비 중");
        try {
            sendChunks(job, sender, "GNSS_RAW".equals(testType) ? "PREPARE_RAW" : "PREPARE", data);
        } catch (RuntimeException e) {
            fail(job, e);
        }
        return job;
    }

    private URI validateStart(String sender, String receiver, String requestedUrl, String testType,
            String senderMode, String receiverMode)
    {
        if (senderMode == null || receiverMode == null || !List.of("DTN", "HDTN").contains(senderMode) || !List.of("DTN", "HDTN").contains(receiverMode))
            throw new IllegalArgumentException("전송 경로는 DTN 또는 HDTN을 선택하세요.");
        if (testType == null || !List.of("AFS_METADATA", "GNSS_RAW", "IQ_SAMPLE").contains(testType))
            throw new IllegalArgumentException("지원하지 않는 시험 유형입니다.");
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
        return destination;
    }

    private DtnJob newJob(String sender, String receiver, URI destination, String testType,
            String senderMode, String receiverMode)
    {
        DtnJob job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setTestType(testType);
        job.setSenderMode(senderMode);
        job.setReceiverMode(receiverMode);
        job.setDevelopment(development);
        job.setSendUrl(destination.toString());
        job.setSenderAgentId(sender);
        job.setReceiverAgentId(receiver);
        job.setCreatedAt(Instant.now());
        return job;
    }

    private byte[] readInput(UUID inputId)
    {
        if (inputId == null) throw new IllegalArgumentException("GNSS 입력을 선택하세요.");
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
        return data.toByteArray();
    }

    private void prepareIq(DtnJob job, UUID inputId, String senderMode, String receiverMode)
    {
        try {
            IqFile file = iq.completed(inputId);
            trace(job.getId(),"I/Q 검증",false,"송신 파일 크기·SHA-256 확인 완료 · "+file.sizeBytes()+" bytes · "+file.sha256());
            Transfer transfer = new Transfer(); transfer.setTestId(job.getId());
            transfer.setTestType(job.getTestType()); transfer.setFormat("LNIS-IQ-FILE-v1");
            transfer.setProfile("LANS-AFS-IQ-v1");
            transfer.setSenderMode(senderMode); transfer.setReceiverMode(receiverMode); transfer.setFile(file);
            transfer.setHdtnConfig(job.getHdtnConfigJson() == null ? null : objectMapper.readValue(job.getHdtnConfigJson(), HdtnConfig.class));
            var source = iq.source(inputId, file);
            if (source != null) {
                transfer.setMetadata(source.metadata()); transfer.setReferencePvt(List.of(source.reference()));
                job.setReferenceJson(objectMapper.writeValueAsString(transfer.getReferencePvt()));
            }
            synchronized (this) {
                if (!"PREPARING".equals(get(job.getId()).getState())) return;
                var packet = objectMapper.valueToTree(transfer);
                ((com.fasterxml.jackson.databind.node.ObjectNode)packet).remove(List.of("prn", "recordCount"));
                job.setSentJson(objectMapper.writeValueAsString(packet));
                update(job, "WAITING_DTN", "I/Q 파일 경로 전달 및 수신 대기");
            }
            sendExternal(job.getId(), job.getSentJson());
        } catch (Exception error) { synchronized (this) { if (ACTIVE.contains(get(job.getId()).getState())) fail(job, error); } }
    }

    private void startTask(UUID id, String name, Runnable action)
    {
        Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try { if (isActive(id)) action.run(); }
            finally { tasks.remove(id, Thread.currentThread()); }
        });
        tasks.put(id, thread);
        thread.start();
    }

    private synchronized boolean isActive(UUID id)
    {
        return !Thread.currentThread().isInterrupted() && ACTIVE.contains(get(id).getState());
    }

    /** 결과를 보존하고 대기·작업만 중지한다. 같은 ID의 반복 중지는 멱등 처리한다. */
    public synchronized DtnJob cancel(UUID id)
    {
        DtnJob job = get(id);
        if (List.of("COMPLETED", "INCONCLUSIVE").contains(job.getState())) return job;
        if (!"CANCELLED".equals(job.getState())) {
            job.setCancelPending(sendingNode());
            update(job, "CANCELLED", sendingNode() ? "시험 중지 · 상대 수신 노드 중지 확인 대기" : "시험 중지");
            chunks.remove(id);
            Thread running = tasks.get(id);
            if (running != null) running.interrupt();
            List<String> agents = nodeLink == null ? List.of(job.getSenderAgentId(), job.getReceiverAgentId())
                    : List.of(sendingNode() ? job.getSenderAgentId() : job.getReceiverAgentId());
            for (String agent : agents) {
                try { agentCommandService.command(agent, id, CommandType.DTN_PROCESS, Map.of("mode", "CANCEL")); }
                catch (RuntimeException error) { trace(id, "시험 중지", false, "처리기 중지 요청 실패 · " + agent + " · " + error.getMessage()); }
            }
        }
        requestCancellation(job);
        return job;
    }

    private void requestCancellation(DtnJob job)
    {
        if (!sendingNode() || !Boolean.TRUE.equals(job.getCancelPending()) || !cancelRequests.add(job.getId())) return;
        Thread.ofVirtual().name("dtn-cancel-peer").start(() -> {
            try {
                nodeLink.cancel(job.getId());
                synchronized (this) {
                    DtnJob current = get(job.getId());
                    current.setCancelPending(false);
                    update(current, current.getState(), "시험 중지 · 상대 수신 노드 정리 완료");
                }
            } catch (RuntimeException error) {
                // 영속 대기 표시를 유지한다. 연결 복구 후 tick에서 같은 ID만 재시도한다.
            } finally { cancelRequests.remove(job.getId()); }
        });
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
        if(received==null || !received.isObject()) throw new IllegalArgumentException("수신 본문은 JSON 객체여야 합니다.");
        UUID id = UUID.fromString(received.path("testId").asText());
        DtnJob job = get(id);
        if ("CANCELLED".equals(job.getState())) throw new IllegalStateException("중지된 시험에는 수신 데이터를 적용할 수 없습니다.");
        JsonNode adapterLogs = received.get("dtnLogsBase64");
        JsonNode plainLogs = received.get("dtnLogs");
        ((com.fasterxml.jackson.databind.node.ObjectNode) received).remove(java.util.List.of("dtnLogsBase64", "dtnLogs"));
        boolean samePayload = nodeLink == null
                ? job.getSentJson() != null && received.equals(objectMapper.readTree(job.getSentJson()))
                : DtnPayloadDigest.sha256(objectMapper, received).equals(job.getExpectedPayloadSha256());
        if (!samePayload) {
            if(logs!=null) logs.add(id,"TEST","ERROR","JSON 검증",false,"전송 원본과 수신 JSON 불일치 · 원본 필드·배열·숫자 변경 여부를 확인하세요.");
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
        String txMode = received.path("senderMode").asText("DTN");
        String rxMode = received.path("receiverMode").asText("HDTN");
        if (!List.of("DTN", "HDTN").contains(txMode) || !List.of("DTN", "HDTN").contains(rxMode))
            throw new IllegalArgumentException("수신 전송 경로 오류");
        job.setSenderMode(txMode);
        job.setReceiverMode(rxMode);
        job.setHdtnConfigJson(received.hasNonNull("hdtnConfig") ? received.get("hdtnConfig").toString() : null);
        String type = received.path("testType").asText("AFS_METADATA");
        if (!List.of("AFS_METADATA", "GNSS_RAW", "IQ_SAMPLE").contains(type)) throw new IllegalArgumentException("시험 유형 오류");
        job.setTestType(type);
        if ("IQ_SAMPLE".equals(type)) {
            String path = received.path("file").path("filePath").asText();
            if (!path.matches("/exchange/[0-9a-fA-F-]{36}\\.bin")) throw new IllegalArgumentException("I/Q 공유 경로 오류");
            job.setIqFileId(UUID.fromString(path.substring(10,46)));
        }
        job.setDevelopment(development);
        job.setReceivedJson(objectMapper.writeValueAsString(received));
        job.setReceivedAt(Instant.now());
        trace(id,"JSON 접수",true,"원본 동일성 확인·저장 완료 · "+body.length+" bytes · "+type+" · "+txMode+" → "+rxMode);
        update(job, "WAITING_RECEIVER", "DTN 수신 완료 / Receiver 연결 대기");
        if (logs != null && (adapterLogs != null || plainLogs != null))
            logs.adapter(job.getId(), adapterLogs, plainLogs, job.getReceivedAt());
        return job;
    }

    /** 검증 실패를 대기로 남기지 않는다. 완료 결과와 처리 중인 최초 수신은 보호한다. */
    public synchronized void rejectReceipt(UUID id, String reason) {
        if(id==null) return;
        DtnJob job=dtnRepository.findById(id).orElse(null);
        if(job!=null && "WAITING_DTN".equals(job.getState()) && job.getReceivedAt()==null)
            fail(job,new IllegalArgumentException("수신 검증 실패 · "+reason+" · 수신 원문 기록 확인 필요"));
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
            if(payload.has("progress")) {
                var progress=payload.path("progress");
                trace(job.getId(),progress.path("stage").asText("처리"),true,progress.path("message").asText());
                return;
            }
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
            if(logs!=null) logs.pvt(job.getId(),"TEST",result.getPvt(),0);
            if (result.getObservations() != null)
                job.setObservationsJson(objectMapper.writeValueAsString(result.getObservations()));
            if (preparing) {
                if (result.getTransfer() == null
                        || !job.getId().equals(result.getTransfer().getTestId())) {
                    throw new IllegalArgumentException("송신 payload 식별 오류");
                }
                job.setReferenceJson(objectMapper.writeValueAsString(result.getPvt()));
                result.getTransfer().setReferencePvt(result.getPvt());
                result.getTransfer().setSenderMode(job.getSenderMode());
                result.getTransfer().setReceiverMode(job.getReceiverMode());
                result.getTransfer().setHdtnConfig(job.getHdtnConfigJson() == null ? null : objectMapper.readValue(job.getHdtnConfigJson(), HdtnConfig.class));
                job.setSentJson(objectMapper.writeValueAsString(result.getTransfer()));
                update(job, "WAITING_DTN", "외부 DTN 전달 및 수신 대기");
                String packet = job.getSentJson();
                startTask(job.getId(), "dtn-http-send", () -> sendExternal(job.getId(), packet));
            } else {
                job.setReceiverJson(objectMapper.writeValueAsString(result.getPvt()));
                if (nodeLink != null && !nodeLink.sender()) {
                    var receivedTransfer=objectMapper.readValue(job.getReceivedJson(),Transfer.class);
                    if(receivedTransfer.getReferencePvt()!=null && !receivedTransfer.getReferencePvt().isEmpty()) {
                        try {
                            var comparison=DtnComparison.compare(receivedTransfer.getReferencePvt(),result.getPvt());
                            trace(job.getId(),"PVT 비교",false,"송·수신 비교 · "+comparison.get("verdict"));
                            trace(job.getId(),"PVT 비교",true,objectMapper.writeValueAsString(comparison));
                        } catch (IllegalArgumentException invalidReference) {
                            trace(job.getId(),"PVT 비교",false,"비교 불가 · 송신 기준 PVT를 확인하세요.");
                        }
                    }
                    // 최종 송신 판정과 별도로 수신 화면 비교 근거를 기록한다.
                    update(job, "COMPLETED", "수신 입력 복원 및 PVT 계산 완료");
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
        for (DtnJob job : dtnRepository.findByCancelPendingTrue()) requestCancellation(job);
        for (DtnJob job : dtnRepository.findByStateIn(ACTIVE)) {
            int timeoutMinutes="IQ_SAMPLE".equals(job.getTestType()) ? 20 : 10;
            if (Instant.now().isAfter(job.getCreatedAt().plus(Duration.ofMinutes(timeoutMinutes)))) {
                fail(job, new IllegalStateException("DTN 시험 제한 시간 "+timeoutMinutes+"분 초과"));
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
                    if ("IQ_SAMPLE".equals(job.getTestType())) {
                        update(job, "CALCULATING", "수신 공유 I/Q 파일 검증 중");
                        startTask(job.getId(), "iq-verify", () -> verifyIq(job));
                        continue;
                    }
                    update(job, "CALCULATING", "Receiver 입력 복원 및 PVT 계산 중");
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
            if (!isActive(id)) return;
            if (sendingNode()) {
                // 수신 DB 등록이 성공한 뒤에만 실제 본문을 외부 DTN에 전달한다.
                trace(id,"시험 등록",true,"수신 서비스에 시험 등록 요청");
                nodeLink.register(get(id));
                trace(id,"시험 등록",true,"수신 서비스 시험 등록 완료");
            }
            if (!isActive(id)) return;
            URI destination = DtnDestination.resolve(get(id).getSendUrl(), sendUrl);
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(destination)
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json");
            if (!sendToken.isBlank() && DtnDestination.usesConfiguredToken(destination, sendUrl)) {
                request.header("Authorization", "Bearer " + sendToken);
            }
            trace(id,"어댑터",true,"JSON 전달 요청 · "+packet.getBytes(StandardCharsets.UTF_8).length+" bytes");
            log.info(
                "DTN_SEND_BODY testId={} BEGIN\n{}\nDTN_SEND_BODY END testId={}",id,DtnLogService.prettyBody(objectMapper,packet),id);
            long started=System.nanoTime();
            int status =
                    httpClient
                            .send(
                                    request.POST(HttpRequest.BodyPublishers.ofString(packet)).build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode();
            trace(id,"어댑터",true,"HTTP "+status+" · "+((System.nanoTime()-started)/1_000_000)+" ms");
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("외부 DTN HTTP 응답: " + status);
            }
            trace(id,"어댑터",false,"어댑터 접수 완료 · 상대 수신 완료와 구분");
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
        if (logs != null) logs.importAdapter(job.getId(), result.getAdapterLogs());
        if (result.getReceivedAt() != null && job.getReceivedAt() == null) {
            job.setReceivedAt(result.getReceivedAt());
            update(job, "WAITING_DTN", "수신 노드 접수 완료 / PVT 계산 결과 대기");
        }
        if ("FAILED".equals(result.getState())) {
            fail(job, new IllegalStateException("수신 노드 실패: " + result.getMessage()));
        } else if ("COMPLETED".equals(result.getState())) {
            try {
                if ("IQ_SAMPLE".equals(job.getTestType())) {
                    if (result.getReceivedAt() == null || result.getFileResult() == null)
                        throw new IllegalArgumentException("I/Q 완료 결과 누락");
                    IqFile file = objectMapper.readValue(job.getSentJson(), Transfer.class).getFile();
                    JsonNode verified = result.getFileResult();
                    if (!"PASS".equals(verified.path("verdict").asText()) || file.sizeBytes() != verified.path("sizeBytes").asLong()
                            || !file.sha256().equals(verified.path("sha256").asText()))
                        throw new IllegalArgumentException("수신 I/Q 검증 결과 불일치");
                    job.setFileResultJson(verified.toString());
                    var sent = objectMapper.readValue(job.getSentJson(), Transfer.class);
                    if (sent.getMetadata()!=null) {
                        if (!(sent.getMetadata() instanceof IqMetadata metadata))
                            throw new IllegalArgumentException("I/Q metadata 형식 오류");
                        if (result.getPvt()==null) throw new IllegalArgumentException("I/Q 수신 PVT 결과 누락");
                        job.setReceiverJson(objectMapper.writeValueAsString(result.getPvt()));
                        var reference=IqReceiver.references(metadata,sent.getReferencePvt(),result.getPvt());
                        job.setReferenceJson(objectMapper.writeValueAsString(reference));
                        job.setComparisonJson(objectMapper.writeValueAsString(IqReceiver.comparison(reference,result.getPvt())));
                    }
                    update(job, "COMPLETED", "I/Q 파일 일치 · "+result.getMessage()); return;
                }
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

    private void verifyIq(DtnJob job) {
        try {
            Transfer transfer = objectMapper.readValue(job.getReceivedJson(), Transfer.class);
            if (!"IQ_SAMPLE".equals(transfer.getTestType()) || !"LNIS-IQ-FILE-v1".equals(transfer.getFormat())
                    || transfer.getSchemaVersion() != 1 || transfer.getFrames() != null || transfer.getGrawBase64() != null)
                throw new IllegalArgumentException("I/Q 전송 형식 오류");
            trace(job.getId(),"I/Q 검증",true,"수신 파일 존재·크기·SHA-256 확인 시작 · "+transfer.getFile().filePath());
            long started=System.nanoTime();
            var result = new LinkedHashMap<>(iq.verify(transfer.getFile()));
            trace(job.getId(),"I/Q 검증",true,"검증 완료 · "+result+" · "+((System.nanoTime()-started)/1_000_000)+" ms");
            result.put("preview", iq.preview(transfer.getFile()));
            IqReceiver.Result decoded=null;
            List<Pvt> reference=List.of();
            Map<String,Object> comparison=Map.of("verdict","INCONCLUSIVE","message","과거 파일: I/Q PVT 메타데이터 없음");
            if(transfer.getMetadata()!=null) {
                if (!(transfer.getMetadata() instanceof IqMetadata metadata))
                    throw new IllegalArgumentException("I/Q metadata 형식 오류");
                if(iqReceiver==null) throw new IllegalStateException("I/Q 수신기 미설정");
                decoded=iqReceiver.decode(iq.path(transfer.getFile()),metadata,
                    message->trace(job.getId(),"I/Q 복원",true,message));
                // File verification and PVT accuracy are different results.
                iq.verify(transfer.getFile()); // Detect replacement/modification during tracking.
                reference=IqReceiver.references(metadata,transfer.getReferencePvt(),decoded.pvt());
                comparison=IqReceiver.comparison(reference,decoded.pvt());
            }
            synchronized (this) {
                if (!"CALCULATING".equals(get(job.getId()).getState())) return;
                job.setFileResultJson(objectMapper.writeValueAsString(result));
                job.setComparisonJson(objectMapper.writeValueAsString(comparison));
                if(decoded!=null) {
                    job.setReceiverJson(objectMapper.writeValueAsString(decoded.pvt()));
                    job.setObservationsJson(objectMapper.writeValueAsString(decoded.observations()));
                    job.setReferenceJson(objectMapper.writeValueAsString(reference));
                }
                update(job, "COMPLETED", decoded==null ? "파일 검증 완료 · 과거 파일은 PVT 메타데이터 없음"
                    : "파일 검증 완료 · 항법정보 보조 I/Q PVT: "+comparison.get("verdict"));
            }
        } catch (Exception error) { synchronized (this) { if ("CALCULATING".equals(get(job.getId()).getState())) fail(job, error); } }
    }

    public synchronized void deleteIq(UUID id) throws java.io.IOException {
        if (dtnRepository.existsByIqFileIdAndStateIn(id, ACTIVE)) throw new IllegalStateException("전송·검증 중인 파일은 삭제할 수 없습니다.");
        iq.delete(id);
    }

    private void compare(DtnJob job, List<Pvt> receiverPvt) throws Exception
    {
        List<Pvt> reference = objectMapper.readValue(job.getReferenceJson(), new TypeReference<>() {});
        Map<String, Object> comparison = DtnComparison.compare(reference, receiverPvt);
        job.setComparisonJson(objectMapper.writeValueAsString(comparison));
        trace(job.getId(),"PVT 비교",true,objectMapper.writeValueAsString(comparison));
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
        if(logs!=null) logs.add(job.getId(),"TEST","FAILED".equals(state)?"ERROR":"INCONCLUSIVE".equals(state)?"WARN":"INFO","시험",false,message);
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
