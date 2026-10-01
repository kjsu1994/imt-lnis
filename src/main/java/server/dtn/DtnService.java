package server.dtn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import server.common.AgentProtocol.*;
import server.common.DtnModels;
import server.common.DtnModels.*;
import server.common.LnisModels.*;
import server.gnss.GrawCodec;
import server.gnss.InputBufferEntity;
import server.gnss.InputBufferService;
import server.iq.IqReceiver;
import server.iq.IqService;
import server.node.AgentCommandService;
import server.node.AgentConnectionRegistry;
import server.node.AgentEntity;
import server.node.AgentRepository;
import server.pvt.DtnComparison;
import server.pvt.DtnDelay;
import server.pvt.DtnPvtCalculator;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private server.management.DataManagementGuard managementGuard;

    private final Map<UUID, Thread> tasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> cancelRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> nextCancellation = new java.util.concurrent.ConcurrentHashMap<>();
    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DtnLogService logs;

    private void trace(UUID id, String stage, boolean detail, String message) {
        if (logs != null) {
            logs.add(id, "TEST", stage, detail, message);
        }
    }

    private void beginLog(DtnJob job, UUID source) {
        if (logs == null) {
            return;
        }
        if (!"DELAY".equals(job.getComparisonMode())) {
            logs.copy(source, job.getId(), "TEST");
        }
        trace(
                job.getId(),
                "시험",
                false,
                "전송시험 시작 · "
                        + job.getTestType()
                        + " · "
                        + job.getSenderMode()
                        + " → "
                        + job.getReceiverMode());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DtnPvtCalculator pvtCalculator;

    private DtnNodeLink nodeLink;

    public record EpochChoice(DtnDelay.Epoch epoch, Pvt reference, boolean afsReady) {
        public EpochChoice(DtnDelay.Epoch epoch, Pvt reference) {
            this(epoch, reference, true);
        }
    }

    public List<EpochChoice> delayEpochs(UUID inputId) {
        if (pvtCalculator == null) {
            throw new IllegalStateException("지연 시험은 통합 노드에서 지원됩니다.");
        }

        var records = GrawCodec.splitLengthPrefixed(readInput(inputId));
        var values = pvtCalculator.calculate(records);
        List<EpochChoice> choices = new ArrayList<>();
        int pvtIndex = 0;

        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            var message = GrawCodec.decode(records.get(recordIndex)).message();
            if (message instanceof GrawCodec.ObservationEpoch epoch) {
                var identity =
                        new DtnDelay.Epoch(recordIndex, epoch.week(), epoch.receiverTowSeconds());
                var selected = GrawCodec.splitLengthPrefixed(DtnDelay.select(records, identity));
                boolean afsReady;
                try {
                    afsReady = server.afs.AfsPvtFrameCodec.select(selected).stream()
                            .anyMatch(payload -> payload.navigation() != null);
                } catch (IllegalArgumentException error) {
                    // RAW 입력 조회는 AFS로 표현할 수 없는 관측값 때문에 실패시키지 않는다.
                    afsReady = false;
                }
                choices.add(new EpochChoice(identity, values.get(pvtIndex++), afsReady));
            }
        }
        return choices;
    }

    public synchronized DtnJob createDelay(
            UUID inputId,
            String sender,
            String receiver,
            String url,
            String testType,
            String senderMode,
            String receiverMode,
            HdtnConfig config,
            DtnDelay.Epoch epoch,
            Instant startedAt) {
        if (pvtCalculator == null || nodeLink == null || !nodeLink.sender()) {
            throw new IllegalStateException("지연 시험은 송신 통합 노드에서 시작하세요.");
        }
        if (!List.of("GNSS_RAW", "AFS_METADATA").contains(testType)) {
            throw new IllegalArgumentException("지연 시험 유형 오류");
        }
        if (epoch == null) {
            epoch =
                    delayEpochs(inputId).stream()
                            .filter(choice -> choice.reference().isPositionValid() || approvedCapture(inputId))
                            .map(EpochChoice::epoch)
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalArgumentException("위치 PVT가 유효한 Epoch가 없습니다."));
        }

        return createConfigured(
                inputId,
                sender,
                receiver,
                url,
                testType,
                senderMode,
                receiverMode,
                config,
                epoch,
                startedAt);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IqService iq;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IqReceiver iqReceiver;

    /** 기존 중앙 서버 모드는 그대로 두고 독립 노드 모드에서만 관리 통신을 연결한다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNodeLink(DtnNodeLink nodeLink) {
        this.nodeLink = nodeLink;
    }

    boolean sendingNode() {
        return nodeLink != null && nodeLink.sender();
    }

    /* 외부 연동 준비 여부와 지원 규격 조회 */
    /** 관리 삭제는 취소된 작업의 실제 종료까지 기다린다. */
    public synchronized boolean managementBusy() {
        return !tasks.isEmpty() || agentCommandService.busy();
    }

    public synchronized boolean sendBusy() {
        return (sendingNode() && managementBusy())
                || dtnRepository.existsByState("PREPARING")
                || dtnRepository.existsByStateInAndSendStatusIn(
                        ACTIVE, List.of("PREPARING", "REQUESTING"));
    }

    public Map<String, Object> configuration() {
        return Map.ofEntries(
                Map.entry("delaySupported", pvtCalculator != null && nodeLink != null),
                Map.entry("sendBusy", sendBusy()),
                Map.entry("exampleEnabled", exampleEnabled),
                Map.entry("development", development),
                Map.entry("iqEnabled", iq != null && iq.enabled()),
                Map.entry(
                        "configured",
                        !sendUrl.isBlank() && (sendingNode() || !receiveToken.isBlank())),
                Map.entry("defaultSendUrl", sendUrl),
                Map.entry(
                        "adapterUrl",
                        nodeLink != null && !sendingNode() && !receiveUrl.isBlank()
                                ? receiveUrl
                                : sendUrl),
                Map.entry("receiveConfigured", !receiveToken.isBlank()),
                Map.entry("sendReady", sendingNode() || !receiveToken.isBlank()),
                Map.entry(
                        "nodeRole",
                        nodeLink == null ? "CENTRAL" : (sendingNode() ? "SENDER" : "RECEIVER")),
                Map.entry("defaultSendTokenConfigured", !sendToken.isBlank()),
                Map.entry("profile", DtnModels.PROFILE),
                Map.entry("maximumInputBytes", DtnModels.MAX_INPUT_BYTES));
    }

    /* 입력과 Agent를 확인한 뒤 기준 PVT 계산 및 AFS 생성을 요청한다. */
    public synchronized DtnJob create(UUID inputId, String sender, String receiver) {
        return create(inputId, sender, receiver, null);
    }

    /** 기존 API는 환경 설정을 기본값으로 사용하고, 새 화면의 URL은 시험마다 별도로 확정한다. */
    public synchronized DtnJob create(
            UUID inputId, String sender, String receiver, String requestedUrl) {
        return create(inputId, sender, receiver, requestedUrl, "AFS_METADATA");
    }

    public synchronized DtnJob create(
            UUID inputId, String sender, String receiver, String requestedUrl, String testType) {
        return create(inputId, sender, receiver, requestedUrl, testType, "DTN", "HDTN");
    }

    public synchronized DtnJob create(
            UUID inputId,
            String sender,
            String receiver,
            String requestedUrl,
            String testType,
            String senderMode,
            String receiverMode) {
        return create(
                inputId, sender, receiver, requestedUrl, testType, senderMode, receiverMode, null);
    }

    public synchronized DtnJob create(
            UUID inputId,
            String sender,
            String receiver,
            String requestedUrl,
            String testType,
            String senderMode,
            String receiverMode,
            HdtnConfig hdtnConfig) {
        return createConfigured(
                inputId,
                sender,
                receiver,
                requestedUrl,
                testType,
                senderMode,
                receiverMode,
                hdtnConfig,
                null,
                null);
    }

    private DtnJob createConfigured(
            UUID inputId,
            String sender,
            String receiver,
            String requestedUrl,
            String testType,
            String senderMode,
            String receiverMode,
            HdtnConfig hdtnConfig,
            DtnDelay.Epoch epoch,
            Instant startedAt) {
        if (hdtnConfig != null && !"HDTN".equals(senderMode) && !"HDTN".equals(receiverMode)) {
            throw new IllegalArgumentException("HDTN 설정은 HDTN이 포함된 전송 경로에서만 사용할 수 있습니다.");
        }
        String hdtnConfigJson =
                hdtnConfig == null ? null : objectMapper.valueToTree(hdtnConfig).toString();
        URI destination =
                validateStart(sender, receiver, requestedUrl, testType, senderMode, receiverMode);
        if ("IQ_SAMPLE".equals(testType)) {
            if (iq == null || inputId == null) {
                throw new IllegalArgumentException("생성된 I/Q 파일을 선택하세요.");
            }
            DtnJob job = newJob(sender, receiver, destination, testType, senderMode, receiverMode);
            job.setHdtnConfigJson(hdtnConfigJson);
            job.setIqFileId(inputId);
            beginLog(job, inputId);
            update(job, "PREPARING", "I/Q 파일 무결성 확인 중");
            startTask(
                    job.getId(),
                    "iq-prepare",
                    () -> prepareIq(job, inputId, senderMode, receiverMode));
            return job;
        }
        inputBufferService.requireApproved(inputId);
        byte[] data = readInput(inputId);
        if (epoch != null) {
            data = DtnDelay.select(GrawCodec.splitLengthPrefixed(data), epoch);
            var reference = pvtCalculator.calculate(GrawCodec.splitLengthPrefixed(data));
            if (reference.size() != 1
                    || (!reference.getFirst().isPositionValid() && !approvedCapture(inputId))) {
                throw new IllegalArgumentException("선택 Epoch의 Reference 위치 PVT를 계산할 수 없습니다.");
            }
            if ("AFS_METADATA".equals(testType)
                    && server.afs.AfsPvtFrameCodec.select(GrawCodec.splitLengthPrefixed(data)).stream()
                            .noneMatch(payload -> payload.navigation() != null)) {
                throw new IllegalArgumentException("AFS 생성에 필요한 GPS LNAV 항법정보 부족 · GNSS RAW로 전송 가능");
            }
        }
        DtnJob job = newJob(sender, receiver, destination, testType, senderMode, receiverMode);
        job.setHdtnConfigJson(hdtnConfigJson);
        job.setInputId(inputId);
        if (epoch != null) {
            job.setComparisonMode("DELAY");
            job.setTestStartedAt(java.util.Objects.requireNonNull(startedAt));
            job.setSelectedEpochJson(objectMapper.valueToTree(epoch).toString());
        }
        if (epoch != null) job.setReferenceSourceBase64(Base64.getEncoder().encodeToString(data));
        beginLog(job, inputId);
        if (epoch != null) {
            trace(
                    job.getId(),
                    "송신 시험 조건",
                    false,
                    "지연 반영 비교 · 1 Epoch · 선택 " + epoch + " · 시험용 입력 " + data.length + " bytes");
            trace(
                    job.getId(),
                    "송신 시작",
                    true,
                    "시작 접수 " + startedAt + " · 이후 준비/변환/전송 대기 포함 · 시계 동기화 정확도 미확인");
        }
        update(job, "PREPARING", "기준 PVT 계산 및 " + testType + " 준비 중");
        try {
            agentCommandService.prepare(
                    sender,
                    job.getId(),
                    data,
                    "GNSS_RAW".equals(testType),
                    startedAt,
                    (stage, message) -> calculationProgress(job.getId(), stage, message),
                    result -> calculated(sender, job.getId(), result));
        } catch (RuntimeException e) {
            fail(job, e);
        }
        return job;
    }

    private URI validateStart(
            String sender,
            String receiver,
            String requestedUrl,
            String testType,
            String senderMode,
            String receiverMode) {
        if (senderMode == null
                || receiverMode == null
                || !List.of("DTN", "HDTN").contains(senderMode)
                || !List.of("DTN", "HDTN").contains(receiverMode)) {
            throw new IllegalArgumentException("전송 경로는 DTN 또는 HDTN을 선택하세요.");
        }
        if (testType == null
                || !List.of("AFS_METADATA", "GNSS_RAW", "IQ_SAMPLE").contains(testType)) {
            throw new IllegalArgumentException("지원하지 않는 시험 유형입니다.");
        }
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
        if (sendBusy()) {
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

    private DtnJob newJob(
            String sender,
            String receiver,
            URI destination,
            String testType,
            String senderMode,
            String receiverMode) {
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
        job.setSendStatus("PREPARING");
        return job;
    }

    private byte[] readInput(UUID inputId) {
        if (inputId == null) {
            throw new IllegalArgumentException("GNSS 입력을 선택하세요.");
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
        return data.toByteArray();
    }

    public synchronized void recordClock(DtnJob job, server.common.ServiceClock.Stamp stamp, boolean sender) {
        String value = objectMapper.valueToTree(stamp).toString();
        if (sender) {
            job.setSenderClockJson(value);
        } else if (job.getReceiverClockJson() == null && job.getReceivedAt() != null) {
            job.setReceiverClockJson(value);
            String before = job.getReceiverRegistrationClockJson();
            if (before != null) {
                try {
                    var previous = objectMapper.readValue(before, server.common.ServiceClock.Stamp.class);
                    if (!previous.session().equals(stamp.session()) || previous.revision() != stamp.revision()
                            || previous.clockChanges() != stamp.clockChanges()) {
                        job.setClockWarning("시험 중 시계 변경 또는 서비스 재시작 감지 · 전달 지연 해석 주의");
                    }
                } catch (java.io.IOException invalid) {
                    job.setClockWarning("등록 시각의 보정 근거를 확인할 수 없습니다.");
                }
            }
            if ("SYSTEM".equals(stamp.source()) || stamp.ageSeconds() > 300) {
                if (job.getClockWarning() == null) {
                    job.setClockWarning("수신 시험 시각 미보정 또는 마지막 보정 후 5분 경과");
                }
            }
            try {
                var source = job.getSenderClockJson() == null ? null
                        : objectMapper.readValue(job.getSenderClockJson(), server.common.ServiceClock.Stamp.class);
                if (job.getClockWarning() == null && (source == null || "SYSTEM".equals(source.source())
                        || source.ageSeconds() > 300)) {
                    job.setClockWarning("송신 시험 시각 미보정 또는 보정 정보가 오래됨");
                }
            } catch (java.io.IOException invalid) {
                job.setClockWarning("송신 시각 보정 근거를 확인할 수 없습니다.");
            }
        }
        dtnRepository.saveAndFlush(job);
    }

    private boolean approvedCapture(UUID inputId) {
        if (inputId == null) {
            return false;
        }
        var input = inputBufferService.get(inputId);
        return input != null && "ACCEPTED".equals(input.captureDecision());
    }

    private void prepareIq(DtnJob job, UUID inputId, String senderMode, String receiverMode) {
        try {
            IqFile file = iq.completed(inputId);
            trace(
                    job.getId(),
                    "I/Q 검증",
                    false,
                    "송신 파일 크기·SHA-256 확인 완료 · " + file.sizeBytes() + " bytes · " + file.sha256());
            Transfer transfer = new Transfer();
            transfer.setTestId(job.getId());
            transfer.setTestType(job.getTestType());
            transfer.setFormat("LNIS-IQ-FILE-v1");
            transfer.setProfile("LANS-AFS-IQ-v1");
            transfer.setSenderMode(senderMode);
            transfer.setReceiverMode(receiverMode);
            transfer.setFile(file);
            transfer.setHdtnConfig(
                    job.getHdtnConfigJson() == null
                            ? null
                            : objectMapper.readValue(job.getHdtnConfigJson(), HdtnConfig.class));
            var source = iq.source(inputId, file);
            if (source != null) {
                transfer.setMetadata(source.metadata());
                transfer.setReferencePvt(List.of(source.reference()));
                if (IqReceiver.FRAME_METHOD.equals(source.metadata().pvtMethod())) {
                    transfer.setSchemaVersion(2);
                    transfer.setFormat("LNIS-IQ-FILE-v2");
                }
                job.setReferenceJson(objectMapper.writeValueAsString(transfer.getReferencePvt()));
            }
            synchronized (this) {
                if (!"PREPARING".equals(get(job.getId()).getState())) {
                    return;
                }
                var packet = objectMapper.valueToTree(transfer);
                ((com.fasterxml.jackson.databind.node.ObjectNode) packet)
                        .remove(List.of("prn", "recordCount"));
                job.setSentJson(objectMapper.writeValueAsString(packet));
                update(job, "WAITING_DTN", "I/Q 파일 경로 전달 및 수신 대기");
            }
            sendExternal(job.getId(), job.getSentJson());
        } catch (Exception error) {
            synchronized (this) {
                if (ACTIVE.contains(get(job.getId()).getState())) {
                    fail(job, error);
                }
            }
        }
    }

    private void startTask(UUID id, String name, Runnable action) {
        Thread thread =
                Thread.ofVirtual()
                        .name(name)
                        .unstarted(
                                () -> {
                                    try {
                                        if (isActive(id)) {
                                            action.run();
                                        }
                                    } finally {
                                        tasks.remove(id, Thread.currentThread());
                                    }
                                });
        tasks.put(id, thread);
        thread.start();
    }

    private synchronized boolean isActive(UUID id) {
        return !Thread.currentThread().isInterrupted() && ACTIVE.contains(get(id).getState());
    }

    /** 결과를 보존하고 대기·작업만 중지한다. 같은 ID의 반복 중지는 멱등 처리한다. */
    public synchronized DtnJob cancel(UUID id) {
        DtnJob job = get(id);
        if (List.of("COMPLETED", "INCONCLUSIVE").contains(job.getState())) {
            return job;
        }
        if (Boolean.TRUE.equals(job.getCancelWaitingOnly())) {
            job.setCancelWaitingOnly(false);
            dtnRepository.save(job);
        }
        if (!"CANCELLED".equals(job.getState())) {
            job.setCancelPending(sendingNode());
            update(job, "CANCELLED", sendingNode() ? "시험 중지 · 상대 수신 노드 중지 확인 대기" : "시험 중지");

            Thread running = tasks.get(id);
            if (running != null) {
                running.interrupt();
            }
            List<String> agents =
                    nodeLink == null
                            ? List.of(job.getSenderAgentId(), job.getReceiverAgentId())
                            : List.of(
                                    sendingNode()
                                            ? job.getSenderAgentId()
                                            : job.getReceiverAgentId());
            for (String agent : agents) {
                try {
                    agentCommandService.cancel(agent, id);
                } catch (RuntimeException error) {
                    trace(
                            id,
                            "시험 중지",
                            false,
                            "처리기 중지 요청 실패 · " + agent + " · " + error.getMessage());
                }
            }
        }
        nextCancellation.remove(id);
        requestCancellation(job);
        return job;
    }

    public record WaitingSummary(int count, Instant asOf, List<UUID> testIds) {}
    public record WaitingCancellation(int requested, int cancelled, int skipped, int pending) {}

    public synchronized WaitingSummary waitingSummary() {
        Instant asOf = Instant.now();
        List<UUID> ids = waitingCandidates(asOf);
        return new WaitingSummary(ids.size(), asOf, ids);
    }

    private boolean cancellableWait(UUID id, String state, String sendStatus,
            Boolean cancelPending, Instant receivedAt, Instant createdAt, Instant asOf) {
        return "WAITING_DTN".equals(state) && receivedAt == null
                && !Boolean.TRUE.equals(cancelPending)
                && createdAt != null && !createdAt.isAfter(asOf)
                && !List.of("PREPARING", "REQUESTING").contains(Objects.toString(sendStatus, ""))
                && !tasks.containsKey(id);
    }

    private List<UUID> waitingCandidates(Instant asOf) {
        return dtnRepository.findTasksByStateIn(List.of("WAITING_DTN")).stream()
                .filter(job -> cancellableWait(job.getId(), job.getState(), job.getSendStatus(),
                        job.getCancelPending(), job.getReceivedAt(), job.getCreatedAt(), asOf))
                .map(DtnRepository.TaskView::getId).toList();
    }

    public WaitingCancellation cancelWaiting(Instant asOf, List<UUID> testIds) {
        if (testIds == null || testIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(testIds).size() != testIds.size()) {
            throw new IllegalArgumentException("수신 대기 확인 목록을 다시 조회하세요.");
        }
        if (asOf == null || asOf.isAfter(Instant.now().plusSeconds(5))) {
            throw new IllegalArgumentException("수신 대기 확인 시각을 다시 조회하세요.");
        }
        List<UUID> ids = List.copyOf(testIds);
        int cancelled = 0, skipped = 0, pending = 0;
        for (UUID id : ids) {
            synchronized (this) {
                DtnJob job = dtnRepository.findById(id).orElse(null);
                if (job == null || !cancellableWait(job.getId(), job.getState(), job.getSendStatus(),
                        job.getCancelPending(), job.getReceivedAt(), job.getCreatedAt(), asOf)
                        || job.getReceivedJson() != null) {
                    skipped++;
                    continue;
                }
                if (sendingNode()) {
                    job.setCancelPending(true);
                    job.setCancelWaitingOnly(true);
                    update(job, "WAITING_DTN", "수신 대기 종료 요청 · 상대 미수신 여부 확인 중");
                    nextCancellation.remove(id);
                    requestCancellation(job);
                    pending++;
                } else {
                    closeWaiting(id, "USER_BULK");
                    cancelled++;
                }
            }
        }
        return new WaitingCancellation(ids.size(), cancelled, skipped, pending);
    }

    /** HTTP 거절 이후 정리는 본문 도착과 같은 잠금에서 미수신 여부를 확인한다. */
    public synchronized void closeWaiting(UUID id) {
        closeWaiting(id, "ADAPTER_REJECTED");
    }

    public synchronized void closeWaiting(UUID id, String reason) {
        if (!List.of("ADAPTER_REJECTED", "USER_BULK").contains(reason)) {
            throw new IllegalArgumentException("수신 대기 종료 사유 오류");
        }
        if (sendingNode()) throw new IllegalArgumentException("수신 노드 전용 요청입니다.");
        DtnJob job = get(id);
        if ("WAITING_DTN".equals(job.getState()) && job.getReceivedAt() == null
                && job.getReceivedJson() == null) {
            update(job, "CANCELLED", "USER_BULK".equals(reason)
                    ? "사용자 요청 · 미수신 대기 종료" : "어댑터 전송 거절 · 미수신 대기 종료");
        }
    }

    /** 송신부의 오래된 상태만으로 상대 계산을 멈추지 않는다. */
    private void applyWaitingClosure(DtnJob job, DtnRemoteResult result) {
        if (result.getState() == null || "WAITING_DTN".equals(result.getState())) {
            throw new IllegalStateException("상대 미수신 종료 여부 미확인");
        }
        job.setCancelPending(false);
        job.setCancelWaitingOnly(false);
        if (!"WAITING_DTN".equals(job.getState())) {
            dtnRepository.save(job);
            return;
        }
        if ("CANCELLED".equals(result.getState()) && result.getReceivedAt() == null) {
            update(job, "CANCELLED", "수신 대기 종료 · 상대 미수신 확인");
            return;
        }
        applyReceiverResult(job, result);
        dtnRepository.save(job);
        trace(job.getId(), "상대 결과 확인", false, "이미 수신된 시험 · 종료하지 않고 결과 확인 유지");
    }

    private void requestCancellation(DtnJob job) {
        if (!sendingNode()
                || !Boolean.TRUE.equals(job.getCancelPending())
                || Instant.now().isBefore(nextCancellation.getOrDefault(job.getId(), Instant.MIN))
                || !cancelRequests.add(job.getId())) {
            return;
        }
        Thread.ofVirtual()
                .name("dtn-cancel-peer")
                .start(
                        () -> {
                            try {
                                boolean rejected, waitingOnly;
                                synchronized (this) {
                                    DtnJob current = get(job.getId());
                                    waitingOnly = Boolean.TRUE.equals(current.getCancelWaitingOnly());
                                    rejected = "FAILED".equals(current.getState())
                                            && "REJECTED".equals(current.getSendStatus());
                                }
                                DtnRemoteResult result = null;
                                if (waitingOnly) result = nodeLink.closeWaiting(job.getId(), "USER_BULK");
                                else if (rejected) result = nodeLink.closeWaiting(job.getId());
                                else nodeLink.cancel(job.getId());
                                if ((waitingOnly || rejected) && result == null) {
                                    throw new IllegalStateException("상대 종료 확인 응답 누락");
                                }
                                synchronized (this) {
                                    DtnJob current = get(job.getId());
                                    // 정리 도중 사용자가 중지했다면 다음 순환에서 일반 중지를 보낸다.
                                    if (rejected && !"FAILED".equals(current.getState())) return;
                                    if (waitingOnly != Boolean.TRUE.equals(current.getCancelWaitingOnly())) return;
                                    if (waitingOnly) {
                                        applyWaitingClosure(current, result);
                                        nextCancellation.remove(current.getId());
                                        return;
                                    }
                                    current.setCancelPending(false);
                                    nextCancellation.remove(current.getId());
                                    if (rejected) {
                                        if (result != null && result.getReceivedAt() != null) {
                                            current.setReceivedAt(result.getReceivedAt());
                                            current.setMessage(current.getMessage() + " · 상대 수신 기록 확인");
                                        }
                                        dtnRepository.save(current);
                                        trace(current.getId(), "상대 결과 확인", true,
                                                "전송 거절 후 정리 확인 · 수신 결과가 있으면 유지");
                                    } else {
                                        update(current, current.getState(),
                                                "시험 중지 · 상대 수신 노드 정리 완료", "상대 결과 확인");
                                    }
                                }
                            } catch (RuntimeException error) {
                                // 기존 사용자의 개별 중지는 기존 주기로 재시도한다.
                                DtnJob current = get(job.getId());
                                if (!"FAILED".equals(current.getState())
                                        && !Boolean.TRUE.equals(current.getCancelWaitingOnly())) return;
                                // 미지원 endpoint도 일반 중지로 대체하지 않는다.
                                boolean first = nextCancellation.put(job.getId(), Instant.now().plusSeconds(30)) == null;
                                if (first && logs != null) {
                                    String reason = error instanceof server.node.NodePeerClient.RemoteRequestException remote
                                            ? "HTTP " + remote.statusCode() : error.getClass().getSimpleName();
                                    logs.add(job.getId(), "TEST", "WARN", "상대 결과 확인", false,
                                            "상대 대기 정리 미확인 · 30초 후 재시도 · " + reason);
                                }
                            } finally {
                                cancelRequests.remove(job.getId());
                            }
                        });
    }

    /** 브라우저 저장소에 의존하지 않아 별도 수신 PC에서도 최근 시험을 볼 수 있다. */
    public List<DtnJob> recent() {
        return dtnRepository.findTop50ByOrderByCreatedAtDesc();
    }

    public List<DtnJob> recent(int page, String state) {
        if (page < 0 || page > 100000) throw new IllegalArgumentException("목록 페이지 오류");
        var paging = org.springframework.data.domain.PageRequest.of(page, 50);
        if (state == null || state.isBlank()) {
            return dtnRepository.findAllByOrderByCreatedAtDescIdDesc(paging);
        }
        if (!"WAITING_DTN".equals(state)) throw new IllegalArgumentException("목록 상태 오류");
        return dtnRepository.findByStateOrderByCreatedAtDescIdDesc(state, paging);
    }

    /* 저장된 시험 조회: 존재하지 않으면 기존 조회 오류를 전달한다. */
    public DtnJob get(UUID id) {
        return dtnRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DTN 시험을 찾을 수 없습니다."));
    }

    /* 입력 존재 여부 확인 후 Sender 수집 종료 요청 */
    public UUID stopCapture(UUID id, String sender) {
        inputBufferService.get(id);
        return agentCommandService.command(sender, id, CommandType.DTN_STOP_CAPTURE, null);
    }

    /** 인증 및 동일성 검증 후 H2 저장이 끝나야 callback 접수를 완료한다. */
    public synchronized DtnJob receive(String authorization, byte[] body) throws Exception {
        return receive(authorization, body, Instant.now());
    }

    public synchronized DtnJob receive(String authorization, byte[] body, Instant receivedAt)
            throws Exception {
        authenticate(authorization);
        if (sendingNode()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DTN callback은 수신 노드로 보내세요.");
        }
        if (body.length > DtnModels.MAX_JSON_BYTES) {
            throw new IllegalArgumentException("DTN JSON 크기 초과");
        }
        JsonNode received = objectMapper.readTree(body);
        if (received == null || !received.isObject()) {
            throw new IllegalArgumentException("수신 본문은 JSON 객체여야 합니다.");
        }
        UUID id = UUID.fromString(received.path("testId").asText());
        DtnJob job = get(id);
        JsonNode adapterLogs = received.get("dtnLogsBase64");
        JsonNode plainLogs = received.get("dtnLogs");
        ((com.fasterxml.jackson.databind.node.ObjectNode) received)
                .remove(java.util.List.of("dtnLogsBase64", "dtnLogs"));
        boolean samePayload =
                nodeLink == null
                        ? job.getSentJson() != null
                                && received.equals(objectMapper.readTree(job.getSentJson()))
                        : DtnPayloadDigest.sha256(objectMapper, received)
                                .equals(job.getExpectedPayloadSha256());
        if (!samePayload) {
            if (logs != null) {
                logs.add(
                        id,
                        "TEST",
                        "ERROR",
                        "JSON 검증",
                        false,
                        "전송 원본과 수신 JSON 불일치 · 원본 필드·배열·숫자 변경 여부를 확인하세요.");
            }
            throw new IllegalArgumentException("전송 JSON과 수신 JSON이 다릅니다.");
        }
        if ("CANCELLED".equals(job.getState())) {
            if (job.getLateReceivedAt() == null) {
                job.setLateReceivedAt(receivedAt);
                update(job, "CANCELLED", "대기 종료 · 이후 수신됨 · 원문만 보관");
            }
            return job;
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
            receivedOriginal =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("DTN JSON 본문은 올바른 UTF-8이어야 합니다.", error);
        }
        job.setReceivedRawJson(receivedOriginal);
        String txMode = received.path("senderMode").asText("DTN");
        String rxMode = received.path("receiverMode").asText("HDTN");
        if (!List.of("DTN", "HDTN").contains(txMode) || !List.of("DTN", "HDTN").contains(rxMode)) {
            throw new IllegalArgumentException("수신 전송 경로 오류");
        }
        job.setSenderMode(txMode);
        job.setReceiverMode(rxMode);
        job.setHdtnConfigJson(
                received.hasNonNull("hdtnConfig") ? received.get("hdtnConfig").toString() : null);
        String type = received.path("testType").asText("AFS_METADATA");
        if (!List.of("AFS_METADATA", "GNSS_RAW", "IQ_SAMPLE").contains(type)) {
            throw new IllegalArgumentException("시험 유형 오류");
        }
        job.setTestType(type);
        if ("IQ_SAMPLE".equals(type)) {
            String path = received.path("file").path("filePath").asText();
            if (!path.matches("/exchange/[0-9a-fA-F-]{36}\\.bin")) {
                throw new IllegalArgumentException("I/Q 공유 경로 오류");
            }
            job.setIqFileId(UUID.fromString(path.substring(10, 46)));
        }
        job.setDevelopment(development);
        job.setReceivedJson(objectMapper.writeValueAsString(received));
        job.setReceivedAt(receivedAt);
        trace(
                id,
                "JSON 접수",
                true,
                "원본 동일성 확인·저장 완료 · "
                        + body.length
                        + " bytes · "
                        + type
                        + " · "
                        + txMode
                        + " → "
                        + rxMode);
        update(job, "WAITING_RECEIVER", "DTN 수신 완료 / Receiver 연결 대기");
        if (logs != null && (adapterLogs != null || plainLogs != null)) {
            logs.adapter(job.getId(), adapterLogs, plainLogs, job.getReceivedAt());
        }
        return job;
    }

    /** 검증 실패를 명시하되 정상 재수신은 허용한다. 최초 정상 수신과 완료 결과는 보호한다. */
    public synchronized void rejectReceipt(UUID id, String reason) {
        if (id == null) {
            return;
        }
        DtnJob job = dtnRepository.findById(id).orElse(null);
        if (job != null && "WAITING_DTN".equals(job.getState()) && job.getReceivedAt() == null) {
            update(job, "WAITING_DTN", "수신 검증 실패 · 정상 데이터 재수신 대기 · " + reason);
        }
    }

    /* 외부 DTN의 Bearer 토큰을 일정 시간 비교 방식으로 확인한다. */
    public void authenticate(String authorization) {
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
    public PayloadResponse payload(UUID id, String direction) {
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

    /** 삭제 보호 잠금 뒤에 시험 잠금을 잡아 삭제와 계산 완료의 경합을 방지한다. */
    public void calculated(String agentId, UUID id, AgentResult result) {
        var lock = managementGuard == null ? null : managementGuard.gate.readLock();
        if (lock != null) {
            lock.lock();
        }
        try {
            if (managementGuard == null || !managementGuard.deleted("DTN", id)) {
                applyCalculated(agentId, id, result);
            }
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    private void calculationProgress(UUID id, String stage, String message) {
        var lock = managementGuard == null ? null : managementGuard.gate.readLock();
        if (lock != null) {
            lock.lock();
        }
        try {
            if (managementGuard == null || !managementGuard.deleted("DTN", id)) {
                applyCalculationProgress(id, stage, message);
            }
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    private synchronized void applyCalculationProgress(UUID id, String stage, String message) {
        var job = dtnRepository.findById(id).orElse(null);
        if (job != null && List.of("PREPARING", "CALCULATING").contains(job.getState())) {
            trace(id, stage, true, message);
        }
    }

    private synchronized void applyCalculated(String agentId, UUID id, AgentResult result) {
        DtnJob job = dtnRepository.findById(id).orElse(null);
        if (job == null) {
            return;
        }
        boolean preparing = "PREPARING".equals(job.getState());
        if (!preparing && !"CALCULATING".equals(job.getState())) {
            return;
        }
        String expected = preparing ? job.getSenderAgentId() : job.getReceiverAgentId();
        if (!expected.equals(agentId)) {
            throw new IllegalArgumentException("DTN 결과 실행기 불일치");
        }
        try {
            if (result.getError() != null) {
                throw new IllegalStateException(result.getError());
            }
            if (result.getPvt() == null || result.getPvt().isEmpty()) {
                throw new IllegalArgumentException("PVT 결과 없음");
            }
            if (logs != null && (preparing || !"DELAY".equals(job.getComparisonMode()))) {
                logs.pvt(
                        job.getId(),
                        "TEST",
                        result.getPvt(),
                        0,
                        preparing ? "송신 Reference" : "수신 PVT");
            }
            if (result.getObservations() != null) {
                job.setObservationsJson(objectMapper.writeValueAsString(result.getObservations()));
            }
            if (preparing) {
                if (result.getTransfer() == null
                        || !job.getId().equals(result.getTransfer().getTestId())) {
                    throw new IllegalArgumentException("송신 payload 식별 오류");
                }
                if ("DELAY".equals(job.getComparisonMode())
                        && (result.getPvt().size() != 1
                                || (!result.getPvt().getFirst().isPositionValid()
                                    && !approvedCapture(job.getInputId())))) {
                    throw new IllegalArgumentException("송신 Reference PVT 계산 불가");
                }
                job.setReferenceJson(objectMapper.writeValueAsString(result.getPvt()));
                if (!server.afs.DelayTransferCodec.supports(result.getTransfer())) {
                    result.getTransfer().setReferencePvt(result.getPvt());
                }
                result.getTransfer().setSenderMode(job.getSenderMode());
                result.getTransfer().setReceiverMode(job.getReceiverMode());
                result.getTransfer()
                        .setHdtnConfig(
                                job.getHdtnConfigJson() == null
                                        ? null
                                        : objectMapper.readValue(
                                                job.getHdtnConfigJson(), HdtnConfig.class));
                job.setSentJson(objectMapper.writeValueAsString(
                        server.afs.DelayTransferCodec.packet(objectMapper, result.getTransfer())));
                update(job, "WAITING_DTN", "외부 DTN 전달 및 수신 대기");
                String packet = job.getSentJson();
                startTask(job.getId(), "dtn-http-send", () -> sendExternal(job.getId(), packet));
            } else {
                job.setReceiverJson(objectMapper.writeValueAsString(result.getPvt()));
                if ("DELAY".equals(job.getComparisonMode())) {
                    if (result.getDelayEvidence() == null) {
                        throw new IllegalArgumentException("지연 계산 근거 누락");
                    }
                    job.setDelayEvidenceJson(
                            objectMapper.writeValueAsString(result.getDelayEvidence()));
                    var transfer = objectMapper.readValue(job.getReceivedJson(), Transfer.class);
                    if (server.afs.DelayTransferCodec.supports(transfer)) {
                        job.setReferenceStatus("WAITING");
                        job.setReferenceAttempts(0);
                        job.setReferenceNextAttemptAt(Instant.now());
                        job.setReferenceMessage("비교자료 대기");
                        if (logs != null) {
                            logs.delay(job.getId(), result.getDelayEvidence());
                            logs.pvt(job.getId(), "TEST", result.getPvt(), 0, "수신 독립 계산 PVT");
                            trace(job.getId(), "수신 계산 입력", true, job.getObservationsJson());
                        }
                        update(job, result.getPvt().getFirst().isPositionValid()
                                ? "COMPLETED" : "INCONCLUSIVE", "수신 계산 종료 · 비교자료 대기");
                        return;
                    }
                    job.setReferenceJson(
                            objectMapper.writeValueAsString(transfer.getReferencePvt()));
                    if (logs != null) {
                        logs.delay(job.getId(), result.getDelayEvidence());
                        logs.pvt(job.getId(), "TEST", result.getPvt(), 0, "수신 지연 반영 PVT");
                    }
                    compareDelay(job, result.getPvt(), false);
                    return;
                }
                if (nodeLink != null && !nodeLink.sender()) {
                    var receivedTransfer =
                            objectMapper.readValue(job.getReceivedJson(), Transfer.class);
                    if (receivedTransfer.getReferencePvt() != null
                            && !receivedTransfer.getReferencePvt().isEmpty()) {
                        try {
                            var comparison =
                                    DtnComparison.compare(
                                            receivedTransfer.getReferencePvt(), result.getPvt());
                            trace(
                                    job.getId(),
                                    "PVT 비교",
                                    false,
                                    "송·수신 비교 · " + comparison.get("verdict"));
                            trace(
                                    job.getId(),
                                    "PVT 비교",
                                    true,
                                    objectMapper.writeValueAsString(comparison));
                        } catch (IllegalArgumentException invalidReference) {
                            trace(job.getId(), "PVT 비교", false, "비교 불가 · 송신 기준 PVT를 확인하세요.");
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

    /* 시험 제한 시간과 Receiver 연결 상태를 확인한다. */
    @Scheduled(fixedDelay = 3000)
    public synchronized void tick() {
        for (DtnJob job : dtnRepository.findByCancelPendingTrue()) {
            requestCancellation(job);
        }
        var waiting = new ArrayList<>(dtnRepository.findTasksByStateIn(ACTIVE));
        waiting.sort(Comparator.comparing(DtnRepository.TaskView::getReceivedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(DtnRepository.TaskView::getId));
        if (sendingNode()) {
            waiting.sort(Comparator.comparing(job ->
                    nextResultPoll.getOrDefault(job.getId(), Instant.MIN)));
        }
        Set<UUID> waitingIds = waiting.stream().map(DtnRepository.TaskView::getId)
                .collect(java.util.stream.Collectors.toSet());
        nextResultPoll.keySet().removeIf(id -> !waitingIds.contains(id));
        for (DtnRepository.TaskView job : waiting) {
            int timeoutMinutes = "IQ_SAMPLE".equals(job.getTestType()) ? 20 : 10;
            Instant stageStart = job.getStageStartedAt() == null
                    ? job.getCreatedAt() : job.getStageStartedAt();
            if (List.of("PREPARING", "CALCULATING").contains(job.getState())
                    && stageStart != null
                    && Instant.now().isAfter(stageStart.plus(Duration.ofMinutes(timeoutMinutes)))) {
                fail(get(job.getId()), new IllegalStateException("실제 작업 제한 시간 " + timeoutMinutes + "분 초과"));
                Thread running = tasks.get(job.getId());
                if (running != null) running.interrupt();
                agentCommandService.cancel(sendingNode() ? job.getSenderAgentId()
                        : job.getReceiverAgentId(), job.getId());
                continue;
            }
            if (sendingNode() && "WAITING_DTN".equals(job.getState())) {
                scheduleReceiverPoll(job);
                continue;
            }
            if ("WAITING_RECEIVER".equals(job.getState())
                    && !managementBusy()
                    && agentConnectionRegistry.online(job.getReceiverAgentId())
                    && agentRepository
                            .find(job.getReceiverAgentId())
                            .map(a -> a.state() == AgentState.READY)
                            .orElse(false)) {
                DtnJob current = get(job.getId());
                try {
                    if ("IQ_SAMPLE".equals(current.getTestType())) {
                        update(current, "CALCULATING", "수신 공유 I/Q 파일 검증 중");
                        startTask(current.getId(), "iq-verify", () -> verifyIq(current));
                        continue;
                    }
                    update(current, "CALCULATING", "Receiver 입력 복원 및 PVT 계산 중");
                    boolean delay = "DELAY".equals(current.getComparisonMode());
                    Transfer transfer =
                            objectMapper.readValue(current.getReceivedJson(), Transfer.class);
                    DtnDelay.Timing timing =
                            delay
                                    ? new DtnDelay.Timing(
                                            current.getTestStartedAt(), current.getReceivedAt())
                                    : null;
                    agentCommandService.receive(
                            current.getReceiverAgentId(),
                            current.getId(),
                            transfer,
                            timing,
                            (stage, message) -> calculationProgress(current.getId(), stage, message),
                            result -> calculated(current.getReceiverAgentId(), current.getId(), result));
                } catch (Exception e) {
                    fail(current, e);
                }
            }
        }
    }

    /* 외부 DTN에 JSON 전달: 늦게 도착한 HTTP 실패가 수신 완료를 덮어쓰지 않게 한다. */
    private void sendExternal(UUID id, String packet) {
        boolean attempted = false;
        boolean responded = false;
        try {
            setSendStatus(id, "REQUESTING");
            if (!isActive(id)) {
                return;
            }
            if (sendingNode()) {
                // 수신 DB 등록이 성공한 뒤에만 실제 본문을 외부 DTN에 전달한다.
                trace(id, "시험 등록", true, "수신 서비스에 시험 등록 요청");
                nodeLink.register(get(id));
                trace(id, "시험 등록", true, "수신 서비스 시험 등록 완료");
            }
            if (!isActive(id)) {
                return;
            }
            URI destination = DtnDestination.resolve(get(id).getSendUrl(), sendUrl);
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(destination)
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json");
            if (!sendToken.isBlank() && DtnDestination.usesConfiguredToken(destination, sendUrl)) {
                request.header("Authorization", "Bearer " + sendToken);
            }
            trace(
                    id,
                    "어댑터",
                    true,
                    "JSON 전달 요청 · " + packet.getBytes(StandardCharsets.UTF_8).length + " bytes");
            long started = System.nanoTime();
            attempted = true;
            int status =
                    server.common.LoggedHttpClient.send(
                                    httpClient,
                                    request.POST(HttpRequest.BodyPublishers.ofString(packet))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode();
            responded = true;
            setSendStatus(id, status >= 200 && status < 300 ? "ACCEPTED" : "REJECTED");
            trace(
                    id,
                    "어댑터",
                    true,
                    "HTTP " + status + " · " + ((System.nanoTime() - started) / 1_000_000) + " ms");
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("외부 DTN HTTP 응답: " + status);
            }
            trace(id, "어댑터", false, "어댑터 접수 완료 · 상대 수신 완료와 구분");
        } catch (Exception e) {
            synchronized (this) {
                DtnJob job = get(id);
                job.setSendStatus(attempted && !responded ? "UNKNOWN" : "REJECTED");
                dtnRepository.save(job);
                // callback이 먼저 도착했다면 전달 성공 상태를 뒤늦은 HTTP 오류로 되돌리지 않는다.
                if ("WAITING_DTN".equals(job.getState()) && job.getReceivedAt() == null) {
                    if (attempted && !responded) {
                        update(job, "WAITING_DTN", "어댑터 응답 미확인 · 수신 대기 · "
                                + e.getClass().getSimpleName());
                    } else {
                        // 명확한 HTTP 거절만 상대의 미수신 등록을 닫는다.
                        // 응답 유실(UNKNOWN)은 도착 가능성이 있으므로 대기를 유지한다.
                        if (responded && sendingNode()) job.setCancelPending(true);
                        fail(job, e);
                        requestCancellation(job);
                    }
                }
            }
        }
    }

    private synchronized void setSendStatus(UUID id, String status) {
        DtnJob job = get(id);
        job.setSendStatus(status);
        job.setUpdatedAt(Instant.now());
        dtnRepository.save(job);
    }

    private final Map<UUID, Instant> nextResultPoll = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> resultRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 네트워크 조회는 시험 잠금 밖에서 수행하고, 결과만 해당 시험에 반영한다. */
    private void scheduleReceiverPoll(DtnRepository.TaskView job) {
        UUID id = job.getId();
        Instant now = Instant.now();
        if (resultRequests.size() >= 2 || now.isBefore(nextResultPoll.getOrDefault(id, Instant.MIN))
                || !resultRequests.add(id)) return;
        nextResultPoll.put(id, now.plusSeconds(10));
        Thread.ofVirtual().name("dtn-result-poll").start(() -> {
            try {
                DtnRemoteResult result = nodeLink.result(id);
                synchronized (this) {
                    DtnJob current = dtnRepository.findById(id).orElse(null);
                    if (current != null && "WAITING_DTN".equals(current.getState())) {
                        applyReceiverResult(current, result);
                    }
                }
            } catch (RuntimeException unavailable) {
                // 전달 대기는 만료하지 않는다. 다음 순환에서 같은 시험을 다시 조회한다.
            } finally {
                resultRequests.remove(id);
            }
        });
    }

    private void applyReceiverResult(DtnJob job, DtnRemoteResult result) {
        if (result == null) return;
        if ("WAITING_DTN".equals(result.getState()) && result.getMessage() != null
                && result.getMessage().startsWith("수신 검증 실패")
                && !result.getMessage().equals(job.getMessage())) {
            update(job, "WAITING_DTN", result.getMessage(), "상대 결과 확인");
        }
        if (result.getReceivedAt() != null && job.getReceivedAt() == null) {
            job.setReceivedAt(result.getReceivedAt());
            update(job, "WAITING_DTN", "상대 수신 완료 · 시험 결과 대기", "상대 결과 확인");
        }
        if ("FAILED".equals(result.getState())) {
            fail(job, new IllegalStateException("수신 노드 실패: " + result.getMessage()), "상대 결과 확인");
        } else if ("CANCELLED".equals(result.getState())) {
            update(job, "CANCELLED", "상대 시험 중지 확인", "상대 결과 확인");
        } else if ("COMPLETED".equals(result.getState())
                || ("DELAY".equals(job.getComparisonMode())
                        && "INCONCLUSIVE".equals(result.getState()))) {
            try {
                if ("IQ_SAMPLE".equals(job.getTestType())) {
                    if (result.getReceivedAt() == null || result.getFileResult() == null) {
                        throw new IllegalArgumentException("I/Q 완료 결과 누락");
                    }
                    IqFile file =
                            objectMapper.readValue(job.getSentJson(), Transfer.class).getFile();
                    JsonNode verified = result.getFileResult();
                    if (!"PASS".equals(verified.path("verdict").asText())
                            || file.sizeBytes() != verified.path("sizeBytes").asLong()
                            || !file.sha256().equals(verified.path("sha256").asText())) {
                        throw new IllegalArgumentException("수신 I/Q 검증 결과 불일치");
                    }
                    job.setFileResultJson(verified.toString());
                    var sent = objectMapper.readValue(job.getSentJson(), Transfer.class);
                    if (sent.getMetadata() != null) {
                        if (!(sent.getMetadata() instanceof IqMetadata metadata)) {
                            throw new IllegalArgumentException("I/Q metadata 형식 오류");
                        }
                        if (result.getPvt() == null) {
                            throw new IllegalArgumentException("I/Q 수신 PVT 결과 누락");
                        }
                        job.setReceiverJson(objectMapper.writeValueAsString(result.getPvt()));
                        var reference =
                                IqReceiver.references(
                                        metadata, sent.getReferencePvt(), result.getPvt());
                        job.setReferenceJson(objectMapper.writeValueAsString(reference));
                        job.setComparisonJson(
                                objectMapper.writeValueAsString(
                                        IqReceiver.comparison(reference, result.getPvt())));
                    }
                    update(job, "COMPLETED", "상대 시험 완료 · I/Q 결과는 수신 화면에서 확인하세요.", "상대 결과 확인");
                    return;
                }
                if (result.getReceivedAt() == null
                        || result.getPvt() == null
                        || result.getPvt().isEmpty()) {
                    throw new IllegalArgumentException("수신 노드의 완료 결과가 불완전합니다.");
                }
                job.setReceiverJson(objectMapper.writeValueAsString(result.getPvt()));
                if ("DELAY".equals(job.getComparisonMode())) {
                    if (result.getDelayEvidence() == null) {
                        throw new IllegalArgumentException("수신측 지연 계산 근거 누락");
                    }
                    job.setDelayEvidenceJson(
                            objectMapper.writeValueAsString(result.getDelayEvidence()));
                    compareDelay(job, result.getPvt(), true);
                } else {
                    compare(job, result.getPvt());
                }
            } catch (Exception error) {
                fail(job, error, "상대 결과 확인");
            }
        }
    }

    private void verifyIq(DtnJob job) {
        try {
            Transfer transfer = objectMapper.readValue(job.getReceivedJson(), Transfer.class);
            boolean legacyFormat =
                    transfer.getSchemaVersion() == 1
                            && "LNIS-IQ-FILE-v1".equals(transfer.getFormat());
            boolean frameFormat =
                    transfer.getSchemaVersion() == 2
                            && "LNIS-IQ-FILE-v2".equals(transfer.getFormat())
                            && transfer.getMetadata() instanceof IqMetadata metadata
                            && IqReceiver.FRAME_METHOD.equals(metadata.pvtMethod());
            if (!"IQ_SAMPLE".equals(transfer.getTestType())
                    || !(legacyFormat || frameFormat)
                    || transfer.getFrames() != null
                    || transfer.getGrawBase64() != null) {
                throw new IllegalArgumentException("I/Q 전송 형식 오류");
            }
            trace(
                    job.getId(),
                    "I/Q 검증",
                    true,
                    "수신 파일 존재·크기·SHA-256 확인 시작 · " + transfer.getFile().filePath());
            long started = System.nanoTime();
            var result = new LinkedHashMap<>(iq.verify(transfer.getFile()));
            trace(
                    job.getId(),
                    "I/Q 검증",
                    true,
                    "검증 완료 · "
                            + result
                            + " · "
                            + ((System.nanoTime() - started) / 1_000_000)
                            + " ms");
            result.put("preview", iq.preview(transfer.getFile()));
            IqReceiver.Result decoded = null;
            List<Pvt> reference = List.of();
            Map<String, Object> comparison =
                    Map.of("verdict", "INCONCLUSIVE", "message", "과거 파일: I/Q PVT 메타데이터 없음");
            if (transfer.getMetadata() != null) {
                if (!(transfer.getMetadata() instanceof IqMetadata metadata)) {
                    throw new IllegalArgumentException("I/Q metadata 형식 오류");
                }
                if (iqReceiver == null) {
                    throw new IllegalStateException("I/Q 수신기 미설정");
                }
                decoded =
                        iqReceiver.decode(
                                iq.path(transfer.getFile()),
                                metadata,
                                message -> trace(job.getId(), "I/Q 복원", true, message));
                // File verification and PVT accuracy are different results.
                iq.verify(transfer.getFile()); // Detect replacement/modification during tracking.
                reference =
                        IqReceiver.references(metadata, transfer.getReferencePvt(), decoded.pvt());
                comparison =
                        new java.util.LinkedHashMap<>(
                                IqReceiver.comparison(reference, decoded.pvt()));
                comparison.put("method", metadata.pvtMethod());
            }
            synchronized (this) {
                if (!"CALCULATING".equals(get(job.getId()).getState())) {
                    return;
                }
                job.setFileResultJson(objectMapper.writeValueAsString(result));
                job.setComparisonJson(objectMapper.writeValueAsString(comparison));
                if (decoded != null) {
                    job.setReceiverJson(objectMapper.writeValueAsString(decoded.pvt()));
                    job.setObservationsJson(
                            objectMapper.writeValueAsString(decoded.observations()));
                    job.setReferenceJson(objectMapper.writeValueAsString(reference));
                }
                update(
                        job,
                        "COMPLETED",
                        decoded == null
                                ? "파일 검증 완료 · 과거 파일은 PVT 메타데이터 없음"
                                : "파일 검증 완료 · I/Q 추적 PVT: " + comparison.get("verdict"));
            }
        } catch (Exception error) {
            synchronized (this) {
                if ("CALCULATING".equals(get(job.getId()).getState())) {
                    fail(job, error);
                }
            }
        }
    }

    public synchronized void deleteIq(UUID id) throws java.io.IOException {
        if (managementGuard != null) {
            managementGuard.requireUnpinned("IQ", id);
        }
        if (dtnRepository.existsByIqFileIdAndStateIn(id, ACTIVE)) {
            throw new IllegalStateException("전송·검증 중인 파일은 삭제할 수 없습니다.");
        }
        iq.delete(id);
    }

    private void compare(DtnJob job, List<Pvt> receiverPvt) throws Exception {
        List<Pvt> reference =
                objectMapper.readValue(job.getReferenceJson(), new TypeReference<>() {});
        Map<String, Object> comparison = DtnComparison.compare(reference, receiverPvt);
        job.setComparisonJson(objectMapper.writeValueAsString(comparison));
        if (!sendingNode()) {
            trace(job.getId(), "PVT 비교", true, objectMapper.writeValueAsString(comparison));
        }
        update(
                job,
                "INCONCLUSIVE".equals(comparison.get("verdict")) ? "INCONCLUSIVE" : "COMPLETED",
                sendingNode()
                        ? "상대 시험 완료 · 판정 " + comparison.get("verdict")
                        : "PVT 비교 완료: " + comparison.get("verdict"),
                sendingNode() ? "상대 결과 확인" : "시험");
    }

    private void compareDelay(DtnJob job, List<Pvt> received, boolean sender) throws Exception {
        var evidence = objectMapper.readValue(job.getDelayEvidenceJson(), DtnDelay.Evidence.class);
        if (!Objects.equals(evidence.timing().startedAt(), job.getTestStartedAt())
                || !Objects.equals(evidence.timing().receivedAt(), job.getReceivedAt())) {
            throw new IllegalArgumentException("지연 측정 기준 시각 불일치");
        }
        var selected = objectMapper.readValue(job.getSelectedEpochJson(), DtnDelay.Epoch.class);
        if (selected.week() != evidence.originalTime().week()
                || Double.compare(selected.towSeconds(), evidence.originalTime().towSeconds())
                        != 0) {
            throw new IllegalArgumentException("등록된 Epoch와 계산 Epoch 불일치");
        }

        List<Pvt> reference =
                objectMapper.readValue(job.getReferenceJson(), new TypeReference<>() {});
        var comparison = DtnComparison.delay(reference, received, evidence);
        job.setComparisonJson(objectMapper.writeValueAsString(comparison));
        if (!sender && logs != null) {
            logs.pvt(job.getId(), "TEST", reference, 0, "비교 기준 Reference (송신측 제공)");
            trace(
                    job.getId(),
                    "수신 PVT 비교",
                    true,
                    "차이 = 수신 − Reference · " + objectMapper.writeValueAsString(comparison));
        }

        String state =
                "INCONCLUSIVE".equals(comparison.get("verdict")) ? "INCONCLUSIVE" : "COMPLETED";
        if (sender) {
            String result =
                    switch (comparison.get("verdict").toString()) {
                        case "MEASURED" -> "측정 완료";
                        case "PARTIAL" -> "부분 비교 · 속도 비교 불가";
                        default -> "PVT 비교 불가";
                    };
            update(job, state, "상대 시험 완료 · " + result + " · 상세 결과는 수신 화면에서 확인하세요.", "상대 결과 확인");
            return;
        }

        var row = objectMapper.valueToTree(comparison).path("epochs").path(0);
        String summary =
                comparison.get("message")
                        + " · 시험 시작→수신 "
                        + evidence.delaySeconds() * 1000
                        + " ms"
                        + " · 위치 차이 "
                        + measurement(row, "positionDifferenceMeters", "m")
                        + " · 속도 차이 "
                        + measurement(row, "velocityDifferenceMetersPerSecond", "m/s")
                        + " · Clock Bias 변화 "
                        + measurement(row, "clockDifferenceSeconds", "s")
                        + " · 지연과의 차이 "
                        + measurement(row, "clockResidualSeconds", "s");
        if (!sender) {
            trace(
                    job.getId(),
                    "Clock Bias 검증",
                    true,
                    "Δb = 수신 Bias − 원본 Bias = "
                            + measurement(row, "clockDifferenceSeconds", "s")
                            + " · Δt = "
                            + evidence.delaySeconds()
                            + " s"
                            + " · εt = Δb − Δt = "
                            + measurement(row, "clockResidualSeconds", "s")
                            + " · c×εt = "
                            + measurement(row, "clockResidualMeters", "m")
                            + " (위치 오차가 아닌 시간 차이의 거리 환산값) · 허용오차 미설정");
        }
        trace(job.getId(), "수신 계산 결과", false, summary);

        update(job, state, comparison.get("message").toString());
    }

    private static String measurement(JsonNode row, String field, String unit) {
        return row.path(field).isNumber() ? row.path(field).asText() + " " + unit : "비교 불가";
    }

    /* 지정 역할과 READY 연결 상태 확인 */
    private void requireAgent(String id, AgentRole role) {
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
    private void update(DtnJob job, String state, String message) {
        update(job, state, message, "시험");
    }

    private void update(DtnJob job, String state, String message, String stage) {
        if (!state.equals(job.getState()) || job.getStageStartedAt() == null) {
            job.setStageStartedAt(Instant.now());
        }
        job.setState(state);
        job.setMessage(message);
        job.setUpdatedAt(Instant.now());
        dtnRepository.save(job);
        if (logs != null) {
            logs.add(
                    job.getId(),
                    "TEST",
                    "FAILED".equals(state)
                            ? "ERROR"
                            : "INCONCLUSIVE".equals(state) ? "WARN" : "INFO",
                    stage,
                    false,
                    message);
        }
    }

    /* 미완성 청크를 정리하고 실패 원인을 저장한다. */
    private void fail(DtnJob job, Exception error) {
        fail(job, error, "시험");
    }

    private void fail(DtnJob job, Exception error, String stage) {

        String message =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        update(job, "FAILED", message.substring(0, Math.min(2000, message.length())), stage);
    }
    @Value("${lnis.native.dir:native}")
    private String referenceNativeDirectory;
    private final Set<UUID> referenceRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 시험에 사용한 불변 스냅샷만 제공한다. 원본 입력을 다시 선택하지 않는다. */
    public ReferenceSnapshot referenceSnapshot(UUID id) throws Exception {
        if (!sendingNode()) throw new ResponseStatusException(HttpStatus.CONFLICT, "송신 노드 전용 API");
        DtnJob job = get(id);
        if (job.getReferenceSourceBase64() == null || job.getReferenceJson() == null || job.getSentJson() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "시험 비교자료 없음");
        }
        byte[] source = Base64.getDecoder().decode(job.getReferenceSourceBase64());
        return new ReferenceSnapshot(id,
                DtnPayloadDigest.sha256(objectMapper, objectMapper.readTree(job.getSentJson())),
                server.common.Hashing.hex(server.common.Hashing.sha256Digest().digest(source)),
                job.getReferenceSourceBase64(), objectMapper.readValue(job.getReferenceJson(), new TypeReference<>() {}));
    }

    public synchronized void retryReference(UUID id) {
        if (sendingNode()) throw new IllegalArgumentException("수신 노드 전용 기능");
        DtnJob job = get(id);
        if (job.getReferenceStatus() == null || "COMPLETE".equals(job.getReferenceStatus())) return;
        job.setReferenceStatus("WAITING");
        job.setReferenceAttempts(0);
        job.setReferenceNextAttemptAt(Instant.now());
        job.setReferenceMessage("비교자료 조회 대기");
        job.setUpdatedAt(Instant.now());
        dtnRepository.save(job);
    }

    @Scheduled(fixedDelay = 3000)
    public void retryReferences() {
        if (nodeLink == null || sendingNode()) return;
        for (DtnRepository.ReferenceTask job : dtnRepository.findTasksByReferenceStatus("WAITING")) {
            if (referenceRequests.size() >= 4) break;
            if (job.getReferenceNextAttemptAt() != null
                    && job.getReferenceNextAttemptAt().isAfter(Instant.now())) continue;
            if (!referenceRequests.add(job.getId())) continue;
            Thread.ofVirtual().name("dtn-reference").start(() -> {
                try {
                    dtnRepository.findById(job.getId()).ifPresent(this::fetchReference);
                } finally {
                    referenceRequests.remove(job.getId());
                }
            });
        }
    }

    private void fetchReference(DtnJob pending) {
        try {
            ReferenceSnapshot snapshot = nodeLink.reference(pending.getId());
            validateReference(pending, snapshot);
            synchronized (this) {
                DtnJob job = dtnRepository.findById(pending.getId()).orElse(null);
                if (job == null || !"WAITING".equals(job.getReferenceStatus())) return;
                job.setReferenceSourceBase64(snapshot.grawBase64());
                job.setReferenceJson(objectMapper.writeValueAsString(snapshot.referencePvt()));
                job.setReferenceStatus("COMPLETE");
                job.setReferenceMessage("비교자료 확인 완료");
                compareDelay(job, objectMapper.readValue(job.getReceiverJson(), new TypeReference<>() {}), false);
            }
        } catch (Exception error) {
            synchronized (this) {
                DtnJob job = dtnRepository.findById(pending.getId()).orElse(null);
                if (job == null || !"WAITING".equals(job.getReferenceStatus())) return;
                boolean permanent = error instanceof IllegalArgumentException
                        || error instanceof server.node.NodePeerClient.RemoteRequestException remote
                        && List.of(400, 401, 403, 404, 409, 410).contains(remote.statusCode());
                int attempts = job.getReferenceAttempts() == null ? 1 : job.getReferenceAttempts() + 1;
                job.setReferenceAttempts(attempts);
                job.setReferenceStatus(error instanceof DataMismatchException ? "MISMATCH"
                        : permanent ? "UNAVAILABLE" : "WAITING");
                job.setReferenceNextAttemptAt(Instant.now().plusSeconds(attempts == 1 ? 10 : attempts == 2 ? 30 : 60));
                String reason = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
                job.setReferenceMessage(reason.substring(0, Math.min(1900, reason.length())));
                job.setUpdatedAt(Instant.now());
                dtnRepository.save(job);
                if (permanent || attempts == 1) {
                    if (logs != null) logs.add(job.getId(), "TEST", "WARN", "비교자료 조회", false,
                            "수신 계산 결과 유지 · " + (permanent ? "재조회 필요 · " : "자동 재시도 · ") + job.getReferenceMessage());
                }
            }
        }
    }

    private static final class DataMismatchException extends IllegalArgumentException {
        DataMismatchException(String message) { super(message); }
    }

    private void validateReference(DtnJob job, ReferenceSnapshot snapshot) throws Exception {
        if (snapshot == null || !job.getId().equals(snapshot.testId())
                || !Objects.equals(job.getExpectedPayloadSha256(), snapshot.payloadSha256())
                || snapshot.grawBase64() == null
                || snapshot.grawBase64().length() > ((DtnModels.MAX_INPUT_BYTES + 2) / 3) * 4
                || snapshot.referencePvt() == null || snapshot.referencePvt().size() != 1) {
            throw new IllegalArgumentException("비교자료 시험 식별/크기 오류");
        }
        byte[] source = Base64.getDecoder().decode(snapshot.grawBase64());
        if (!server.common.Hashing.hex(server.common.Hashing.sha256Digest().digest(source))
                .equals(snapshot.sourceSha256())) throw new IllegalArgumentException("비교자료 원본 해시 오류");
        var records = GrawCodec.splitLengthPrefixed(source);
        Transfer expected;
        try (var codec = server.afs.NativeAfsCodec.load(java.nio.file.Path.of(referenceNativeDirectory))) {
            expected = server.afs.DelayTransferCodec.prepare(job.getId(), records,
                    "GNSS_RAW".equals(job.getTestType()), job.getTestStartedAt(), codec);
        }
        expected.setSenderMode(job.getSenderMode());
        expected.setReceiverMode(job.getReceiverMode());
        expected.setHdtnConfig(job.getHdtnConfigJson() == null ? null
                : objectMapper.readValue(job.getHdtnConfigJson(), HdtnConfig.class));
        String hash = DtnPayloadDigest.sha256(objectMapper,
                objectMapper.readTree(objectMapper.writeValueAsBytes(
                        server.afs.DelayTransferCodec.packet(objectMapper, expected))));
        if (!hash.equals(snapshot.payloadSha256())) {
            throw new DataMismatchException("비교자료와 실제 전달 데이터 불일치");
        }
        var epoch = objectMapper.readValue(job.getSelectedEpochJson(), DtnDelay.Epoch.class);
        Pvt reference = snapshot.referencePvt().getFirst();
        if (reference.getWeek() != epoch.week()
                || Double.compare(reference.getTowSeconds(), epoch.towSeconds()) != 0) {
            throw new IllegalArgumentException("Reference Epoch 불일치");
        }
    }


}
