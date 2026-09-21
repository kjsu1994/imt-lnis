package server.central.dtn;

import server.shared.codec.DtnDelay;

import server.shared.model.DtnModels.Pvt;

import java.util.*;

/** 관측 시각과 유효성을 검사한 다음 동일 epoch의 PVT 차이를 비교한다. */
public final class DtnComparison {
    private DtnComparison()
    {}

    public static Map<String, Object> compare(List<Pvt> reference, List<Pvt> received)
    {
        if (reference == null || received == null || reference.size() != received.size()) {
            throw new IllegalArgumentException("PVT 관측 개수 불일치");
        }
        List<Map<String, Object>> epochs = new ArrayList<>();
        int comparable = 0, velocityComparable = 0;
        boolean matches = true;
        for (int i = 0; i < reference.size(); i++) {
            Pvt a = reference.get(i), b = received.get(i);
            if (a.getWeek() != b.getWeek()
                    || Double.compare(a.getTowSeconds(), b.getTowSeconds()) != 0) {
                throw new IllegalArgumentException("PVT 관측 시각 또는 순서 불일치");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", a.getWeek());
            row.put("towSeconds", a.getTowSeconds());
            row.put("referenceValid", a.isPositionValid());
            row.put("receivedValid", b.isPositionValid());
            if (a.isPositionValid() != b.isPositionValid()
                    || a.isVelocityValid() != b.isVelocityValid()) {
                matches = false;
            }
            if (a.isPositionValid() && b.isPositionValid()) {
                comparable++;
                double position = distance(a.getEcefMeters(), b.getEcefMeters());
                if (a.getReceiverClockBiasSeconds() == null
                        || b.getReceiverClockBiasSeconds() == null) {
                    throw new IllegalArgumentException("시계 오차 누락");
                }
                double clock =
                        Math.abs(a.getReceiverClockBiasSeconds() - b.getReceiverClockBiasSeconds());
                if (!Double.isFinite(clock)) {
                    throw new IllegalArgumentException("시계 오차 범위 오류");
                }
                row.put("positionDifferenceMeters", position);
                row.put("clockDifferenceSeconds", clock);
                matches &= position <= 0.001 && clock <= 1e-9;
                if (a.isVelocityValid() && b.isVelocityValid()) {
                    velocityComparable++;
                    double velocity =
                            distance(
                                    a.getVelocityMetersPerSecond(), b.getVelocityMetersPerSecond());
                    row.put("velocityDifferenceMetersPerSecond", velocity);
                    matches &= velocity <= 0.001;
                }
            }
            epochs.add(row);
        }
        return Map.of(
                "comparableEpochs",
                comparable,
                "velocityComparableEpochs",
                velocityComparable,
                "epochs",
                epochs,
                "verdict",
                !matches
                        ? "FAIL"
                        : comparable == 0 || velocityComparable == 0 ? "INCONCLUSIVE" : "PASS",
                "positionToleranceMeters",
                0.001,
                "velocityToleranceMetersPerSecond",
                0.001,
                "clockToleranceSeconds",
                1e-9);
    }

    /** 서로 다른 관측 시각을 의도적으로 비교한다. 기존 동일성 판정과 분리한다. */
    public static Map<String, Object> delay(
            List<Pvt> reference, List<Pvt> received, DtnDelay.Evidence evidence)
    {
        if (reference == null || received == null
                || reference.size() != 1 || received.size() != 1 || evidence == null) {
            throw new IllegalArgumentException("1 Epoch 지연 계산 결과 누락");
        }
        Pvt source = reference.getFirst();
        Pvt calculated = received.getFirst();
        if (source.getWeek() != evidence.originalTime().week()
                || Double.compare(source.getTowSeconds(), evidence.originalTime().towSeconds()) != 0) {
            throw new IllegalArgumentException("Reference Epoch 불일치");
        }
        if (evidence.error() == null && (evidence.shiftedTime() == null
                || calculated.getWeek() != evidence.shiftedTime().week()
                || Double.compare(calculated.getTowSeconds(), evidence.shiftedTime().towSeconds()) != 0)) {
            throw new IllegalArgumentException("지연 반영 Epoch 불일치");
        }

        boolean position = evidence.error() == null && source.isPositionValid() && calculated.isPositionValid();
        boolean velocity = position && source.isVelocityValid() && calculated.isVelocityValid();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("week", calculated.getWeek());
        row.put("towSeconds", calculated.getTowSeconds());
        row.put("referenceWeek", source.getWeek());
        row.put("referenceTowSeconds", source.getTowSeconds());
        row.put("referenceValid", source.isPositionValid());
        row.put("receivedValid", calculated.isPositionValid());
        if (position) {
            row.put("positionDifferenceMeters", distance(source.getEcefMeters(), calculated.getEcefMeters()));
            row.put("positionDeltaMeters", delta(source.getEcefMeters(), calculated.getEcefMeters()));
            if (source.getReceiverClockBiasSeconds() == null || calculated.getReceiverClockBiasSeconds() == null) {
                throw new IllegalArgumentException("시계 오차 누락");
            }
            double clockDelta = calculated.getReceiverClockBiasSeconds() - source.getReceiverClockBiasSeconds();
            if (!Double.isFinite(clockDelta)) {
                throw new IllegalArgumentException("시계 오차 범위 오류");
            }
            row.put("clockDifferenceSeconds", clockDelta);
        }
        if (velocity) {
            row.put("velocityDifferenceMetersPerSecond",
                    distance(source.getVelocityMetersPerSecond(), calculated.getVelocityMetersPerSecond()));
            row.put("velocityDeltaMetersPerSecond",
                    delta(source.getVelocityMetersPerSecond(), calculated.getVelocityMetersPerSecond()));
        }

        String verdict = !position ? "INCONCLUSIVE" : velocity ? "MEASURED" : "PARTIAL";
        String message = !position ? "수신 완료 · PVT 계산 불가"
                : velocity ? "지연 반영 PVT 측정 완료" : "위치·시계 오차 측정 완료 · 속도 비교 불가";
        return Map.of(
                "mode", "DELAY",
                "verdict", verdict,
                "message", message,
                "comparableEpochs", position ? 1 : 0,
                "velocityComparableEpochs", velocity ? 1 : 0,
                "epochs", List.of(row),
                "delaySeconds", evidence.delaySeconds());
    }

    private static double[] delta(double[] source, double[] calculated)
    {
        distance(source, calculated); // 기존 벡터 유효성 검증 재사용
        return new double[] {
                calculated[0] - source[0],
                calculated[1] - source[1],
                calculated[2] - source[2]
        };
    }

    private static double distance(double[] a, double[] b)
    {
        if (a == null || b == null || a.length != 3 || b.length != 3) {
            throw new IllegalArgumentException("PVT 벡터 길이 오류");
        }
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])) {
                throw new IllegalArgumentException("PVT 값 오류");
            }
            sum += Math.pow(a[i] - b[i], 2);
        }
        return Math.sqrt(sum);
    }
}

