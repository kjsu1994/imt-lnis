package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(10)
class DtnAdapterHealthServiceTest {
  private HttpServer server;
  private ExecutorService executor;
  private String base;

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach void close() { server.stop(0); executor.shutdownNow(); }

  @Test void probesBothEndpointsConcurrentlyAndPreservesPartialFailure() {
    var receiverRequested = new CountDownLatch(1);
    server.createContext("/sender/health", exchange -> {
      try {
        boolean parallel = receiverRequested.await(2, TimeUnit.SECONDS);
        exchange.sendResponseHeaders(parallel && "GET".equals(exchange.getRequestMethod()) ? 200 : 500, -1);
      } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    server.createContext("/receiver/health", exchange -> {
      receiverRequested.countDown(); exchange.sendResponseHeaders(503, -1); exchange.close();
    });
    var result = service(Duration.ofSeconds(3)).check();
    assertTrue(result.sender().ok());
    assertEquals(200, result.sender().httpStatus());
    assertFalse(result.receiver().ok());
    assertEquals(503, result.receiver().httpStatus());
    assertEquals(base + "/receiver/health", result.receiver().url());
    assertNotNull(result.checkedAt());
    var response = new DtnAdapterHealthController(service(Duration.ofSeconds(3))).check();
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals(200, response.getStatusCode().value());
  }

  @Test void timeoutDoesNotHideHealthyReceiver() {
    server.createContext("/sender/health", exchange -> {
      try { Thread.sleep(2000); exchange.sendResponseHeaders(200, -1); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    server.createContext("/receiver/health", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
    var result = service(Duration.ofMillis(500)).check();
    assertFalse(result.sender().ok());
    assertNull(result.sender().httpStatus());
    assertEquals("응답 시간 초과", result.sender().message());
    assertTrue(result.receiver().ok());
  }

  @Test void configurationErrorAndRedirectAreNotReportedAsHealthy() {
    server.createContext("/receiver/health", exchange -> {
      exchange.getResponseHeaders().add("Location", base + "/other");
      exchange.sendResponseHeaders(302, -1); exchange.close();
    });
    var result = new DtnAdapterHealthService(HttpClient.newHttpClient(), "file:///health",
        base + "/receiver/health", Duration.ofSeconds(2)).check();
    assertFalse(result.sender().ok());
    assertEquals("주소 설정 오류", result.sender().message());
    assertEquals(302, result.receiver().httpStatus());
    assertFalse(result.receiver().ok());
  }

  private DtnAdapterHealthService service(Duration timeout) {
    return new DtnAdapterHealthService(HttpClient.newHttpClient(), base + "/sender/health",
        base + "/receiver/health", timeout);
  }
}
