package server.central.node;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import server.shared.model.LnisModels.AgentRole;

import java.io.IOException;

/** 역할 전환은 같은 JVM의 모드를 바꾸는 대신 상대 PC의 화면으로 이동한다. */
@Component
@Profile("node")
@RequiredArgsConstructor
public class NodeRoleFilter extends OncePerRequestFilter {
    private final NodeProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException
    {
        String path = request.getRequestURI();
        boolean receiver = properties.getRole() == AgentRole.RECEIVER;
        if ("GET".equals(request.getMethod())) {
            String opposite = receiver ? "sender" : "receiver";
            if (path.equals("/lnis/afstest/" + opposite) || path.equals("/lnis/dtntest/" + opposite)
                    || path.equals("/lnis/dtntest/" + opposite + "/clear")) {
                if (properties.getPeerBaseUrl() == null) {
                    response.sendError(409, "상대 노드 주소를 설정하세요.");
                } else {
                    response.sendRedirect(properties.getPeerBaseUrl() + path);
                }
                return;
            }
            if (receiver && (path.equals("/") || path.equals("/lnis") || path.equals("/lnis/"))) {
                response.sendRedirect("/lnis/afstest/receiver");
                return;
            }
        }
        // 수신 PC에서 업로드/수집/송신을 시작하는 실수를 API에서도 차단한다.
        if (receiver && !java.util.List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                && path.startsWith("/lnis/api/v1/")
                && !path.startsWith("/lnis/api/v1/node/peer/")
                && !path.startsWith("/lnis/api/v1/data-management/")
                && !path.equals("/lnis/api/v1/dtn/receive")
                && !("POST".equals(request.getMethod()) && (path.equals("/lnis/api/v1/dtn/logs/screen") || path.equals("/lnis/api/v1/logs/screen")))
                && !("DELETE".equals(request.getMethod()) && path.matches("/lnis/api/v1/dtn/iq/[0-9a-fA-F-]+"))
                && !path.matches("/lnis/api/v1/sessions/[0-9a-fA-F-]+/cancel")) {
            response.sendError(409, "이 기능은 송신 노드 화면에서 실행하세요.");
            return;
        }
        chain.doFilter(request, response);
    }
}
