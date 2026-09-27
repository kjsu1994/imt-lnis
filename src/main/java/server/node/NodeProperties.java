package server.node;

import lombok.Getter;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import server.common.LnisModels.AgentRole;

import java.net.URI;
import java.util.Locale;

/** 역할과 인증 설정은 시작 시 고정하고, 상대 주소만 검증·저장 후 변경한다. */
@Getter
@Component
@Profile("node")
public class NodeProperties {
    private final AgentRole role;
    private final String agentId;
    private final String peerAgentId;
    private final URI baseUrl;
    private volatile URI peerBaseUrl;
    private final String managementToken;

    public NodeProperties(Environment environment) {
        role =
                AgentRole.valueOf(
                        environment
                                .getRequiredProperty("lnis.node.role")
                                .trim()
                                .toUpperCase(Locale.ROOT));
        agentId =
                environment.getProperty(
                        "lnis.agent.id", role == AgentRole.SENDER ? "sender-1" : "receiver-1");
        peerAgentId =
                environment.getProperty(
                        "lnis.node.peer-id", role == AgentRole.SENDER ? "receiver-1" : "sender-1");
        baseUrl =
                parseBaseUrl(
                        environment.getProperty("lnis.node.base-url", "http://localhost:8088"));
        String peerAddress = environment.getProperty("lnis.node.peer-url", "").trim();
        peerBaseUrl = peerAddress.isEmpty() ? null : parseBaseUrl(peerAddress);
        managementToken = environment.getProperty("lnis.node.management-token", "");
        if (agentId.isBlank() || peerAgentId.isBlank() || agentId.equals(peerAgentId)) {
            throw new IllegalArgumentException("노드 ID와 상대 노드 ID는 서로 다른 값이어야 합니다.");
        }
        if (peerBaseUrl != null && peerBaseUrl.equals(baseUrl)) {
            throw new IllegalArgumentException("상대 노드 주소는 현재 노드 주소와 달라야 합니다.");
        }
    }

    public boolean peerConfigured() {
        return peerBaseUrl != null && !managementToken.isBlank();
    }

    /** 검증 및 영속 저장을 완료한 설정 서비스만 호출한다. 역할과 인증 토큰은 변경하지 않는다. */
    void applyPeerAddress(URI address) {
        URI validated = parseBaseUrl(address.toString());
        if (validated.equals(baseUrl)) {
            throw new IllegalArgumentException("자기 자신을 수신 노드로 지정할 수 없습니다.");
        }
        peerBaseUrl = validated;
    }

    private static URI parseBaseUrl(String value) {
        URI address = URI.create(value.trim());
        if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme()))
                || address.getHost() == null
                || address.getUserInfo() != null
                || address.getQuery() != null
                || address.getFragment() != null
                || !(address.getPath().isEmpty() || "/".equals(address.getPath()))) {
            throw new IllegalArgumentException("노드 주소는 경로 없는 http(s)://호스트:포트 형식이어야 합니다.");
        }
        return URI.create(address.getScheme() + "://" + address.getRawAuthority());
    }
}
