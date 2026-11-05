package server.gnss;

import server.common.DtnModels;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** 항법정보의 수신 순서를 유지하며 유효한 Epoch를 지정 개수만큼 선별한다. */
public final class SingleEpochCapture {
    private record Epoch(int week, double tow) {}

    private final List<byte[]> records = new ArrayList<>();
    private final Predicate<List<byte[]>> solvable;
    private final int targetCount;
    private final boolean legacyFallback;
    private final Set<Epoch> selected = new HashSet<>();
    private final ByteArrayOutputStream ubx = new ByteArrayOutputStream();
    private int bytes;
    private boolean complete;
    private List<byte[]> candidate;
    private boolean awaitingDecision;

    public SingleEpochCapture(Predicate<List<byte[]>> solvable) {
        this(1, solvable, true);
    }

    public SingleEpochCapture(int targetCount, Predicate<List<byte[]>> solvable) {
        this(targetCount, solvable, false);
    }

    private SingleEpochCapture(int targetCount, Predicate<List<byte[]>> solvable, boolean legacyFallback) {
        if (targetCount < 1 || targetCount > 50) {
            throw new IllegalArgumentException("수집 개수는 1~50 Epoch 범위입니다.");
        }
        this.targetCount = targetCount;
        this.legacyFallback = legacyFallback;
        this.solvable = solvable;
    }

    public List<byte[]> accept(byte[] record) {
        return accept(record, null);
    }

    public List<byte[]> accept(byte[] record, UbloxParser.UbxFrame source) {
        if (complete) return null;
        var message = GrawCodec.decode(record).message();
        if (bytes + record.length + 4 > DtnModels.MAX_INPUT_BYTES) {
            throw new IllegalStateException("수집 입력이 1 MiB를 초과했습니다. 유효 Epoch "
                    + count() + " / " + targetCount);
        }
        if (message instanceof GrawCodec.ObservationEpoch epoch) {
            var key = new Epoch(epoch.week(), epoch.receiverTowSeconds());
            if (epoch.observations().isEmpty() || selected.contains(key)) return null;
            var next = new ArrayList<>(records);
            next.add(record);
            candidate = List.copyOf(next);
            if (!solvable.test(next)) return null;
            records.add(record);
            bytes += record.length + 4;
            selected.add(key);
            archive(source);
            if (count() < targetCount) return null;
            complete = true;
            return List.copyOf(records);
        }
        records.add(record);
        bytes += record.length + 4;
        archive(source);
        return null;
    }

    /** Checksum 검사를 통과한 원본 payload를 같은 UBX 헤더·Checksum으로 보관한다. */
    public void archive(UbloxParser.UbxFrame frame) {
        if (frame == null) return;
        byte[] packet = UbloxParser.command(frame.messageClass(), frame.messageId(), frame.payload());
        if (ubx.size() + packet.length > DtnModels.MAX_INPUT_BYTES) {
            throw new IllegalStateException("UBX 보관 크기가 1 MiB를 초과했습니다.");
        }
        ubx.writeBytes(packet);
    }

    public byte[] ubx() {
        return ubx.toByteArray();
    }

    public int count() {
        return selected.size();
    }

    public int targetCount() {
        return targetCount;
    }

    /** 기존 1 Epoch 호출자만 마지막 후보의 사용 여부를 선택할 수 있다. */
    public List<byte[]> onTimeout() {
        if (!legacyFallback || complete || candidate == null) return null;
        awaitingDecision = !solvable.test(candidate);
        complete = true;
        return candidate;
    }

    public boolean awaitingDecision() {
        return awaitingDecision;
    }
}
