package server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import server.common.LnisModels.AgentRole;
import server.node.AgentConfig;
import server.node.AgentConnectionRegistry;
import server.node.AgentMessageService;
import server.node.AgentRepository;
import server.node.LocalNodeLifecycle;

import java.util.Locale;

/** 웹 서비스와 로컬 실행기의 조립만 담당한다. 기능 서비스끼리의 의존 방향은 바꾸지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("node")
public class NodeModeConfiguration {
    @Bean
    server.pvt.DtnPvtCalculator dtnPvtCalculator(Environment environment) {
        var directory = java.nio.file.Path.of(environment.getProperty("lnis.native.dir", "native"));
        return (records, constellation) -> {
            try (var codec = new server.pvt.NativePvtCodec(directory)) {
                return codec.calculate(records, constellation);
            }
        };
    }

    @Bean(destroyMethod = "close")
    LocalNodeLifecycle localNodeLifecycle(
            Environment environment,
            AgentConnectionRegistry connectionRegistry,
            AgentMessageService messageService,
            AgentRepository agentRepository) {
        String configuredRole = environment.getRequiredProperty("lnis.node.role");
        AgentRole role = AgentRole.valueOf(configuredRole.trim().toUpperCase(Locale.ROOT));
        AgentConfig agentConfig = AgentConfig.from(environment, role);
        return new LocalNodeLifecycle(
                agentConfig, connectionRegistry, messageService, agentRepository);
    }
}
