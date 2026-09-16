package server.central.dtn;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DtnAdapterLogTest {
    @Test void restoresMillisecondsAndMidnightInKoreanTime() {
        var entries=DtnLogService.parseAdapter("[23:59:59.538] sent\n[00:00:01.001] [ERROR] failed\ncontinuation",
            Instant.parse("2026-09-16T15:00:05Z"));
        assertEquals(Instant.parse("2026-09-16T14:59:59.538Z"),entries.get(0).occurredAt());
        assertEquals(Instant.parse("2026-09-16T15:00:01.001Z"),entries.get(1).occurredAt());
        assertEquals("ERROR",entries.get(1).level());
        assertEquals(entries.get(1).occurredAt(),entries.get(2).occurredAt());
    }
    @Test void explicitOffsetAndBadTimestampRemainInspectable() {
        var entries=DtnLogService.parseAdapter("[2026-09-16T17:12:36.538+09:00] received\n[bad-time] original",
            Instant.parse("2026-09-16T09:00:00Z"));
        assertEquals(Instant.parse("2026-09-16T08:12:36.538Z"),entries.getFirst().occurredAt());
        assertTrue(entries.get(1).message().contains("[bad-time] original"));
    }
    @Test void malformedBase64IsDiagnosticNotTransferFailure() {
        var repository=mock(DtnLogRepository.class);
        var logs=new DtnLogService(repository);
        logs.adapter(UUID.randomUUID(),new TextNode("not-base64!"),Instant.now());
        verify(repository).save(argThat(e -> "WARN".equals(e.getLevel()) && "DTN".equals(e.getStage())));
    }
    @Test void boundsLogCount() {
        var entries=DtnLogService.parseAdapter("line\n".repeat(600),Instant.now());
        assertEquals(501,entries.size());
        assertEquals("WARN",entries.getLast().level());
    }
}
