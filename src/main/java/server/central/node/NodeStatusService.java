package server.central.node;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import server.central.agent.AgentConnectionRegistry;
import server.central.agent.AgentEntity;
import server.central.agent.AgentRepository;
import server.shared.model.LnisModels.AgentState;

/** H2에 남은 과거 READY 값 대신 실제 로컬 실행기 등록 상태를 함께 확인한다. */
@Service
@Profile("node")
@RequiredArgsConstructor
public class NodeStatusService {
    public static final int PROTOCOL_VERSION = 1;
    private final NodeProperties nodeProperties;
    private final AgentRepository agentRepository;
    private final AgentConnectionRegistry connectionRegistry;

    public NodeDto.StatusResponse status()
    {
        AgentEntity agent = agentRepository.find(nodeProperties.getAgentId()).orElse(null);
        boolean online = connectionRegistry.online(nodeProperties.getAgentId());
        AgentState state = agent == null ? AgentState.ERROR : agent.state();
        return new NodeDto.StatusResponse(PROTOCOL_VERSION, nodeProperties.getAgentId(),
                nodeProperties.getRole(), state, online, agent == null ? 0 : agent.codecAbiVersion(),
                nodeProperties.getBaseUrl().toString(), connectionRegistry.online(nodeProperties.getPeerAgentId()));
    }
}
