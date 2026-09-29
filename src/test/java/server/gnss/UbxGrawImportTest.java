package server.gnss;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class UbxGrawImportTest {
    @Test void retainsLastTenDistinctEpochsAndEarlierNavigationWithoutInventingFields() {
        var raw = new ByteArrayOutputStream();
        byte[] nav = new byte[12]; nav[0] = 0; nav[1] = 29; nav[4] = 1; nav[6] = 2;
        raw.writeBytes(UbloxParser.command(2, 0x13, nav));
        for (int i = 0; i < 12; i++) {
            byte[] payload = new byte[48];
            var b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            b.putDouble(100 + i).putShort((short) 2438).put((byte) 18).put((byte) 1).put((byte) 1).put((byte) 1);
            b.position(16); b.putDouble(21_000_000 + i).putDouble(42).putFloat(-100);
            payload[37] = 29; payload[46] = 3;
            byte[] frame = UbloxParser.command(2, 0x15, payload);
            raw.writeBytes(frame);
            if (i == 4) raw.writeBytes(frame); // Repeated transport data is not a new epoch.
        }
        var result = GrawCodec.splitLengthPrefixed(UbxGrawImport.convert(raw.toByteArray(), 10, Instant.EPOCH, "real.ubx"));
        var epochs = result.stream().map(GrawCodec::decode).map(GrawCodec.Envelope::message)
                .filter(GrawCodec.ObservationEpoch.class::isInstance).map(GrawCodec.ObservationEpoch.class::cast).toList();
        assertEquals(10, epochs.size());
        assertEquals(102, epochs.getFirst().receiverTowSeconds());
        assertEquals(111, epochs.getLast().receiverTowSeconds());
        assertEquals(21_000_011, epochs.getLast().observations().getFirst().pseudorangeMeters());
        assertTrue(GrawCodec.decode(result.get(1)).message() instanceof GrawCodec.NavigationUpdate);
        assertThrows(IllegalArgumentException.class, () -> UbxGrawImport.convert(raw.toByteArray(), 13, Instant.EPOCH, "source"));
    }
}
