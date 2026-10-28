package server.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import server.common.DtnModels;
import server.common.LnisModels.AgentRole;
import server.dtn.DtnJob;
import server.dtn.DtnNodeLink;
import server.dtn.DtnPayloadDigest;
import server.dtn.DtnRemoteResult;
import server.dtn.DtnRepository;
import server.pvt.DtnDelay;

import java.time.Instant;
import java.util.UUID;

/** 송신 기준 자료와 수신 자료를 서로 다른 DB에 보관하는 관리 통신 서비스다. */
@Service
@Profile("node")
@RequiredArgsConstructor
@Slf4j
public class NodeDtnService implements DtnNodeLink {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private server.management.DataManagementGuard managementGuard;

    private final NodeProperties properties;
    private final NodePeerClient peerClient;
    private final DtnRepository repository;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private server.dtn.DtnLogService logs;

    @Override
    public boolean sender() {
        return properties.getRole() == AgentRole.SENDER;
    }

    @Override
    public void validateParticipants(String senderId, String receiverId) {
        String expectedSender = sender() ? properties.getAgentId() : properties.getPeerAgentId();
        String expectedReceiver = sender() ? properties.getPeerAgentId() : properties.getAgentId();
        if (!expectedSender.equals(senderId) || !expectedReceiver.equals(receiverId)) {
            throw new IllegalArgumentException("시작 시 설정한 송신/수신 노드와 시험 참여자가 다릅니다.");
        }
    }

    @Override
    public void register(DtnJob job) {
        if (!sender()) {
            throw new IllegalStateException("수신 노드는 DTN 전송을 시작할 수 없습니다.");
        }
        validateParticipants(job.getSenderAgentId(), job.getReceiverAgentId());
        // 잘못 연결된 다른 노드에 시험을 등록하지 않도록 ID와 역할을 먼저 확인한다.
        NodeDto.StatusResponse status = peerClient.status();
        if (!status.isOnline()) {
            throw new IllegalStateException("수신 노드의 실행기가 준비되지 않았습니다.");
        }
        if ("DELAY".equals(job.getComparisonMode())) {
            try {
                var capabilities =
                        peerClient.exchange(
                                "/lnis/api/v1/node/peer/dtn/tests/capabilities",
                                null,
                                com.fasterxml.jackson.databind.JsonNode.class,
                                4096);
                if (!capabilities.path("delaySupported").asBoolean()
                        || !capabilities.path("delayTransferSupported").asBoolean()) {
                    throw new IllegalStateException("지연 시험 미지원");
                }
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                        "수신 LNIS의 지연 시험 지원을 확인할 수 없습니다. 양쪽 서비스 버전과 연결을 확인하세요.", error);
            }
        }
        try {
            NodeDtnRegistration registration = registration(job);
            DtnRemoteResult accepted =
                    peerClient.exchange(
                            "/lnis/api/v1/node/peer/dtn/tests",
                            registration,
                            DtnRemoteResult.class,
                            16 * 1024);
            if ("DELAY".equals(job.getComparisonMode())
                    && !Boolean.TRUE.equals(accepted.getDelaySupported())) {
                throw new IllegalStateException("수신 LNIS가 지연 시험을 지원하지 않습니다. 양쪽 서비스를 갱신하세요.");
            }
            if ("CANCELLED".equals(accepted.getState())) {
                throw new IllegalStateException("이미 중지된 수신 시험입니다.");
            }
            if (!job.getId().equals(accepted.getTestId())) {
                throw new IllegalStateException("수신 노드의 시험 식별자가 다릅니다.");
            }
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("송신 JSON 검증 실패", error);
        }
    }

    public NodeDtnRegistration registration(UUID id) throws java.io.IOException {
        if (!sender()) throw new ResponseStatusException(HttpStatus.CONFLICT, "송신 노드 전용 API입니다.");
        DtnJob job = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        validateParticipants(job.getSenderAgentId(), job.getReceiverAgentId());
        if (job.getSentJson() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "전송 원문이 없는 시험입니다.");
        }
        return registration(job);
    }

    private NodeDtnRegistration registration(DtnJob job) throws java.io.IOException {
        var payload = mapper.readTree(job.getSentJson());
        NodeDtnRegistration registration = new NodeDtnRegistration();
        registration.setTestType(job.getTestType() == null ? payload.path("testType").asText(null) : job.getTestType());
        registration.setSenderMode(job.getSenderMode() == null ? payload.path("senderMode").asText(null) : job.getSenderMode());
        registration.setReceiverMode(job.getReceiverMode() == null ? payload.path("receiverMode").asText(null) : job.getReceiverMode());
        registration.setTestId(job.getId());
        registration.setComparisonMode(job.getComparisonMode());
        registration.setPvtConstellation(job.getPvtConstellation() == null ? "GPS" : job.getPvtConstellation());
        registration.setTestStartedAt(job.getTestStartedAt());
        if (job.getSenderClockJson() != null) {
            registration.setSenderClock(mapper.readValue(job.getSenderClockJson(), server.common.ServiceClock.Stamp.class));
        }
        if (job.getSelectedEpochJson() != null) {
            registration.setSelectedEpoch(
                    mapper.readValue(job.getSelectedEpochJson(), DtnDelay.Epoch.class));
        }
        registration.setSenderAgentId(job.getSenderAgentId());
        registration.setReceiverAgentId(job.getReceiverAgentId());
        registration.setProfile(payload.path("profile").asText());
        registration.setPayloadSha256(
                DtnPayloadDigest.sha256(mapper, payload));
        return registration;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void restoreTrialMetadataAfterStart() {
        if (!sender()) Thread.ofVirtual().name("dtn-trial-metadata").start(() -> {
            try {
                restoreMissingTrialMetadata();
            } catch (RuntimeException error) {
                log.warn("시험 유형 보완 실패 · {}", error.getClass().getSimpleName());
            }
        });
    }

    /** 기동 시 한 번만 조회한다. 연결 실패 시 기존 기록은 미확인으로 남긴다. */
    void restoreMissingTrialMetadata() {
        var ids = repository.findMissingTrialMetadata();
        if (ids.isEmpty()) return;
        try {
            if (!peerClient.status().isOnline()) {
                log.warn("시험 유형 보완 보류 · 송신 노드 실행기 미준비 · {}건", ids.size());
                return;
            }
        } catch (RuntimeException error) {
            log.warn("시험 유형 보완 보류 · 송신 노드 연결 확인 불가 · {}건", ids.size());
            return;
        }
        int restored = 0;
        for (UUID id : ids) {
            try {
                var registration = peerClient.exchange("/lnis/api/v1/node/peer/dtn/tests/" + id + "/registration",
                        null, NodeDtnRegistration.class, 8192);
                if (registration == null || !id.equals(registration.getTestId()) || registration.getTestType() == null) {
                    continue;
                }
                validateParticipants(registration.getSenderAgentId(), registration.getReceiverAgentId());
                validateTrialMetadata(registration);
                DtnJob existing = repository.findById(id).orElse(null);
                if (existing != null) restored += fillExistingTrialMetadata(existing, registration);
            } catch (RuntimeException error) {
                log.debug("시험 유형 보완 불가 · testId={} · {}", id, error.getClass().getSimpleName());
            }
        }
        log.info("시험 유형 보완 · 확인 {} / {}건 · 시험 상태·원문·결과 유지", restored, ids.size());
    }

    @Override
    public DtnRemoteResult result(UUID testId) {
        DtnRemoteResult result =
                peerClient.exchange(
                        "/lnis/api/v1/node/peer/dtn/tests/" + testId,
                        null,
                        DtnRemoteResult.class,
                        DtnModels.MAX_JSON_BYTES);
        if (!testId.equals(result.getTestId())) {
            throw new IllegalStateException("상대 노드의 결과 식별자가 다릅니다.");
        }
        return result;
    }

    @Override
    public server.common.DtnModels.ReferenceSnapshot reference(UUID testId) {
        return peerClient.exchange("/lnis/api/v1/node/peer/dtn/tests/" + testId + "/reference",
                null, server.common.DtnModels.ReferenceSnapshot.class, DtnModels.MAX_JSON_BYTES);
    }

    @Override
    public void cancel(UUID testId) {
        if (!sender()) {
            throw new IllegalStateException("송신 노드 전용 중지 요청입니다.");
        }
        DtnRemoteResult result =
                peerClient.exchange(
                        "/lnis/api/v1/node/peer/dtn/tests/" + testId + "/cancel",
                        java.util.Map.of(),
                        DtnRemoteResult.class,
                        DtnModels.MAX_JSON_BYTES);
        if (!testId.equals(result.getTestId())
                || !java.util.List.of("CANCELLED", "COMPLETED", "INCONCLUSIVE")
                        .contains(result.getState())) {
            throw new IllegalStateException("상대 노드의 중지 결과를 확인할 수 없습니다.");
        }
    }

    @Override
    public DtnRemoteResult closeWaiting(UUID testId) {
        return closeWaiting(testId, "ADAPTER_REJECTED");
    }

    @Override
    public DtnRemoteResult closeWaiting(UUID testId, String reason) {
        if (!sender()) throw new IllegalStateException("송신 노드 전용 요청입니다.");
        DtnRemoteResult result = peerClient.exchange(
                "/lnis/api/v1/node/peer/dtn/tests/" + testId + "/close-waiting",
                java.util.Map.of("reason", reason), DtnRemoteResult.class, DtnModels.MAX_JSON_BYTES);
        if (result == null || !testId.equals(result.getTestId())) {
            throw new IllegalStateException("상대 노드의 시험 식별자가 다릅니다.");
        }
        return result;
    }

    /** 등록보다 먼저 중지가 도착해도 뒤늦은 등록이 시험을 다시 열지 않게 한다. */
    @Transactional
    public synchronized void prepareCancellation(UUID testId) {
        requireReceiver();
        if (managementGuard != null) {
            managementGuard.requirePresent("DTN", testId);
        }
        DtnJob existing = repository.findById(testId).orElse(null);
        if (existing != null) {
            validateParticipants(existing.getSenderAgentId(), existing.getReceiverAgentId());
            return;
        }
        DtnJob job = new DtnJob();
        job.setId(testId);
        job.setSenderAgentId(properties.getPeerAgentId());
        job.setReceiverAgentId(properties.getAgentId());
        job.setState("CANCELLED");
        job.setMessage("송신 요청으로 시험 중지 · 등록 전 중지");
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(job.getCreatedAt());
        repository.saveAndFlush(job);
    }

    /** 같은 ID와 해시의 재등록은 최초 상태를 유지한다. 변경된 내용으로 덮어쓰기는 금지한다. */
    @Transactional
    public synchronized DtnRemoteResult accept(NodeDtnRegistration registration) {
        return accept(registration, null);
    }

    @Transactional
    public synchronized DtnRemoteResult accept(
            NodeDtnRegistration registration, server.common.ServiceClock.Stamp receiverClock) {
        requireReceiver();
        DtnModels.requireGps(registration.getPvtConstellation());
        validateParticipants(registration.getSenderAgentId(), registration.getReceiverAgentId());
        if (registration.getTestId() == null
                || !(DtnModels.PROFILE.equals(registration.getProfile())
                        || "LANS-AFS-IQ-v1".equals(registration.getProfile()))
                || registration.getPayloadSha256() == null
                || !registration.getPayloadSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("지원하지 않는 DTN 등록 정보입니다.");
        }
        if (registration.getComparisonMode() != null
                && !java.util.List.of("DELAY", "RESTORE")
                        .contains(registration.getComparisonMode())) {
            throw new IllegalArgumentException("지원하지 않는 PVT 비교 방식");
        }
        if ("DELAY".equals(registration.getComparisonMode())
                && (registration.getTestStartedAt() == null
                        || registration.getSelectedEpoch() == null)) {
            throw new IllegalArgumentException("지연 시험의 시작 시각/Epoch 누락");
        }
        validateTrialMetadata(registration);
        if (managementGuard != null) {
            managementGuard.requirePresent("DTN", registration.getTestId());
        }
        DtnJob existing = repository.findById(registration.getTestId()).orElse(null);
        if (existing != null) {
            if ("CANCELLED".equals(existing.getState())
                    && existing.getExpectedPayloadSha256() == null) {
                return view(existing);
            }
            fillExistingTrialMetadata(existing, registration);
            return view(existing);
        }
        DtnJob job = new DtnJob();
        job.setId(registration.getTestId());
        job.setTestType(registration.getTestType());
        job.setSenderMode(registration.getSenderMode());
        job.setReceiverMode(registration.getReceiverMode());
        job.setSenderAgentId(registration.getSenderAgentId());
        job.setReceiverAgentId(registration.getReceiverAgentId());
        job.setExpectedPayloadSha256(registration.getPayloadSha256());
        if (receiverClock != null) {
            job.setReceiverRegistrationClockJson(mapper.valueToTree(receiverClock).toString());
        }
        job.setComparisonMode(registration.getComparisonMode());
        job.setPvtConstellation(
                registration.getPvtConstellation() == null ? "GPS" : registration.getPvtConstellation());
        job.setTestStartedAt(registration.getTestStartedAt());
        if (registration.getSenderClock() != null) {
            job.setSenderClockJson(mapper.valueToTree(registration.getSenderClock()).toString());
        }
        if (registration.getSelectedEpoch() != null) {
            job.setSelectedEpochJson(
                    mapper.valueToTree(registration.getSelectedEpoch()).toString());
        }
        job.setState("WAITING_DTN");
        job.setMessage("외부 DTN/HDTN 수신 대기");
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(job.getCreatedAt());
        repository.saveAndFlush(job);
        if (logs != null) {
            logs.add(job.getId(), "TEST", "시험 등록", false, "시험 접수 완료 · 외부 DTN/HDTN 데이터 수신 대기");
        }
        return view(job);
    }

    private int fillExistingTrialMetadata(DtnJob existing, NodeDtnRegistration registration) {
        if (!java.util.Objects.equals(
                        registration.getComparisonMode(), existing.getComparisonMode())
                || !java.util.Objects.equals(
                        registration.getTestStartedAt(), existing.getTestStartedAt())
                || !java.util.Objects.equals(
                        registration.getSelectedEpoch() == null
                                ? null
                                : mapper.valueToTree(registration.getSelectedEpoch())
                                        .toString(),
                        existing.getSelectedEpochJson())
                || !registration.getPayloadSha256().equals(existing.getExpectedPayloadSha256())
                || !registration.getSenderAgentId().equals(existing.getSenderAgentId())
                || !registration.getReceiverAgentId().equals(existing.getReceiverAgentId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 시험 내용과 다릅니다.");
        }
        if (conflicts(existing.getTestType(), registration.getTestType())
                || conflicts(existing.getSenderMode(), registration.getSenderMode())
                || conflicts(existing.getReceiverMode(), registration.getReceiverMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 시험 유형·경로와 다릅니다.");
        }
        if (existing.getTestType() == null && registration.getTestType() != null
                || existing.getSenderMode() == null && registration.getSenderMode() != null
                || existing.getReceiverMode() == null && registration.getReceiverMode() != null) {
            return repository.fillTrialMetadata(existing.getId(), registration.getPayloadSha256(),
                    registration.getTestType(), registration.getSenderMode(), registration.getReceiverMode());
        }
        return 0;
    }

    private static boolean conflicts(String stored, String incoming) {
        return stored != null && incoming != null && !stored.equals(incoming);
    }

    private static void validateTrialMetadata(NodeDtnRegistration registration) {
        if (registration.getTestType() != null
                && !java.util.List.of("GNSS_RAW", "AFS_METADATA", "IQ_SAMPLE").contains(registration.getTestType())
                || registration.getSenderMode() != null
                && !java.util.List.of("DTN", "HDTN").contains(registration.getSenderMode())
                || registration.getReceiverMode() != null
                && !java.util.List.of("DTN", "HDTN").contains(registration.getReceiverMode())) {
            throw new IllegalArgumentException("지원하지 않는 시험 유형·경로입니다.");
        }
    }

    public DtnRemoteResult localResult(UUID testId) {
        requireReceiver();
        DtnJob job =
                repository
                        .findById(testId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        validateParticipants(job.getSenderAgentId(), job.getReceiverAgentId());
        return view(job);
    }

    private void requireReceiver() {
        if (sender()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "수신 노드 전용 API입니다.");
        }
    }

    private DtnRemoteResult view(DtnJob job) {
        DtnRemoteResult result = new DtnRemoteResult();
        result.setTestId(job.getId());
        result.setDelaySupported(true);
        if (job.getDelayEvidenceJson() != null) {
            try {
                result.setDelayEvidence(
                        mapper.readValue(job.getDelayEvidenceJson(), DtnDelay.Evidence.class));
            } catch (java.io.IOException error) {
                throw new IllegalStateException("지연 계산 근거 조회 실패", error);
            }
        }
        result.setState(job.getState());
        result.setMessage(job.getMessage());
        result.setReceivedAt(job.getReceivedAt());
        // 어댑터 상세는 수신 노드에만 보관한다. 관리 채널은 상태와 계산 결과만 반환한다.
        if (job.getFileResultJson() != null) {
            try {
                result.setFileResult(mapper.readTree(job.getFileResultJson()));
            } catch (java.io.IOException error) {
                throw new IllegalStateException("I/Q 결과 조회 실패", error);
            }
        }
        if (job.getReceiverJson() != null) {
            try {
                result.setPvt(mapper.readValue(job.getReceiverJson(), new TypeReference<>() {}));
            } catch (java.io.IOException error) {
                throw new IllegalStateException("저장된 수신 계산 결과를 읽을 수 없습니다.", error);
            }
        }
        return result;
    }
}
