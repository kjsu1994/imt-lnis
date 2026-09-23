package server.central.node;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import server.central.dtn.DtnJob;
import server.central.dtn.DtnRepository;
import server.central.dtn.DtnService;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.AgentState;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeConnectionServiceTest {
    private final NodeProperties properties = new NodeProperties(new MockEnvironment()
            .withProperty("lnis.node.role", "sender")
            .withProperty("lnis.node.management-token", "test-private-token")
            .withProperty("lnis.node.peer-url", "http://192.168.1.20:8088"));
    private final NodePeerClient client = mock(NodePeerClient.class);
    private final NodePeerSettingRepository settings = mock(NodePeerSettingRepository.class);
    private final NodePeerConnection connection = mock(NodePeerConnection.class);
    private final DtnRepository jobs = mock(DtnRepository.class);
    private final NodeConnectionService service = new NodeConnectionService(properties, client, settings,
            connection, mock(DtnService.class), jobs);

    @Test
    void testDoesNotSaveOrChangeCurrentAddressAndReportsFailure()
    {
        when(client.statusAt(any())).thenReturn(ready());
        assertTrue(service.test(request("192.168.1.30", 8089)).isReady());
        assertEquals("http://192.168.1.20:8088", properties.getPeerBaseUrl().toString());
        verifyNoInteractions(settings, connection);
        when(client.statusAt(any())).thenThrow(new IllegalStateException("상대 노드 상태 조회 실패: HTTP 401"));
        NodeConnectionDto.ProbeResult result = service.test(request("192.168.1.30", 8089));
        assertFalse(result.isConnected());
        assertTrue(result.getMessage().contains("401"));
        assertFalse(result.getMessage().contains("test-private-token"));
    }

    @Test
    void invalidAddressesDoNotSendRequests()
    {
        for (String ip : List.of("example.com", "http://192.168.1.2", "192.168.1.2/path", "256.0.0.1",
                "0.0.0.0", "224.0.0.1", "169.254.169.254", "192.168.01.2")) {
            assertThrows(IllegalArgumentException.class, () -> service.test(request(ip, 8088)), ip);
        }
        assertThrows(IllegalArgumentException.class, () -> service.test(request("192.168.1.2", 0)));
        assertThrows(IllegalArgumentException.class, () -> service.test(request("192.168.1.2", 65536)));
        verifyNoInteractions(client);
    }

    @Test
    void activeDtnPreventsAddressChange()
    {
        when(jobs.findByStateIn(anyList())).thenReturn(List.of(new DtnJob()));
        assertThrows(IllegalStateException.class, () -> service.save(request("192.168.1.30", 8088)));
        verifyNoInteractions(client, settings, connection);
    }

    @Test
    void saveRequiresReadyAndDoesNotApplyWhenDatabaseFails()
    {
        NodeDto.StatusResponse status = ready();
        status.setState(AgentState.BUSY);
        when(client.statusAt(any())).thenReturn(status);
        assertThrows(IllegalStateException.class, () -> service.save(request("192.168.1.30", 8088)));
        verifyNoInteractions(settings, connection);
        status.setState(AgentState.READY);
        when(settings.saveAndFlush(any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class, () -> service.save(request("192.168.1.30", 8088)));
        verifyNoInteractions(connection);
    }

    @Test
    void savesOnlyAddressAndRestoresIt()
    {
        when(client.statusAt(any())).thenReturn(ready());
        doAnswer(call -> { properties.applyPeerAddress(call.getArgument(0)); return null; })
                .when(connection).applyAddress(any());
        assertEquals("http://192.168.1.30:8089", service.save(request("192.168.1.30", 8089)).getBaseUrl());
        org.mockito.ArgumentCaptor<NodePeerSetting> saved = org.mockito.ArgumentCaptor.forClass(NodePeerSetting.class);
        verify(settings).saveAndFlush(saved.capture());
        assertEquals(1, saved.getValue().getId());
        properties.applyPeerAddress(URI.create("http://192.168.1.40:8088"));
        when(settings.findById(1)).thenReturn(Optional.of(saved.getValue()));
        service.restore();
        assertEquals("http://192.168.1.30:8089", properties.getPeerBaseUrl().toString());
    }

    private static NodeDto.StatusResponse ready()
    {
        return new NodeDto.StatusResponse(1, "receiver-1", AgentRole.RECEIVER, AgentState.READY,
                true, 1, "http://192.168.1.30:8089");
    }

    private static NodeConnectionDto.AddressRequest request(String ip, int port)
    {
        NodeConnectionDto.AddressRequest request = new NodeConnectionDto.AddressRequest();
        request.setIp(ip);
        request.setPort(port);
        return request;
    }
}
