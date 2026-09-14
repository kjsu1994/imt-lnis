package server.central.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import server.central.dtn.DtnJob;
import server.central.dtn.DtnNodeLink;
import server.central.dtn.DtnPayloadDigest;
import server.central.dtn.DtnRemoteResult;
import server.central.dtn.DtnRepository;
import server.shared.model.DtnModels;
import server.shared.model.LnisModels.AgentRole;

import java.time.Instant;
import java.util.UUID;

/** 송신 기준 자료와 수신 자료를 서로 다른 DB에 보관하는 관리 통신 서비스다. */
@Service
@Profile("node")
@RequiredArgsConstructor
public class NodeDtnService implements DtnNodeLink {
    private final NodeProperties properties;
    private final NodePeerClient peerClient;
    private final DtnRepository repository;
    private final ObjectMapper mapper;

    @Override
    public boolean sender()
    {
        return properties.getRole() == AgentRole.SENDER;
    }

    @Override
    public void validateParticipants(String senderId, String receiverId)
    {
        String expectedSender = sender() ? properties.getAgentId() : properties.getPeerAgentId();
        String expectedReceiver = sender() ? properties.getPeerAgentId() : properties.getAgentId();
        if (!expectedSender.equals(senderId) || !expectedReceiver.equals(receiverId)) {
            throw new IllegalArgumentException("시작 시 설정한 송신/수신 노드와 시험 참여자가 다릅니다.");
        }
    }

    @Override
    public void register(DtnJob job)
    {
        if (!sender()) {
            throw new IllegalStateException("수신 노드는 DTN 전송을 시작할 수 없습니다.");
        }
        validateParticipants(job.getSenderAgentId(), job.getReceiverAgentId());
        // 잘못 연결된 다른 노드에 시험을 등록하지 않도록 ID와 역할을 먼저 확인한다.
        NodeDto.StatusResponse status = peerClient.status();
        if (!status.isOnline()) {
            throw new IllegalStateException("수신 노드의 실행기가 준비되지 않았습니다.");
        }
        try {
            NodeDtnRegistration registration = new NodeDtnRegistration();
            registration.setTestId(job.getId());
            registration.setSenderAgentId(job.getSenderAgentId());
            registration.setReceiverAgentId(job.getReceiverAgentId());
            registration.setProfile(mapper.readTree(job.getSentJson()).path("profile").asText());
            registration.setPayloadSha256(DtnPayloadDigest.sha256(mapper, mapper.readTree(job.getSentJson())));
            DtnRemoteResult accepted = peerClient.exchange("/lnis/api/v1/node/peer/dtn/tests",
                    registration, DtnRemoteResult.class, 16 * 1024);
            if (!job.getId().equals(accepted.getTestId())) {
                throw new IllegalStateException("수신 노드의 시험 식별자가 다릅니다.");
            }
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("송신 JSON 검증 실패", error);
        }
    }

    @Override
    public DtnRemoteResult result(UUID testId)
    {
        DtnRemoteResult result = peerClient.exchange("/lnis/api/v1/node/peer/dtn/tests/" + testId,
                null, DtnRemoteResult.class, DtnModels.MAX_JSON_BYTES);
        if (!testId.equals(result.getTestId())) {
            throw new IllegalStateException("상대 노드의 결과 식별자가 다릅니다.");
        }
        return result;
    }

    /** 같은 ID와 해시의 재등록은 최초 상태를 유지한다. 변경된 내용으로 덮어쓰기는 금지한다. */
    @Transactional
    public synchronized DtnRemoteResult accept(NodeDtnRegistration registration)
    {
        requireReceiver();
        validateParticipants(registration.getSenderAgentId(), registration.getReceiverAgentId());
        if (registration.getTestId() == null || !(DtnModels.PROFILE.equals(registration.getProfile()) || "LANS-AFS-IQ-v1".equals(registration.getProfile()))
                || registration.getPayloadSha256() == null
                || !registration.getPayloadSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("지원하지 않는 DTN 등록 정보입니다.");
        }
        DtnJob existing = repository.findById(registration.getTestId()).orElse(null);
        if (existing != null) {
            if (!registration.getPayloadSha256().equals(existing.getExpectedPayloadSha256())
                    || !registration.getSenderAgentId().equals(existing.getSenderAgentId())
                    || !registration.getReceiverAgentId().equals(existing.getReceiverAgentId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 시험 내용과 다릅니다.");
            }
            return view(existing);
        }
        DtnJob job = new DtnJob();
        job.setId(registration.getTestId());
        job.setSenderAgentId(registration.getSenderAgentId());
        job.setReceiverAgentId(registration.getReceiverAgentId());
        job.setExpectedPayloadSha256(registration.getPayloadSha256());
        job.setState("WAITING_DTN");
        job.setMessage("외부 DTN/HDTN 수신 대기");
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(job.getCreatedAt());
        repository.saveAndFlush(job);
        return view(job);
    }

    public DtnRemoteResult localResult(UUID testId)
    {
        requireReceiver();
        DtnJob job = repository.findById(testId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        validateParticipants(job.getSenderAgentId(), job.getReceiverAgentId());
        return view(job);
    }

    private void requireReceiver()
    {
        if (sender()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "수신 노드 전용 API입니다.");
        }
    }

    private DtnRemoteResult view(DtnJob job)
    {
        DtnRemoteResult result = new DtnRemoteResult();
        result.setTestId(job.getId());
        result.setState(job.getState());
        result.setMessage(job.getMessage());
        result.setReceivedAt(job.getReceivedAt());
        if (job.getFileResultJson() != null) {
            try { result.setFileResult(mapper.readTree(job.getFileResultJson())); }
            catch (java.io.IOException error) { throw new IllegalStateException("I/Q 결과 조회 실패", error); }
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
