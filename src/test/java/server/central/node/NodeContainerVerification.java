package server.central.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import server.agent.codec.NativePvtIntegrationTest;
import server.shared.model.LnisModels.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 개발 검증 전용: 별도 Linux 컨테이너 두 개에 HTTP로 접속하여 실제 네트워크 왕복을 검사한다. */
public final class NodeContainerVerification {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private NodeContainerVerification() {}

    public static void main(String[] arguments) throws Exception
    {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("인수: sender-base-url receiver-base-url verifier-hostname");
        }
        String sender = arguments[0];
        String receiver = arguments[1];
        assertTrue(json("GET", sender + "/lnis/api/v1/node", null).path("online").asBoolean());
        assertTrue(json("GET", receiver + "/lnis/api/v1/node", null).path("online").asBoolean());
        byte[] source = NativePvtIntegrationTest.validSample();
        UUID input = UUID.fromString(json("POST", sender + "/lnis/api/v1/inputs",
                Map.of("fileName", "linux-synthetic.graw", "size", source.length, "kind", "GRAW_UPLOAD"))
                .path("inputId").asText());
        HttpResponse<byte[]> upload = HTTP.send(HttpRequest.newBuilder(URI.create(sender + "/lnis/api/v1/inputs/"
                        + input + "/chunks/0")).header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(source)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, upload.statusCode());
        json("POST", sender + "/lnis/api/v1/inputs/" + input + "/complete", Map.of());
        AtomicReference<byte[]> delivered = new AtomicReference<>();
        HttpServer adapter = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        adapter.createContext("/transfer", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                delivered.set(body);
                try {
                    HttpResponse<byte[]> callback = HTTP.send(HttpRequest.newBuilder(URI.create(receiver
                                    + "/lnis/api/v1/dtn/receive"))
                            .header("Authorization", "Bearer isolated-dtn-only")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    exchange.sendResponseHeaders(callback.statusCode() == 202 ? 202 : 502, -1);
                } catch (Exception error) {
                    exchange.sendResponseHeaders(500, -1);
                }
            }
        });
        adapter.start();
        try {
            String id = json("POST", sender + "/lnis/api/v1/dtn/tests", Map.of("inputId", input,
                    "senderAgentId", "sender-1", "receiverAgentId", "receiver-1",
                    "sendUrl", "http://" + arguments[2] + ":" + adapter.getAddress().getPort() + "/transfer"))
                    .path("testId").asText();
            JsonNode result = waitComplete(sender + "/lnis/api/v1/dtn/tests/" + id);
            assertEquals("PASS", result.path("verdict").asText(), result.toString());
            JsonNode report = json("GET", receiver + "/lnis/api/v1/dtn/tests/" + id + "/report", null);
            assertTrue(report.path("referencePvt").isArray());
            assertEquals("PASS", report.path("comparison").path("verdict").asText());
            assertTrue(report.path("receivedPvt").isArray());
            assertArrayEquals(delivered.get(), HTTP.send(HttpRequest.newBuilder(URI.create(receiver
                    + "/lnis/api/v1/dtn/tests/" + id + "/payload/received")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body());
            System.out.println("PASS: Linux containers DTN external REST roundtrip, isolated DB, original JSON, PVT comparison");
        } finally {
            adapter.stop(0);
        }
    }

    private static JsonNode waitComplete(String url) throws Exception
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        JsonNode result;
        do {
            result = json("GET", url, null);
            String state = result.path("state").asText();
            if (state.equals("COMPLETED") || state.equals("INCONCLUSIVE")) {
                return result;
            }
            assertNotEquals("FAILED", state, result.toString());
            assertNotEquals("CANCELLED", state, result.toString());
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("시험 제한 시간 초과: " + result);
    }

    private static JsonNode json(String method, String url, Object body) throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body)));
        }
        HttpResponse<byte[]> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                url + " HTTP " + response.statusCode() + ": " + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        return MAPPER.readTree(response.body());
    }
}
