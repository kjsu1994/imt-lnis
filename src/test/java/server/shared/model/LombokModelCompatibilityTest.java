package server.shared.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import server.shared.model.AgentProtocol.Hello;
import server.shared.model.LnisModels.InputKind;
import server.shared.model.LnisModels.InputManifest;

/** Verify the shared Agent and GNSS input JSON contracts after retiring AFS-only models. */
class LombokModelCompatibilityTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void jsonFieldNamesAndValuesRoundTrip() throws Exception {
    Hello source =
        new Hello(
            "1.0.0", 1, "Windows", "amd64", java.util.Map.of("afs", true), List.of("192.0.2.1"));

    String encoded = json.writeValueAsString(source);
    Hello decoded = json.readValue(encoded, Hello.class);

    assertEquals(source, decoded);
    assertEquals("1.0.0", json.readTree(encoded).get("agentVersion").asText());
    assertEquals("192.0.2.1", json.readTree(encoded).get("ipv4Addresses").get(0).asText());
  }

  @Test
  void inputManifestPreservesDtnUploadContract() throws Exception {
    InputManifest source = new InputManifest(UUID.randomUUID(), InputKind.GRAW_UPLOAD,
        "sample.graw", 128, "abc", 2, 1, Instant.parse("2026-09-23T00:00:00Z"));
    String encoded = json.writeValueAsString(source);

    assertEquals(source, json.readValue(encoded, InputManifest.class));
    assertEquals("GRAW_UPLOAD", json.readTree(encoded).path("kind").asText());
    assertEquals(128, json.readTree(encoded).path("size").asInt());
  }
}
