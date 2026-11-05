package server.gnss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import server.afs.NativePvtIntegrationTest;
import server.pvt.NativePvtCodec;

import java.nio.file.Path;
import java.util.List;

class SingleEpochCaptureTest {
    @Test
    void timeoutPreservesLastEpochWithoutAddingLaterNavigation() throws Exception {
        var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
        var epoch = records.stream().filter(record -> GrawCodec.decode(record).message()
                instanceof GrawCodec.ObservationEpoch).findFirst().orElseThrow();
        var navigation = records.stream().filter(record -> GrawCodec.decode(record).message()
                instanceof GrawCodec.NavigationUpdate).findFirst().orElseThrow();
        var capture = new SingleEpochCapture(candidate -> false);
        assertNull(capture.accept(epoch));
        capture.accept(navigation);
        assertNull(capture.accept(epoch));
        capture.accept(navigation);
        var selected = capture.onTimeout();
        assertEquals(2, selected.size());
        assertArrayEquals(navigation, selected.getFirst());
        assertArrayEquals(epoch, selected.getLast());
        assertTrue(capture.awaitingDecision());
        assertNull(capture.onTimeout());
        assertNull(capture.accept(epoch));
    }

    @Test
    void timeoutWithoutObservationDoesNotOfferUnusableData() {
        var capture = new SingleEpochCapture(candidate -> false);
        assertNull(capture.onTimeout());
        assertFalse(capture.awaitingDecision());
    }

    @Test
    void dropsUnsolvableEpochsAndKeepsNavigationOrder() throws Exception {
        var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
        var epoch =
                records.stream()
                        .filter(
                                r ->
                                        GrawCodec.decode(r).message()
                                                instanceof GrawCodec.ObservationEpoch)
                        .findFirst()
                        .orElseThrow();
        var capture = new SingleEpochCapture(candidate -> candidate.size() > 1);
        assertNull(capture.accept(epoch));
        List<byte[]> selected = null;
        for (byte[] record : records) {
            selected = capture.accept(record);
            if (selected != null) {
                break;
            }
        }
        assertNotNull(selected);
        assertNull(capture.accept(epoch), "A completed capture must never emit a second epoch");
        assertEquals(
                1,
                selected.stream()
                        .filter(
                                r ->
                                        GrawCodec.decode(r).message()
                                                instanceof GrawCodec.ObservationEpoch)
                        .count());
    }

    @Test
    void selectsAnEpochWithTheExistingEarthEngine() throws Exception {
        Path directory = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
        var capture =
                new SingleEpochCapture(
                        candidate -> {
                            try (var codec = new NativePvtCodec(directory)) {
                                var pvt = codec.calculate(candidate).getFirst();
                                return pvt.isPositionValid() && pvt.isVelocityValid();
                            }
                        });
        var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
        var observation =
                records.stream()
                        .filter(
                                r ->
                                        GrawCodec.decode(r).message()
                                                instanceof GrawCodec.ObservationEpoch)
                        .findFirst()
                        .orElseThrow();
        assertNull(
                capture.accept(observation), "An epoch without navigation must not finish capture");
        List<byte[]> selected = null;
        for (byte[] record : records) {
            selected = capture.accept(record);
            if (selected != null) {
                break;
            }
        }
        assertNotNull(selected);
        try (var codec = new NativePvtCodec(directory)) {
            var pvt = codec.calculate(selected).getFirst();
            assertTrue(pvt.isPositionValid());
            assertTrue(pvt.isVelocityValid());
        }
    }

    @Test
    void boundsNavigationMemory() throws Exception {
        var record =
                GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()).stream()
                        .filter(
                                r ->
                                        !(GrawCodec.decode(r).message()
                                                instanceof GrawCodec.ObservationEpoch))
                        .findFirst()
                        .orElseThrow();
        var capture = new SingleEpochCapture(candidate -> false);
        assertThrows(
                IllegalStateException.class,
                () -> {
                    for (int i = 0; i < 100000; i++) {
                        capture.accept(record);
                    }
                });
    }
    private static byte[] epoch(int week, double tow) throws Exception {
        var sample = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample()).stream()
                .map(GrawCodec::decode).filter(e -> e.message() instanceof GrawCodec.ObservationEpoch)
                .findFirst().orElseThrow();
        var original = (GrawCodec.ObservationEpoch) sample.message();
        return GrawCodec.encode(new GrawCodec.Envelope(sample.testId(), java.util.UUID.randomUUID(),
                (long) tow, java.time.Instant.EPOCH,
                new GrawCodec.ObservationEpoch(tow, week, original.leapSeconds(), original.receiverStatus(),
                        original.rawxVersion(), original.observations())));
    }

    @Test
    void countsOnlyDistinctValidEpochsAndKeepsTheirArrivalOrder() throws Exception {
        var capture = new SingleEpochCapture(3, records -> {
            var last = (GrawCodec.ObservationEpoch) GrawCodec.decode(records.getLast()).message();
            return last.receiverTowSeconds() != 101;
        });
        assertNull(capture.accept(epoch(2400, 100)));
        assertNull(capture.accept(epoch(2400, 100)));
        assertNull(capture.accept(epoch(2400, 101)));
        assertEquals(1, capture.count());
        assertNull(capture.accept(epoch(2400, 604799)));
        var result = capture.accept(epoch(2401, 0));
        assertEquals(3, capture.count());
        assertEquals(3, result.size());
        assertEquals(java.util.List.of(100.0, 604799.0, 0.0), result.stream()
                .map(GrawCodec::decode).map(e -> ((GrawCodec.ObservationEpoch) e.message()).receiverTowSeconds()).toList());
        assertNull(capture.accept(epoch(2401, 1)));
    }

    @Test
    void explicitCountNeverTurnsPartialOrInvalidCaptureIntoSuccess() throws Exception {
        var multiple = new SingleEpochCapture(10, records -> true);
        multiple.accept(epoch(2400, 100));
        assertNull(multiple.onTimeout());
        assertEquals(1, multiple.count());
        var one = new SingleEpochCapture(1, records -> false);
        one.accept(epoch(2400, 100));
        assertNull(one.onTimeout());
        assertFalse(one.awaitingDecision());
        assertEquals(50, new SingleEpochCapture(50, records -> true).targetCount());
        assertThrows(IllegalArgumentException.class, () -> new SingleEpochCapture(0, records -> true));
        assertThrows(IllegalArgumentException.class, () -> new SingleEpochCapture(51, records -> true));
    }

}
