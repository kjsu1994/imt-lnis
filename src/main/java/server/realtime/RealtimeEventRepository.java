package server.realtime;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import server.common.AgentProtocol.EventType;
import server.common.LnisModels.AgentRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 기준 Redis Stream의 전역 sequence, 세션별 최근 10,000개와 보존 기간을 H2로 이식한다. */
@RequiredArgsConstructor
@Repository
public class RealtimeEventRepository {
    private static final long MAX_EVENTS_PER_STREAM = 10_000;
    private final RealtimeEventJpaRepository realtimeEventJpaRepository;

    @Transactional
    public synchronized long append(
            String streamKey,
            EventType type,
            String agentId,
            AgentRole role,
            UUID sessionId,
            String payload,
            Instant createdAt) {
        RealtimeEventEntity saved =
                realtimeEventJpaRepository.saveAndFlush(
                        new RealtimeEventEntity(
                                streamKey, type, agentId, role, sessionId, payload, createdAt));
        long overflow =
                realtimeEventJpaRepository.countByStreamKey(streamKey) - MAX_EVENTS_PER_STREAM;
        if (overflow > 0) {
            List<Long> ids =
                    realtimeEventJpaRepository.findOldestIds(
                            streamKey, PageRequest.of(0, (int) overflow));
            realtimeEventJpaRepository.deleteAllByIdInBatch(ids);
        }
        return saved.getSequence();
    }

    public long count(String streamKey) {
        return realtimeEventJpaRepository.countByStreamKey(streamKey);
    }

    @Transactional
    public void deleteExpiredStreams(Instant cutoff) {
        for (String streamKey : realtimeEventJpaRepository.findExpiredStreamKeys(cutoff)) {
            realtimeEventJpaRepository.deleteByStreamKey(streamKey);
        }
    }
}

interface RealtimeEventJpaRepository extends JpaRepository<RealtimeEventEntity, Long> {
    long countByStreamKey(String streamKey);

    @Query(
            "select event.sequence from RealtimeEventEntity event "
                    + "where event.streamKey = :streamKey order by event.sequence")
    List<Long> findOldestIds(@Param("streamKey") String streamKey, Pageable pageable);

    @Query(
            "select event.streamKey from RealtimeEventEntity event group by event.streamKey "
                    + "having max(event.createdAt) < :cutoff")
    List<String> findExpiredStreamKeys(@Param("cutoff") Instant cutoff);

    void deleteByStreamKey(String streamKey);
}
