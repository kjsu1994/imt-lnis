package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import server.common.DtnModels.*;
import server.gnss.InputBufferService;
import server.iq.IqReceiver;
import server.iq.IqService;
import server.node.*;

import java.io.IOException;
import java.util.*;

class IqReceiveFormatTest {
    @ParameterizedTest
    @CsvSource({
        "1,LNIS-IQ-FILE-v1,legacy,false",
        "2,LNIS-IQ-FILE-v2,frame,false",
        "3,LNIS-IQ-FILE-v3,frame,true",
        "1,LNIS-IQ-FILE-v2,frame,false",
        "2,LNIS-IQ-FILE-v1,frame,false",
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
                        "frame".equals(method) ? IqReceiver.FRAME_METHOD : IqReceiver.METHOD,
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

            doReturn(Map.of("verdict", "PASS"))
                    .doThrow(new IqService.FileMismatchException("I/Q 파일 크기 불일치"))
                    .when(iq).verify(any(), any());
            doReturn(new IqReceiver.Result(List.of(), Map.of())).when(receiver).decode(any(), any(), any(), any());
            job.setState("CALCULATING");
            ReflectionTestUtils.invokeMethod(service, "verifyIq", job);
            assertEquals("FAIL", json.readTree(job.getFileResultJson()).path("verdict").asText(),
                    "a mismatch after decoding overrides the earlier PASS");

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
}
