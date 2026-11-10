package server.common;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 시스템 시각을 기록하고 시험 중 시계 변경을 감지한다. */
@Component
public class ServiceClock {
    // 과거 시험 기록과 노드 간 등록 JSON을 읽기 위해 기존 필드를 유지한다.
    public record Stamp(Instant rawAt, Instant trialAt, String source, Instant calibratedAt,
                        double offsetSeconds, double ageSeconds, Double roundTripSeconds,
                        String session, long revision, long clockChanges) {}
    private final Supplier<Instant> wall;
    private final LongSupplier monotonic;
    private final String session = UUID.randomUUID().toString();
    private Instant lastWall;
    private long lastTick, changes;

    public ServiceClock() {
        this(Instant::now, System::nanoTime);
    }

    ServiceClock(Supplier<Instant> wall, LongSupplier monotonic) {
        this.wall = wall;
        this.monotonic = monotonic;
    }

    public synchronized Stamp stamp() {
        Instant raw = wall.get();
        long tick = monotonic.getAsLong();
        if (lastWall != null && Math.abs(seconds(lastWall, raw) - (tick - lastTick) / 1e9) > 0.05) {
            changes++;
        }
        lastWall = raw;
        lastTick = tick;
        return new Stamp(raw, raw, "SYSTEM", null, 0, 0, null, session, 0, changes);
    }

    public static double seconds(Instant from, Instant to) {
        Duration value = Duration.between(from, to);
        return value.getSeconds() + value.getNano() / 1e9;
    }
}
