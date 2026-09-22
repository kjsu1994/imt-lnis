package server.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.TimeZone;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ConsoleLayoutTest {
    @Test void configuredTimestampIsKoreanTimeEvenInUtcJvmAndOnlyLevelIsColored() throws Exception {
        String config;
        try (var stream = getClass().getResourceAsStream("/logback-spring.xml")) {
            config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var pattern = Pattern.compile("%d\\{([^}]+)}").matcher(config);
        assertTrue(pattern.find());
        var previous = TimeZone.getDefault();
        var context = new LoggerContext();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            var layout = new PatternLayout();
            layout.setContext(context); layout.setPattern("%d{" + pattern.group(1) + "}"); layout.start();
            var event = new LoggingEvent();
            event.setTimeStamp(Instant.parse("2026-09-22T00:00:00Z").toEpochMilli());
            event.setLevel(Level.WARN);
            assertEquals("2026-09-22 09:00:00.000+09:00", layout.doLayout(event));
            var converter = new ConsoleLevelConverter();
            assertEquals("\u001b[1;33m[WARN]\u001b[0m", converter.convert(event));
            event = new LoggingEvent();
            event.setLevel(Level.ERROR);
            assertEquals("\u001b[31m[ERROR]\u001b[0m", converter.convert(event));
            event = new LoggingEvent();
            event.setLevel(Level.INFO);
            assertEquals("[INFO]", converter.convert(event));
            layout.stop();
        } finally {
            TimeZone.setDefault(previous); context.stop();
        }
    }
}
