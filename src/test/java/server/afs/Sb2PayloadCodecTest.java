package server.afs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Sb2PayloadCodecTest {
    @Test
    void encodesLansDefaultPrnOneAtUpstreamOffsets() {
        byte[] bits = Sb2PayloadCodec.encode(2300, 123, 1);

        assertEquals(1176, bits.length);
        assertEquals(2300, read(bits, 0, 13));
        assertEquals(123, read(bits, 13, 9));
        assertEquals(0x4e00L, read(bits, 22, 16));
        assertEquals(0x99999999L, read(bits, 38, 32));
        assertEquals(0x4feabd2dL, read(bits, 70, 32));
        assertEquals(0x28091a2bL, read(bits, 102, 32));
        assertEquals(0L, read(bits, 134, 32));
        assertEquals(0x40000000L, read(bits, 166, 32));
        assertEquals(0L, read(bits, 198, 32));
        assertEquals(0x4e00L, read(bits, 230, 16));
        assertEquals(0L, read(bits, 246, 22));
        assertEquals(0L, read(bits, 268, 16));
        for (int index = 284; index < bits.length; index++) {
            assertEquals(index & 1, bits[index], "tail bit " + index);
        }
    }

    @Test
    void decodesAfsItowWithoutConfusingUbloxMilliseconds() {
        byte[] bits = Sb2PayloadCodec.encode(2300, 123, 1);
        var decoded = Sb2PayloadCodec.decode(bits, 1, 2300, 123);

        assertEquals(2300, decoded.week());
        assertEquals(123, decoded.afsItow());
        assertEquals(319488, decoded.toeSeconds());
        assertEquals(0.6, decoded.eccentricity(), Math.scalb(1.0, -32));
        assertEquals(2557.342371, decoded.sqrtSemiMajorAxis(), Math.scalb(1.0, -19));
        assertTrue(decoded.headerMatchesPacket());
        assertTrue(decoded.ephemerisMatchesConfigured());
        assertTrue(decoded.tailTestPatternValid());
    }

    @Test
    void preservesSignedTwoComplementFieldsForAllProfiles() {
        assertEquals(0x40000000L, read(Sb2PayloadCodec.encode(2300, 1, 2), 198, 32));
        assertEquals(0x80000000L, read(Sb2PayloadCodec.encode(2300, 1, 3), 198, 32));
        assertEquals(0xc0000000L, read(Sb2PayloadCodec.encode(2300, 1, 4), 198, 32));
        assertEquals(0x80000000L, read(Sb2PayloadCodec.encode(2300, 1, 5), 134, 32));
    }

    @Test
    void reportsEphemerisAndTailMismatchesSeparately() {
        byte[] bits = Sb2PayloadCodec.encode(2300, 123, 1);
        bits[22] ^= 1;
        bits[284] ^= 1;
        var decoded = Sb2PayloadCodec.decode(bits, 1, 2300, 123);

        assertTrue(decoded.headerMatchesPacket());
        assertFalse(decoded.ephemerisMatchesConfigured());
        assertFalse(decoded.tailTestPatternValid());
    }

    @Test
    void rejectsUnsupportedPrn() {
        assertThrows(IllegalArgumentException.class, () -> Sb2PayloadCodec.encode(2300, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Sb2PayloadCodec.encode(2300, 1, 9));
    }

    private static long read(byte[] bits, int offset, int length) {
        long value = 0;
        for (int index = 0; index < length; index++) {
            value = (value << 1) | bits[offset + index];
        }
        return value;
    }
}
