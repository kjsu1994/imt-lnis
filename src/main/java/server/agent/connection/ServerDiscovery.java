package server.agent.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds the LNIS server on the local LAN after a configured connection fails. */
final class ServerDiscovery {
  private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(700);
  private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(4);
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build();

  Optional<URI> discover(int port) {
    return discover(port, candidateHosts(), DISCOVERY_TIMEOUT);
  }

  Optional<URI> discover(int port, Collection<String> candidates, Duration overallTimeout) {
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var completion = new ExecutorCompletionService<URI>(executor);
      // /24 후보를 직렬 조회하면 수 분이 걸릴 수 있어 짧은 HTTP probe를 virtual thread로 동시에 실행한다.
      for (String host : candidates) {
        completion.submit(() -> probe(host, port));
      }
      long deadline = System.nanoTime() + overallTimeout.toNanos();
      for (int remaining = candidates.size(); remaining > 0; remaining--) {
        long wait = deadline - System.nanoTime();
        if (wait <= 0) {
          return Optional.empty();
        }
        var completed = completion.poll(wait, TimeUnit.NANOSECONDS);
        if (completed == null) {
          return Optional.empty();
        }
        URI result = completed.get();
        if (result != null) {
          // 가장 먼저 LNIS 식별 응답을 준 주소를 채택하고 나머지 probe는 executor 종료 시 취소한다.
          return Optional.of(result);
        }
      }
    } catch (Exception ignored) {
      // The next reconnect cycle retries discovery.
    }
    return Optional.empty();
  }

  private URI probe(String host, int port) {
    try {
      URI endpoint = URI.create("http://" + host + ":" + port + "/lnis/api/v1/discovery");
      HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT).GET().build();
      HttpResponse<String> response = server.shared.http.LoggedHttpClient.send(http, request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return null;
      }
      DiscoveryResponse value = json.readValue(response.body(), DiscoveryResponse.class);
      // 단순히 200을 반환하는 다른 장비를 서버로 오인하지 않도록 서비스명과 상대 경로를 확인한다.
      if (!"lnis-server".equals(value.service())
          || value.agentWebSocketPath() == null
          || !value.agentWebSocketPath().startsWith("/")) {
        return null;
      }
      return URI.create("ws://" + host + ":" + port + value.agentWebSocketPath());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Collection<String> candidateHosts() {
    LinkedHashSet<String> hosts = new LinkedHashSet<>();
    // 개발 환경의 동일 PC 서버도 찾을 수 있도록 LAN 후보보다 localhost를 먼저 넣는다.
    hosts.add("127.0.0.1");
    try {
      NetworkInterface.networkInterfaces()
          .filter(
              network -> {
                try {
                  return network.isUp() && !network.isLoopback() && !network.isVirtual();
                } catch (Exception ignored) {
                  return false;
                }
              })
          .flatMap(NetworkInterface::inetAddresses)
          .filter(Inet4Address.class::isInstance)
          .map(Inet4Address.class::cast)
          .filter(Inet4Address::isSiteLocalAddress)
          .forEach(address -> addSlash24(hosts, address.getAddress()));
    } catch (Exception ignored) {
      // Keep localhost as a candidate.
    }
    return hosts;
  }

  private static void addSlash24(Collection<String> hosts, byte[] address) {
    int first = Byte.toUnsignedInt(address[0]);
    int second = Byte.toUnsignedInt(address[1]);
    int third = Byte.toUnsignedInt(address[2]);
    for (int last = 1; last < 255; last++) {
      hosts.add(first + "." + second + "." + third + "." + last);
    }
  }

  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  private static class DiscoveryResponse {
    String service;
    String agentWebSocketPath;
  }
}
