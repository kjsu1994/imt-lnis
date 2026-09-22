package server.central.common;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import server.central.dtn.DtnController.ScreenLog;
import server.shared.http.ApiLog;
import java.time.ZoneId;

/** AFS browser-only messages. Server events are logged at publication, never echoed by the browser. */
@RestController
@Slf4j
@RequestMapping("/lnis/api/v1/logs")
public class ScreenLogController {
    @PostMapping("/screen")
    public ResponseEntity<Void> screen(@Valid @RequestBody ScreenLog request) {
        String format = "SCREEN_EVENT source=AFS scopeId={} occurredAt={} {}";
        Object[] values = {request.scopeId(), request.occurredAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
                ApiLog.safe(request.message())};
        switch (request.level()) {
            case "ERROR" -> log.error(format, values);
            case "WARN" -> log.warn(format, values);
            default -> log.info(format, values);
        }
        return ResponseEntity.noContent().build();
    }
}
