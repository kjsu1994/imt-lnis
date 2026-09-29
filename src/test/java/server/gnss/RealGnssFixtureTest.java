package server.gnss;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RealGnssFixtureTest {
    @Test void everySavedMeasurementAndNavigationWordExistsInTheActualUbxArchive() throws Exception {
        byte[] raw = Files.readAllBytes(Path.of("real-gnss-source.ubx"));
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
}
