package server.central.dtn;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-only probes of the configured adapter endpoints, independent of trial state. */
@Service
public class DtnAdapterHealthService {
  public record EndpointHealth(String url, boolean ok, Integer httpStatus,
      long elapsedMillis, String message) {}
  public record HealthReport(Instant checkedAt, EndpointHealth sender, EndpointHealth receiver) {}

  private final HttpClient client;
  private final String senderUrl;
  private final String receiverUrl;
  private final Duration timeout;

  @Autowired
  public DtnAdapterHealthService(
      @Value("${lnis.dtn.sender-adapter-health-url:http://192.168.1.154:8080/sender/health}") String senderUrl,
      @Value("${lnis.dtn.receiver-adapter-health-url:http://192.168.1.154:8080/receiver/health}") String receiverUrl) {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build(), senderUrl, receiverUrl, Duration.ofSeconds(5));
  }

  DtnAdapterHealthService(HttpClient client, String senderUrl, String receiverUrl, Duration timeout) {
    this.client = client;
    this.senderUrl = senderUrl;
    this.receiverUrl = receiverUrl;
    this.timeout = timeout;
  }

  public HealthReport check() {
    var sender = probe(senderUrl);
    var receiver = probe(receiverUrl);
    var senderResult = sender.join();
    var receiverResult = receiver.join();
    return new HealthReport(Instant.now(), senderResult, receiverResult);
  }

  private CompletableFuture<EndpointHealth> probe(String url) {
    long started = System.nanoTime();
    try {
      URI uri = URI.create(url);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
        throw new IllegalArgumentException("Invalid health URL");
      var request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
      return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
          .handle((response, error) -> {
            if (error != null) return failure(url, started, error);
            int status = response.statusCode();
            boolean ok = status >= 200 && status < 300;
            return new EndpointHealth(url, ok, status, elapsed(started), ok ? "응답 정상" : "응답 오류");
          });
    } catch (IllegalArgumentException error) {
      return CompletableFuture.completedFuture(new EndpointHealth(url, false, null,
          elapsed(started), "주소 설정 오류"));
    }
  }

  private EndpointHealth failure(String url, long started, Throwable error) {
    while (error.getCause() != null) error = error.getCause();
    String message = error instanceof HttpTimeoutException || error instanceof TimeoutException
        ? "응답 시간 초과" : "연결 실패";
    return new EndpointHealth(url, false, null, elapsed(started), message);
  }

  private static long elapsed(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }
}
