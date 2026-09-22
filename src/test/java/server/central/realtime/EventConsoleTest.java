package server.central.realtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import server.shared.model.AgentProtocol.EventType;
import server.shared.model.LnisModels.AgentRole;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventConsoleTest {
    @Test void publishesWithoutBrowserAndDoesNotRepeatDtnEventsOrDiscloseTokens() {
        var repository = mock(RealtimeEventRepository.class);
        var browser = mock(BrowserWebSocketHandler.class);
        var service = new EventService(repository, browser);
        var logger = (Logger) LoggerFactory.getLogger(EventService.class);
        var previous = logger.getLevel();
        var output = new ListAppender<ILoggingEvent>();
        output.start(); logger.addAppender(output); logger.setLevel(Level.INFO);
        try {
            UUID id = UUID.randomUUID();
            var payload = Map.of("message", "detail", "nested", Map.of("token", "PRIVATE_TOKEN"));
            service.publish(EventType.TX_STATUS, "sender-1", AgentRole.SENDER, id, payload);
            service.publish(EventType.ERROR, "receiver-1", AgentRole.RECEIVER, id, payload);
            service.publish(EventType.ERROR, "receiver-1", AgentRole.RECEIVER, id, payload, true);
            verify(browser, times(3)).broadcast(any());
            assertEquals(2, output.list.size());
            assertEquals(Level.INFO, output.list.get(0).getLevel());
            assertEquals(Level.ERROR, output.list.get(1).getLevel());
            assertTrue(output.list.getFirst().getFormattedMessage().contains("AFS_EVENT END"));
            assertFalse(output.list.toString().contains("PRIVATE_TOKEN"));
        } finally {
            logger.detachAppender(output); logger.setLevel(previous); output.stop();
        }
    }
}
