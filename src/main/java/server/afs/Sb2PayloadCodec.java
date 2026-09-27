package server.afs;

import server.common.LnisModels.Sb2EphemerisResult;

import java.util.List;

/**
 * LANS-AFS-SIM의 SB2 정보 비트 배치를 생성하고 해석한다.
 *
 * <p>AFS ITOW는 GPS 주 내 1,200초 구간 번호이며 u-blox iTOW(ms)와 다르다.
 */
public final class Sb2PayloadCodec {
    public static final int DATA_BITS = 1176;
    public static final int EPHEMERIS_OFFSET = 22;
    public static final int EPHEMERIS_BITS = 262;

    private static final double POW2_M19 = Math.scalb(1.0, -19);
    private static final double POW2_M31 = Math.scalb(1.0, -31);
    private static final double POW2_M32 = Math.scalb(1.0, -32);
    private static final double POW2_M43 = Math.scalb(1.0, -43);

    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    static class EphemerisProfile {
        int prn;
        double toeSeconds;
        double eccentricity;
        double sqrtSemiMajorAxis;
        double inclinationRadians;
        double ascendingNodeRadians;
        double argumentOfPerigeeRadians;
        double meanAnomalyRadians;
        double tocSeconds;
        double af0Seconds;
        double af1SecondsPerSecond;

        String profileId() {
            return "LANS-AFS-SIM:" + ":default_almanac:PRN-" + String.format("%02d", prn);
        }
    }

    // default_almanac.txt의 PRN 1~8 프로파일 구성
    private static final List<EphemerisProfile> PROFILES =
            List.of(
                    profile(1, 0.0),
                    profile(2, 1.5707963268),
                    profile(3, 3.1415926536),
                    profile(4, -1.5707963268),
                    profile(5, 0.7853981634),
                    profile(6, 2.3561944902),
                    profile(7, -2.3561944902),
                    profile(8, -0.7853981634));

    private Sb2PayloadCodec() {}

    public static byte[] encode(int week, int afsIntervalOfWeek, int prn) {
        if (week < 0 || week > 0x1fff) {
            throw new IllegalArgumentException("AFS week must be 0 to 8191");
        }
        if (afsIntervalOfWeek < 0 || afsIntervalOfWeek > 0x1ff) {
            throw new IllegalArgumentException("AFS ITOW must be 0 to 511");
        }
        EphemerisProfile profile = profile(prn);
        byte[] bits = new byte[DATA_BITS];

        for (int index = 0; index < bits.length; index++) {
            bits[index] = (byte) (index & 1);
        }
        write(bits, 0, 13, week);
        write(bits, 13, 9, afsIntervalOfWeek);
        writeEphemeris(bits, profile);
        return bits;
    }

    public static Sb2EphemerisResult decode(
            byte[] bits, int prn, int packetWeek, int packetAfsIntervalOfWeek) {
        requireBits(bits);
        EphemerisProfile configured = profile(prn);
        int week = (int) readUnsigned(bits, 0, 13);
        int afsItow = (int) readUnsigned(bits, 13, 9);
        int toe = (int) readUnsigned(bits, 22, 16);
        long ecc = readUnsigned(bits, 38, 32);
        long sqrta = readUnsigned(bits, 70, 32);
        long inc0 = readSigned(bits, 102, 32);
        long omg0 = readSigned(bits, 134, 32);
        long aop = readSigned(bits, 166, 32);
        long m0 = readSigned(bits, 198, 32);
        int toc = (int) readUnsigned(bits, 230, 16);
        long af0 = readSigned(bits, 246, 22);
        long af1 = readSigned(bits, 268, 16);

        byte[] expected = encode(packetWeek, packetAfsIntervalOfWeek, prn);
        boolean ephemerisMatches =
                equalRange(bits, expected, EPHEMERIS_OFFSET, EPHEMERIS_OFFSET + EPHEMERIS_BITS);
        boolean tailValid = true;
        for (int index = EPHEMERIS_OFFSET + EPHEMERIS_BITS; index < DATA_BITS; index++) {
            if (bits[index] != (byte) (index & 1)) {
                tailValid = false;
                break;
            }
        }
        return new Sb2EphemerisResult(
                configured.profileId(),
                prn,
                week,
                afsItow,
                toe * 16,
                ecc * POW2_M32,
                sqrta * POW2_M19,
                inc0 * POW2_M31 * Math.PI,
                omg0 * POW2_M31 * Math.PI,
                aop * POW2_M31 * Math.PI,
                m0 * POW2_M31 * Math.PI,
                toc * 16,
                af0 * POW2_M31,
                af1 * POW2_M43,
                week == packetWeek && afsItow == packetAfsIntervalOfWeek,
                ephemerisMatches,
                tailValid);
    }

    static EphemerisProfile profile(int prn) {
        if (prn < 1 || prn > PROFILES.size()) {
            throw new IllegalArgumentException("AFS PRN must be 1 to 8");
        }
        return PROFILES.get(prn - 1);
    }

    // 알마낙에서 가져온 공통 궤도 값
    private static EphemerisProfile profile(int prn, double meanAnomalyRadians) {
        double ascendingNodeRadians = prn <= 4 ? 0.0 : 3.1415926536;
        return new EphemerisProfile(
                prn,
                319488.0,
                0.6,
                2557.342371,
                0.9826203689,
                ascendingNodeRadians,
                1.570796327,
                meanAnomalyRadians,
                319488.0,
                0.0,
                0.0);
    }

    private static void writeEphemeris(byte[] bits, EphemerisProfile profile) {
        long toe = (long) (profile.toeSeconds / 16.0);
        long ecc = (long) (profile.eccentricity / POW2_M32);
        long sqrta = (long) (profile.sqrtSemiMajorAxis / POW2_M19);
        int inc0 = signed32(profile.inclinationRadians / POW2_M31 / Math.PI);
        int omg0 = signed32(profile.ascendingNodeRadians / POW2_M31 / Math.PI);
        int aop = signed32(profile.argumentOfPerigeeRadians / POW2_M31 / Math.PI);
        int m0 = signed32(profile.meanAnomalyRadians / POW2_M31 / Math.PI);
        long toc = (long) (profile.tocSeconds / 16.0);
        int af0 = signed32(profile.af0Seconds / POW2_M31);
        int af1 = signed32(profile.af1SecondsPerSecond / POW2_M43);

        write(bits, 22, 16, toe);
        write(bits, 38, 32, ecc);
        write(bits, 70, 32, sqrta);
        write(bits, 102, 32, Integer.toUnsignedLong(inc0));
        write(bits, 134, 32, Integer.toUnsignedLong(omg0));
        write(bits, 166, 32, Integer.toUnsignedLong(aop));
        write(bits, 198, 32, Integer.toUnsignedLong(m0));
        write(bits, 230, 16, toc);
        write(bits, 246, 22, Integer.toUnsignedLong(af0));
        write(bits, 268, 16, Integer.toUnsignedLong(af1));
    }

    private static int signed32(double value) {
        return (int) (long) value;
    }

    private static void write(byte[] bits, int offset, int length, long value) {
        for (int index = 0; index < length; index++) {
            bits[offset + index] = (byte) ((value >>> (length - index - 1)) & 1L);
        }
    }

    private static long readUnsigned(byte[] bits, int offset, int length) {
        long value = 0;
        for (int index = 0; index < length; index++) {
            value = (value << 1) | bits[offset + index];
        }
        return value;
    }

    private static long readSigned(byte[] bits, int offset, int length) {
        long value = readUnsigned(bits, offset, length);
        long sign = 1L << (length - 1);
        return (value & sign) == 0 ? value : value - (1L << length);
    }

    private static boolean equalRange(byte[] left, byte[] right, int from, int to) {
        for (int index = from; index < to; index++) {
            if (left[index] != right[index]) {
                return false;
            }
        }
        return true;
    }

    private static void requireBits(byte[] bits) {
        if (bits == null || bits.length != DATA_BITS) {
            throw new IllegalArgumentException("SB2 must contain 1176 data bits");
        }
        for (byte bit : bits) {
            if (bit != 0 && bit != 1) {
                throw new IllegalArgumentException("SB2 values must be binary");
            }
        }
    }
}
