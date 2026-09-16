package server.central.node;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import server.central.dtn.DtnRemoteResult;

import java.util.UUID;

/** 외부 DTN callback과 다른 인증 토큰을 사용하는 노드 관리 API다. */
@RestController
@Profile("node")
@RequiredArgsConstructor
@RequestMapping("/lnis/api/v1/node/peer/dtn/tests")
public class NodeDtnController {
    private final NodeAuthenticationService authentication;
    private final NodeDtnService service;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final server.central.dtn.DtnService dtnService;

    @PostMapping
    public ResponseEntity<DtnRemoteResult> register(HttpServletRequest request) throws Exception
    {
        // 인증 전에 JSON을 역직렬화하지 않고 작은 관리 요청만 허용한다.
        authentication.authenticate(request.getHeader("Authorization"));
        byte[] body = request.getInputStream().readNBytes(8193);
        if (body.length > 8192) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        NodeDtnRegistration registration = mapper.readValue(body, NodeDtnRegistration.class);
        if (registration == null || !validator.validate(registration).isEmpty()) {
            throw new IllegalArgumentException("DTN 사전 등록 필수 항목을 확인하세요.");
        }
        return new ResponseEntity<>(service.accept(registration), HttpStatus.ACCEPTED);
    }

    @PostMapping("/{testId}/cancel")
    public ResponseEntity<DtnRemoteResult> cancel(@PathVariable UUID testId,
            @RequestHeader(value = "Authorization", required = false) String authorization)
    {
        authentication.authenticate(authorization);
        service.prepareCancellation(testId);
        dtnService.cancel(testId);
        return ResponseEntity.ok(service.localResult(testId));
    }

    @GetMapping("/{testId}")
    public ResponseEntity<DtnRemoteResult> result(@PathVariable UUID testId,
            @RequestHeader(value = "Authorization", required = false) String authorization)
    {
        authentication.authenticate(authorization);
        return new ResponseEntity<>(service.localResult(testId), HttpStatus.OK);
    }
}
