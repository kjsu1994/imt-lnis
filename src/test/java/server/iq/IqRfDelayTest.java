package server.iq;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.afs.AfsPvtFrameCodec;
import server.afs.NativePvtIntegrationTest;
import server.common.DtnModels.*;
import server.gnss.GrawCodec;
import server.pvt.DtnDelay;
import server.pvt.NativePvtCodec;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class IqRfDelayTest {
    @TempDir Path directory;
    private final Path nativePath = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));

    private String frames(List<byte[]> records) {
        var log = new StringBuilder();
        for (var payload : AfsPvtFrameCodec.select(records)) {
            var blocks = AfsPvtFrameCodec.encode(payload);
            var parts = List.of(blocks.sb2(), blocks.sb3(), blocks.sb4());
            for (int block = 0; block < parts.size(); block++) {
                byte[] bits = parts.get(block), bytes = new byte[(bits.length + 7) / 8];
                for (int i = 0; i < bits.length; i++) bytes[i / 8] |= (byte) (bits[i] << (7 - i % 8));
                log.append("$IQAFS,24.000,").append(payload.prn()).append(',').append(block + 2)
                        .append(',').append(HexFormat.of().withUpperCase().formatHex(bytes)).append('\n');
            }
        }
        return log.toString();
    }

    private String tracking(List<byte[]> records, double perturbRange, float perturbDoppler) {
        var epoch = (GrawCodec.ObservationEpoch) GrawCodec.decode(records.getLast()).message();
        var log = new StringBuilder(frames(records));
        boolean first = true;
        for (var o : epoch.observations()) {
            double range = o.pseudorangeMeters() + (first ? perturbRange : 0);
            double doppler = o.dopplerHz() + (first ? perturbDoppler : 0);
            double transmitted = epoch.receiverTowSeconds() + .002 - range / DtnDelay.C;
            int towMillis = (int) Math.ceil(transmitted * 1000);
            double offset = towMillis * .001 - transmitted;
            log.append(String.format(Locale.ROOT, "$IQOBS,0.000,%d,%d,%d,%.12f,%.6f,0,45%n",
                    epoch.week(), o.satelliteId(), towMillis, offset, doppler));
            first = false;
        }
        return log.toString();
    }

    @Test
    void rfMeasurementsDriveBothSolutionsAndCommonDelayLeavesDopplerUnchanged() throws Exception {
        var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
        var receiver = new IqReceiver("unused", nativePath.toString());
        try (var codec = new NativePvtCodec(nativePath)) {
            var reference = codec.calculate(records).getFirst();
            String input = IqService.earthInput(records, reference);
            var metadata = IqReceiver.source(input, "A".repeat(64)).metadata();
            assertEquals(IqReceiver.RF_METHOD, metadata.pvtMethod());
            assertThrows(IllegalArgumentException.class, () -> IqReceiver.source(
                    input.replaceFirst("O [^\n]+", "O 19 25000000 0"), "A".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> IqReceiver.source(
                    input.replaceFirst("O [^\n]+\n", ""), "A".repeat(64)));
            Path log = directory.resolve("tracking.log");
            Files.writeString(log, tracking(records, 10, 40));
            var start = Instant.parse("2026-11-03T00:00:00Z");
            for (long nanos : new long[] {0, 1_000_000, 1_000_000_000, 20_000_000_000L}) {
                var result = receiver.calculate(log, metadata, new DtnDelay.Timing(start, start.plusNanos(nanos)));
                var baseline = result.rfBaselinePvt().getFirst();
                var delayed = result.pvt().getFirst();
                assertTrue(baseline.isPositionValid(), baseline.getMessage());
                assertTrue(delayed.isPositionValid(), delayed.getMessage());
                assertTrue(baseline.isVelocityValid() && delayed.isVelocityValid());
                assertEquals(nanos / 1e9, delayed.getReceiverClockBiasSeconds() - baseline.getReceiverClockBiasSeconds(), 1e-8);
                assertTrue(distance(baseline.getEcefMeters(), delayed.getEcefMeters()) < .05);
                assertTrue(distance(baseline.getVelocityMetersPerSecond(), delayed.getVelocityMetersPerSecond()) < .001);
                assertTrue(distance(reference.getEcefMeters(), baseline.getEcefMeters()) > 1);
                assertTrue(distance(reference.getVelocityMetersPerSecond(), baseline.getVelocityMetersPerSecond()) > .1);
                assertEquals("IQ_RF", result.observations().get("source"));
                assertEquals(0, result.observations().get("selectedElapsedSeconds"));
                assertNotNull(result.observations().get("receivedValues"));
            }
            var negative = receiver.calculate(log, metadata, new DtnDelay.Timing(start, start.minusMillis(1)));
            assertFalse(negative.pvt().getFirst().isPositionValid());
            assertNotNull(negative.delayEvidence().error());
            Files.writeString(log, frames(records));
            var missing = receiver.calculate(log, metadata, new DtnDelay.Timing(start, start));
            assertFalse(missing.pvt().getFirst().isPositionValid(), "Never use SB4 as an RF measurement fallback");
            assertFalse(missing.rfBaselinePvt().getFirst().isPositionValid());
        }
    }

    private static double distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < 3; i++) sum += Math.pow(a[i] - b[i], 2);
        return Math.sqrt(sum);
    }
}
