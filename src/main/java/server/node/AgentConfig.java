package server.node;

import org.springframework.core.env.Environment;

import server.common.LnisModels.AgentRole;

import java.nio.file.Path;

/** 로컬 실행에 필요한 역할, 식별자와 네이티브 경로만 보유한다. */
@lombok.Value
@lombok.AllArgsConstructor
@lombok.experimental.Accessors(fluent = true)
public class AgentConfig {
    String agentId;
    AgentRole role;
    Path nativeDirectory;

    public static AgentConfig from(Environment environment, AgentRole expectedRole) {
        AgentRole role =
                AgentRole.valueOf(
                        environment
                                .getProperty("lnis.agent.role", expectedRole.name())
                                .trim()
                                .toUpperCase(java.util.Locale.ROOT));
        if (role != expectedRole) {
            throw new IllegalStateException("실행 역할과 lnis.agent.role이 일치하지 않습니다.");
        }
        String defaultId = role == AgentRole.SENDER ? "sender-1" : "receiver-1";
        return new AgentConfig(
                environment.getProperty("lnis.agent.id", defaultId),
                role,
                Path.of(environment.getProperty("lnis.native.dir", "native")));
    }
}
