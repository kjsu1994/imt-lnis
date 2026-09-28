package server.pvt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** 전파시간의 정밀도를 보존한다. PC 시계의 측정 정확도를 의미하지 않는다. */
public record DelayTime(long seconds, long femtoseconds) {
    public static final long SCALE = 1_000_000_000_000_000L;

    public DelayTime {
        if (femtoseconds < 0 || femtoseconds >= SCALE) {
            throw new IllegalArgumentException("시각 소수부 범위 오류");
        }
        Instant.ofEpochSecond(seconds);
    }

    public static DelayTime from(Instant value) {
        return new DelayTime(value.getEpochSecond(), value.getNano() * 1_000_000L);
    }

    public static DelayTime transmit(Instant start, double meters) {
        if (!Double.isFinite(meters) || meters <= 0) {
            throw new IllegalArgumentException("송신 시각 역산 의사거리 오류");
        }
        BigDecimal propagation = BigDecimal.valueOf(meters)
                .divide(BigDecimal.valueOf(DtnDelay.C), 15, RoundingMode.HALF_EVEN);
        BigDecimal value = from(start).decimal().subtract(propagation);
        long seconds = value.setScale(0, RoundingMode.FLOOR).longValueExact();
        long fraction = value.subtract(BigDecimal.valueOf(seconds)).movePointRight(15).longValueExact();
        return new DelayTime(seconds, fraction);
    }

    private BigDecimal decimal() {
        return BigDecimal.valueOf(seconds).add(BigDecimal.valueOf(femtoseconds, 15));
    }

    public double until(Instant received) {
        return from(received).decimal().subtract(decimal()).doubleValue();
    }

    public double rangeAt(Instant received) {
        double range = until(received) * DtnDelay.C;
        if (!Double.isFinite(range) || range <= 0) {
            throw new IllegalArgumentException("수신 시각과 가상 송신 시각의 순서/범위 오류");
        }
        return range;
    }
}
