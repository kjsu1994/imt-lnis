package server.central.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.RoleResult;
import server.shared.model.LnisModels.SessionState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Repository
/** 시험 세션과 역할별 결과를 H2 테이블에 보관한다. */
public class SessionRepository {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private server.central.management.DataManagementGuard managementGuard;

    private final SessionJpaRepository sessionJpaRepository;
    private final RoleResultJpaRepository roleResultJpaRepository;
    private final ObjectMapper objectMapper;

    public void save(TestSessionEntity value)
    {
        if(managementGuard!=null) managementGuard.requirePresent("AFS",value.sessionId());
        sessionJpaRepository.save(value);
    }

    public Optional<TestSessionEntity> find(UUID id)
    {
        return sessionJpaRepository.findById(id);
    }

    public void saveResult(RoleResult result)
    {
        try {
            if(managementGuard!=null) managementGuard.requirePresent("AFS",result.sessionId());
            roleResultJpaRepository.save(
                    new RoleResultEntity(
                            result.sessionId(),
                            result.role(),
                            objectMapper.writeValueAsString(result),
                            Instant.now()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<RoleResult> result(UUID id, AgentRole role)
    {
        return roleResultJpaRepository
                .findBySessionIdAndRole(id, role)
                .map(
                        value -> {
                            try {
                                return objectMapper.readValue(
                                        value.getResultJson(), RoleResult.class);
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        });
    }

    public boolean existsByInputId(UUID inputId)
    {
        return sessionJpaRepository.existsByInputId(inputId);
    }

    public List<UUID> terminalBefore(Instant cutoff)
    {
        return sessionJpaRepository
                .findByStateInAndUpdatedAtBefore(
                        List.of(
                                SessionState.COMPLETED, SessionState.CANCELLED,
                                SessionState.FAILED, SessionState.INCONCLUSIVE),
                        cutoff)
                .stream()
                .map(TestSessionEntity::sessionId)
                .toList();
    }

    public void delete(UUID id)
    {
        roleResultJpaRepository.deleteBySessionId(id);
        sessionJpaRepository.deleteById(id);
    }
}

interface SessionJpaRepository extends JpaRepository<TestSessionEntity, UUID> {
    boolean existsByInputId(UUID inputId);

    List<TestSessionEntity> findByStateInAndUpdatedAtBefore(
            List<SessionState> states, Instant cutoff);
}

interface RoleResultJpaRepository extends JpaRepository<RoleResultEntity, RoleResultEntity.Key> {
    Optional<RoleResultEntity> findBySessionIdAndRole(UUID sessionId, AgentRole role);

    void deleteBySessionId(UUID sessionId);
}

