package server.management;

import static server.management.DataManagementService.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/lnis/api/v1/data-management")
@RequiredArgsConstructor
public class DataManagementController {
    private final DataManagementService service;
    private final DataResetService reset;

    public record Selection(List<Key> items) {}

    public record Confirmation(UUID token) {}

    public record PinRequest(Key item, boolean pinned) {}

    public record ResetRequest(DataResetService.Mode mode) {}
    public record ResetConfirmation(UUID token, String confirmation) {}

    @GetMapping("/reset/status")
    public Object resetStatus() { return reset.status(); }

    @PostMapping("/reset/preview")
    public Object resetPreview(@RequestBody ResetRequest request) {
        return reset.preview(request.mode());
    }

    @PostMapping("/reset/execute")
    public Object resetExecute(@RequestBody ResetConfirmation request) {
        return reset.start(request.token(), request.confirmation());
    }

    @PostMapping("/reset/waiting/close")
    public Object closeResetWaiting(@RequestBody Confirmation request) {
        return reset.closeWaiting(request.token());
    }

    @PostMapping("/reset/retry")
    public Object resetRetry(@RequestBody ResetConfirmation request) {
        return reset.retry(request.token(), request.confirmation());
    }

    @GetMapping("/summary")
    public Object summary() {
        return service.summary();
    }

    @GetMapping("/items")
    public Object list(
            @RequestParam Kind kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.list(kind, page, search, state, from, to);
    }

    @GetMapping("/items/{kind}/{id}")
    public Object detail(@PathVariable Kind kind, @PathVariable UUID id) {
        return service.detail(new Key(kind, id));
    }

    @PostMapping("/preview")
    public Object preview(@RequestBody Selection request) {
        return service.preview(request.items());
    }

    @PostMapping("/selection/preview")
    public Object previewQuery(@RequestBody QuerySelection request) {
        return service.previewQuery(request);
    }

    @PostMapping("/delete")
    public Object delete(@RequestBody Confirmation request) {
        return service.execute(request.token(), "MANUAL");
    }

    @PostMapping("/pin")
    public Object pin(@RequestBody PinRequest request) {
        service.pin(request.item(), request.pinned());
        return Map.of("saved", true);
    }

    @GetMapping("/settings")
    public Object settings() {
        return service.settings();
    }

    @PostMapping("/settings/preview")
    public Object previewSettings(@RequestBody Settings request) {
        return service.previewPolicy(request);
    }

    @PostMapping("/settings")
    public Object saveSettings(@RequestBody Confirmation request) {
        return service.savePolicy(request.token());
    }

    @PostMapping("/cleanup/preview")
    public Object cleanupPreview() {
        return service.previewCleanup();
    }

    @GetMapping("/history")
    public Object history() {
        return service.history();
    }

    @PostMapping("/history/{id}/retry")
    public Object retry(@PathVariable UUID id) {
        return service.retry(id);
    }

    @GetMapping("/files/{kind}/{id}")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable Kind kind, @PathVariable UUID id) {
        if (kind != Kind.INPUT && kind != Kind.IQ) {
            throw new IllegalArgumentException("파일 종류 오류");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + id
                                + (kind == Kind.IQ ? ".bin" : ".graw")
                                + "\"")
                .body(output -> service.download(new Key(kind, id), output));
    }
}
