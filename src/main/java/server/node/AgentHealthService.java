package server.node;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import server.common.AgentProtocol.EventType;
import server.common.LnisModels.AgentState;
import server.realtime.EventService;

import java.time.Duration;
import java.time.Instant;

@RequiredArgsConstructor
@Service
/** Heartbeat가 끊긴 Agent를 OFFLINE으로 전환하고 상태 이벤트를 발행한다. */
public class AgentHealthService {
    private final AgentRepository agentRepository;
    private final EventService eventService;

    /* Heartbeat가 끊긴 Agent를 OFFLINE으로 전환한다. */
    @Scheduled(fixedDelay = 5000)
    public void markStaleAgentsOffline() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(15));
        for (AgentEntity agent : agentRepository.findAll()) {
            if (agent.state() == AgentState.OFFLINE || !agent.lastSeen().isBefore(cutoff)) {
                continue;
            }
            AgentEntity offline =
                    new AgentEntity(
                            agent.agentId(),
                            agent.role(),
                            AgentState.OFFLINE,
                            agent.lastSeen(),
                            agent.version(),
                            agent.codecAbiVersion(),
                            agent.os(),
                            agent.architecture(),
                            agent.ipv4Addresses(),
                            "Heartbeat timeout");
            agentRepository.save(offline);
            eventService.publish(
                    EventType.AGENT_STATUS, agent.agentId(), agent.role(), null, offline);
        }
    }
}
