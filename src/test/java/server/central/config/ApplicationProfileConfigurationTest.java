package server.central.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;

class ApplicationProfileConfigurationTest {
  private ConfigurableEnvironment settings(Map<String,Object> overrides, String... profiles) {
    try (var context = new GenericApplicationContext()) {
      context.getEnvironment().setActiveProfiles(profiles);
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
      new ConfigDataApplicationContextInitializer().initialize(context);
      return context.getEnvironment();
    }
  }

  @Test void commonSettingsDoNotActivateDatabaseOrAgentConfiguration() {
    var env = settings(Map.of());
    assertEquals("lnis", env.getProperty("spring.application.name"));
    assertEquals("/lnis/api/v1/actuator", env.getProperty("management.endpoints.web.base-path"));
    assertNull(env.getProperty("spring.datasource.url"));
    assertNull(env.getProperty("lnis.agent.role"));
  }

  @Test void serverAndNodeKeepDatabaseAndSchedulingIsolatedFromStandaloneAgents() {
    for (var profiles : new String[][] {{"server"}, {"server", "node"}}) {
      var env = settings(Map.of(), profiles);
      assertEquals("8088", env.getProperty("server.port"));
      assertEquals("jdbc:h2:file:./data/lnis", env.getProperty("spring.datasource.url"));
      assertEquals("update", env.getProperty("spring.jpa.hibernate.ddl-auto"));
      assertNull(env.getProperty("spring.autoconfigure.exclude[0]"));
      assertNull(env.getProperty("lnis.agent.role"));
      assertEquals(profiles.length == 2 ? "4" : null, env.getProperty("spring.task.scheduling.pool.size"));
    }
  }

  @Test void standaloneRolesKeepTheirDefaultsWithoutServerDatabase() {
    for (String role : new String[] {"sender", "receiver"}) {
      var env = settings(Map.of(), role);
      assertEquals(role.toUpperCase(java.util.Locale.ROOT), env.getProperty("lnis.agent.role"));
      assertEquals(role + "-1", env.getProperty("lnis.agent.id"));
      assertEquals("change-me-" + role, env.getProperty("lnis.agent.token"));
      assertEquals("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration", env.getProperty("spring.autoconfigure.exclude[0]"));
      assertEquals("ws://localhost:8088/lnis/agent/ws", env.getProperty("lnis.server.ws"));
      assertNull(env.getProperty("spring.datasource.url"));
      assertNull(env.getProperty("spring.task.scheduling.pool.size"));
    }
  }

  @Test void deploymentOverridesAndLegacyAdapterFallbackRemainSupported() {
    var env = settings(Map.of("server.port", "8089", "LNIS_DB_URL", "jdbc:h2:mem:profile-check",
        "dtn_adapter", "http://adapter:8080"), "server", "node");
    assertEquals("8089", env.getProperty("server.port"));
    assertEquals("jdbc:h2:mem:profile-check", env.getProperty("spring.datasource.url"));
    assertEquals("http://adapter:8080", env.getProperty("lnis.dtn.send-url"));
    assertEquals("http://adapter:8080", env.getProperty("lnis.dtn.receive-url"));
    var legacy = settings(Map.of("LNIS_DTN_SEND_URL", "http://legacy:8080"), "server");
    assertEquals("http://legacy:8080", legacy.getProperty("lnis.dtn.receive-url"));
    var agent = settings(Map.of("LNIS_AGENT_ID", "custom", "LNIS_AGENT_TOKEN", "test-token"), "receiver");
    assertEquals("custom", agent.getProperty("lnis.agent.id"));
    assertEquals("test-token", agent.getProperty("lnis.agent.token"));
  }
}
