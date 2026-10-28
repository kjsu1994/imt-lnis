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

    @org.springframework.data.jpa.repository.Query("""
            select (count(e) > 0) from DtnJob e
            where e.state in ('PREPARING', 'WAITING_DTN', 'WAITING_RECEIVER', 'CALCULATING')
               or e.cancelPending = true or e.referenceStatus = 'WAITING'
            """)
    boolean peerAddressBusy();

    boolean existsByState(String state);

    boolean existsByStateIn(List<String> states);

    boolean existsByStateInAndSendStatusIn(List<String> states, List<String> statuses);

    /** 목록 응답에 필요한 값만 읽으며 원문은 존재 여부만 조회한다. */
    interface SummaryView {
        UUID getId();
        String getTestType();
        String getSenderMode();
        String getReceiverMode();
        String getHdtnConfigJson();
        String getDtnConfigJson();
        Boolean getDevelopment();
        String getComparisonMode();
        String getPvtConstellation();
        java.time.Instant getTestStartedAt();
        String getSelectedEpochJson();
        UUID getInputId();
        String getSenderAgentId();
        String getReceiverAgentId();
        String getState();
        String getSendStatus();
        java.time.Instant getStageStartedAt();
        java.time.Instant getLateReceivedAt();
        Boolean getCancelPending();
        String getSendUrl();
        String getMessage();
        java.time.Instant getCreatedAt();
        java.time.Instant getUpdatedAt();
        java.time.Instant getReceivedAt();
        String getReceiverJson();
        String getFileResultJson();
        String getReferenceJson();
        String getComparisonJson();
        boolean isDtnReceived();
        boolean isSentPayloadAvailable();
        boolean isReceivedPayloadAvailable();
        boolean isReceivedOriginalAvailable();
        boolean isComparisonOnSender();
    }

    // 엔티티의 상위 타입과 조회 projection을 구분해야 Spring Data가 Tuple을 변환한다.
    interface SummaryRow extends SummaryView {}

    @org.springframework.data.jpa.repository.Query("""
            select
                j.id as id,
                j.testType as testType,
                j.senderMode as senderMode,
                j.receiverMode as receiverMode,
                j.hdtnConfigJson as hdtnConfigJson,
                j.dtnConfigJson as dtnConfigJson,
                j.development as development,
                j.comparisonMode as comparisonMode,
                j.pvtConstellation as pvtConstellation,
                j.testStartedAt as testStartedAt,
                j.selectedEpochJson as selectedEpochJson,
                j.inputId as inputId,
                j.senderAgentId as senderAgentId,
                j.receiverAgentId as receiverAgentId,
                j.state as state,
                j.sendStatus as sendStatus,
                j.stageStartedAt as stageStartedAt,
                j.lateReceivedAt as lateReceivedAt,
                j.cancelPending as cancelPending,
                j.sendUrl as sendUrl,
                j.message as message,
                j.createdAt as createdAt,
                j.updatedAt as updatedAt,
                j.receivedAt as receivedAt,
                j.receiverJson as receiverJson,
                j.fileResultJson as fileResultJson,
                j.referenceJson as referenceJson,
                j.comparisonJson as comparisonJson,
                case when j.receivedJson is not null or j.receivedAt is not null then true else false end as dtnReceived,
                case when j.sentJson is not null then true else false end as sentPayloadAvailable,
                case when j.receivedJson is not null then true else false end as receivedPayloadAvailable,
                case when j.receivedRawJson is not null then true else false end as receivedOriginalAvailable,
                case when j.expectedPayloadSha256 is not null then true else false end as comparisonOnSender
            from DtnJob j
            where (:state is null or j.state = :state)
            order by j.createdAt desc, j.id desc
            """)
    List<SummaryRow> findSummaries(@org.springframework.data.repository.query.Param("state") String state,
            org.springframework.data.domain.Pageable page);

    @org.springframework.data.jpa.repository.Query("""
            select j.id from DtnJob j
            where j.expectedPayloadSha256 is not null
              and (j.testType is null or j.senderMode is null or j.receiverMode is null)
            """)
    List<UUID> findMissingTrialMetadata();

    /** 결과·상태를 덮어쓰지 않고 검증된 표시 정보만 보완한다. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update DtnJob j
            set j.testType = coalesce(j.testType, :type),
                j.senderMode = coalesce(j.senderMode, :senderMode),
                j.receiverMode = coalesce(j.receiverMode, :receiverMode)
            where j.id = :id and j.expectedPayloadSha256 = :digest
              and (j.testType is null or :type is null or j.testType = :type)
              and (j.senderMode is null or :senderMode is null or j.senderMode = :senderMode)
              and (j.receiverMode is null or :receiverMode is null or j.receiverMode = :receiverMode)
            """)
    int fillTrialMetadata(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("digest") String digest,
            @org.springframework.data.repository.query.Param("type") String type,
            @org.springframework.data.repository.query.Param("senderMode") String senderMode,
            @org.springframework.data.repository.query.Param("receiverMode") String receiverMode);

    List<DtnJob> findByCancelPendingTrue();

    List<DtnJob> findByReferenceStatus(String status);

    boolean existsByInputId(UUID inputId);

    boolean existsByIqFileIdAndStateIn(UUID iqFileId, List<String> states);
}
