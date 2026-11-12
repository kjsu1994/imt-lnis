package server.node;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import server.common.ServiceClock;
import server.common.LnisModels.AgentRole;
import java.time.Instant;

/** 송신 서비스의 시스템 시각에 맞춰 수신 서비스의 시험 시각을 보정한다. */
@RestController
@Profile("node")
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/lnis/api/v1/node")
public class NodeClockController {
    private final ServiceClock clock;
    private final NodePeerClient peer;
    private final NodeProperties properties;
    private final NodeAuthenticationService authentication;
    private volatile ServiceClock.Stamp lastReceiverClock;

    public record Probe(ServiceClock.Stamp clock, ServiceClock.Stamp receiverClock) {}
    public record Exchange(Instant receivedAt, Instant sentAt, ServiceClock.Stamp clock) {}

    @GetMapping("/clock")
    public Probe status() {
        return new Probe(clock.stamp(), lastReceiverClock);
    }

    @GetMapping("/peer/clock")
    public Exchange exchange(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authentication.authenticate(authorization);
        var received = clock.stamp();
        if (properties.getRole() != AgentRole.SENDER) {
            throw new IllegalStateException("송신 서비스만 기준 시각을 제공합니다.");
        }
        var sent = clock.stamp();
        return new Exchange(received.rawAt(), sent.rawAt(), sent);
    }

    @PostMapping("/clock/sync")
    public Probe sync() {
        if (properties.getRole() == AgentRole.SENDER) {
            peer.status();
            var result = peer.exchange("/lnis/api/v1/node/peer/clock/sync", java.util.Map.of(),
                    Probe.class, 16384);
            lastReceiverClock = result.clock();
            return status();
        }
        synchronizeReceiver();
        return status();
    }

    @PostMapping("/peer/clock/sync")
    public Probe remoteSync(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authentication.authenticate(authorization);
        synchronizeReceiver();
        return status();
    }

    private synchronized void synchronizeReceiver() {
        if (properties.getRole() != AgentRole.RECEIVER) {
            throw new IllegalStateException("수신 서비스에서 시험 시각을 보정합니다.");
        }
        peer.status();
        var expected = clock.stamp();
        ServiceClock.Sample best = null;
        ServiceClock.Stamp reference = null;
        long started = System.nanoTime();
        for (int i = 0; i < 7 && System.nanoTime() - started < 5_000_000_000L; i++) {
            var t1 = clock.stamp();
            long tick = System.nanoTime();
            var response = peer.exchange("/lnis/api/v1/node/peer/clock", null, Exchange.class, 16384);
            double elapsed = (System.nanoTime() - tick) / 1e9;
            var t4 = clock.stamp();
            if (t1.clockChanges() != t4.clockChanges()
                    || Math.abs(ServiceClock.seconds(t1.rawAt(), t4.rawAt()) - elapsed) > 0.05) {
                throw new IllegalStateException("측정 중 PC 시각이 변경됐습니다. 다시 맞추세요.");
            }
            if (response == null || response.clock() == null
                    || !"SYSTEM".equals(response.clock().source())) {
                throw new IllegalStateException("송신 서비스의 시각 응답을 확인하세요.");
            }
            if (reference != null && (!reference.session().equals(response.clock().session())
                    || reference.clockChanges() != response.clock().clockChanges())) {
                throw new IllegalStateException("측정 중 송신 시각 기준이 변경됐습니다. 다시 맞추세요.");
            }
            reference = response.clock();
            var sample = ServiceClock.sample(t1.rawAt(), response.receivedAt(), response.sentAt(), t4.rawAt());
            if (best == null || sample.roundTripSeconds() < best.roundTripSeconds()) best = sample;
        }
        if (best == null) throw new IllegalStateException("시각 차이를 측정하지 못했습니다.");
        var applied = clock.apply(best, expected, reference);
        log.info("시험 시각 보정 완료 · 송신 기준 · 보정량 {} ms · 측정 왕복 {} ms",
                applied.offsetSeconds() * 1000, applied.roundTripSeconds() * 1000);
    }
}
