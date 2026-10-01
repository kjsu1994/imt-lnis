package server.dtn;

import jakarta.persistence.*;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 기존 AFS 테이블과 분리해 DTN 전달 및 계산 상태를 보관한다. */
@Entity
@Table(name = "dtn_job", indexes = {
        @Index(name = "dtn_job_state_created", columnList = "state,createdAt"),
        @Index(name = "dtn_job_reference_retry", columnList = "referenceStatus,referenceNextAttemptAt"),
        @Index(name = "dtn_job_cancel_pending", columnList = "cancelPending")
})
@Data
public class DtnJob {
    @Id private UUID id;
    private UUID inputId;
    private UUID iqFileId;
    private String senderAgentId;
    private String receiverAgentId;
    private String state;

    /** 어댑터 요청과 최종 수신 대기는 서로 다른 수명으로 관리한다. */
    private String sendStatus;
    private Instant stageStartedAt;
    private Instant lateReceivedAt;

    /** 연결이 복구되면 상대 노드에 중지 요청을 다시 전달한다. */
    private Boolean cancelPending;

    /** 일괄 종료는 상대가 아직 미수신인 경우에만 닫는다. 재시도·재기동에도 유지한다. */
    private Boolean cancelWaitingOnly;

    private String testType;
    private String senderMode;
    private String receiverMode;

    /** 시험 시작 시 확정한 HDTN 설정이다. 기존 시험은 null이다. */
    @Lob private String hdtnConfigJson;

    private String comparisonMode;
    @Column(columnDefinition = "timestamp(9) with time zone")
    private Instant testStartedAt;
    @Lob private String selectedEpochJson;
    @Lob private String delayEvidenceJson;
    /** 내부 시간 보정 근거. 외부 어댑터 JSON에는 포함하지 않는다. */
    @Lob private String senderClockJson;
    @Lob private String receiverClockJson;
    @Lob private String receiverRegistrationClockJson;
    private String clockWarning;
    private Boolean development;
    private Instant createdAt;
    private Instant updatedAt;

    /** 독립 수신 노드는 본문 대신 사전 등록된 해시만으로 외부 전달 내용을 검증한다. */
    @Column(length = 64)
    private String expectedPayloadSha256;

    /** 송신 노드에서도 원문을 복사하지 않고 수신 완료 여부를 표시한다. */
    @Column(columnDefinition = "timestamp(9) with time zone")
    private Instant receivedAt;

    /** 생성 시 확정한 전송 대상이다. 이후 화면 URL을 바꿔도 진행 중인 시험에는 영향을 주지 않는다. */
    @Column(length = 2048)
    private String sendUrl;

    @Column(length = 2048)
    private String message;

    @Lob private String sentJson;
    @Lob private String receivedJson;

    /** 검증을 통과한 최초 UTF-8 수신 본문이다. 공백과 줄바꿈을 바꾸지 않고 보관한다. */
    @Lob private String receivedRawJson;

    @Lob private String referenceJson;
    @Lob private String referenceSourceBase64;
    private String referenceStatus;
    private Integer referenceAttempts;
    private Instant referenceNextAttemptAt;
    @Column(length = 2048) private String referenceMessage;
    @Lob private String receiverJson;
    @Lob private String comparisonJson;

    /** Local input/restored observations for the UI; not part of the adapter contract. */
    @Lob private String observationsJson;

    @Lob private String fileResultJson;
}
