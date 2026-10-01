package server.gnss;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class UbxGrawImportTest {
    @Test void readsRealReceiverArchiveWhenAvailable() throws Exception {
        var file = java.nio.file.Path.of("real-gnss-source.ubx");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(file));
        byte[] raw = java.nio.file.Files.readAllBytes(file);
        var info = UbxGrawImport.receiverInfo(raw);
        assertEquals("ZED-F9T-20B", info.model());
        assertEquals("TIM 2.25", info.firmware());
        assertEquals("29.25", info.protocol());
        var epochs = GrawCodec.splitLengthPrefixed(UbxGrawImport.convertAll(raw, Instant.EPOCH, file.toString()))
                .stream().map(GrawCodec::decode).map(GrawCodec.Envelope::message)
                .filter(GrawCodec.ObservationEpoch.class::isInstance)
                .map(GrawCodec.ObservationEpoch.class::cast).toList();
        assertEquals(24, epochs.size());
        assertEquals(23, epochs.getLast().receiverTowSeconds() - epochs.getFirst().receiverTowSeconds(), 1e-6);
    }

    @Test void readsOnlyChecksumValidatedMonVerAndSeparatesSupportedSystems() {
        var fields = new String[]{"MOD=ZED-F9T-20B", "FWVER=TIM 2.25", "PROTVER=29.25",
                "GPS;GAL;BDS", "SBAS;QZSS", "NAVIC", "BD=1E01C"};
        byte[] payload = new byte[40 + 30 * fields.length];
        for (int i = 0; i < fields.length; i++) {
            byte[] text = fields[i].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(text, 0, payload, 40 + 30 * i, text.length);
        }
        byte[] frame = UbloxParser.command(10, 4, payload);
        var info = UbxGrawImport.receiverInfo(frame);
        assertEquals("ZED-F9T-20B", info.model());
        assertEquals("TIM 2.25", info.firmware());
        assertEquals("29.25", info.protocol());
        assertEquals(java.util.List.of("GPS", "Galileo", "BeiDou", "SBAS", "QZSS", "NavIC"),
                info.supportedConstellations());
        frame[frame.length - 1] ^= 1;
        assertEquals("", UbxGrawImport.receiverInfo(frame).model());
        assertEquals("", UbxGrawImport.receiverInfo(new byte[0]).protocol());
    }

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
        var all = GrawCodec.splitLengthPrefixed(UbxGrawImport.convertAll(raw.toByteArray(), Instant.EPOCH, "source.ubx"));
        assertEquals(12, all.stream().map(GrawCodec::decode).map(GrawCodec.Envelope::message)
                .filter(GrawCodec.ObservationEpoch.class::isInstance).count());
        assertTrue(all.stream().map(GrawCodec::decode).allMatch(record -> record.capturedAt().equals(Instant.EPOCH)));
        assertThrows(IllegalArgumentException.class, () -> UbxGrawImport.convertAll(new byte[0], Instant.EPOCH, "empty.ubx"));
        assertThrows(IllegalArgumentException.class, () -> UbxGrawImport.convertAll(
                UbloxParser.command(2, 0x13, nav), Instant.EPOCH, "nav-only.ubx"));
    }
}
