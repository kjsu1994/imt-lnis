package server.central.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import server.shared.model.AgentProtocol.*;
import server.shared.model.LnisModels.AgentRole;

import java.util.UUID;

@RequiredArgsConstructor
@Service
/**
 * 중앙 서버의 명령과 입력 청크를 대상 Agent WebSocket으로 전달한다.
 *
 * <p>AgentRepository에서 대상 역할을 확인해 envelope에 기록하고, ConnectionRegistry를 통해 현재 활성 연결로만 전송한다. 오프라인
 * Agent에는 명령을 대기열에 적재하지 않고 즉시 오류를 반환한다.
 */
public class AgentCommandService {
    private final AgentConnectionRegistry agentConnectionRegistry;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    /** 명령 인수를 JSON tree로 변환해 COMMAND envelope로 전송하고 추적용 message ID를 반환한다. */
    public UUID command(String agentId, UUID sessionId, CommandType type, Object arguments)
    {
        AgentRole role =
                agentRepository
                        .find(agentId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Unknown agent: " + agentId))
                        .role();
        Envelope envelope =
                Envelope.of(
                        MessageType.COMMAND,
                        agentId,
                        role,
                        sessionId,
                        objectMapper.valueToTree(
                                new Command(type, objectMapper.valueToTree(arguments))));
        agentConnectionRegistry.send(agentId, envelope);
        return envelope.messageId();
    }


}

