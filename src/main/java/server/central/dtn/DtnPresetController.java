package server.central.dtn;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

/** 기존 로컬 설정 화면과 동일한 신뢰 LAN용 API. 수신 노드의 설정 변경은 허용하지 않는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/lnis/api/v1/dtn/presets")
public class DtnPresetController {
    private final DtnPresetService service;
    @Value("${lnis.node.role:SENDER}")
    private String role;

    @GetMapping
    public ResponseEntity<List<DtnPresetService.View>> list() {
        senderOnly();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list());
    }
    @PostMapping
    public DtnPresetService.View create(@Valid @RequestBody DtnPresetService.Save request) {
        senderOnly();
        return service.save(null, request);
    }
    @PutMapping("/{id}")
    public DtnPresetService.View update(@PathVariable UUID id, @Valid @RequestBody DtnPresetService.Save request) {
        senderOnly();
        return service.save(id, request);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        senderOnly();
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> presetError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(
                ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason()));
    }
    private void senderOnly() {
        if ("RECEIVER".equalsIgnoreCase(role))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프리셋은 송신 서비스에서 관리합니다.");
    }
}
