package server.node;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import server.common.AgentProtocol.*;
import server.common.LnisModels.AgentRole;
import server.gnss.InputBufferService;
import server.realtime.EventService;

import java.util.Map;
import java.util.UUID;

class AgentMessageServiceTest {
    private final InputBufferService inputs = mock(InputBufferService.class);
    private final EventService events = mock(EventService.class);
    private final AgentMessageService service =
            new AgentMessageService(new ObjectMapper().findAndRegisterModules(), inputs, events);

    @Test
    void missingEventTypeIsReportedAsError() {
        UUID id = UUID.randomUUID();
        service.status(
                "receiver-1",
                AgentRole.RECEIVER,
                id,
                new Progress(null, 100, "RESULT", "", Map.of()));
        verify(events)
                .publish(
                        eq(EventType.ERROR),
                        eq("receiver-1"),
                        eq(AgentRole.RECEIVER),
                        eq(id),
                        any());
    }

    @Test
    void completionWithoutPvtKeepsExistingInputContract() {
        UUID id = UUID.randomUUID();
        service.status(
                "sender",
                AgentRole.SENDER,
                id,
                new Progress(EventType.GNSS_STATUS, 100, "SingleEpochComplete", "", Map.of()));
        verify(inputs).complete(id);
    }

    @Test
    void invalidPvtNeverCompletesInput() {
        var pvt = new server.common.DtnModels.Pvt();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.status(
                                "sender",
                                AgentRole.SENDER,
                                UUID.randomUUID(),
                                new Progress(
                                        EventType.GNSS_STATUS,
                                        100,
                                        "SingleEpochComplete",
                                        "",
                                        Map.of("pvt", java.util.List.of(pvt)))));
        verifyNoInteractions(inputs);
    }

    @Test
    void canonicalBytesArePassedWithoutBase64Conversion() {
        UUID id = UUID.randomUUID();
        var input = mock(server.gnss.InputBufferEntity.class);
        when(inputs.get(id)).thenReturn(input);
        when(input.chunkCount()).thenReturn(2L);
        byte[] bytes = {1, 2, 3};
        service.input(id, bytes);
        verify(inputs).append(id, 2, bytes);
    }
}
