package server.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Docker stdout에서도 레벨 표시만 색칠하고 즉시 색상을 복원한다. */
public class ConsoleLevelConverter extends ClassicConverter {
    @Override public String convert(ILoggingEvent event) {
        String label="["+event.getLevel()+"]";
        return switch(event.getLevel().toInt()) {
            case Level.WARN_INT -> "\u001b[1;33m"+label+"\u001b[0m";
            case Level.ERROR_INT -> "\u001b[31m"+label+"\u001b[0m";
            default -> label;
        };
    }
}
