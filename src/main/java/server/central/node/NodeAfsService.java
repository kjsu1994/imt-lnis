package server.central.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import server.central.agent.AgentConnectionRegistry;
import server.central.session.ActiveSessionLockRepository;
import server.central.session.CreateSessionRequest;
import server.central.session.SessionRepository;
import server.central.session.SessionService;
import server.central.session.TestSessionEntity;
import server.shared.model.AgentProtocol;
import server.shared.model.AgentProtocol.Command;
import server.shared.model.AgentProtocol.CommandType;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.AgentProtocol.MessageType;
import server.shared.model.LnisModels.*;

import java.time.Instant;
import java.util.UUID;

/** 관리 채널의 수신 준비·취소와 AFS frame batch 전달을 검증한다. */
@Service
@Profile("node")
@RequiredArgsConstructor
public class NodeAfsService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private server.central.management.DataManagementGuard managementGuard;

    private final NodeProperties properties;
    private final AgentConnectionRegistry connections;
    private final SessionRepository sessions;
    private final ActiveSessionLockRepository locks;
    private final SessionService sessionService;
    private final ObjectMapper mapper;

    public synchronized SessionSnapshot command(Envelope envelope) throws Exception
    {
        if (properties.getRole() != AgentRole.RECEIVER || envelope == null
                || envelope.protocolVersion() != AgentProtocol.PROTOCOL_VERSION
                || envelope.type() != MessageType.COMMAND || envelope.sessionId() == null
                || envelope.role() != AgentRole.RECEIVER
                || !properties.getAgentId().equals(envelope.agentId())) {
            throw new IllegalArgumentException("허용하지 않는 원격 시험 제어 요청입니다.");
        }
        Command command = mapper.treeToValue(envelope.payload(), Command.class);
        if (command.command() != CommandType.ARM_RECEIVER && command.command() != CommandType.CANCEL_SESSION) {
            throw new IllegalArgumentException("관리 채널은 수신 준비와 취소만 지원합니다.");
        }
        UUID id = envelope.sessionId();
        if(managementGuard!=null) managementGuard.requirePresent("AFS",id);
        TestSessionEntity previous = sessions.find(id).orElse(null);
        if (command.command() == CommandType.CANCEL_SESSION) {
            if (previous == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            // 존재하는 세션만 취소해야 지연된 잘못된 요청이 다른 시험의 소켓을 닫지 않는다.
            if (terminal(previous.state())) {
                return sessionService.snapshot(id);
            }
            sessionService.cancel(id);
            return sessionService.snapshot(id);
        }
        CreateSessionRequest request = mapper.treeToValue(command.arguments(), CreateSessionRequest.class);
        SessionService.validateSettings(request);
        if (!properties.getAgentId().equals(request.receiverAgentId())
                || !properties.getPeerAgentId().equals(request.senderAgentId())) {
            throw new IllegalArgumentException("등록된 송신/수신 노드와 요청이 다릅니다.");
        }
        if (previous != null) {
            if (!mapper.readTree(previous.requestJson()).equals(mapper.valueToTree(request))
                    || terminal(previous.state())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 시험 ID입니다.");
            }
            // 동일 준비 요청의 재전송은 Receiver 세션을 중복 생성하지 않는다.
            return sessionService.snapshot(id);
        }
        String requestJson = mapper.writeValueAsString(request);
        if (!connections.online(properties.getAgentId()) || !locks.tryAcquire(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "수신 실행기가 오프라인이거나 시험 진행 중입니다.");
        }
        Instant now = Instant.now();
        TestSessionEntity session = new TestSessionEntity(id, SessionState.WAITING_RECEIVER,
                request.options().testType(), request.senderAgentId(), request.receiverAgentId(),
                request.inputId(), 0, "원격 송신 노드의 AFS 프레임 수신 준비", Verdict.INCONCLUSIVE,
                requestJson, now, now);
        try {
            sessions.save(session);
            connections.send(properties.getAgentId(), envelope);
            return sessionService.snapshot(id);
        } catch (Exception error) {
            try {
                if (sessions.find(id).isPresent()) {
                    sessionService.cancel(id);
                }
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            } finally {
                locks.release(id);
            }
            throw error;
        }
    }

    public SessionSnapshot result(UUID id)
    {
        SessionSnapshot result = sessionService.snapshot(id);
        if (!properties.getAgentId().equals(result.receiverAgentId())
                || !properties.getPeerAgentId().equals(result.senderAgentId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return result;
    }

    public synchronized void message(Envelope envelope)
    {
        if (properties.getRole() != AgentRole.RECEIVER || envelope == null
                || envelope.protocolVersion() != AgentProtocol.PROTOCOL_VERSION
                || envelope.sessionId() == null || envelope.role() != AgentRole.RECEIVER
                || !properties.getAgentId().equals(envelope.agentId())
                || (envelope.type() != MessageType.AFS_TRANSFER_START
                    && envelope.type() != MessageType.AFS_TRANSFER_BATCH
                    && envelope.type() != MessageType.AFS_TRANSFER_COMPLETE)) {
            throw new IllegalArgumentException("허용하지 않는 AFS frame 전달 요청입니다.");
        }
        TestSessionEntity session = sessions.find(envelope.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (terminal(session.state()) || !session.senderAgentId().equals(properties.getPeerAgentId())
                || !session.receiverAgentId().equals(properties.getAgentId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "수신 준비된 시험이 아닙니다.");
        }
        connections.send(properties.getAgentId(), envelope);
    }

    private static boolean terminal(SessionState state)
    {
        return state == SessionState.COMPLETED || state == SessionState.CANCELLED
                || state == SessionState.FAILED || state == SessionState.INCONCLUSIVE;
    }
}
