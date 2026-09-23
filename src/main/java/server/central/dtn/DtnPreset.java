package server.central.dtn;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

/** 시험 조건만 보관한다. 입력 파일과 PC 연결 주소는 포함하지 않는다. */
@Entity
@Table(name = "dtn_preset")
@Data
public class DtnPreset {
    @Id
    private UUID id;
    @Version
    private Long version;
    @Column(nullable = false, unique = true, length = 40)
    private String name;
    @Lob
    @Column(nullable = false)
    private String settingsJson;
    @Column(nullable = false)
    private Instant updatedAt;
}
