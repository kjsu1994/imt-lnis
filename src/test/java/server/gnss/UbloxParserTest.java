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
    void normalizesRawxVersionToCanonicalVersionOne() {
        byte[] payload = new byte[16];
        payload[13] = 9;

        var message =
                (server.gnss.GrawCodec.ObservationEpoch)
                        UbloxParser.toCanonical(new UbloxParser.UbxFrame(0x02, 0x15, payload));

        assertEquals(1, message.rawxVersion());
    }
}
