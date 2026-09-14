package server.central.dtn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-only probes derived from the Sender and Receiver adapter base URLs. */
@Service
public class DtnAdapterHealthService {
  public record EndpointHealth(String url, boolean ok, Integer httpStatus, long elapsedMillis,
      String status, String message, JsonNode response, String rawResponse) {}
  public record HealthReport(Instant checkedAt, EndpointHealth sender, EndpointHealth receiver) {}

  private final HttpClient client;
  private final ObjectMapper json;
  private final String defaultSendUrl;
  private final String defaultReceiveUrl;
  private final Duration timeout;

  @Autowired
  public DtnAdapterHealthService(ObjectMapper json,
      @Value("${lnis.dtn.send-url:}") String defaultSendUrl,
      @Value("${lnis.dtn.receive-url:}") String defaultReceiveUrl) {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build(), json, defaultSendUrl,
        defaultReceiveUrl, Duration.ofSeconds(5));
  }

  DtnAdapterHealthService(HttpClient client, ObjectMapper json, String defaultSendUrl,
      String defaultReceiveUrl, Duration timeout) {
    this.client = client;
    this.json = json;
    this.defaultSendUrl = defaultSendUrl;
    this.defaultReceiveUrl = defaultReceiveUrl;
    this.timeout = timeout;
  }

  /** Legacy callers may continue supplying one adapter address for both roles. */
  public HealthReport check(String adapterUrl) {
    return check(adapterUrl, adapterUrl);
  }

  public HealthReport check(String sendUrl, String receiveUrl) {
    String senderValue = configured(sendUrl, defaultSendUrl, null);
    String receiverValue = configured(receiveUrl, defaultReceiveUrl, senderValue);
    var sender = probe(healthUrl(adapterBase(senderValue, "송신"), "sender"));
    var receiver = probe(healthUrl(adapterBase(receiverValue, "수신"), "receiver"));
    var senderResult = sender.join();
    var receiverResult = receiver.join();
    return new HealthReport(Instant.now(), senderResult, receiverResult);
  }

  private CompletableFuture<EndpointHealth> probe(String url) {
    long started = System.nanoTime();
    try {
      URI uri = URI.create(url);
      var request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
      return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
          .handle((response, error) -> {
            if (error != null) return failure(url, started);
            int httpStatus = response.statusCode();
            JsonNode body = parse(response.body());
            if (httpStatus < 200 || httpStatus >= 300)
              return new EndpointHealth(url, false, httpStatus, elapsed(started), null,
                  "연결실패", body, response.body());
            String adapterStatus = body == null ? null : body.path("status").asText(null);
            if ("ready".equalsIgnoreCase(adapterStatus))
              return new EndpointHealth(url, true, httpStatus, elapsed(started), "ready",
                  "정상연결", body, response.body());
            if ("busy".equalsIgnoreCase(adapterStatus))
              return new EndpointHealth(url, false, httpStatus, elapsed(started), "busy",
                  "시험대기", body, response.body());
            return new EndpointHealth(url, false, httpStatus, elapsed(started), adapterStatus,
                "상태확인 필요", body, response.body());
          });
    } catch (IllegalArgumentException error) {
      return CompletableFuture.completedFuture(new EndpointHealth(url, false, null,
          elapsed(started), null, "주소 설정 오류", null, null));
    }
  }

  private EndpointHealth failure(String url, long started) {
    return new EndpointHealth(url, false, null, elapsed(started), null,
        "연결실패", null, null);
  }

  private JsonNode parse(String body) {
    try { return body == null || body.isBlank() ? null : json.readTree(body); }
    catch (Exception ignored) { return null; }
  }

  private static String configured(String requested, String configured, String fallback) {
    if (requested != null && !requested.isBlank()) return requested;
    if (configured != null && !configured.isBlank()) return configured;
    if (fallback != null && !fallback.isBlank()) return fallback;
    throw new IllegalStateException("외부 어댑터 서버 주소가 설정되지 않았습니다.");
  }

  private static URI adapterBase(String value, String role) {
    URI uri = URI.create(value.trim());
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
        || uri.getPort() == 0 || uri.getPort() > 65535)
      throw new IllegalArgumentException("외부 " + role + " 어댑터 서버 주소가 올바르지 않습니다.");
    return uri;
  }

  private static String healthUrl(URI base, String role) {
    try {
      return new URI(base.getScheme(), null, base.getHost(), base.getPort(),
          "/" + role + "/health", null, null).toString();
    } catch (Exception error) {
      throw new IllegalArgumentException("헬스체크 URL을 만들 수 없습니다.", error);
    }
  }

  private static long elapsed(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }
}
