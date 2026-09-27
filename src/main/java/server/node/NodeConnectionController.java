package server.node;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 외부 노드 관리 API와 구분한 로컬 화면용 설정 API다. 시험용 신뢰 LAN에서 사용한다. */
@RestController
@Profile("node")
@RequiredArgsConstructor
@RequestMapping("/lnis/api/v1/node/connection")
public class NodeConnectionController {
    private final NodeConnectionService service;

    @GetMapping
    public ResponseEntity<NodeConnectionDto.Configuration> configuration() {
        return ResponseEntity.ok(service.configuration());
    }

    @PostMapping(value = "/test", consumes = "application/json")
    public ResponseEntity<NodeConnectionDto.ProbeResult> test(
            @Valid @RequestBody NodeConnectionDto.AddressRequest request) {
        return ResponseEntity.ok(service.test(request));
    }

    @PutMapping(consumes = "application/json")
    public ResponseEntity<NodeConnectionDto.Configuration> save(
            @Valid @RequestBody NodeConnectionDto.AddressRequest request) {
        return ResponseEntity.ok(service.save(request));
    }
}
