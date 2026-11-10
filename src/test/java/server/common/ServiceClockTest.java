package server.common;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ServiceClockTest {
    @Test
    void usesSystemTimeWithoutOffsetAndDetectsClockChanges() {
        var wall = new AtomicReference<>(Instant.parse("2026-11-10T00:00:00.123456789Z"));
        var tick = new AtomicLong();
        var clock = new ServiceClock(wall::get, tick::get);
        var first = clock.stamp();
        assertEquals(wall.get(), first.trialAt());
        assertEquals("SYSTEM", first.source());
        assertEquals(0, first.offsetSeconds());
        assertNull(first.calibratedAt());
        wall.set(wall.get().plusSeconds(1));
        tick.set(1_000_000_000L);
        assertEquals(0, clock.stamp().clockChanges());
        wall.set(wall.get().minusSeconds(10));
        var changed = clock.stamp();
        assertEquals(wall.get(), changed.rawAt());
        assertEquals(changed.rawAt(), changed.trialAt());
        assertEquals(1, changed.clockChanges());
        assertEquals(first.session(), changed.session());
    }

    @Test
    void durationRetainsSubsecondPrecision() {
        var start = Instant.parse("2026-11-10T00:00:00Z");
        assertEquals(0.123456789, ServiceClock.seconds(start, start.plusNanos(123456789)), 1e-15);
    }
}
