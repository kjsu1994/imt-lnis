package server.common;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 시험 시각 보정과 시스템 시각 변경 감지. Windows 시계는 변경하지 않는다. */
@Component
public class ServiceClock {
    // 과거 시험 기록과 노드 간 등록 JSON을 읽기 위해 기존 필드를 유지한다.
    public record Stamp(Instant rawAt, Instant trialAt, String source, Instant calibratedAt,
                        double offsetSeconds, double ageSeconds, Double roundTripSeconds,
                        String session, long revision, long clockChanges) {}
    private final Supplier<Instant> wall;
    private final LongSupplier monotonic;
    private final String session = UUID.randomUUID().toString();
    private Instant lastWall;
    private long lastTick, changes, revision, calibratedTick;
    private Instant calibratedAt;
    private double offset;
    private Double roundTrip;
    private String peerSession;
    private long peerChanges;

    public record Sample(double offsetSeconds, double roundTripSeconds) {}

    public static Sample sample(Instant t1, Instant t2, Instant t3, Instant t4) {
        if (t1 == null || t2 == null || t3 == null || t4 == null) {
            throw new IllegalArgumentException("시각 측정 응답이 비어 있습니다.");
        }
        double transit = seconds(t1, t4) - seconds(t2, t3);
        double offset = (seconds(t1, t2) + seconds(t4, t3)) / 2;
        if (transit < 0 || !Double.isFinite(transit) || !Double.isFinite(offset)
                || Math.abs(offset) >= Long.MAX_VALUE / 1e9 || t3.isBefore(t2)) {
            throw new IllegalArgumentException("시각 측정 응답을 확인하세요.");
        }
        return new Sample(offset, transit);
    }


    public ServiceClock() {
        this(Instant::now, System::nanoTime);
    }

    ServiceClock(Supplier<Instant> wall, LongSupplier monotonic) {
        this.wall = wall;
        this.monotonic = monotonic;
    }

    public synchronized Stamp stamp() {
        Instant raw = wall.get();
        long tick = monotonic.getAsLong();
        if (lastWall != null && Math.abs(seconds(lastWall, raw) - (tick - lastTick) / 1e9) > 0.05) {
            changes++;
            calibratedAt = null;
            offset = 0;
            roundTrip = null;
            revision++;
        }
        lastWall = raw;
        lastTick = tick;
        return new Stamp(raw, raw.plusNanos(Math.round(offset * 1e9)),
                calibratedAt == null ? "SYSTEM" : "TCP_PEER", calibratedAt, offset,
                calibratedAt == null ? 0 : (tick - calibratedTick) / 1e9,
                roundTrip, session, revision, changes);
    }

    public synchronized Stamp apply(Sample sample, Stamp expected, Stamp peer) {
        Stamp now = stamp();
        if (!now.session().equals(expected.session()) || now.clockChanges() != expected.clockChanges()
                || now.revision() != expected.revision()) {
            throw new IllegalStateException("측정 중 시각 기준이 변경됐습니다. 다시 맞추세요.");
        }
        if (sample == null || !Double.isFinite(sample.offsetSeconds())
                || Math.abs(sample.offsetSeconds()) >= Long.MAX_VALUE / 1e9
                || !Double.isFinite(sample.roundTripSeconds()) || sample.roundTripSeconds() < 0
                || peer == null || !"SYSTEM".equals(peer.source())) {
            throw new IllegalArgumentException("송신 시각 측정값을 확인하세요.");
        }
        offset = sample.offsetSeconds();
        roundTrip = sample.roundTripSeconds();
        calibratedAt = now.rawAt();
        calibratedTick = monotonic.getAsLong();
        peerSession = peer.session();
        peerChanges = peer.clockChanges();
        revision++;
        return stamp();
    }

    public synchronized Stamp registration(Stamp sender) {
        stamp();
        if (calibratedAt != null && (sender == null || !java.util.Objects.equals(peerSession, sender.session())
                || peerChanges != sender.clockChanges())) {
            calibratedAt = null;
            offset = 0;
            roundTrip = null;
            revision++;
        }
        return stamp();
    }

    /** 등록 시 고정한 보정값만 사용한다. 이후 재보정은 대기 중인 시험에 적용하지 않는다. */
    public static Stamp forTrial(Stamp received, Stamp registered) {
        if (registered == null || !"TCP_PEER".equals(registered.source())) {
            return new Stamp(received.rawAt(), received.rawAt(), "SYSTEM", null, 0, 0,
                    null, received.session(), registered == null ? received.revision() : registered.revision(), received.clockChanges());
        }
        boolean valid = registered.session().equals(received.session())
                && registered.clockChanges() == received.clockChanges();
        return new Stamp(received.rawAt(),
                received.rawAt().plusNanos(Math.round(registered.offsetSeconds() * 1e9)),
                valid ? "TCP_PEER" : "TCP_INVALID", registered.calibratedAt(),
                registered.offsetSeconds(), registered.ageSeconds()
                    + seconds(registered.rawAt(), received.rawAt()),
                registered.roundTripSeconds(), received.session(), registered.revision(), received.clockChanges());
    }

    public static double seconds(Instant from, Instant to) {
        Duration value = Duration.between(from, to);
        return value.getSeconds() + value.getNano() / 1e9;
    }
}
