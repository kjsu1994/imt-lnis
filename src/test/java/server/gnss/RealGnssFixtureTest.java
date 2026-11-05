package server.gnss;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RealGnssFixtureTest {
    @Test void everySavedMeasurementAndNavigationWordExistsInTheActualUbxArchive() throws Exception {
        byte[] raw = Files.readAllBytes(Path.of("test2.ubx"));
        var parser = new UbloxParser();
        List<GrawCodec.Message> source = new ArrayList<>();
        for (int offset = 0; offset < raw.length; offset += 8192) {
            byte[] block = java.util.Arrays.copyOfRange(raw, offset, Math.min(raw.length, offset + 8192));
            for (var frame : parser.push(block, block.length)) {
                var message = UbloxParser.toCanonical(frame);
                if (message != null) source.add(message);
            }
        }
        var messages = GrawCodec.splitLengthPrefixed(Files.readAllBytes(Path.of("real-gnss-10epochs.graw")))
                .stream().map(GrawCodec::decode).map(GrawCodec.Envelope::message).toList();
        var epochs = messages.stream().filter(GrawCodec.ObservationEpoch.class::isInstance)
                .map(GrawCodec.ObservationEpoch.class::cast).toList();
        assertEquals(10, epochs.size());
        assertEquals(10, epochs.stream().map(e -> e.week() + "/" + e.receiverTowSeconds()).distinct().count());
        assertTrue(epochs.stream().allMatch(e -> !e.observations().isEmpty()));
        for (var message : messages) {
            if (!(message instanceof GrawCodec.ReceiverMetadata)) assertTrue(source.contains(message),
                    "All observations, flags and navigation words must match real serial bytes");
        }
    }
    @Test
    void capturesTenSolvableEpochsAndReimportsTheSameOriginalUbxMessages() throws Exception {
        byte[] raw = Files.readAllBytes(Path.of("test1.ubx"));
        var directory = Path.of(System.getProperty("lnis.native.candidate", "native/bin/win-x64"));
        var capture = new SingleEpochCapture(10, records -> {
            try (var engine = new server.pvt.NativePvtCodec(directory)) {
                var pvt = engine.calculate(records).getLast();
                return pvt.isPositionValid() && pvt.isVelocityValid();
            }
        });
        var parser = new UbloxParser();
        var original = new java.util.HashSet<String>();
        List<byte[]> result = null;
        long sequence = 0;
        var id = java.util.UUID.randomUUID();
        outer:
        for (int offset = 0; offset < raw.length; offset += 8192) {
            byte[] block = java.util.Arrays.copyOfRange(raw, offset, Math.min(raw.length, offset + 8192));
            for (var frame : parser.push(block, block.length)) {
                original.add(java.util.HexFormat.of().formatHex(
                        UbloxParser.command(frame.messageClass(), frame.messageId(), frame.payload())));
                if (frame.messageClass() == 10 && frame.messageId() == 4) capture.archive(frame);
                var message = UbloxParser.toCanonical(frame);
                if (message == null) continue;
                result = capture.accept(GrawCodec.encode(new GrawCodec.Envelope(id,
                        java.util.UUID.randomUUID(), sequence++, java.time.Instant.EPOCH, message)), frame);
                if (result != null) break outer;
            }
        }
        assertNotNull(result, "Actual UBX fixture must provide ten solvable epochs");
        assertEquals(10, capture.count());
        try (var engine = new server.pvt.NativePvtCodec(directory)) {
            var values = engine.calculate(result);
            assertEquals(10, values.size());
            assertTrue(values.stream().allMatch(v -> v.isPositionValid() && v.isVelocityValid()));
        }
        var reimported = GrawCodec.splitLengthPrefixed(UbxGrawImport.convertAll(
                capture.ubx(), java.time.Instant.EPOCH, "captured.ubx")).stream()
                .map(GrawCodec::decode).map(GrawCodec.Envelope::message)
                .filter(m -> !(m instanceof GrawCodec.ReceiverMetadata)).toList();
        assertEquals(result.stream().map(GrawCodec::decode).map(GrawCodec.Envelope::message).toList(), reimported);
        for (var frame : new UbloxParser().push(capture.ubx(), capture.ubx().length)) {
            assertTrue(original.contains(java.util.HexFormat.of().formatHex(
                    UbloxParser.command(frame.messageClass(), frame.messageId(), frame.payload()))));
        }
    }

}
