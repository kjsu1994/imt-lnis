package server.central.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.central.agent.AgentRepository;
import server.central.input.InputBufferService;
import server.central.realtime.EventService;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.AgentProtocol.EventType;
import server.shared.model.AgentProtocol.MessageType;
import server.shared.model.LnisModels.AgentRole;

/** Agent가 잘못된 STATUS payload를 보내더라도 WebSocket 연결 전체가 종료되지 않는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class AgentMessageServiceTest {
  @Mock private AgentRepository agents;

  @Mock private InputBufferService inputs;


  @Mock private EventService events;




  @Test
  void statusWithoutEventTypeIsReportedAsErrorInsteadOfThrowingNullPointerException() {
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    AgentMessageService service =
        new AgentMessageService(
            json, agents, inputs, events);
    UUID sessionId = UUID.randomUUID();
    Envelope malformedStatus =
        new Envelope(
            1,
            MessageType.STATUS,
            UUID.randomUUID(),
            null,
            "receiver-1",
            AgentRole.RECEIVER,
            sessionId,
            Instant.now(),
            json.valueToTree(
                Map.of(
                    "percent",
                    100,
                    "stage",
                    "RESULT",
                    "message",
                    "event type omitted",
                    "counters",
                    Map.of())));

    assertDoesNotThrow(() -> service.handle(malformedStatus));

    verify(events)
        .publish(
            eq(EventType.ERROR), eq("receiver-1"), eq(AgentRole.RECEIVER), eq(sessionId), any());
  }
  @Test void olderAgentCanCompleteCaptureWithoutPvtCounters() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var service = new AgentMessageService(json, agents, inputs, events);
    UUID id = UUID.randomUUID();
    service.handle(Envelope.of(MessageType.STATUS, "sender", AgentRole.SENDER, id,
        json.valueToTree(new server.shared.model.AgentProtocol.Progress(EventType.GNSS_STATUS,
            100, "SingleEpochComplete", "Complete", Map.of()))));
    verify(inputs).complete(id);
  }

  @Test void invalidAgentPvtDoesNotMarkInputComplete() {
    var json = new ObjectMapper().findAndRegisterModules();
    var service = new AgentMessageService(json, agents, inputs, events);
    UUID id = UUID.randomUUID();
    var pvt = new server.shared.model.DtnModels.Pvt();
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.handle(
        Envelope.of(MessageType.STATUS, "sender", AgentRole.SENDER, id,
            json.valueToTree(new server.shared.model.AgentProtocol.Progress(EventType.GNSS_STATUS,
                100, "SingleEpochComplete", "Complete", Map.of("pvt", java.util.List.of(pvt)))))));
    org.mockito.Mockito.verifyNoInteractions(inputs);
  }
}
