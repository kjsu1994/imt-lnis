package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import server.pvt.DtnComparison;
import server.pvt.DtnDelay;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DtnDelayReportTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void referenceNavigationUsesSameClassificationAsSenderWithoutChangingSavedReference() throws Exception {
        var service = mock(DtnService.class);
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        var words = List.of(0x8bL << 22, 2L << 8, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        byte[] record = server.gnss.GrawCodec.encode(new server.gnss.GrawCodec.Envelope(
                job.getId(), UUID.randomUUID(), 25, Instant.parse("2026-10-01T00:00:00Z"),
                new server.gnss.GrawCodec.NavigationUpdate(0, 4, 0, 0, 2, words)));
        String saved = java.util.Base64.getEncoder().encodeToString(
                java.nio.ByteBuffer.allocate(record.length + 4).putInt(record.length).put(record).array());
        job.setReferenceSourceBase64(saved);
        when(service.get(job.getId())).thenReturn(job);
        var report = new DtnController(service, json).report(job.getId()).getBody();
        var reference = (JsonNode) report.get("referenceObservations");
        assertEquals(2, reference.path("navigation").get(0).path("display").path("subframeId").asInt());
        assertEquals(json.valueToTree(words), reference.path("navigation").get(0).path("message").path("words"));
        assertEquals(25, reference.path("navigation").get(0).path("sequence").asInt());
        assertFalse(reference.path("records").get(0).has("display"));
        assertEquals(saved, job.getReferenceSourceBase64());
    }

    @Test
    void navigationHeadersUseLnavWordsWithoutChangingSource() throws Exception {
        var original = json.createObjectNode();
        var navigation = original.putArray("navigation");
        for (int sf = 1; sf <= 5; sf++) {
            var row = navigation.addObject();
            row.put("sequence", 100 + sf);
            var message = row.putObject("message");
            message.put("constellationId", 0);
            message.put("satelliteId", 4);
            message.put("signalId", 0);
            var words = message.putArray("words");
            words.add(0x8bL << 22);
            words.add((long) sf << 8);
            words.add((1L << 28) | (56L << 22));
            for (int word = 3; word < 10; word++) {
                words.add(0);
            }
        }
        String saved = original.toString();
        var view = DtnController.navigationView(original);
        for (int sf = 1; sf <= 5; sf++) {
            assertEquals(sf, view.path("navigation").get(sf - 1).path("display").path("subframeId").asInt());
        }
        assertTrue(view.path("navigation").get(3).path("display").path("commonCorrection").asBoolean());
        assertFalse(view.path("navigation").get(4).path("display").path("commonCorrection").asBoolean());
        assertEquals(saved, original.toString());
        assertEquals(101, view.path("navigation").get(0).path("sequence").asInt());

        // IQ contains parity-stripped 24-bit words, not receiver 32-bit words.
        original.put("source", "IQ_TRACKING");
        for (var row : navigation) {
            var words = (com.fasterxml.jackson.databind.node.ArrayNode) row.path("message").path("words");
            for (int i = 0; i < words.size(); i++) {
                words.set(i, json.getNodeFactory().numberNode(words.get(i).longValue() >>> 6));
            }
        }
        assertEquals(4, DtnController.navigationView(original).path("navigation").get(3)
                .path("display").path("subframeId").asInt());

        var message = (com.fasterxml.jackson.databind.node.ObjectNode) navigation.get(0).path("message");
        message.put("constellationId", 2);
        assertFalse(DtnController.navigationView(original).path("navigation").get(0).has("display"));
        message.put("constellationId", 0);
        ((com.fasterxml.jackson.databind.node.ArrayNode) message.path("words")).set(0, json.getNodeFactory().numberNode(-1));
        assertFalse(DtnController.navigationView(original).path("navigation").get(0).has("display"));
    }

    @Test
    void historicalReportAddsSignedResidualAndAlignmentWithoutChangingStoredData()
            throws Exception {
        var service = mock(DtnService.class);
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setComparisonMode("DELAY");
        var start = Instant.parse("2026-09-21T00:00:00Z");
        var evidence =
                new DtnDelay.Evidence(
                        new DtnDelay.Timing(start, start.plusSeconds(2)),
                        2,
                        2 * DtnDelay.C,
                        new DtnDelay.Time(2400, 100000),
                        new DtnDelay.Time(2400, 100002),
                        List.of(
                                new DtnDelay.Satellite(
                                        0,
                                        19,
                                        0,
                                        DtnDelay.C * 0.068,
                                        0.068,
                                        new DtnDelay.Time(2400, 99999.932),
                                        DtnDelay.C * 2.068,
                                        -430f,
                                        45,
                                        true)),
                        null);
        job.setDelayEvidenceJson(json.writeValueAsString(evidence));
        String saved =
                "{\"verdict\":\"MEASURED\",\"epochs\":[{\"clockDifferenceSeconds\":1.999999999}]}";
        job.setComparisonJson(saved);
        when(service.get(job.getId())).thenReturn(job);

        var controller = new DtnController(service, json);
        var report = controller.report(job.getId()).getBody();
        var row = ((JsonNode) report.get("comparison")).path("epochs").path(0);
        assertEquals(-1e-9, row.path("clockResidualSeconds").asDouble(), 1e-15);
        assertEquals(-0.299792458, row.path("clockResidualMeters").asDouble(), 1e-6);
        var alignment = (DtnDelay.TimeAlignment) report.get("delayTimeAlignment");
        assertEquals(start.minusMillis(68), alignment.satellites().getFirst().alignedTransmitAt());
        assertEquals(saved, job.getComparisonJson());
        assertEquals(json.writeValueAsString(evidence), job.getDelayEvidenceJson());
        verify(service).get(job.getId());
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingEvidenceAndRestoreReportsDoNotInventClockMeasurements() throws Exception {
        var service = mock(DtnService.class);
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setComparisonJson("{\"verdict\":\"PASS\",\"epochs\":[{\"clockDifferenceSeconds\":0}]}");
        when(service.get(job.getId())).thenReturn(job);
        var controller = new DtnController(service, json);
        for (String mode : List.of("RESTORE", "DELAY")) {
            job.setComparisonMode(mode);
            var report = controller.report(job.getId()).getBody();
            assertNull(report.get("delayTimeAlignment"));
            assertFalse(
                    ((JsonNode) report.get("comparison"))
                            .path("epochs")
                            .path(0)
                            .has("clockResidualSeconds"));
        }
        assertNull(DtnDelay.alignment(null));
        assertNull(DtnComparison.clockResidual(Double.NaN, 1));
    }
}
