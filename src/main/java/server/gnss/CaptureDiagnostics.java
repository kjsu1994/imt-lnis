package server.gnss;

import java.util.Map;

/** Distinguish transport activity from new usable GNSS measurements. */
final class CaptureDiagnostics {
    private long bytes, frames, epochs, navigation, repeated;
    private int observations, gpsL1;
    private int week = -1;
    private double tow = Double.NaN;
    private long lastBytes, lastFrame, lastEpoch;
    private final java.util.Map<Integer, Integer> gpsSubframes = new java.util.HashMap<>();

    void bytes(int count, long now) { bytes += count; if (count > 0) lastBytes = now; }
    void frame(long now) { frames++; lastFrame = now; }
    void message(GrawCodec.Message message, long now) {
        if (message instanceof GrawCodec.NavigationUpdate nav) {
            navigation++;
            if (nav.constellationId() == 0 && nav.signalId() == 0 && nav.words().size() == 10
                    && ((nav.words().getFirst() >>> 22) & 255) == 0x8b) {
                int subframe = (int) ((nav.words().get(1) >>> 8) & 7);
                if (subframe >= 1 && subframe <= 3) gpsSubframes.merge(nav.satelliteId(), 1 << subframe, (a, b) -> a | b);
            }
        }
        if (message instanceof GrawCodec.ObservationEpoch e) {
            epochs++;
            if (week == e.week() && Double.compare(tow, e.receiverTowSeconds()) == 0) repeated++;
            else { lastEpoch = now; week = e.week(); tow = e.receiverTowSeconds(); }
            observations = e.observations().size();
            gpsL1 = (int) e.observations().stream().filter(o -> o.constellationId() == 0
                    && o.signalId() == 0 && (o.trackingStatus() & 1) != 0
                    && Double.isFinite(o.pseudorangeMeters()) && o.pseudorangeMeters() > 0
                    && Float.isFinite(o.dopplerHz())).map(GrawCodec.Observation::satelliteId)
                    .distinct().count();
        }
    }

    SerialCaptureService.CaptureProgress snapshot(long now) {
        String stage, text;
        if (bytes == 0 || now - lastBytes > 5_000_000_000L) {
            stage = "WaitingForBytes"; text = "데이터 수신 대기 · 포트·연결·속도 확인";
        } else if (frames == 0 || now - lastFrame > 5_000_000_000L) {
            stage = "WaitingForUbx"; text = "바이트 수신 중 · 유효한 UBX 프레임 없음 (NMEA만 출력 또는 통신 손상)";
        } else if (epochs == 0) {
            stage = "WaitingForRawx"; text = "UBX 수신 중 · RAWX 관측 메시지 대기";
        } else if (now - lastEpoch > 5_000_000_000L) {
            stage = "StaleEpoch"; text = "새 RAWX 시각이 갱신되지 않음 · 반복/정체 확인";
        } else if (observations == 0) {
            stage = "WaitingForMeasurements"; text = "RAWX 수신 정상 · 측정값 0개 · 안테나/위성 추적 대기";
        } else if (gpsL1 < 4) {
            stage = "WaitingForGpsL1"; text = "관측값 " + observations + "개 · 유효 GPS L1 위성 " + gpsL1 + "개 · 추가 수신 대기";
        } else if (gpsSubframes.values().stream().filter(mask -> mask == 14).count() < 4) {
            stage = "WaitingForNavigation"; text = "관측값 확보 · GPS LNAV 서브프레임 1·2·3 확보 위성 "
                    + gpsSubframes.values().stream().filter(mask -> mask == 14).count() + "개 · 항법정보 추가 수신 대기";
        } else {
            stage = "PvtWaiting"; text = "관측값·항법정보 수신 중 · 계산 가능한 GPS L1 한 시점 대기";
        }
        return new SerialCaptureService.CaptureProgress(stage, text,
                Map.of("bytes", bytes, "ubxFrames", frames, "rawxEpochs", epochs,
                        "observations", observations, "gpsL1Satellites", gpsL1,
                        "navigationMessages", navigation, "repeatedEpochs", repeated));
    }
}
