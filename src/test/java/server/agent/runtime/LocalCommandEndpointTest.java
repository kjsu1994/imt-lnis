package server.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agent.config.AgentConfig;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.AgentProtocol.MessageType;
import server.shared.model.LnisModels.AgentRole;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalCommandEndpointTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentRuntime agentRuntime = mock(AgentRuntime.class);
    private final List<Envelope> results = new ArrayList<>();
    private Consumer<Envelope> outbound;
    private LocalCommandEndpoint endpoint;

    @BeforeEach
    void setUp()
    {
        doAnswer(invocation -> {
            outbound = invocation.getArgument(0);
            return null;
        }).when(agentRuntime).outbound(any());
        AgentConfig config = new AgentConfig("sender-1", AgentRole.SENDER,
                URI.create("ws://unused.invalid"), "unused", Path.of("native"));
        endpoint = new LocalCommandEndpoint(config, agentRuntime, results::add);
    }

    @Test
    void callsRuntimeWithoutNetworkAndForwardsAcknowledgment()
    {
        respond(true);
        endpoint.send(command());
        assertEquals(1, results.size());
        assertEquals(MessageType.COMMAND_ACK, results.getFirst().type());
    }

    @Test
    void rejectsNegativeAndMissingAcknowledgments()
    {
        respond(false);
        assertThrows(IllegalStateException.class, () -> endpoint.send(command()));
        doNothing().when(agentRuntime).handle(any());
        assertThrows(IllegalStateException.class, () -> endpoint.send(command()));
    }

    @Test
    void refusesWrongIdentityBeforeExecution()
    {
        Envelope message = Envelope.of(MessageType.COMMAND, "receiver-1", AgentRole.RECEIVER,
                null, objectMapper.createObjectNode());
        assertThrows(IllegalArgumentException.class, () -> endpoint.send(message));
        verify(agentRuntime, never()).handle(any());
    }

    @Test
    void refusesNonCommandMessagesBeforeExecution()
    {
        for (MessageType type : MessageType.values()) {
            if (type == MessageType.COMMAND) continue;
            var message = Envelope.of(type, "sender-1", AgentRole.SENDER, null, objectMapper.createObjectNode());
            assertThrows(IllegalArgumentException.class, () -> endpoint.send(message));
        }
        verify(agentRuntime, never()).handle(any());
    }

    @Test
    void closesRuntimeOnceAndRefusesNewWork()
    {
        endpoint.close();
        endpoint.close();
        assertFalse(endpoint.online());
        assertThrows(IllegalStateException.class, () -> endpoint.send(command()));
        verify(agentRuntime, times(1)).close();
    }

    private void respond(boolean accepted)
    {
        doAnswer(invocation -> {
            Envelope request = invocation.getArgument(0);
            outbound.accept(new Envelope(request.protocolVersion(), MessageType.COMMAND_ACK,
                    UUID.randomUUID(), request.messageId(), request.agentId(), request.role(),
                    request.sessionId(), Instant.now(), objectMapper.createObjectNode()
                            .put("accepted", accepted).put("message", "test acknowledgment")));
            return null;
        }).when(agentRuntime).handle(any());
    }

    private Envelope command()
    {
        return Envelope.of(MessageType.COMMAND, "sender-1", AgentRole.SENDER,
                null, objectMapper.createObjectNode());
    }
}
