package server.node;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로컬 화면의 역할 조회와 인증된 상대 노드의 상태 조회를 분리한다. */
@RestController
@Profile("node")
@RequestMapping("/lnis/api/v1/node")
@RequiredArgsConstructor
public class NodeController {
    private final NodeStatusService nodeStatusService;
    private final NodeAuthenticationService authenticationService;

    @GetMapping
    public ResponseEntity<NodeDto.StatusResponse> localStatus() {
        NodeDto.StatusResponse response = nodeStatusService.status();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @GetMapping("/peer/status")
    public ResponseEntity<NodeDto.StatusResponse> peerStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticationService.authenticate(authorization);
        NodeDto.StatusResponse response = nodeStatusService.status();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
