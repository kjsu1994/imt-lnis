package server.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

class ApplicationProfileConfigurationTest {
    private ConfigurableEnvironment settings(Map<String, Object> overrides, String... profiles) {
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("test-overrides", overrides));
            new ConfigDataApplicationContextInitializer().initialize(context);
            return context.getEnvironment();
        }
    }

    @Test
    void integratedNodePreservesDatabaseSchedulingAndAdapterOverrides() {
        var env =
                settings(
                        Map.of(
                                "server.port",
                                "8090",
                                "LNIS_DB_URL",
                                "jdbc:h2:mem:profile-check",
                                "dtn_adapter",
                                "http://adapter:8080"),
                        "server",
                        "node");
        assertEquals("8090", env.getProperty("server.port"));
        assertEquals("jdbc:h2:mem:profile-check", env.getProperty("spring.datasource.url"));
        assertEquals("4", env.getProperty("spring.task.scheduling.pool.size"));
        assertEquals("update", env.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("http://adapter:8080", env.getProperty("lnis.dtn.send-url"));
        assertEquals("http://adapter:8080", env.getProperty("lnis.dtn.receive-url"));
        var legacyAdapter =
                settings(Map.of("LNIS_DTN_SEND_URL", "http://legacy:8080"), "server", "node");
        assertEquals("http://legacy:8080", legacyAdapter.getProperty("lnis.dtn.receive-url"));
    }

    @Test
    void removedStandaloneProfilesCannotActivateOldTransport() {
        for (String role : new String[] {"sender", "receiver"}) {
            var env = settings(Map.of(), role);
            assertNull(env.getProperty("lnis.agent.token"));
            assertNull(env.getProperty("lnis.server.ws"));
            assertNull(env.getProperty("spring.autoconfigure.exclude[0]"));
        }
    }
}
