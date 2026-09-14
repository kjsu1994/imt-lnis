package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(10)
class DtnAdapterHealthServiceTest {
  private HttpServer server;
  private ExecutorService executor;
  private String base;
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach void close() { server.stop(0); executor.shutdownNow(); }

  @Test void eachNodeOnlyChecksItsLocalRoleAndPreservesRawJson() {
    var senderCalls = new java.util.concurrent.atomic.AtomicInteger();
    var receiverCalls = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext("/sender/health", exchange -> {
      senderCalls.incrementAndGet();
      reply(exchange, "GET".equals(exchange.getRequestMethod()) ? 200 : 405, "{\"status\":\"ready\"}");
    });
    server.createContext("/receiver/health", exchange -> {
      receiverCalls.incrementAndGet(); reply(exchange, 200, "{\"status\":\"busy\"}");
    });
    var response = new DtnAdapterHealthController(service("sender", base, Duration.ofSeconds(2))).check(null);
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals("sender", response.getBody().role());
    assertTrue(response.getBody().adapter().ok());
    assertEquals("정상연결", response.getBody().adapter().message());
    assertEquals(1, senderCalls.get()); assertEquals(0, receiverCalls.get());
    var receiver = service("receiver", "http://unconfigured.invalid", Duration.ofSeconds(2)).check(base + "/transfers");
    assertEquals(base + "/receiver/health", receiver.adapter().url());
    assertEquals("시험대기", receiver.adapter().message());
    assertEquals("{\"status\":\"busy\"}", receiver.adapter().rawResponse());
    assertEquals("busy", receiver.adapter().response().path("status").asText());
    assertEquals(1, senderCalls.get()); assertEquals(1, receiverCalls.get());
  }

  @Test void timeoutAndHttpErrorAreConnectionFailures() {
    server.createContext("/sender/health", exchange -> {
      try { Thread.sleep(2000); reply(exchange, 200, "{}"); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    });
    server.createContext("/receiver/health", exchange -> reply(exchange, 503, "{\"status\":\"busy\"}"));
    var sender = service("sender", base, Duration.ofMillis(100)).check(null).adapter();
    assertEquals("연결실패", sender.message()); assertNull(sender.httpStatus());
    var receiver = service("receiver", base, Duration.ofSeconds(2)).check(null).adapter();
    assertEquals("연결실패", receiver.message()); assertEquals(503, receiver.httpStatus());
    assertNull(receiver.status()); assertNotNull(receiver.rawResponse());
  }

  @Test void malformedResponseRemainsVisibleAndInvalidAddressesAreRejected() {
    server.createContext("/sender/health", exchange -> reply(exchange, 200, "not-json"));
    var service = service("sender", base, Duration.ofSeconds(2));
    var value = service.check(null).adapter();
    assertEquals("상태확인 필요", value.message());
    assertEquals("not-json", value.rawResponse()); assertNull(value.response());
    assertThrows(IllegalArgumentException.class, () -> service.check("file:///adapter"));
    assertThrows(IllegalStateException.class, () -> service("sender", "", Duration.ofSeconds(2)).check(null));
  }

  private DtnAdapterHealthService service(String role, String url, Duration timeout) {
    return new DtnAdapterHealthService(HttpClient.newHttpClient(), json, url, role, timeout);
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (Exception error) { throw new RuntimeException(error); }
    finally { exchange.close(); }
  }
}
