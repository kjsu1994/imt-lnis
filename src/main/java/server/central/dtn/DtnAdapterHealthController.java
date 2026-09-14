package server.central.dtn;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/lnis/api/v1/dtn/adapter-health")
public class DtnAdapterHealthController {
  private final DtnAdapterHealthService health;

  @GetMapping
  public ResponseEntity<DtnAdapterHealthService.HealthReport> check() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(health.check());
  }
}
