package server.shared.codec;

import java.time.Instant;
import java.util.*;
import server.shared.codec.GrawCodec.*;

/** LNIS FID0 extension: one independently decodable GPS observation per frame.
 * FEC is deliberately outside this class. Both DTN and the I/Q modulator use these bits.
 */
public final class AfsPvtFrameCodec {
    public static final String FORMAT = "LNIS-AFS-GNSS-v4";
    public static final int TYPE = 63, VERSION = 2;
    public static final int SB3_USED = 517, SB4_USED = 606;
    private static final UUID INTERNAL_ID = new UUID(0, 0);
    // LNAV subframe, bit offset (parity stripped), AFS SB2 offset, bit count.
    private static final int[][] FIELDS = {
        {1,216,22,16}, {1,136,39,31}, {1,184,70,32}, {2,112,102,32},
        {2,64,134,32}, {2,160,166,32}, {1,88,198,32},
        {0,176,230,16}, {0,216,246,22}, {0,200,268,16}
    };
    private static final boolean[] MAPPED = new boolean[720];
    static {
        for (int[] f : FIELDS) Arrays.fill(MAPPED, f[0] * 240 + f[1], f[0] * 240 + f[1] + f[3], true);
    }

    private AfsPvtFrameCodec() {}

    public record Payload(int epoch, int measurement, int week, double tow, int prn,
            double range, float doppler, int cn0, int status, int[][] navigation,
            int ionPrn, int[] ionosphere) {}
    public record Blocks(byte[] sb2, byte[] sb3, byte[] sb4) {}

    /** Capture the last coherent ephemeris, not the last incomplete LNAV update. */
    public static List<Payload> select(List<byte[]> records) {
        Map<Integer,int[][]> pending = new HashMap<>(), active = new HashMap<>();
        int[] ion = null;
        int ionPrn = 0, epochIndex = 0;
        List<Payload> result = new ArrayList<>();
        for (byte[] bytes : records) {
            checkpoint();
            var message = GrawCodec.decode(bytes).message();
            if (message instanceof NavigationUpdate n && n.constellationId() == 0 && n.signalId() == 0
                    && n.satelliteId() >= 1 && n.satelliteId() <= 32 && n.words().size() == 10) {
                int[] words = n.words().stream().mapToInt(w -> (int)((w >>> 6) & 0xffffff)).toArray();
                if (read(words, 0, 8) != 0x8b) continue;
                int sf = (int)read(words, 43, 3);
                if (sf >= 1 && sf <= 3) {
                    int[][] triple = pending.computeIfAbsent(n.satelliteId(), k -> new int[3][]);
                    triple[sf - 1] = words;
                    if (coherent(triple)) active.put(n.satelliteId(), triple.clone());
                } else if (sf == 4 && read(words, 50, 6) == 56) {
                    ion = words;
                    ionPrn = n.satelliteId();
                }
            } else if (message instanceof ObservationEpoch e) {
                int before = result.size();
                Set<Integer> seen = new HashSet<>();
                for (int index = 0; index < e.observations().size(); index++) {
                    Observation o = e.observations().get(index);
                    if (o.constellationId() != 0 || o.signalId() != 0 || (o.trackingStatus() & 1) == 0) continue;
                    if (!seen.add(o.satelliteId())) throw invalid("중복 GPS L1 관측값");
                    result.add(new Payload(epochIndex, index, e.week(), e.receiverTowSeconds(), o.satelliteId(),
                            o.pseudorangeMeters(), o.dopplerHz(), o.carrierToNoiseDbHz(), o.trackingStatus(),
                            active.get(o.satelliteId()), ionPrn, ion));
                }
                // An explicit empty epoch preserves existing INCONCLUSIVE results and ordering.
                if (result.size() == before) result.add(new Payload(epochIndex, 255, e.week(), e.receiverTowSeconds(),
                        0, 0, 0, 0, 0, null, ionPrn, ion));
                epochIndex++;
            }
        }
        if (result.isEmpty()) throw invalid("RAWX 관측값 누락");
        return List.copyOf(result);
    }

    public static Blocks encode(Payload p) {
        validate(p);
        byte[] sb2 = new byte[1176];
        for (int i = 284; i < sb2.length; i++) sb2[i] = (byte)(i % 2);
        put(sb2, 0, 13, p.week());
        put(sb2, 13, 9, (int)(p.tow() / 1200));
        if (p.navigation() != null) {
            for (int[] f : FIELDS) put(sb2, f[2], f[3], read(p.navigation()[f[0]], f[1], f[3]));
        }
        Bits b3 = header(p, 3);
        b3.write(p.navigation() == null ? 0 : 1, 1);
        for (int i = 0; i < 720; i++) {
            if (!MAPPED[i]) b3.write(p.navigation() == null ? 0 : read(p.navigation()[i / 240], i % 240, 1), 1);
        }
        Bits b4 = header(p, 4);
        b4.write(p.measurement(), 8);
        b4.write(p.week(), 16);
        b4.write(Double.doubleToRawLongBits(p.tow()), 64);
        b4.write(Double.doubleToRawLongBits(p.range()), 64);
        b4.write(Float.floatToRawIntBits(p.doppler()), 32);
        b4.write(p.cn0(), 8);
        b4.write(p.status(), 8);
        b4.write(p.ionosphere() == null ? 0 : 1, 1);
        b4.write(p.ionPrn(), 8);
        for (int i = 0; i < 10; i++) b4.write(p.ionosphere() == null ? 0 : p.ionosphere()[i], 24);
        return new Blocks(sb2, b3.data, b4.data);
    }

    public static Payload decode(byte[] sb2, byte[] sb3, byte[] sb4) {
        checkBits(sb2, 1176);
        Bits b3 = new Bits(sb3), b4 = new Bits(sb4);
        int[] h3 = readHeader(b3, 3), h4 = readHeader(b4, 4);
        if (!Arrays.equals(h3, h4)) throw invalid("SB3/SB4 식별 불일치");
        boolean hasNav = b3.read(1) != 0;
        int[][] nav = new int[3][10];
        for (int i = 0; i < 720; i++) if (!MAPPED[i]) write(nav[i / 240], i % 240, 1, b3.read(1));
        for (int[] f : FIELDS) write(nav[f[0]], f[1], f[3], read(sb2, f[2], f[3]));
        int measurement = (int)b4.read(8), week = (int)b4.read(16);
        double tow = Double.longBitsToDouble(b4.read(64)), range = Double.longBitsToDouble(b4.read(64));
        float doppler = Float.intBitsToFloat((int)b4.read(32));
        int cn0 = (int)b4.read(8), status = (int)b4.read(8);
        boolean hasIon = b4.read(1) != 0;
        int ionPrn = (int)b4.read(8);
        int[] ion = new int[10];
        for (int i = 0; i < 10; i++) ion[i] = (int)b4.read(24);
        Payload p = new Payload(h3[0], measurement, week, tow, h3[1], range, doppler, cn0, status,
                hasNav ? nav : null, ionPrn, hasIon ? ion : null);
        // Canonical re-encoding also checks all unused bits, flags and the SB2 time fields.
        Blocks canonical = encode(p);
        if (!Arrays.equals(sb2, canonical.sb2()) || !Arrays.equals(sb3, canonical.sb3())
                || !Arrays.equals(sb4, canonical.sb4())) throw invalid("예약 비트/시각/존재 플래그 불일치");
        return p;
    }

    /** Build solver-only records. Original envelopes and non-PVT values never enter this path. */
    public static List<byte[]> records(List<Payload> payloads) {
        if (payloads.isEmpty() || payloads.size() > 20000) throw invalid("계산 프레임 수 오류");
        Map<Integer,List<Payload>> epochs = new TreeMap<>();
        for (Payload p : payloads) {
            validate(p);
            epochs.computeIfAbsent(p.epoch(), k -> new ArrayList<>()).add(p);
        }
        List<byte[]> records = new ArrayList<>();
        int expected = 0;
        for (var entry : epochs.entrySet()) {
            if (entry.getKey() != expected++) throw invalid("Epoch 누락");
            List<Payload> values = entry.getValue();
            values.sort(Comparator.comparingInt(Payload::measurement));
            Payload first = values.getFirst();
            Set<Integer> prns = new HashSet<>(), indices = new HashSet<>();
            List<Observation> observations = new ArrayList<>();
            if (first.ionosphere() != null) addNavigation(records, first.ionPrn(), first.ionosphere());
            for (Payload p : values) {
                if (p.week() != first.week() || Double.compare(p.tow(), first.tow()) != 0
                        || p.ionPrn() != first.ionPrn() || !Arrays.equals(p.ionosphere(), first.ionosphere())
                        || !prns.add(p.prn()) || !indices.add(p.measurement())) throw invalid("Epoch/관측/공통 보정정보 불일치");
                if (p.prn() == 0) {
                    if (values.size() != 1) throw invalid("빈 Epoch에 관측 중복");
                    continue;
                }
                if (p.navigation() != null) for (int[] words : p.navigation()) addNavigation(records, p.prn(), words);
                observations.add(new Observation(p.range(), 0, p.doppler(), 0, p.prn(), 0, 0,
                        0, p.cn0(), 0, 0, 0, p.status()));
            }
            add(records, new ObservationEpoch(first.tow(), first.week(), 0, 0, 1, observations));
        }
        return records;
    }

    private static void addNavigation(List<byte[]> records, int prn, int[] words) {
        add(records, new NavigationUpdate(0, prn, 0, 0, 2,
                Arrays.stream(words).mapToObj(w -> ((long)w) << 6).toList()));
    }

    private static void add(List<byte[]> records, Message message) {
        records.add(GrawCodec.encode(new Envelope(INTERNAL_ID, new UUID(0, records.size() + 1L),
                records.size(), Instant.EPOCH, message)));
    }

    private static Bits header(Payload p, int block) {
        Bits bits = new Bits(new byte[846]);
        bits.write(TYPE, 6);
        bits.write(VERSION, 8);
        bits.write(block, 3);
        bits.write(p.epoch(), 32);
        bits.write(p.prn(), 8);
        return bits;
    }

    private static int[] readHeader(Bits bits, int block) {
        if (bits.read(6) != TYPE || bits.read(8) != VERSION || bits.read(3) != block)
            throw invalid("LNIS 확장 버전/블록 오류");
        return new int[]{(int)bits.read(32), (int)bits.read(8)};
    }

    private static void validate(Payload p) {
        if (p == null || p.epoch() < 0 || p.epoch() >= 15000 || p.week() < 0 || p.week() > 8191
                || !Double.isFinite(p.tow()) || p.tow() < 0 || p.tow() >= 604800
                || p.measurement() < 0 || p.measurement() > 255 || p.prn() < 0 || p.prn() > 32
                || !Double.isFinite(p.range()) || !Float.isFinite(p.doppler())
                || p.cn0() < 0 || p.cn0() > 100 || p.status() < 0 || p.status() > 255)
            throw invalid("계산 입력 범위 오류");
        if (p.prn() == 0) {
            if (p.measurement() != 255 || p.range() != 0 || p.doppler() != 0 || p.cn0() != 0
                    || p.status() != 0 || p.navigation() != null) throw invalid("빈 Epoch 표식 오류");
        } else if (p.range() <= 0 || p.measurement() == 255 || (p.status() & 1) == 0) {
            throw invalid("GPS 관측 유효성 오류");
        }
        if (p.navigation() != null && !coherent(p.navigation())) throw invalid("항법정보 IODE/subframe 불일치");
        if (p.ionosphere() == null) {
            if (p.ionPrn() != 0) throw invalid("전리층 보정 존재 플래그 오류");
        } else if (p.ionPrn() < 1 || p.ionPrn() > 32 || !wordsValid(p.ionosphere())
                || read(p.ionosphere(), 43, 3) != 4 || read(p.ionosphere(), 50, 6) != 56) {
            throw invalid("전리층 보정 페이지 오류");
        }
    }

    private static boolean coherent(int[][] nav) {
        if (nav.length != 3) return false;
        for (int i = 0; i < 3; i++) if (!wordsValid(nav[i]) || read(nav[i], 43, 3) != i + 1) return false;
        long iode = read(nav[1], 48, 8);
        return iode == read(nav[0], 168, 8) && iode == read(nav[2], 216, 8);
    }

    private static boolean wordsValid(int[] words) {
        return words != null && words.length == 10 && Arrays.stream(words).allMatch(w -> w >= 0 && w <= 0xffffff)
                && read(words, 0, 8) == 0x8b;
    }

    private static long read(int[] words, int offset, int count) {
        long result = 0;
        for (int i = offset; i < offset + count; i++) result = (result << 1) | ((words[i / 24] >>> (23 - i % 24)) & 1);
        return result;
    }

    private static void write(int[] words, int offset, int count, long value) {
        for (int i = 0; i < count; i++) {
            int bit = offset + i, mask = 1 << (23 - bit % 24);
            words[bit / 24] = (words[bit / 24] & ~mask) | ((int)((value >>> (count - i - 1)) & 1) * mask);
        }
    }

    private static long read(byte[] bits, int offset, int count) {
        long result = 0;
        for (int i = offset; i < offset + count; i++) result = (result << 1) | bits[i];
        return result;
    }

    private static void put(byte[] bits, int offset, int count, long value) {
        for (int i = 0; i < count; i++) bits[offset + i] = (byte)((value >>> (count - i - 1)) & 1);
    }

    private static void checkBits(byte[] bits, int size) {
        if (bits == null || bits.length != size) throw invalid("SB 크기 오류");
        for (byte bit : bits) if (bit != 0 && bit != 1) throw invalid("이진 비트 오류");
    }

    private static final class Bits {
        private final byte[] data;
        private int offset;
        private Bits(byte[] data) { checkBits(data, 846); this.data = data; }
        private void write(long value, int count) { put(data, offset, count, value); offset += count; }
        private long read(int count) { long value = AfsPvtFrameCodec.read(data, offset, count); offset += count; return value; }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("AFS PVT frame: " + message);
    }

    private static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("AFS 작업 취소");
    }
}
