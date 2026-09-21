package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import server.shared.codec.DtnDelay;

class DtnDelayReportTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void historicalReportAddsSignedResidualAndAlignmentWithoutChangingStoredData() throws Exception
    {
        var service = mock(DtnService.class);
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setComparisonMode("DELAY");
        var start = Instant.parse("2026-09-21T00:00:00Z");
        var evidence = new DtnDelay.Evidence(new DtnDelay.Timing(start, start.plusSeconds(2)),
                2, 2 * DtnDelay.C, new DtnDelay.Time(2400, 100000), new DtnDelay.Time(2400, 100002),
                List.of(new DtnDelay.Satellite(0, 19, 0, DtnDelay.C * 0.068, 0.068,
                        new DtnDelay.Time(2400, 99999.932), DtnDelay.C * 2.068, -430f, 45, true)), null);
        job.setDelayEvidenceJson(json.writeValueAsString(evidence));
        String saved = "{\"verdict\":\"MEASURED\",\"epochs\":[{\"clockDifferenceSeconds\":1.999999999}]}";
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
    void missingEvidenceAndRestoreReportsDoNotInventClockMeasurements() throws Exception
    {
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
            assertFalse(((JsonNode) report.get("comparison")).path("epochs").path(0).has("clockResidualSeconds"));
        }
        assertNull(DtnDelay.alignment(null));
        assertNull(DtnComparison.clockResidual(Double.NaN, 1));
    }
}
