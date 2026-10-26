package server.iq;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.afs.AfsPvtFrameCodec;
import server.afs.NativePvtIntegrationTest;
import server.gnss.GrawCodec;
import server.pvt.DtnDelay;
import server.pvt.NativePvtCodec;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class IqCommonDelayTest {
    @TempDir Path directory;
    private final Path nativePath = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));

    @Test
    void decodedFramesUseTrialDelayAndOriginalDopplerWithoutGenerationWait() throws Exception {
        var records = GrawCodec.splitLengthPrefixed(NativePvtIntegrationTest.validSample());
        var frames = AfsPvtFrameCodec.select(records);
        var log = new StringBuilder();
        for (var frame : frames) {
            var blocks = AfsPvtFrameCodec.encode(frame);
            var parts = List.of(blocks.sb2(), blocks.sb3(), blocks.sb4());
            for (int i = 0; i < parts.size(); i++) {
                log.append("$IQAFS,24.000,").append(frame.prn()).append(',').append(i + 2)
                        .append(',').append(hex(parts.get(i))).append('\n');
            }
        }
        Path tracking = directory.resolve("tracking.log");
        Files.writeString(tracking, log);
        var receiver = new IqReceiver("unused", nativePath.toString());
        try (var solver = new NativePvtCodec(nativePath)) {
            var reference = solver.calculate(records).getFirst();
            var metadata = IqReceiver.source(IqService.earthInput(records, reference), "A".repeat(64)).metadata();
            var start = Instant.parse("2026-10-26T00:00:00.123456789Z");
            var result = receiver.calculate(tracking, metadata,
                    new DtnDelay.Timing(start, start.plusSeconds(20)));
            assertEquals(1, result.pvt().size());
            var pvt = result.pvt().getFirst();
            assertTrue(pvt.isPositionValid(), pvt.getMessage());
            assertTrue(pvt.isVelocityValid(), pvt.getMessage());
            assertEquals(20, result.delayEvidence().delaySeconds());
            assertEquals(20, pvt.getReceiverClockBiasSeconds() - reference.getReceiverClockBiasSeconds(), 1e-8);
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(reference.getEcefMeters()[axis], pvt.getEcefMeters()[axis], .01);
                assertEquals(reference.getVelocityMetersPerSecond()[axis], pvt.getVelocityMetersPerSecond()[axis], .01);
            }
            var later = receiver.calculate(tracking, metadata,
                    new DtnDelay.Timing(start.plusSeconds(86400), start.plusSeconds(86420)));
            assertEquals(pvt, later.pvt().getFirst());
            assertEquals(frames.getFirst().doppler(), result.delayEvidence().satellites().getFirst().dopplerHz());
            assertNotNull(result.observations().get("trackingEpochs"));

            var negative = receiver.calculate(tracking, metadata,
                    new DtnDelay.Timing(start, start.minusMillis(1)));
            assertFalse(negative.pvt().getFirst().isPositionValid());
            assertNotNull(negative.delayEvidence().error());
        }
    }

    private static String hex(byte[] bits) {
        byte[] packed = new byte[(bits.length + 7) / 8];
        for (int i = 0; i < bits.length; i++) packed[i / 8] |= (byte) (bits[i] << (7 - i % 8));
        return HexFormat.of().withUpperCase().formatHex(packed);
    }
}
