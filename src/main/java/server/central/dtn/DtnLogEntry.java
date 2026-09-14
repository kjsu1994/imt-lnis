package server.central.dtn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Local processing evidence, never part of the external transfer payload. */
@Entity
@Table(name = "dtn_log", indexes = @Index(columnList = "scopeId,sequence"))
@Getter @NoArgsConstructor
public class DtnLogEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long sequence;
    private UUID scopeId;
    private String scopeType;
    private Instant occurredAt;
    private String level;
    private String stage;
    private boolean detail;
    @Column(length = 2000) private String message;

    DtnLogEntry(UUID id, String type, Instant at, String level, String stage, boolean detail, String message) {
        this.scopeId=id; this.scopeType=type; this.occurredAt=at;
        this.level=level; this.stage=stage; this.detail=detail; this.message=message;
    }
}

interface DtnLogRepository extends JpaRepository<DtnLogEntry, Long> {
    List<DtnLogEntry> findByScopeIdAndSequenceGreaterThanOrderBySequence(UUID scopeId, long sequence, Pageable page);
    boolean existsByScopeId(UUID scopeId);
    boolean existsByScopeIdAndStage(UUID scopeId, String stage);
    long deleteByScopeTypeNotAndOccurredAtBefore(String type, Instant cutoff);
}
