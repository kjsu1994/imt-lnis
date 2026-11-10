package server.node;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import server.common.ServiceClock;

/** 화면 표시용 시스템 시각. GNSS·NTP 보정은 적용하지 않는다. */
@RestController
@Profile("node")
@RequiredArgsConstructor
@RequestMapping("/lnis/api/v1/node")
public class NodeClockController {
    private final ServiceClock clock;

    public record Probe(ServiceClock.Stamp clock) {}

    @GetMapping("/clock")
    public Probe status() {
        return new Probe(clock.stamp());
    }
}
