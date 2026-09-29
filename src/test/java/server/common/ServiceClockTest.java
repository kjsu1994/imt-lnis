package server.common;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ServiceClockTest {
    private final AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2026-09-29T12:00:00Z"));
    private final AtomicLong tick = new AtomicLong();
    private final ServiceClock clock = new ServiceClock(wall::get, tick::get);

    private void advance(long seconds) {
        wall.set(wall.get().plusSeconds(seconds));
        tick.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    void defaultsToPcTimeAndDoesNotChangeWallClock() {
        assertEquals("SYSTEM", clock.stamp().source());
        assertEquals(wall.get(), clock.stamp().trialAt());
        var proposal = clock.prepare("GNSS_USB", 0.25, 0.01);
        String token = UUID.randomUUID().toString();
        clock.reserve(token);
        var result = clock.apply(proposal.ticket(), token);
        assertEquals(wall.get().plusMillis(250), result.trialAt());
        assertEquals(Instant.parse("2026-09-29T12:00:00Z"), wall.get());
        assertThrows(IllegalStateException.class, () -> clock.apply(proposal.ticket(), token));
    }

    @Test
    void calibratedTimeRemainsMonotonicWhenPcTimeJumps() {
        var proposal = clock.prepare("NTP", -0.1, 0.02);
        String token = UUID.randomUUID().toString();
        clock.reserve(token);
        var start = clock.apply(proposal.ticket(), token);
        advance(2);
        wall.set(wall.get().minusSeconds(10));
        var end = clock.stamp();
        assertEquals(start.trialAt().plusSeconds(2), end.trialAt());
        assertEquals(1, end.clockChanges());
    }

    @Test
    void reservationProtectsStartAndExpiresWithoutWallTime() {
        String token = UUID.randomUUID().toString();
        clock.reserve(token);
        assertThrows(IllegalStateException.class, clock::requireAvailable);
        clock.release(UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, clock::requireAvailable);
        advance(21);
        assertDoesNotThrow(clock::requireAvailable);
    }

    @Test
    void rejectsExpiredOrChangedPreviewAndOutOfRangeInputs() {
        assertThrows(IllegalArgumentException.class, () -> clock.prepare("NTP", Double.NaN, null));
        assertThrows(IllegalArgumentException.class, () -> clock.prepare("NTP", 301, null));
        var proposal = clock.prepare("NTP", 0.1, 0.01);
        advance(31);
        String token = UUID.randomUUID().toString();
        clock.reserve(token);
        assertThrows(IllegalStateException.class, () -> clock.apply(proposal.ticket(), token));
        clock.release(token);
        var next = clock.prepare("NTP", 0.1, 0.01);
        clock.reserve(token);
        wall.set(wall.get().plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> clock.apply(next.ticket(), token));
    }
}
