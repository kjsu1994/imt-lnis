package server.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import server.common.AgentProtocol.PortDescriptor;
import server.common.AgentProtocol.PortList;
import server.common.LnisModels.InputKind;
import server.common.LnisModels.InputManifest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Verify the shared Agent and GNSS input JSON contracts after retiring AFS-only models. */
class LombokModelCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void jsonFieldNamesAndValuesRoundTrip() throws Exception {
        PortList source = new PortList(List.of(new PortDescriptor("COM3", "receiver")));

        String encoded = json.writeValueAsString(source);
        PortList decoded = json.readValue(encoded, PortList.class);

        assertEquals(source, decoded);
        assertEquals("COM3", json.readTree(encoded).path("ports").get(0).path("name").asText());
        assertEquals(
                "receiver",
                json.readTree(encoded).path("ports").get(0).path("description").asText());
    }

    @Test
    void inputManifestPreservesDtnUploadContract() throws Exception {
        InputManifest source =
                new InputManifest(
                        UUID.randomUUID(),
                        InputKind.GRAW_UPLOAD,
                        "sample.graw",
                        128,
                        "abc",
                        2,
                        1,
                        Instant.parse("2026-09-23T00:00:00Z"));
        String encoded = json.writeValueAsString(source);

        assertEquals(source, json.readValue(encoded, InputManifest.class));
        assertEquals("GRAW_UPLOAD", json.readTree(encoded).path("kind").asText());
        assertEquals(128, json.readTree(encoded).path("size").asInt());
    }
}
