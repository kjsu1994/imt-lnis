package server.central.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import server.shared.logging.ConsoleLevelConverter;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleLevelConverterTest {
    @Test void onlyWarningAndErrorLabelsHaveColorAndAlwaysReset() {
        var converter=new ConsoleLevelConverter();
        var event=new LoggingEvent();
        event.setLevel(Level.WARN);
        assertEquals("\u001b[1;33m[WARN]\u001b[0m",converter.convert(event));
        event=new LoggingEvent();event.setLevel(Level.ERROR);
        assertEquals("\u001b[31m[ERROR]\u001b[0m",converter.convert(event));
        event=new LoggingEvent();event.setLevel(Level.INFO);
        assertEquals("[INFO]",converter.convert(event));
        event=new LoggingEvent();event.setLevel(Level.DEBUG);
        assertEquals("[DEBUG]",converter.convert(event));
    }
}
