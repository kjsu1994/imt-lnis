package server.central.management;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** 관리 설정·보관 고정·삭제 식별·정리 이력만 저장한다. 시험 원문을 복사하지 않는다. */
@Entity @Table(name="data_management") @Getter @Setter @NoArgsConstructor
public class ManagementEntry {
    @Id private String id;
    private String kind;
    private Instant createdAt;
    @Lob @Column(name="payload_json") private String value;
    public ManagementEntry(String id,String kind,String value) {
        this.id=id;this.kind=kind;this.value=value;this.createdAt=Instant.now();
    }
}
