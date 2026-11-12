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

    @Test
    void correctsReceiverBehindSenderAndFreezesQueuedTrialsAcrossResync() {
        var wall = new AtomicReference<>(Instant.parse("2026-11-12T00:00:00Z"));
        var tick = new AtomicLong();
        var receiver = new ServiceClock(wall::get, tick::get);
        var sender = new ServiceClock().stamp();
        var t1 = wall.get();
        // Receiver is 5 ms behind. Each network leg takes 1 ms.
        var measured = ServiceClock.sample(t1, t1.plusMillis(6), t1.plusMillis(6), t1.plusMillis(2));
        assertEquals(.005, measured.offsetSeconds(), 1e-15);
        assertEquals(.002, measured.roundTripSeconds(), 1e-15);
        receiver.apply(measured, receiver.stamp(), sender);
        var frozen = receiver.registration(sender);
        assertEquals("TCP_PEER", frozen.source());
        var started = wall.get().plusMillis(5);
        receiver.apply(new ServiceClock.Sample(.020, .001), receiver.stamp(), sender);
        wall.set(wall.get().plusMillis(2));
        tick.set(2_000_000);
        var received = ServiceClock.forTrial(receiver.stamp(), frozen);
        assertEquals(.002, ServiceClock.seconds(started, received.trialAt()), 1e-15);
        assertEquals(.005, received.offsetSeconds(), 1e-15);
        assertEquals(frozen.revision(), received.revision());
        assertEquals(.020, receiver.stamp().offsetSeconds(), 1e-15);
    }

    @Test
    void uncorrectedTrialDoesNotAdoptLaterCorrection() {
        var receiver = new ServiceClock();
        var original = receiver.stamp();
        receiver.apply(new ServiceClock.Sample(.01, .002), original, new ServiceClock().stamp());
        var result = ServiceClock.forTrial(receiver.stamp(), original);
        assertEquals("SYSTEM", result.source());
        assertEquals(result.rawAt(), result.trialAt());
    }

    @Test
    void clockJumpAndRestartInvalidateCorrectedTrial() {
        var wall = new AtomicReference<>(Instant.parse("2026-11-12T00:00:00Z"));
        var tick = new AtomicLong();
        var receiver = new ServiceClock(wall::get, tick::get);
        receiver.apply(new ServiceClock.Sample(.005, .002), receiver.stamp(), new ServiceClock().stamp());
        var frozen = receiver.stamp();
        wall.set(wall.get().minusSeconds(1));
        assertEquals("SYSTEM", receiver.stamp().source());
        assertEquals("TCP_INVALID", ServiceClock.forTrial(receiver.stamp(), frozen).source());
        assertEquals("TCP_INVALID", ServiceClock.forTrial(new ServiceClock().stamp(), frozen).source());
    }

    @Test
    void changedSenderSessionInvalidatesCorrectionForNewTrials() {
        var receiver = new ServiceClock();
        var sender = new ServiceClock().stamp();
        receiver.apply(new ServiceClock.Sample(.005, .002), receiver.stamp(), sender);
        assertEquals("SYSTEM", receiver.registration(new ServiceClock().stamp()).source());
        assertThrows(IllegalArgumentException.class,
                () -> ServiceClock.sample(Instant.EPOCH, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), Instant.EPOCH));
    }
}
