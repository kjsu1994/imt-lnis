package server.gnss;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CaptureDiagnosticsTest {
    @Test void distinguishesMissingTransportEmptyObservationsAndStaleEpoch() {
        var d = new CaptureDiagnostics();
        long now = 10_000_000_000L;
        assertEquals("WaitingForBytes", d.snapshot(now).stage());
        d.bytes(100, now);
        assertEquals("WaitingForUbx", d.snapshot(now).stage());
        d.frame(now);
        assertEquals("WaitingForRawx", d.snapshot(now).stage());
        var empty = new GrawCodec.ObservationEpoch(100, 2400, 18, 1, 1, List.of());
        d.message(empty, now);
        assertEquals("WaitingForMeasurements", d.snapshot(now).stage());
        now += 6_000_000_000L;
        d.bytes(100, now); d.frame(now); d.message(empty, now);
        assertEquals("StaleEpoch", d.snapshot(now).stage());
        assertEquals(1L, d.snapshot(now).counters().get("repeatedEpochs"));
    }
}
