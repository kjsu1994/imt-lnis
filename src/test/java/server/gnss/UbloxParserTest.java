package server.gnss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 분할 수신된 UBX 프레임의 checksum 검증과 payload 복원을 확인한다. */
class UbloxParserTest {
    @Test
    void parsesSplitCheckedFrame() {
        byte[] frame = UbloxParser.command(0x0A, 0x04, new byte[] {1, 2, 3, 4});
        UbloxParser parser = new UbloxParser();
        assertTrue(parser.push(frame, 3).isEmpty());
        byte[] rest = java.util.Arrays.copyOfRange(frame, 3, frame.length);
        var parsed = parser.push(rest, rest.length);
        assertEquals(1, parsed.size());
        assertEquals(0x0A, parsed.getFirst().messageClass());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, parsed.getFirst().payload());
    }

    @Test
    void preservesRawxVersionAndRejectsUnknownLayout() {
        byte[] payload = new byte[16];
        for (int version : new int[] {0, 1}) {
            payload[13] = (byte) version;
            var message = (GrawCodec.ObservationEpoch) UbloxParser.toCanonical(
                    new UbloxParser.UbxFrame(2, 0x15, payload));
            assertEquals(version, message.rawxVersion());
        }
        payload[13] = 9;
        assertThrows(IllegalArgumentException.class, () -> UbloxParser.toCanonical(
                new UbloxParser.UbxFrame(2, 0x15, payload)));
    }

    @Test
    void recoversFromCorruptLengthAndChecksumInMixedNmeaStream() {
        byte[] valid = UbloxParser.command(2, 0x15, new byte[16]);
        byte[] corrupt = {(byte) 0xb5, 0x62, 0x0a, 0x36, (byte) 0xff, (byte) 0xff};
        var stream = new java.io.ByteArrayOutputStream();
        stream.writeBytes("$GNRMC,,V*00\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        stream.writeBytes(corrupt);
        stream.writeBytes(valid);
        byte[] bad = valid.clone();
        bad[bad.length - 1] ^= 1;
        stream.writeBytes(bad);
        stream.writeBytes(valid);
        byte[] bytes = stream.toByteArray();
        var frames = new UbloxParser().push(bytes, bytes.length);
        assertEquals(2, frames.size());
        assertTrue(frames.stream().allMatch(f -> f.messageId() == 0x15));
    }

    @Test
    void rejectsPayloadCountsThatDoNotMatchWireLength() {
        byte[] rawx = new byte[16]; rawx[11] = 1;
        assertThrows(IllegalArgumentException.class, () -> UbloxParser.toCanonical(new UbloxParser.UbxFrame(2, 0x15, rawx)));
        byte[] sfrbx = new byte[12];
        assertThrows(IllegalArgumentException.class, () -> UbloxParser.toCanonical(new UbloxParser.UbxFrame(2, 0x13, sfrbx)));
    }
}
