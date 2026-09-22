package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import server.central.agent.*;
import server.central.input.InputBufferService;
import server.shared.model.DtnModels.*;

class IqReceiveFormatTest {
    @ParameterizedTest
    @CsvSource({"1,LNIS-IQ-FILE-v1,legacy,true", "2,LNIS-IQ-FILE-v2,frame,true",
            "1,LNIS-IQ-FILE-v2,frame,false", "2,LNIS-IQ-FILE-v1,frame,false",
            "2,LNIS-IQ-FILE-v2,legacy,false"})
    void acceptsMatchingVersionsBeforeVerifyingFile(int version, String format, String method, boolean accepted)
            throws Exception {
        var json = new ObjectMapper();
        var repository = mock(DtnRepository.class);
        var service = new DtnService(repository, mock(AgentCommandService.class), mock(AgentRepository.class),
                mock(AgentConnectionRegistry.class), mock(InputBufferService.class), json);
        var iq = mock(IqService.class);
        ReflectionTestUtils.setField(service, "iq", iq);
        // Stop at the file boundary; malformed envelopes must never reach disk verification.
        when(iq.verify(any())).thenThrow(new IOException("verification boundary reached"));
        var transfer = new Transfer();
        transfer.setSchemaVersion(version);
        transfer.setFormat(format);
        transfer.setTestType("IQ_SAMPLE");
        transfer.setFile(new IqFile("/exchange/" + UUID.randomUUID() + ".bin", 2160000000L,
                "A".repeat(64), 90, 12000000, "IQ_INTERLEAVED_INT8", 2));
        transfer.setMetadata(new IqMetadata("AFSD", "frame".equals(method) ? IqReceiver.FRAME_METHOD : IqReceiver.METHOD,
                2400, 100000, "ECEF_CONSTANT_VELOCITY", List.of(19), List.of()));
        var job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setState("CALCULATING");
        job.setReceivedJson(json.writeValueAsString(transfer));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.invokeMethod(service, "verifyIq", job);
        verify(iq, times(accepted ? 1 : 0)).verify(any());
        assertEquals("FAILED", job.getState());
        if (accepted) assertEquals("verification boundary reached", job.getMessage());
    }
}
