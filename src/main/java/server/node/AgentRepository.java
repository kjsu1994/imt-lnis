package server.node;

import lombok.RequiredArgsConstructor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@RequiredArgsConstructor
@Repository
/** Agent 접속 상태를 H2에 저장하는 업무용 저장소다. */
public class AgentRepository {
    private final AgentJpaRepository agentJpaRepository;

    public void save(AgentEntity agent) {
        agentJpaRepository.save(agent);
    }

    public Optional<AgentEntity> find(String id) {
        return agentJpaRepository.findById(id);
    }

    public List<AgentEntity> findAll() {
        return agentJpaRepository.findAll().stream()
                .sorted(Comparator.comparing(AgentEntity::agentId))
                .toList();
    }

    public void remove(String id) {
        agentJpaRepository.deleteById(id);
    }

    public void deleteLastSeenBefore(Instant cutoff) {
        agentJpaRepository.deleteByLastSeenBefore(cutoff);
    }
}

interface AgentJpaRepository extends JpaRepository<AgentEntity, String> {
    void deleteByLastSeenBefore(Instant cutoff);
}
