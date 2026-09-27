package server.realtime;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import server.common.AgentProtocol.EventType;
import server.common.LnisModels.AgentRole;

import java.time.Instant;
import java.util.UUID;

/** 기준 버전의 Redis Stream 항목을 H2에서 보존하는 실시간 이벤트 행이다. */
@Entity
@Table(
        name = "realtime_events",
        indexes = @Index(name = "idx_event_stream", columnList = "stream_key,event_sequence"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RealtimeEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_sequence")
    private Long sequence;

    @Column(name = "stream_key", nullable = false)
    private String streamKey;

    @Enumerated(EnumType.STRING)
    private EventType type;

    private String agentId;

    @Enumerated(EnumType.STRING)
    private AgentRole role;

    private UUID sessionId;
    @Lob private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    public RealtimeEventEntity(
            String streamKey,
            EventType type,
            String agentId,
            AgentRole role,
            UUID sessionId,
            String payload,
            Instant createdAt) {
        this.streamKey = streamKey;
        this.type = type;
        this.agentId = agentId;
        this.role = role;
        this.sessionId = sessionId;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
