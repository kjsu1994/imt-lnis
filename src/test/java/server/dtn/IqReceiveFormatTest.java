package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import server.common.DtnModels.*;
import server.gnss.InputBufferService;
import server.iq.IqReceiver;
import server.iq.IqService;
import server.node.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;

class IqReceiveFormatTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({
        "1,LNIS-IQ-FILE-v1,legacy,false",
        "2,LNIS-IQ-FILE-v2,frame,false",
        "1,LNIS-IQ-FILE-v1,frame,true",
        "1,LNIS-IQ-FILE-v1,rf,true",
        "1,LNIS-IQ-FILE-v2,frame,false",
        "2,LNIS-IQ-FILE-v1,frame,false",
        "3,LNIS-IQ-FILE-v1,rf,false",
        "2,LNIS-IQ-FILE-v2,legacy,false"
    })
    void acceptsMatchingVersionsBeforeVerifyingFile(
            int version, String format, String method, boolean accepted) throws Exception {
        var json = new ObjectMapper();
        var repository = mock(DtnRepository.class);
        var service =
                new DtnService(
                        repository,
                        mock(AgentCommandService.class),
                        mock(AgentRepository.class),
                        mock(AgentConnectionRegistry.class),
                        mock(InputBufferService.class),
                        json);
        var iq = mock(IqService.class);
        ReflectionTestUtils.setField(service, "iq", iq);
        Path bin = Files.write(directory.resolve("sample.bin"), new byte[] {1, 2, 3, 4});
        when(iq.path(any())).thenReturn(bin);
        // Stop at the file boundary; malformed envelopes must never reach disk verification.
        when(iq.verify(any(), any())).thenThrow(new IOException("verification boundary reached"));
        var transfer = new Transfer();
        transfer.setSchemaVersion(version);
        transfer.setFormat(format);
        transfer.setTestType("IQ_SAMPLE");
        transfer.setFile(
                new IqFile(
                        "/exchange/" + UUID.randomUUID() + ".bin",
                        2160000000L,
                        "A".repeat(64),
                        90,
                        12000000,
                        "IQ_INTERLEAVED_INT8",
                        2));
        transfer.setMetadata(
                new IqMetadata(
                        "AFSD",
                        "rf".equals(method) ? IqReceiver.RF_METHOD : "frame".equals(method) ? IqReceiver.FRAME_METHOD : IqReceiver.METHOD,
                        2400,
                        100000,
                        "ECEF_CONSTANT_VELOCITY",
                        List.of(19),
                        List.of()));
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setState("CALCULATING");
        job.setReceivedJson(json.writeValueAsString(transfer));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.invokeMethod(service, "verifyIq", job);
        verify(iq, times(accepted ? 1 : 0)).verify(any(), any());
        assertEquals("FAILED", job.getState());
        if (accepted) {
            assertEquals("verification boundary reached", job.getMessage());
            assertNull(job.getFileResultJson(), "I/O failure is not proof of a mismatch");
            job.setState("CALCULATING");
            doThrow(new IqService.FileMismatchException("I/Q SHA-256 불일치")).when(iq).verify(any(), any());
            ReflectionTestUtils.invokeMethod(service, "verifyIq", job);
            assertEquals("FAIL", json.readTree(job.getFileResultJson()).path("verdict").asText());

            var receiver = mock(IqReceiver.class);
            ReflectionTestUtils.setField(service, "iqReceiver", receiver);
            doReturn(Map.of("verdict", "PASS")).when(iq).verify(any(), any());
            doThrow(new IOException("decoder failed")).when(receiver).decode(any(), any(), any(), any());
            job.setFileResultJson(null);
            job.setState("CALCULATING");
            ReflectionTestUtils.invokeMethod(service, "verifyIq", job);
            assertEquals("FAILED", job.getState());
            assertEquals("decoder failed", job.getMessage());
            assertEquals("PASS", json.readTree(job.getFileResultJson()).path("verdict").asText(),
                    "decoder failure must retain the verified file result");

            doAnswer(call -> {
                Files.setLastModifiedTime(bin, FileTime.from(Instant.now().plusSeconds(10)));
                return new IqReceiver.Result(List.of(), Map.of());
            }).when(receiver).decode(any(), any(), any(), any());
            job.setState("CALCULATING");
            ReflectionTestUtils.invokeMethod(service, "verifyIq", job);
            assertEquals("FAIL", json.readTree(job.getFileResultJson()).path("verdict").asText(),
                    "a file change after decoding overrides the earlier PASS");

            clearInvocations(receiver);
            doAnswer(call -> {
                job.setState("CANCELLED");
                return Map.of("verdict", "PASS");
            }).when(iq).verify(any(), any());
            job.setState("CALCULATING");
            ReflectionTestUtils.invokeMethod(service, "verifyIq", job);
            assertEquals("CANCELLED", job.getState());
            verify(receiver, never()).decode(any(), any(), any(), any());

        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unchanged", "size", "modified", "replaced", "removed", "cancelled", "rf"})
    void verifiesHashOnceAndPublishesResultsOnlyForUnchangedActiveTrial(String change) throws Exception {
        boolean rf = change.equals("rf");
        var json = new ObjectMapper().findAndRegisterModules();
        var repository = mock(DtnRepository.class);
        var service = new DtnService(repository, mock(AgentCommandService.class),
                mock(AgentRepository.class), mock(AgentConnectionRegistry.class),
                mock(InputBufferService.class), json);
        var iq = mock(IqService.class);
        var receiver = mock(IqReceiver.class);
        ReflectionTestUtils.setField(service, "iq", iq);
        ReflectionTestUtils.setField(service, "iqReceiver", receiver);
        Path bin = Files.write(directory.resolve("sample.bin"), new byte[] {1, 2, 3, 4});
        when(iq.path(any())).thenReturn(bin);
        if (change.equals("replaced")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    Files.readAttributes(bin, java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null,
                    "File identity is not provided on this filesystem; covered by the Linux verification.");
        }
        when(iq.verify(any(), any())).thenReturn(Map.of("verdict", "PASS", "sizeBytes", IqService.EXPECTED_BYTES, "sha256", "A".repeat(64)));
        var start = Instant.parse("2026-11-02T00:00:00Z");
        var timing = new server.pvt.DtnDelay.Timing(start, start.plusSeconds(2));
        var evidence = new server.pvt.DtnDelay.Evidence(timing, 2, 2 * server.pvt.DtnDelay.C,
                new server.pvt.DtnDelay.Time(2400, rf ? 100021 : 100000),
                new server.pvt.DtnDelay.Time(2400, rf ? 100023 : 100002), List.of(), null);
        var reference = new Pvt();
        reference.setWeek(2400);
        reference.setTowSeconds(100000);
        reference.setPositionValid(true);
        reference.setVelocityValid(true);
        reference.setEcefMeters(new double[] {1, 2, 3});
        reference.setVelocityMetersPerSecond(new double[] {4, 5, 6});
        reference.setReceiverClockBiasSeconds(0.0);
        var received = json.readValue(json.writeValueAsString(reference), Pvt.class);
        received.setTowSeconds(rf ? 100023 : 100002);
        var baseline = json.readValue(json.writeValueAsString(reference), Pvt.class);
        baseline.setTowSeconds(rf ? 100021 : 100000);
        received.setReceiverClockBiasSeconds(2.0);
        var transfer = new Transfer();
        transfer.setSchemaVersion(1);
        transfer.setFormat("LNIS-IQ-FILE-v1");
        transfer.setTestType("IQ_SAMPLE");
        transfer.setFile(new IqFile("/exchange/" + UUID.randomUUID() + ".bin",
                IqService.EXPECTED_BYTES, "A".repeat(64), 90, 12000000, "IQ_INTERLEAVED_INT8", 2));
        transfer.setMetadata(new IqMetadata("AFSD", rf ? IqReceiver.RF_METHOD : IqReceiver.FRAME_METHOD, 2400, 100000,
                "ECEF_CONSTANT_VELOCITY", List.of(19, 23, 24, 28), List.of()));
        transfer.setReferencePvt(List.of(reference));
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setTestType("IQ_SAMPLE");
        job.setState("CALCULATING");
        job.setTestStartedAt(start);
        job.setReceivedAt(timing.receivedAt());
        job.setReceivedJson(json.writeValueAsString(transfer));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(receiver.decode(any(), any(), any(), any())).thenAnswer(call -> {
            var modified = Files.getLastModifiedTime(bin);
            switch (change) {
                case "size" -> Files.write(bin, new byte[] {1});
                case "modified" -> {
                    Files.write(bin, new byte[] {4, 3, 2, 1});
                    Files.setLastModifiedTime(bin, FileTime.from(modified.toInstant().plusSeconds(2)));
                }
                case "replaced" -> {
                    Path other = Files.write(directory.resolve("other.bin"), new byte[] {4, 3, 2, 1});
                    Files.setLastModifiedTime(other, modified);
                    Files.move(other, bin, StandardCopyOption.REPLACE_EXISTING);
                }
                case "removed" -> Files.delete(bin);
                case "cancelled" -> job.setState("CANCELLED");
            }
            return new IqReceiver.Result(List.of(received), Map.of("source", rf ? "IQ_RF" : "IQ_FRAME"), evidence,
                    rf ? List.of(baseline) : List.of());
        });

        ReflectionTestUtils.invokeMethod(service, "verifyIq", job);

        verify(iq, times(1)).verify(any(), any());
        verify(receiver, times(1)).decode(eq(bin), any(), eq(timing), any());
        if (change.equals("unchanged") || rf) {
            assertEquals("COMPLETED", job.getState());
            assertEquals("PASS", json.readTree(job.getFileResultJson()).path("verdict").asText());
            assertEquals("MEASURED", json.readTree(job.getComparisonJson()).path("verdict").asText());
            assertEquals(json.valueToTree(List.of(received)), json.readTree(job.getReceiverJson()));
            assertEquals(evidence, json.readValue(job.getDelayEvidenceJson(), server.pvt.DtnDelay.Evidence.class));
            if (rf) {
                assertEquals("RF_BASELINE", json.readTree(job.getComparisonJson()).path("comparisonBasis").asText());
                assertEquals(json.valueToTree(List.of(reference)), json.readTree(job.getReferenceJson()));
                assertEquals(json.valueToTree(List.of(baseline)), json.readTree(job.getFileResultJson()).path("rfBaselinePvt"));
                var reportService = mock(DtnService.class);
                when(reportService.get(job.getId())).thenReturn(job);
                var report = new DtnController(reportService, json).report(job.getId()).getBody();
                assertEquals("RF_BASELINE", report.get("comparisonBasis"));
                assertEquals(json.valueToTree(List.of(baseline)), report.get("rfBaselinePvt"));
                var sender = new DtnJob();
                sender.setId(job.getId());
                sender.setTestType("IQ_SAMPLE");
                sender.setState("WAITING_DTN");
                sender.setSentJson(json.writeValueAsString(transfer));
                var remote = new DtnRemoteResult();
                remote.setState("COMPLETED");
                remote.setReceivedAt(timing.receivedAt());
                remote.setPvt(List.of(received));
                remote.setDelayEvidence(evidence);
                remote.setFileResult(json.readTree(job.getFileResultJson()));
                ReflectionTestUtils.invokeMethod(service, "applyReceiverResult", sender, remote);
                assertEquals("COMPLETED", sender.getState());
                assertEquals("RF_BASELINE", json.readTree(sender.getComparisonJson()).path("comparisonBasis").asText());
                assertEquals(json.valueToTree(List.of(reference)), json.readTree(sender.getReferenceJson()));
            }
        } else {
            assertEquals(change.equals("cancelled") ? "CANCELLED" : "FAILED", job.getState());
            assertNull(job.getReceiverJson());
            assertNull(job.getComparisonJson());
            if (List.of("size", "modified", "replaced").contains(change)) {
                assertEquals("FAIL", json.readTree(job.getFileResultJson()).path("verdict").asText());
            }
        }
    }
}
