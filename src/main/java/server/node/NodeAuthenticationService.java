package server.node;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 외부 DTN 콜백 토큰과 구분되는 노드 간 관리 전용 인증이다. */
@Service
@Profile("node")
@RequiredArgsConstructor
public class NodeAuthenticationService {
    private final NodeProperties nodeProperties;

    public void authenticate(String authorization) {
        String token = nodeProperties.getManagementToken();
        if (token.isBlank()
                || authorization == null
                || !MessageDigest.isEqual(
                        ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                        authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "노드 관리 인증이 필요합니다.");
        }
    }
}
