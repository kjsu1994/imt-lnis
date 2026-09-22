package server.central.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import server.central.node.NodeProperties;
import server.central.node.NodeRoleFilter;
import server.shared.model.LnisModels.AgentRole;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScreenLogTest {
    @Test void receiverAcceptsScreenLogsValidatesInputAndStillRejectsSenderActions() throws Exception {
        var properties = mock(NodeProperties.class);
        when(properties.getRole()).thenReturn(AgentRole.RECEIVER);
        var mvc = MockMvcBuilders.standaloneSetup(new ScreenLogController())
                .addFilters(new NodeRoleFilter(properties)).build();
        var logger = (Logger) LoggerFactory.getLogger(ScreenLogController.class);
        var previous = logger.getLevel();
        var output = new ListAppender<ILoggingEvent>();
        output.start(); logger.addAppender(output); logger.setLevel(Level.INFO);
        try {
            String body = "{\"occurredAt\":\"2026-09-22T00:00:00Z\",\"level\":\"WARN\",\"message\":\"Bearer private-key\"}";
            mvc.perform(post("/lnis/api/v1/logs/screen").contentType("application/json").content(body))
                    .andExpect(status().isNoContent());
            assertEquals(1, output.list.size());
            assertEquals(Level.WARN, output.list.getFirst().getLevel());
            assertTrue(output.list.getFirst().getFormattedMessage().contains("09:00+09:00"));
            assertFalse(output.list.getFirst().getFormattedMessage().contains("private-key"));
            mvc.perform(post("/lnis/api/v1/logs/screen").contentType("application/json").content(body.replace("WARN", "TRACE")))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/lnis/api/v1/sessions").contentType("application/json").content("{}"))
                    .andExpect(status().isConflict());
            assertEquals(1, output.list.size());
        } finally {
            logger.detachAppender(output); logger.setLevel(previous); output.stop();
        }
    }
}
