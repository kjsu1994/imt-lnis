package server.central.dtn;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DtnConsoleLogTest {
    @Test void storedDetailedScreenAndAdapterEventsReachConsoleWithoutReadReplay() {
        var repository=mock(DtnLogRepository.class);
        var service=new DtnLogService(repository);
        var logger=(Logger)LoggerFactory.getLogger(DtnLogService.class);
        var appender=new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        Level previous=logger.getLevel(); logger.setLevel(Level.INFO);
        UUID id=UUID.randomUUID(); Instant at=Instant.parse("2026-09-17T04:32:51Z");
        try {
            service.add(id,"TEST","ERROR","JSON 검증",true,"rejected");
            service.screen(id,at,"WARN","Bearer secret\nwarning");
            service.importAdapter(id,List.of(new DtnRemoteResult.AdapterLog(at,"INFO","adapter detail")));
            assertEquals(3,appender.list.size());
            assertFalse(appender.list.get(0).getFormattedMessage().endsWith("\n"));
            assertEquals(Level.ERROR,appender.list.get(0).getLevel());
            assertTrue(appender.list.get(0).getFormattedMessage().contains("detail=true"));
            assertTrue(appender.list.get(1).getFormattedMessage().contains("type=SCREEN"));
            assertFalse(appender.list.get(1).getFormattedMessage().contains("secret"));
            assertTrue(appender.list.get(2).getFormattedMessage().contains(at.atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString()));
            service.read(id,0);
            assertEquals(3,appender.list.size());
            verify(repository,times(1)).saveAndFlush(any());
            verify(repository,times(1)).save(any());
        } finally { logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }
}
