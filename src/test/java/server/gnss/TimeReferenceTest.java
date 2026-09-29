package server.gnss;

import org.junit.jupiter.api.Test;
import java.nio.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class TimeReferenceTest {
    private UbloxParser.UbxFrame utc(int second, int valid) {
        byte[] payload = new byte[20];
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putShort(12, (short) 2026);
        payload[14] = 9; payload[15] = 29; payload[16] = 12;
        payload[18] = (byte) second; payload[19] = (byte) valid;
        return new UbloxParser().push(UbloxParser.command(1, 0x21, payload), 28).getFirst();
    }

    @Test
    void requiresUniqueStableRecentEpochs() {
        var reference = new TimeReference();
        for (int i = 0; i < 5; i++) {
            reference.observe(utc(i, 7), i * 1_000_000_000L);
        }
        var result = reference.reading(4_500_000_000L);
        assertTrue(result.ready());
        assertEquals(Instant.parse("2026-09-29T12:00:04.500Z"), result.utc());
        assertFalse(reference.reading(8_000_000_000L).ready());
        reference.observe(utc(5, 3), 5_000_000_000L);
        assertFalse(reference.reading(5_000_000_000L).ready());
    }

    @Test
    void duplicateOrDiscontinuousMessagesAreNotIndependentSamples() {
        var reference = new TimeReference();
        for (int i = 0; i < 8; i++) {
            reference.observe(utc(0, 7), i * 100_000_000L);
        }
        assertEquals(1, reference.reading(800_000_000L).samples());
        reference.observe(utc(10, 7), 1_000_000_000L);
        assertEquals(1, reference.reading(1_000_000_000L).samples());
        reference.observe(utc(60, 7), 2_000_000_000L);
        assertEquals(0, reference.reading(2_000_000_000L).samples());
    }

    @Test
    void fourTimestampExchangeSeparatesOffsetAndRoundTrip() {
        Instant base = Instant.parse("2026-09-29T12:00:00Z");
        var result = TimeReference.exchange(base, base.plusMillis(210), base.plusMillis(215), base.plusMillis(25), .025);
        assertEquals(.2, result.offsetSeconds(), 1e-9);
        assertEquals(.020, result.roundTripSeconds(), 1e-9);
        assertThrows(IllegalStateException.class, () -> TimeReference.exchange(
                base, base, base, base.plusSeconds(1), .020));
    }

    @Test
    void rejectsUntrustedNtpShapes() {
        byte[] request = new byte[48], response = new byte[48];
        request[40] = 1;
        response[0] = 0x24; response[1] = 2; response[24] = 1;
        assertDoesNotThrow(() -> TimeReference.validateNtp(request, response, 48));
        response[24] = 2;
        assertThrows(IllegalStateException.class, () -> TimeReference.validateNtp(request, response, 48));
        response[24] = 1; response[1] = 0;
        assertThrows(IllegalStateException.class, () -> TimeReference.validateNtp(request, response, 48));
    }
}
