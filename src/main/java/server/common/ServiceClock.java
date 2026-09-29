package server.common;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 시험 전용 시계. OS·로그·DB 관리 시각은 변경하지 않는다. 재시작하면 미보정으로 시작한다. */
@Component
public class ServiceClock {
    public record Stamp(Instant rawAt, Instant trialAt, String source, Instant calibratedAt,
                        double offsetSeconds, double ageSeconds, Double roundTripSeconds,
                        String session, long revision, long clockChanges) {}
    public record Proposal(String ticket, String source, double offsetSeconds,
                           Double roundTripSeconds, int expiresInSeconds) {}
    private final Supplier<Instant> wall;
    private final LongSupplier monotonic;
    private final String session = UUID.randomUUID().toString();
    private Instant anchor, calibratedAt, lastWall;
    private long anchorTick, lastTick, revision, changes, proposalTick, reservedUntil;
    private String source = "SYSTEM", reservation;
    private Double roundTrip;
    private Proposal proposal;

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
        }
        lastWall = raw;
        lastTick = tick;
        Instant trial = anchor == null ? raw : anchor.plusNanos(tick - anchorTick);
        return new Stamp(raw, trial, source, calibratedAt, seconds(raw, trial),
                anchor == null ? 0 : (tick - anchorTick) / 1e9, roundTrip, session, revision, changes);
    }

    public synchronized Proposal prepare(String source, double offset, Double roundTrip) {
        if (!java.util.Set.of("GNSS_USB", "PEER_GNSS", "NTP").contains(source)
                || !Double.isFinite(offset) || Math.abs(offset) > 300
                || roundTrip != null && (!Double.isFinite(roundTrip) || roundTrip < 0 || roundTrip > 5)) {
            throw new IllegalArgumentException("시간원 또는 보정량을 확인하세요. 5분 초과 차이는 먼저 PC 설정을 확인하세요.");
        }
        proposal = new Proposal(UUID.randomUUID().toString(), source, offset, roundTrip, 30);
        proposalTick = monotonic.getAsLong();
        stamp();
        proposalChanges = changes;
        return proposal;
    }

    private long proposalChanges;

    public synchronized Stamp apply(String ticket, String lock) {
        stamp();
        if (!reserved(lock) || proposal == null || !proposal.ticket().equals(ticket)
                || monotonic.getAsLong() - proposalTick > TimeUnit.SECONDS.toNanos(30)
                || proposalChanges != changes) {
            throw new IllegalStateException("보정안 만료 또는 시각 변경 · 다시 시간 맞추기를 실행하세요.");
        }
        anchorTick = monotonic.getAsLong();
        calibratedAt = wall.get();
        anchor = calibratedAt.plusNanos(Math.round(proposal.offsetSeconds() * 1e9));
        source = proposal.source();
        roundTrip = proposal.roundTripSeconds();
        proposal = null;
        revision++;
        return stamp();
    }

    public synchronized void reserve(String token) {
        UUID.fromString(token);
        requireAvailable();
        reservation = token;
        reservedUntil = monotonic.getAsLong() + TimeUnit.SECONDS.toNanos(20);
    }

    public synchronized boolean reserved(String token) {
        return token != null && token.equals(reservation) && monotonic.getAsLong() < reservedUntil;
    }

    public synchronized void release(String token) {
        if (token != null && token.equals(reservation)) {
            reservation = null;
        }
    }

    public synchronized void requireAvailable() {
        if (reservation != null && monotonic.getAsLong() < reservedUntil) {
            throw new IllegalStateException("시험 시각 보정 중입니다. 잠시 후 다시 시도하세요.");
        }
    }

    public static double seconds(Instant from, Instant to) {
        Duration value = Duration.between(from, to);
        return value.getSeconds() + value.getNano() / 1e9;
    }
}
