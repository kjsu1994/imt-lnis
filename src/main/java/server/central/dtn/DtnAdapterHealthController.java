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
  public ResponseEntity<DtnAdapterHealthService.HealthReport> check(
      @RequestParam(required = false) String sendUrl,
      @RequestParam(required = false) String receiveUrl,
      @RequestParam(required = false) String transferUrl) {
    String legacyUrl = transferUrl == null || transferUrl.isBlank() ? null : transferUrl;
    String sender = sendUrl == null || sendUrl.isBlank() ? legacyUrl : sendUrl;
    String receiver = receiveUrl == null || receiveUrl.isBlank() ? legacyUrl : receiveUrl;
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(health.check(sender, receiver));
  }
}
