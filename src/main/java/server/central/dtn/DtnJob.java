package server.central.dtn;

import jakarta.persistence.*;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 기존 AFS 테이블과 분리해 DTN 전달 및 계산 상태를 보관한다. */
@Entity
@Table(name = "dtn_job")
@Data
public class DtnJob {
    @Id
    private UUID id;
    private UUID inputId;
    private String senderAgentId;
    private String receiverAgentId;
    private String state;
    private Instant createdAt;
    private Instant updatedAt;
    /** 독립 수신 노드는 본문 대신 사전 등록된 해시만으로 외부 전달 내용을 검증한다. */
    @Column(length = 64)
    private String expectedPayloadSha256;
    /** 송신 노드에서도 원문을 복사하지 않고 수신 완료 여부를 표시한다. */
    private Instant receivedAt;
    /** 생성 시 확정한 전송 대상이다. 이후 화면 URL을 바꿔도 진행 중인 시험에는 영향을 주지 않는다. */
    @Column(length = 2048)
    private String sendUrl;

    @Column(length = 2048)
    private String message;

    @Lob
    private String sentJson;
    @Lob
    private String receivedJson;
    /** 검증을 통과한 최초 UTF-8 수신 본문이다. 공백과 줄바꿈을 바꾸지 않고 보관한다. */
    @Lob
    private String receivedRawJson;
    @Lob
    private String referenceJson;
    @Lob
    private String receiverJson;
    @Lob
    private String comparisonJson;
    /** Local input/restored observations for the UI; not part of the adapter contract. */
    @Lob
    private String observationsJson;
}
