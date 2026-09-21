package server.shared.codec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import server.shared.model.DtnModels.Transfer;

/** 지연 시험의 입력 변환만 담당한다. 항법/PVT 알고리즘은 기존 RTKLIB를 사용한다. */
public final class DtnDelay {
    public static final double C = 299792458.0;
    private static final double WEEK_SECONDS = 604800.0;

    private DtnDelay() {}

    public record Epoch(int recordIndex, int week, double towSeconds) {}

    public record Timing(Instant startedAt, Instant receivedAt) {
        public double seconds()
        {
            if (startedAt == null || receivedAt == null) {
                throw new IllegalArgumentException("지연 측정 시각 누락");
            }
            Duration value = Duration.between(startedAt, receivedAt);
            return value.getSeconds() + value.getNano() / 1e9;
        }
    }

    /** LNIS 실행기 전용. 외부 어댑터 JSON에는 추가하지 않는다. */
    public record Receive(Transfer transfer, Timing timing) {}

    public record Time(int week, double towSeconds) {}

    public record Satellite(
            int constellationId, int satelliteId, int signalId,
            Double originalMeters, Double propagationSeconds, Time transmitTime,
            Double recalculatedMeters, Float dopplerHz, int cn0, boolean solverInput) {}

    public record Evidence(
            Timing timing, double delaySeconds, double addedMeters,
            Time originalTime, Time shiftedTime, List<Satellite> satellites, String error) {}

    public record Converted(List<byte[]> records, Evidence evidence) {}

    /** GPS 주차 경계를 정규화한다. UTC/윤초와 섞지 않는다. */
    public static Time shift(int week, double tow, double seconds)
    {
        double total = tow + seconds;
        if (!Double.isFinite(total)) {
            throw new IllegalArgumentException("GNSS 시각 범위 오류");
        }
        long carry = (long) Math.floor(total / WEEK_SECONDS);
        long shiftedWeek = week + carry;
        if (shiftedWeek < 0 || shiftedWeek > 8191) {
            throw new IllegalArgumentException("GPS week 범위 오류");
        }
        return new Time((int) shiftedWeek, total - carry * WEEK_SECONDS);
    }

    /** 관측 하나와 그 이전 항법/메타데이터만 원래 순서와 바이트로 보존한다. */
    public static byte[] select(List<byte[]> records, Epoch selected)
    {
        if (selected == null || selected.recordIndex() < 0 || selected.recordIndex() >= records.size()) {
            throw new IllegalArgumentException("시험 Epoch를 선택하세요.");
        }
        var message = GrawCodec.decode(records.get(selected.recordIndex())).message();
        if (!(message instanceof GrawCodec.ObservationEpoch epoch)
                || epoch.week() != selected.week()
                || Double.compare(epoch.receiverTowSeconds(), selected.towSeconds()) != 0) {
            throw new IllegalArgumentException("선택 Epoch와 원본 입력이 다릅니다.");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i <= selected.recordIndex(); i++) {
            byte[] record = records.get(i);
            boolean observation = GrawCodec.decode(record).message() instanceof GrawCodec.ObservationEpoch;
            if (i != selected.recordIndex() && observation) {
                continue;
            }
            output.writeBytes(ByteBuffer.allocate(4).putInt(record.length).array());
            output.writeBytes(record);
        }
        return output.toByteArray();
    }

    public static Converted convert(List<byte[]> records, Timing timing)
    {
        var epochs = records.stream()
                .map(GrawCodec::decode)
                .filter(record -> record.message() instanceof GrawCodec.ObservationEpoch)
                .toList();
        if (epochs.size() != 1) {
            throw new IllegalArgumentException("지연 시험은 1 Epoch 입력만 허용합니다.");
        }

        var epoch = (GrawCodec.ObservationEpoch) epochs.getFirst().message();
        double delay = timing.seconds();
        double addedMeters = C * delay;
        Time original = new Time(epoch.week(), epoch.receiverTowSeconds());
        if (delay < 0) {
            return failed(records, timing, original, "음수 지연 · 양쪽 PC 시계 동기화를 확인하세요.");
        }

        Time shifted;
        try {
            shifted = shift(epoch.week(), epoch.receiverTowSeconds(), delay);
        } catch (IllegalArgumentException error) {
            return failed(records, timing, original, error.getMessage());
        }

        List<Satellite> satellites = new ArrayList<>();
        List<GrawCodec.Observation> observations = new ArrayList<>();
        for (var observation : epoch.observations()) {
            boolean validRange = Double.isFinite(observation.pseudorangeMeters())
                    && observation.pseudorangeMeters() > 0;
            double recalculated = observation.pseudorangeMeters() + addedMeters;
            Time transmit = null;
            if (validRange) {
                try {
                    transmit = shift(epoch.week(), epoch.receiverTowSeconds(), -observation.pseudorangeMeters() / C);
                } catch (IllegalArgumentException error) {
                    return failed(records, timing, original, error.getMessage());
                }
            }
            boolean solverInput = observation.constellationId() == 0
                    && observation.signalId() == 0
                    && (observation.trackingStatus() & 1) != 0;
            if (solverInput && (!validRange || !Float.isFinite(observation.dopplerHz()))) {
                return failed(records, timing, original, "계산 대상 위성의 의사거리/Doppler가 유효하지 않습니다.");
            }

            satellites.add(new Satellite(
                    observation.constellationId(), observation.satelliteId(), observation.signalId(),
                    validRange ? observation.pseudorangeMeters() : null,
                    validRange ? observation.pseudorangeMeters() / C : null,
                    transmit, validRange ? recalculated : null,
                    Float.isFinite(observation.dopplerHz()) ? observation.dopplerHz() : null,
                    observation.carrierToNoiseDbHz(), solverInput));
            observations.add(validRange ? withRange(observation, recalculated) : observation);
        }

        var replacement = new GrawCodec.ObservationEpoch(
                shifted.towSeconds(), shifted.week(), epoch.leapSeconds(),
                epoch.receiverStatus(), epoch.rawxVersion(), observations);
        List<byte[]> converted = new ArrayList<>();
        for (byte[] record : records) {
            var envelope = GrawCodec.decode(record);
            if (envelope.message() instanceof GrawCodec.ObservationEpoch) {
                var changed = new GrawCodec.Envelope(
                        envelope.testId(), envelope.messageId(), envelope.sequence(),
                        envelope.capturedAt(), replacement);
                converted.add(GrawCodec.encode(changed));
            } else {
                converted.add(record);
            }
        }
        var evidence = new Evidence(timing, delay, addedMeters, original, shifted, List.copyOf(satellites), null);
        return new Converted(converted, evidence);
    }

    private static GrawCodec.Observation withRange(GrawCodec.Observation source, double range)
    {
        return new GrawCodec.Observation(
                range, source.carrierPhaseCycles(), source.dopplerHz(),
                source.constellationId(), source.satelliteId(), source.signalId(), source.frequencyId(),
                source.lockTimeMilliseconds(), source.carrierToNoiseDbHz(),
                source.pseudorangeStdDev(), source.carrierPhaseStdDev(),
                source.dopplerStdDev(), source.trackingStatus());
    }

    private static Converted failed(List<byte[]> records, Timing timing, Time original, String message)
    {
        double seconds = timing.seconds();
        var evidence = new Evidence(timing, seconds, C * seconds, original, null, List.of(), message);
        return new Converted(records, evidence);
    }
}
