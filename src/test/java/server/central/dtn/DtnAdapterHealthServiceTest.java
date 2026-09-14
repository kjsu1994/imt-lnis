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

  @Test void checksSeparateSenderAndReceiverAddressesInParallel() {
    String receiverBase = base.replace("127.0.0.1", "localhost");
    var receiverRequested = new CountDownLatch(1);
    server.createContext("/sender/health", exchange -> {
      try {
        boolean parallel = receiverRequested.await(2, TimeUnit.SECONDS);
        reply(exchange, parallel && "GET".equals(exchange.getRequestMethod()) ? 200 : 500,
            "{\"status\":\"ready\",\"source\":\"sender-adapter\"}");
      } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    });
    server.createContext("/receiver/health", exchange -> {
      receiverRequested.countDown(); reply(exchange, 200, "{\"status\":\"busy\"}");
    });
    var result = service(Duration.ofSeconds(3), base + "/configured/transfers",
        receiverBase + "/configured").check(base, receiverBase);
    assertTrue(result.sender().ok());
    assertEquals("ready", result.sender().status());
    assertEquals("정상연결", result.sender().message());
    assertEquals("sender-adapter", result.sender().response().path("source").asText());
    assertEquals("{\"status\":\"ready\",\"source\":\"sender-adapter\"}", result.sender().rawResponse());
    assertFalse(result.receiver().ok());
    assertEquals("busy", result.receiver().status());
    assertEquals("시험대기", result.receiver().message());
    assertEquals(receiverBase + "/receiver/health", result.receiver().url());
    assertNotNull(result.checkedAt());
  }

  @Test void defaultsUseEnvironmentAddressesAndControllerDoesNotCache() {
    String receiverBase = base.replace("127.0.0.1", "localhost");
    server.createContext("/sender/health", exchange -> reply(exchange, 200, "{\"status\":\"ready\"}"));
    server.createContext("/receiver/health", exchange -> reply(exchange, 200, "{\"status\":\"ready\"}"));
    var service = service(Duration.ofSeconds(2), base, receiverBase);
    var response = new DtnAdapterHealthController(service).check(null, null, null);
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals(200, response.getStatusCode().value());
    assertEquals(base + "/sender/health", response.getBody().sender().url());
    assertEquals(receiverBase + "/receiver/health", response.getBody().receiver().url());
  }

  @Test void legacyTransferUrlChecksBothRoles() {
    server.createContext("/sender/health", exchange -> reply(exchange, 200, "{\"status\":\"ready\"}"));
    server.createContext("/receiver/health", exchange -> reply(exchange, 200, "{\"status\":\"ready\"}"));
    var service = service(Duration.ofSeconds(2), "", "");
    var response = new DtnAdapterHealthController(service).check(null, null, base + "/transfers");
    assertEquals(base + "/sender/health", response.getBody().sender().url());
    assertEquals(base + "/receiver/health", response.getBody().receiver().url());
  }

  @Test void missingReceiveUrlFallsBackToSenderForExistingDeployments() {
    server.createContext("/sender/health", exchange -> reply(exchange, 200, "{\"status\":\"ready\"}"));
    server.createContext("/receiver/health", exchange -> reply(exchange, 200, "{\"status\":\"ready\"}"));
    var result = service(Duration.ofSeconds(2), base, "").check(null, null);
    assertEquals(base + "/sender/health", result.sender().url());
    assertEquals(base + "/receiver/health", result.receiver().url());
  }

  @Test void timeoutAndHttpErrorAreConnectionFailuresWithoutHidingOtherEndpoint() {
    server.createContext("/sender/health", exchange -> {
      try { Thread.sleep(2000); reply(exchange, 200, "{\"status\":\"ready\"}"); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    });
    server.createContext("/receiver/health", exchange -> reply(exchange, 503, "{\"status\":\"busy\"}"));
    var result = service(Duration.ofMillis(300), base, base).check(null, null);
    assertEquals("연결실패", result.sender().message());
    assertNull(result.sender().httpStatus());
    assertEquals("연결실패", result.receiver().message());
    assertEquals(503, result.receiver().httpStatus());
    assertEquals("busy", result.receiver().response().path("status").asText());
  }

  @Test void unknownOrMalformedStatusRequiresAttention() {
    server.createContext("/sender/health", exchange -> reply(exchange, 200, "{\"status\":\"starting\"}"));
    server.createContext("/receiver/health", exchange -> reply(exchange, 200, "not-json"));
    var result = service(Duration.ofSeconds(2), base, base).check(null, null);
    assertEquals("상태확인 필요", result.sender().message());
    assertEquals("starting", result.sender().status());
    assertEquals("상태확인 필요", result.receiver().message());
    assertNull(result.receiver().response());
    assertEquals("not-json", result.receiver().rawResponse());
  }

  @Test void invalidOrMissingAdapterUrlsAreRejectedBeforeAnyProbe() {
    var service = service(Duration.ofSeconds(2), base, base);
    assertThrows(IllegalArgumentException.class, () -> service.check("file:///sender", base));
    assertThrows(IllegalArgumentException.class, () -> service.check(base, "file:///receiver"));
    var missing = service(Duration.ofSeconds(2), "", "");
    assertThrows(IllegalStateException.class, () -> missing.check(null, null));
  }

  private DtnAdapterHealthService service(Duration timeout, String defaultSendUrl,
      String defaultReceiveUrl) {
    return new DtnAdapterHealthService(HttpClient.newHttpClient(), json, defaultSendUrl,
        defaultReceiveUrl, timeout);
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
