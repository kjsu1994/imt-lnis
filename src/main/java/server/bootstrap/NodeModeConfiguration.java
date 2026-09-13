package server.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import server.agent.config.AgentConfig;
import server.central.agent.AgentConnectionRegistry;
import server.central.agent.AgentMessageService;
import server.central.agent.AgentRepository;
import server.shared.model.LnisModels.AgentRole;

import java.util.Locale;

/** 웹 서비스와 로컬 실행기의 조립만 담당한다. 기능 서비스끼리의 의존 방향은 바꾸지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("node")
public class NodeModeConfiguration {
    @Bean
    server.central.dtn.DtnPvtCalculator dtnPvtCalculator(Environment environment) {
        var directory = java.nio.file.Path.of(environment.getProperty("lnis.native.dir", "native"));
        return records -> {
            try (var codec = new server.agent.codec.NativePvtCodec(directory)) {
                return codec.calculate(records);
            }
        };
    }
    @Bean(destroyMethod = "close")
    LocalNodeLifecycle localNodeLifecycle(Environment environment, ObjectMapper objectMapper,
            AgentConnectionRegistry connectionRegistry, AgentMessageService messageService,
            AgentRepository agentRepository)
    {
        String configuredRole = environment.getRequiredProperty("lnis.node.role");
        AgentRole role = AgentRole.valueOf(configuredRole.trim().toUpperCase(Locale.ROOT));
        AgentConfig agentConfig = AgentConfig.from(environment, role);
        return new LocalNodeLifecycle(agentConfig, objectMapper, connectionRegistry,
                messageService, agentRepository);
    }
}
