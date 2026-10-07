package server.pvt;

import server.gnss.GrawCodec;

import java.util.Locale;

/**
 * 시험 단위로 선택하는 PVT 계산 대상 위성군이다.
 * u-blox gnssId/sigId를 RTKLIB navsys 비트로 연결한다. 기본값은 기존과 같은 GPS L1 C/A다.
 */
public enum PvtConstellation {
    GPS("GPS L1 C/A", NativePvtCodec.SYS_GPS),
    BEIDOU("BeiDou B1I", NativePvtCodec.SYS_BDS),
    GALILEO("Galileo E1", NativePvtCodec.SYS_GAL),
    ALL("Multi-GNSS", NativePvtCodec.SYS_GPS | NativePvtCodec.SYS_BDS | NativePvtCodec.SYS_GAL);

    private final String label;
    private final int navsys;

    PvtConstellation(String label, int navsys) {
        this.label = label;
        this.navsys = navsys;
    }

    public String label() {
        return label;
    }

    int navsys() {
        return navsys;
    }

    boolean includesGps() {
        return (navsys & NativePvtCodec.SYS_GPS) != 0;
    }

    boolean includesBeidou() {
        return (navsys & NativePvtCodec.SYS_BDS) != 0;
    }

    boolean includesGalileo() {
        return (navsys & NativePvtCodec.SYS_GAL) != 0;
    }

    /** RAWX 관측값이 이 위성군의 단일 주파수 계산 대상인지 판정한다. */
    boolean accepts(GrawCodec.Observation o) {
        if (o.pseudorangeMeters() <= 0) return false;
        return switch (o.constellationId()) {
            case 0 -> includesGps() && o.signalId() == 0; // GPS L1 C/A
            case 3 -> includesBeidou() && o.signalId() == 0
                    && o.satelliteId() >= 6 && o.satelliteId() <= 58; // B1I D1 (MEO/IGSO)
            case 2 -> includesGalileo() && (o.signalId() == 0 || o.signalId() == 1); // E1 C/B
            default -> false;
        };
    }

    static int rtklibSystem(int ubloxGnssId) {
        return switch (ubloxGnssId) {
            case 0 -> NativePvtCodec.SYS_GPS;
            case 2 -> NativePvtCodec.SYS_GAL;
            case 3 -> NativePvtCodec.SYS_BDS;
            default -> 0;
        };
    }

    /** 이전 시험·요청은 값이 없으므로 GPS로 해석한다. */
    public static PvtConstellation parse(String value) {
        if (value == null || value.isBlank()) return GPS;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("지원하지 않는 PVT 위성군입니다: " + value);
        }
    }
}
