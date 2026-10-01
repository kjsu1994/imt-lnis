package server.dtn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 서버 재시작 후에도 외부 callback과 시험을 연결하는 영속 저장소다. */
public interface DtnRepository extends JpaRepository<DtnJob, UUID> {
    List<DtnJob> findByStateIn(List<String> states);

    /** 반복 상태 조회에서 송수신 원문과 계산 결과 LOB를 읽지 않는다. */
    interface TaskView {
        UUID getId();
        String getState();
        String getSendStatus();
        Boolean getCancelPending();
        String getTestType();
        String getReceiverAgentId();
        String getSenderAgentId();
        java.time.Instant getCreatedAt();
        java.time.Instant getStageStartedAt();
        java.time.Instant getReceivedAt();
    }

    interface ReferenceTask {
        UUID getId();
        java.time.Instant getReferenceNextAttemptAt();
    }

    List<TaskView> findTasksByStateIn(List<String> states);

    List<ReferenceTask> findTasksByReferenceStatus(String status);

    boolean existsByState(String state);

    boolean existsByStateIn(List<String> states);

    boolean existsByStateInAndSendStatusIn(List<String> states, List<String> statuses);

    List<DtnJob> findTop50ByOrderByCreatedAtDesc();

    List<DtnJob> findAllByOrderByCreatedAtDescIdDesc(org.springframework.data.domain.Pageable page);

    List<DtnJob> findByStateOrderByCreatedAtDescIdDesc(String state,
            org.springframework.data.domain.Pageable page);

    List<DtnJob> findByCancelPendingTrue();

    List<DtnJob> findByReferenceStatus(String status);

    boolean existsByInputId(UUID inputId);

    boolean existsByIqFileIdAndStateIn(UUID iqFileId, List<String> states);
}
