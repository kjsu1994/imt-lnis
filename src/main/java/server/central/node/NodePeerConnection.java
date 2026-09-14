package server.central.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import server.central.agent.AgentConnectionRegistry;
import server.central.agent.AgentEntity;
import server.central.agent.AgentRepository;
import server.central.frameevidence.FrameEvidenceService;
import server.central.session.SessionRepository;
import server.central.session.SessionService;
import server.shared.model.AgentProtocol.Command;
import server.shared.model.AgentProtocol.CommandType;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.AgentProtocol.FrameEvidenceMessage;
import server.shared.model.AgentProtocol.MessageType;
import server.shared.model.CommandEndpoint;
import server.shared.model.LnisModels.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 상대 실행기를 목록에 노출하고 AFS frame을 관리 HTTP로 전달하며 수신 결과를 가져온다. */
@Component
@Profile("node")
@RequiredArgsConstructor
public class NodePeerConnection implements CommandEndpoint {
    private final NodeProperties properties;
    private final NodePeerClient client;
    private final AgentRepository agents;
    private final AgentConnectionRegistry connections;
    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final FrameEvidenceService evidenceService;
    private final ObjectMapper mapper;
    private volatile Instant lastOnline;
    private volatile Boolean reverseOnline;
    public Boolean reverseOnline() { return online() ? reverseOnline : null; }
    private boolean registered;

    @Scheduled(fixedDelay = 3000)
    public void poll()
    {
        if (!properties.peerConfigured()) {
            return;
        }
        java.net.URI checkedAddress = properties.getPeerBaseUrl();
        try {
            NodeDto.StatusResponse status = client.statusAt(checkedAddress);
            synchronized (this) {
                if (!java.util.Objects.equals(checkedAddress, properties.getPeerBaseUrl())) {
                    return;
                }
                if (!registered) {
                    connections.registerEndpoint(properties.getPeerAgentId(), this);
                    registered = true;
                }
                lastOnline = status.isOnline() ? Instant.now() : null;
                reverseOnline = status.getPeerOnline();
                String host = checkedAddress.getHost();
                List<String> addresses = host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                        ? List.of(host) : List.of();
                agents.save(new AgentEntity(status.getAgentId(), status.getRole(),
                        status.isOnline() ? status.getState() : AgentState.OFFLINE, Instant.now(),
                        "node-v" + status.getProtocolVersion(), status.getCodecAbiVersion(),
                        "remote-node", "unknown", addresses, null));
            }
        } catch (RuntimeException unavailable) {
            synchronized (this) {
                if (!java.util.Objects.equals(checkedAddress, properties.getPeerBaseUrl())) {
                    return;
                }
                lastOnline = null;
                agents.find(properties.getPeerAgentId()).ifPresent(previous -> agents.save(
                        new AgentEntity(previous.agentId(), previous.role(), AgentState.OFFLINE,
                                previous.lastSeen(), previous.version(), previous.codecAbiVersion(),
                                previous.os(), previous.architecture(), previous.ipv4Addresses(), "상대 노드 연결 끊김")));
            }
            return;
        }
        if (properties.getRole() == AgentRole.SENDER) {
            sessionService.activeSnapshot().ifPresent(snapshot -> collect(snapshot.sessionId()));
        }
    }

    @Override
    public boolean online()
    {
        Instant seen = lastOnline;
        return seen != null && Instant.now().isBefore(seen.plusSeconds(15));
    }

    /** 이전 주소의 READY 상태를 새 주소에 재사용하지 않는다. 다음 정상 상태 조회 후 연결된다. */
    public synchronized void applyAddress(java.net.URI address)
    {
        properties.applyPeerAddress(address);
        lastOnline = null;
        agents.find(properties.getPeerAgentId()).ifPresent(previous -> agents.save(
                new AgentEntity(previous.agentId(), previous.role(), AgentState.OFFLINE,
                        previous.lastSeen(), previous.version(), previous.codecAbiVersion(),
                        previous.os(), previous.architecture(), List.of(), "주소 변경 후 연결 확인 중")));
    }

    @Override
    public void send(Envelope message)
    {
        if (properties.getRole() != AgentRole.SENDER
                || !properties.getPeerAgentId().equals(message.agentId())) {
            throw new IllegalArgumentException("허용하지 않는 원격 AFS 요청입니다.");
        }
        if (message.type() == MessageType.AFS_TRANSFER_START
                || message.type() == MessageType.AFS_TRANSFER_BATCH
                || message.type() == MessageType.AFS_TRANSFER_COMPLETE) {
            client.exchange("/lnis/api/v1/node/peer/afs/messages", message,
                    java.util.Map.class, 1024);
            return;
        }
        if (message.type() != MessageType.COMMAND) {
            throw new IllegalArgumentException("원격 실행기에 전달할 수 없는 메시지입니다.");
        }
        Command command = mapper.convertValue(message.payload(), Command.class);
        if (command.command() != CommandType.ARM_RECEIVER && command.command() != CommandType.CANCEL_SESSION) {
            throw new IllegalArgumentException("원격 관리 명령은 수신 준비와 취소만 가능합니다.");
        }
        SessionSnapshot response = client.exchange("/lnis/api/v1/node/peer/afs/commands", message,
                SessionSnapshot.class, 4 * 1024 * 1024);
        if (!message.sessionId().equals(response.sessionId())) {
            throw new IllegalStateException("원격 명령 응답의 시험 ID가 다릅니다.");
        }
    }

    private void collect(UUID id)
    {
        try {
            SessionSnapshot remote = client.exchange("/lnis/api/v1/node/peer/afs/sessions/" + id,
                    null, SessionSnapshot.class, 4 * 1024 * 1024);
            if (!id.equals(remote.sessionId()) || !properties.getPeerAgentId().equals(remote.receiverAgentId())
                    || !properties.getAgentId().equals(remote.senderAgentId())) {
                throw new IllegalStateException("원격 AFS 결과 식별자가 다릅니다.");
            }
            RoleResult result = remote.rxResult();
            if (result == null) {
                if (remote.state() == SessionState.CANCELLED || remote.state() == SessionState.FAILED) {
                    sessionService.cancel(id);
                }
                return;
            }
            if (!id.equals(result.sessionId()) || result.role() != AgentRole.RECEIVER) {
                throw new IllegalStateException("원격 AFS 역할 결과가 다릅니다.");
            }
            if (sessions.result(id, AgentRole.RECEIVER).isEmpty()) {
                int cursor = -1;
                while (true) {
                    FrameEvidenceMessage[] page = client.exchange("/lnis/api/v1/node/peer/afs/sessions/"
                            + id + "/evidence?after=" + cursor, null, FrameEvidenceMessage[].class, 4 * 1024 * 1024);
                    if (page.length == 0) {
                        break;
                    }
                    for (FrameEvidenceMessage evidence : page) {
                        if (evidence.frameIndex() <= cursor) {
                            throw new IllegalStateException("원격 프레임 순서가 잘못되었습니다.");
                        }
                        evidenceService.save(id, AgentRole.RECEIVER, evidence);
                        cursor = evidence.frameIndex();
                    }
                }
                sessions.saveResult(result);
            }
            sessionService.onResult(id);
        } catch (RuntimeException unavailable) {
            // 통신 실패 시 결과를 만들거나 데이터 송신을 반복하지 않는다. 기존 watchdog이 제한 시간을 관리한다.
        }
    }

    @PreDestroy
    public void close()
    {
        lastOnline = null;
        connections.removeEndpoint(properties.getPeerAgentId(), this);
    }
}
