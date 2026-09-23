package server.central.node;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import server.central.agent.AgentConnectionRegistry;
import server.central.agent.AgentEntity;
import server.central.agent.AgentRepository;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.CommandEndpoint;
import server.shared.model.LnisModels.*;

import java.time.Instant;
import java.util.List;

/** 상대 실행기의 연결·READY 상태를 조회하여 Agent 목록에 반영한다. */
@Component
@Profile("node")
@RequiredArgsConstructor
public class NodePeerConnection implements CommandEndpoint {
    private final NodeProperties properties;
    private final NodePeerClient client;
    private final AgentRepository agents;
    private final AgentConnectionRegistry connections;
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

    /** 상대 노드는 상태 조회용이며 DTN 관리 요청은 NodeDtnService가 담당한다. */
    @Override
    public void send(Envelope message)
    {
        throw new IllegalArgumentException("상대 실행기 직접 명령은 지원하지 않습니다.");
    }

    @PreDestroy
    public void close()
    {
        lastOnline = null;
        connections.removeEndpoint(properties.getPeerAgentId(), this);
    }
}
