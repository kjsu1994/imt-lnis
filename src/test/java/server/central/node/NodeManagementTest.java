package server.central.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.AgentState;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 HTTP 응답으로 노드 식별과 관리 전용 인증을 검증한다. */
class NodeManagementTest {
    private static final String TOKEN = "node-management-test-token";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsMissingAndInvalidManagementTokens()
    {
        NodeAuthenticationService authentication = new NodeAuthenticationService(new NodeProperties(environment()));
        authentication.authenticate("Bearer " + TOKEN);
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> authentication.authenticate(null));
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> authentication.authenticate("Bearer dtn-token"));
        NodeProperties unconfigured = new NodeProperties(environment().withProperty("lnis.node.management-token", ""));
        assertThrows(ResponseStatusException.class,
                () -> new NodeAuthenticationService(unconfigured).authenticate("Bearer "));
    }

    @Test
    void validatesRoleAndFixedAddresses()
    {
        NodeProperties properties = new NodeProperties(environment());
        assertEquals(AgentRole.SENDER, properties.getRole());
        assertEquals("sender-1", properties.getAgentId());
        assertEquals("receiver-1", properties.getPeerAgentId());
        assertFalse(properties.peerConfigured());
        for (String address : new String[] {"file:///tmp/node", "http://user:secret@localhost", "http://localhost/lnis", "http://localhost?token=x"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new NodeProperties(environment().withProperty("lnis.node.peer-url", address)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new NodeProperties(environment().withProperty("lnis.node.peer-id", "sender-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new NodeProperties(environment().withProperty("lnis.node.role", "both")));
    }

    @Test
    void readsAuthenticatedPeerStatusAndRejectsWrongIdentity() throws Exception
    {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<NodeDto.StatusResponse> response = new AtomicReference<>(
                new NodeDto.StatusResponse(1, "receiver-1", AgentRole.RECEIVER,
                        AgentState.READY, true, 1, "http://receiver:8088"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lnis/api/v1/node/peer/status", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = objectMapper.writeValueAsBytes(response.get());
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            NodeProperties properties = new NodeProperties(environment().withProperty("lnis.node.peer-url",
                    "http://127.0.0.1:" + server.getAddress().getPort()));
            NodePeerClient client = new NodePeerClient(properties, objectMapper);
            assertEquals("receiver-1", client.status().getAgentId());
            assertEquals("Bearer " + TOKEN, authorization.get());
            response.get().setRole(AgentRole.SENDER);
            assertThrows(IllegalStateException.class, client::status);
            response.get().setRole(AgentRole.RECEIVER);
            response.get().setProtocolVersion(999);
            assertThrows(IllegalStateException.class, client::status);
            response.get().setProtocolVersion(1);
            response.get().setAgentId("unexpected-receiver");
            assertThrows(IllegalStateException.class, client::status);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesRemoteConflictDetail() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lnis/api/v1/node/peer/afs/commands", exchange -> {
            try (exchange) {
                byte[] body = "{\"detail\":\"수신 실행기가 오프라인이거나 시험 진행 중입니다.\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(409, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            NodeProperties properties = new NodeProperties(environment().withProperty("lnis.node.peer-url",
                    "http://127.0.0.1:" + server.getAddress().getPort()));
            NodePeerClient client = new NodePeerClient(properties, objectMapper);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> client.exchange("/lnis/api/v1/node/peer/afs/commands",
                            Map.of("command", "ARM_RECEIVER"), Map.class, 4096));
            assertTrue(error.getMessage().contains("HTTP 409"));
            assertEquals(409, assertInstanceOf(NodePeerClient.RemoteRequestException.class, error).statusCode());
            assertTrue(error.getMessage().contains("수신 실행기가 오프라인이거나 시험 진행 중입니다."));
        } finally {
            server.stop(0);
        }
    }
    private MockEnvironment environment()
    {
        return new MockEnvironment().withProperty("lnis.node.role", "sender")
                .withProperty("lnis.node.base-url", "http://127.0.0.1:8088")
                .withProperty("lnis.node.management-token", TOKEN);
    }
}
