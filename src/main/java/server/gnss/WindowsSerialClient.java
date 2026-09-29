package server.gnss;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Authenticated connection to the same-PC Windows serial bridge. */
final class WindowsSerialClient {
    private final URI base;
    private final String token;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    static WindowsSerialClient configured() {
        String url = System.getenv("LNIS_SERIAL_BRIDGE_URL");
        return url == null || url.isBlank() ? null :
            new WindowsSerialClient(URI.create(url), System.getenv("LNIS_SERIAL_BRIDGE_TOKEN"));
    }

    WindowsSerialClient(URI base, String token) {
        if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null)
            throw new IllegalArgumentException("Invalid serial bridge URL");
        if (token == null || token.length() < 32) throw new IllegalArgumentException("Serial bridge token is missing");
        this.base = base;
        this.token = token;
    }

    JsonNode request(String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
            var response = http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
            var result = json.readTree(response.body());
            if (response.statusCode() != 200) throw new IllegalStateException(result.path("error").asText("Windows COM 중계 오류"));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Windows COM 중계 연결이 중단됐습니다.", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Windows COM 중계에 연결할 수 없습니다. start-serial-bridge.ps1 실행 상태를 확인하세요.", e);
        }
    }

    List<SerialCaptureService.DetectedPort> ports() {
        var result = new ArrayList<SerialCaptureService.DetectedPort>();
        request("/ports", Map.of()).forEach(p -> result.add(new SerialCaptureService.DetectedPort(
            p.path("name").asText(), "Windows · " + p.path("description").asText(), p.path("identity").asText())));
        return result;
    }

    SerialConnection open(SerialCaptureService.Settings settings) {
        String session = request("/open", settings).path("session").asText();
        if (session.isBlank()) throw new IllegalStateException("Missing COM session");
        return new SerialConnection() {
            private boolean open = true;
            public int readBytes(byte[] buffer, int length) {
                byte[] bytes = Base64.getDecoder().decode(request("/read", Map.of("session", session,
                    "length", Math.min(length, 8192))).path("data").asText());
                if (bytes.length > length) throw new IllegalStateException("Invalid bridge read length");
                System.arraycopy(bytes, 0, buffer, 0, bytes.length);
                return bytes.length;
            }
            public int writeBytes(byte[] bytes, int length) {
                return request("/write", Map.of("session", session, "data",
                    Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, length)))).path("count").asInt();
            }
            public boolean isOpen() { return open; }
            public void closePort() {
                if (!open) return;
                try { request("/close", Map.of("session", session)); }
                finally { open = false; }
            }
        };
    }
}
