package server.node;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import server.common.ServiceClock;
import server.dtn.DtnRepository;
import server.dtn.DtnService;
import server.gnss.TimeReference;

import java.time.Instant;
import java.util.*;

/** 시각 조회와 수동 내부 보정. 어댑터 계약·Windows 시계에는 영향을 주지 않는다. */
@RestController
@Profile("node")
@RequiredArgsConstructor
@RequestMapping("/lnis/api/v1/node")
public class NodeClockController {
    private final ServiceClock clock;
    private final DtnService dtn;
    private final DtnRepository jobs;
    private final NodePeerClient peer;
    private final NodeAuthenticationService authentication;
    private final LocalNodeLifecycle lifecycle;
    private final NodeProperties properties;
    @Value("${LNIS_NTP_SERVER:time.windows.com}")
    private String ntpServer;

    public record Probe(Instant receivedAt, Instant sentAt, ServiceClock.Stamp clock,
                        String gnssTimeState, boolean busy) {}
    public record LockRequest(String token, boolean release) {}
    public record ApplyRequest(String ticket) {}
    private volatile ServiceClock.Proposal pending;

    private String gnssState() {
        return lifecycle.runtime(properties.getAgentId()).gnss().status().timeState();
    }

    private boolean busy() {
        return dtn.managementBusy() || jobs.existsByStateIn(
                List.of("PREPARING", "WAITING_DTN", "WAITING_RECEIVER", "CALCULATING"));
    }

    @GetMapping("/clock")
    public Probe status() {
        Instant received = Instant.now();
        // Finish potentially slow status queries before recording the reply timestamp.
        String gnss = gnssState();
        boolean active = busy();
        ServiceClock.Stamp stamp = clock.stamp();
        return new Probe(received, stamp.rawAt(), stamp, gnss, active);
    }

    @GetMapping("/peer/clock")
    public Probe peerStatus(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authentication.authenticate(authorization);
        return status();
    }

    @PostMapping("/peer/clock/reservation")
    public Map<String, Boolean> reservation(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody LockRequest request) {
        authentication.authenticate(authorization);
        synchronized (clock) {
            if (request.release()) {
                clock.release(request.token());
            } else {
                requireIdle();
                clock.reserve(request.token());
            }
            return Map.of("reserved", clock.reserved(request.token()));
        }
    }

    @PostMapping("/clock/prepare")
    public ServiceClock.Proposal prepare() throws Exception {
        synchronized (clock) {
            clock.requireAvailable();
            requireIdle();
        }
        TimeReference.NetworkSample sample = localGnss();
        String source = "GNSS_USB";
        if (sample == null) {
            source = "PEER_GNSS";
            sample = peerGnss();
        }
        if (sample == null) {
            source = "NTP";
            Exception last = null;
            for (int i = 0; i < 3; i++) {
                try {
                    var candidate = TimeReference.ntp(ntpServer);
                    if (sample == null || candidate.roundTripSeconds() < sample.roundTripSeconds()) {
                        sample = candidate;
                    }
                } catch (Exception error) {
                    last = error;
                }
            }
            if (sample == null) {
                throw new IllegalStateException("GNSS·상대 GNSS·공통 NTP를 확인할 수 없습니다. 기존 시험 시각을 유지합니다.", last);
            }
        }
        synchronized (clock) {
            requireIdle();
            clock.requireAvailable();
            pending = clock.prepare(source, sample.offsetSeconds(), sample.roundTripSeconds());
            return pending;
        }
    }

    private TimeReference.NetworkSample localGnss() {
        try {
            if (!"VALID".equals(gnssState())) {
                return null;
            }
            Instant t1 = Instant.now();
            long tick = System.nanoTime();
            var value = lifecycle.runtime(properties.getAgentId()).gnss().timeReference();
            Instant t4 = Instant.now();
            double elapsed = (System.nanoTime() - tick) / 1e9;
            if (!Boolean.TRUE.equals(value.get("ready")) || value.get("utc") == null) {
                return null;
            }
            Instant t2 = Instant.parse(value.get("receivedAt").toString());
            Instant t3 = Instant.parse(value.get("sentAt").toString());
            var network = TimeReference.exchange(t1, t2, t3, t4, elapsed);
            return new TimeReference.NetworkSample(network.offsetSeconds()
                    + TimeReference.seconds(t3, Instant.parse(value.get("utc").toString())), network.roundTripSeconds());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private TimeReference.NetworkSample peerGnss() {
        TimeReference.NetworkSample best = null;
        try {
            peer.status(); // 주소가 동일 역할/다른 노드를 가리키는 경우 시간원으로 사용하지 않는다.
            for (int i = 0; i < 5; i++) {
                Instant t1 = Instant.now();
                long tick = System.nanoTime();
                Probe value = peer.exchange("/lnis/api/v1/node/peer/clock", null, Probe.class, 8192);
                Instant t4 = Instant.now();
                double elapsed = (System.nanoTime() - tick) / 1e9;
                if (!"GNSS_USB".equals(value.clock().source()) || value.clock().ageSeconds() > 300
                        || !"VALID".equals(value.gnssTimeState())) {
                    return null;
                }
                var network = TimeReference.exchange(t1, value.receivedAt(), value.sentAt(), t4, elapsed);
                var candidate = new TimeReference.NetworkSample(
                        network.offsetSeconds() + value.clock().offsetSeconds(), network.roundTripSeconds());
                if (best == null || candidate.roundTripSeconds() < best.roundTripSeconds()) {
                    best = candidate;
                }
            }
        } catch (RuntimeException unavailable) {
            return null;
        }
        return best;
    }

    @PostMapping("/clock/apply")
    public ServiceClock.Stamp apply(@RequestBody ApplyRequest request) {
        ServiceClock.Proposal proposal = pending;
        if (proposal == null || !Objects.equals(proposal.ticket(), request.ticket())) {
            throw new IllegalStateException("먼저 시간 맞추기로 보정안을 확인하세요.");
        }
        String token = UUID.randomUUID().toString();
        synchronized (clock) {
            requireIdle();
            clock.reserve(token);
        }
        boolean remoteReserved = false;
        try {
            peer.status();
            var response = peer.exchange("/lnis/api/v1/node/peer/clock/reservation",
                    new LockRequest(token, false), com.fasterxml.jackson.databind.JsonNode.class, 4096);
            remoteReserved = response.path("reserved").asBoolean();
            if (!remoteReserved) {
                throw new IllegalStateException("상대 서비스의 시험 대기 상태를 확인할 수 없습니다.");
            }
            if (proposal.source().equals("GNSS_USB") && localGnss() == null
                    || proposal.source().equals("PEER_GNSS") && peerGnss() == null) {
                throw new IllegalStateException("선택된 GNSS 시간원이 끊겼습니다. 다시 확인하세요.");
            }
            synchronized (clock) {
                requireIdle();
                return clock.apply(request.ticket(), token);
            }
        } finally {
            clock.release(token);
            if (remoteReserved) {
                try {
                    peer.exchange("/lnis/api/v1/node/peer/clock/reservation",
                            new LockRequest(token, true), com.fasterxml.jackson.databind.JsonNode.class, 4096);
                } catch (RuntimeException ignored) {
                    // 상대의 잠금은 단조 시계 기준 20초 후 자동 만료된다.
                }
            }
        }
    }

    private void requireIdle() {
        if (busy()) {
            throw new IllegalStateException("진행 중인 시험을 완료하거나 중지한 뒤 시간을 맞추세요.");
        }
    }
}
