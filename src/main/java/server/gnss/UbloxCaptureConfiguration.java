package server.gnss;

import java.util.ArrayList;
import java.util.List;

/** Configure only the port carrying this session; never save to BBR/flash. */
final class UbloxCaptureConfiguration {
    interface Exchange {
        List<UbloxParser.UbxFrame> exchange(byte[] request);
    }

    private final Exchange exchange;
    private final List<byte[]> restore = new ArrayList<>();
    private int portId;

    UbloxCaptureConfiguration(Exchange exchange) {
        this.exchange = exchange;
    }

    void configure() {
        var port = exchange.exchange(UbloxParser.command(6, 0, new byte[0])).stream()
                .filter(f -> f.messageClass() == 6 && f.messageId() == 0
                        && f.payload().length == 20).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "수신기 포트 조회 응답 없음: 연결·속도·다른 프로그램의 포트 사용을 확인하세요."));
        portId = Byte.toUnsignedInt(port.payload()[0]);
        if (portId > 4) throw new IllegalStateException("지원하지 않는 수신기 포트: " + portId);
        int outputMask = Byte.toUnsignedInt(port.payload()[14])
                | Byte.toUnsignedInt(port.payload()[15]) << 8;
        if ((outputMask & 1) == 0) throw new IllegalStateException(
                "현재 수신기 포트의 UBX 출력이 꺼져 있습니다. UBX 출력 프로토콜을 활성화하세요.");
        for (int id : new int[] {0x15, 0x13}) {
            int original = rate(id);
            if (original == 1) continue;
            // Register undo before writing: an ACK can be lost after a successful mutation.
            restore.add(new byte[] {2, (byte) id, (byte) original});
            set(id, 1);
        }
    }

    private int rate(int id) {
        var response = exchange.exchange(UbloxParser.command(6, 1, new byte[] {2, (byte) id})).stream()
                .filter(f -> f.messageClass() == 6 && f.messageId() == 1
                        && (f.payload().length == 8 || f.payload().length == 3)
                        && f.payload()[0] == 2 && Byte.toUnsignedInt(f.payload()[1]) == id)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "RAWX/SFRBX 출력 설정 조회 실패: 원래 설정을 보존할 수 없어 수집을 중단합니다."));
        return Byte.toUnsignedInt(response.payload()[response.payload().length == 3 ? 2 : 2 + portId]);
    }

    private void set(int id, int value) {
        var replies = exchange.exchange(UbloxParser.command(6, 1,
                new byte[] {2, (byte) id, (byte) value}));
        if (replies.stream().anyMatch(f -> f.messageClass() == 5 && f.messageId() == 0
                && f.payload().length == 2 && f.payload()[0] == 6 && f.payload()[1] == 1)) {
            throw new IllegalStateException("수신기가 RAWX/SFRBX 출력 설정을 거절했습니다.");
        }
        if (rate(id) != value) throw new IllegalStateException("RAWX/SFRBX 출력 설정 재조회 불일치");
    }

    void restore() {
        RuntimeException failure = null;
        for (byte[] entry : restore) {
            try { set(Byte.toUnsignedInt(entry[1]), Byte.toUnsignedInt(entry[2])); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        restore.clear();
        if (failure != null) throw new IllegalStateException(
                "임시 출력 설정 복원 실패. 수신기 설정을 확인하세요.", failure);
    }
}
