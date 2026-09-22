package server.central.dtn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import server.shared.codec.AfsPvtFrameCodec;
import server.shared.model.DtnModels.IqMetadata;
import server.shared.model.DtnModels.IqNavigation;

/** Uses only PocketSDR CRC-verified subblocks from a single decoded RF frame. */
final class IqFrameRecovery {
    record Recovered(List<IqNavigation> navigation, Set<Integer> prns) {}
    private IqFrameRecovery() {}

    static Recovered read(Path tracking, IqMetadata metadata) throws IOException {
        Map<String,byte[][]> pending = new HashMap<>();
        Map<Integer,AfsPvtFrameCodec.Payload> recovered = new TreeMap<>();
        try (var reader = Files.newBufferedReader(tracking)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("$IQAFS,")) continue;
                String[] row = line.split(",", -1);
                if (row.length != 5) throw new IOException("I/Q AFS 복호 출력 형식 오류");
                double time = Double.parseDouble(row[1]);
                int prn = Integer.parseInt(row[2]), block = Integer.parseInt(row[3]);
                if (!Double.isFinite(time) || time < 0 || time > 90 || !metadata.prns().contains(prn)
                        || block < 2 || block > 4) throw new IOException("I/Q AFS 식별 범위 오류");
                String key = row[1] + ":" + prn;
                byte[][] parts = pending.computeIfAbsent(key, k -> new byte[3][]);
                if (pending.size() > 1024 || parts[block - 2] != null) throw new IOException("I/Q AFS 중복/한도 초과");
                parts[block - 2] = unpack(row[4], block == 2 ? 1176 : 846);
                if (Arrays.stream(parts).anyMatch(Objects::isNull)) continue;
                var payload = AfsPvtFrameCodec.decode(parts[0], parts[1], parts[2]);
                if (payload.epoch() != 0 || payload.prn() != prn || payload.week() != metadata.week()
                        || Double.compare(payload.tow(), metadata.towSeconds()) != 0 || payload.navigation() == null)
                    throw new IOException("I/Q 프레임과 시험 Epoch/PRN 불일치");
                var previous = recovered.putIfAbsent(prn, payload);
                if (previous != null) {
                    var before = AfsPvtFrameCodec.encode(previous);
                    if (!Arrays.equals(before.sb2(), parts[0]) || !Arrays.equals(before.sb3(), parts[1])
                            || !Arrays.equals(before.sb4(), parts[2])) throw new IOException("I/Q 반복 프레임 내용 불일치");
                }
            }
        }
        if (recovered.size() < 4) throw new IOException("I/Q 계산용 AFS 프레임 복원 부족: " + recovered.size() + "/4 PRN");
        // Validate common epoch/ionosphere state without using original RAWX in the RF solver.
        AfsPvtFrameCodec.records(new ArrayList<>(recovered.values()));
        List<IqNavigation> navigation = new ArrayList<>();
        var first = recovered.values().iterator().next();
        if (first.ionosphere() != null) navigation.add(nav(first.ionPrn(), first.ionosphere()));
        for (var payload : recovered.values()) for (int[] words : payload.navigation()) navigation.add(nav(payload.prn(), words));
        return new Recovered(List.copyOf(navigation), Set.copyOf(recovered.keySet()));
    }

    private static IqNavigation nav(int prn, int[] words) {
        return new IqNavigation(prn, Arrays.stream(words).boxed().toList());
    }

    private static byte[] unpack(String hex, int count) throws IOException {
        if (hex.length() != ((count + 7) / 8) * 2 || !hex.matches("[0-9A-F]+"))
            throw new IOException("I/Q 복호 비트 크기/형식 오류");
        byte[] packed = HexFormat.of().parseHex(hex), bits = new byte[count];
        if (count % 8 != 0 && (packed[packed.length - 1] & ((1 << (8 - count % 8)) - 1)) != 0)
            throw new IOException("I/Q 복호 패딩 오류");
        for (int i = 0; i < count; i++) bits[i] = (byte)((packed[i / 8] >>> (7 - i % 8)) & 1);
        return bits;
    }

    static byte[] inputBits(String text, int count) {
        if (text.length() != count || !text.matches("[01]+")) throw new IllegalArgumentException("I/Q 생성 AFS 비트 오류");
        byte[] bits = new byte[count];
        for (int i = 0; i < count; i++) bits[i] = (byte)(text.charAt(i) - '0');
        return bits;
    }
}
