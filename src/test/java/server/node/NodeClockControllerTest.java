package server.node;

import org.junit.jupiter.api.Test;
import server.common.ServiceClock;
import server.common.LnisModels.AgentRole;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeClockControllerTest {
    private final NodePeerClient peer = mock(NodePeerClient.class);
    private final NodeProperties properties = mock(NodeProperties.class);
    private final NodeAuthenticationService auth = mock(NodeAuthenticationService.class);
    private final NodeClockController controller = new NodeClockController(new ServiceClock(), peer, properties, auth);

    @Test
    void statusStartsWithSystemTime() {
        var value = controller.status().clock();
        assertEquals("SYSTEM", value.source());
        assertEquals(value.rawAt(), value.trialAt());
        assertNull(controller.status().receiverClock());
    }

    @Test
    void senderDelegatesCorrectionAndNeverChangesItsOwnClock() {
        when(properties.getRole()).thenReturn(AgentRole.SENDER);
        var receiver = new ServiceClock().stamp();
        when(peer.exchange(eq("/lnis/api/v1/node/peer/clock/sync"), any(), eq(NodeClockController.Probe.class), anyInt()))
                .thenReturn(new NodeClockController.Probe(receiver, null));
        var result = controller.sync();
        assertEquals("SYSTEM", result.clock().source());
        assertEquals(receiver, result.receiverClock());
        verify(peer).status();
    }

    @Test
    void onlySenderServesTimeAndRemoteRequestsAreAuthenticated() {
        when(properties.getRole()).thenReturn(AgentRole.SENDER);
        var result = controller.exchange("Bearer test");
        verify(auth).authenticate("Bearer test");
        assertFalse(result.sentAt().isBefore(result.receivedAt()));
        when(properties.getRole()).thenReturn(AgentRole.RECEIVER);
        assertThrows(IllegalStateException.class, () -> controller.exchange("Bearer test"));
        doThrow(new IllegalStateException("unauthorized")).when(auth).authenticate(null);
        assertThrows(IllegalStateException.class, () -> controller.remoteSync(null));
    }

    @Test
    void failedProbeDoesNotApplyCorrection() {
        when(properties.getRole()).thenReturn(AgentRole.RECEIVER);
        when(peer.exchange(eq("/lnis/api/v1/node/peer/clock"), isNull(), eq(NodeClockController.Exchange.class), anyInt()))
                .thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, controller::sync);
        assertEquals("SYSTEM", controller.status().clock().source());
    }

    @Test
    void receiverSamplesSenderAndAppliesTcpCorrection() {
        when(properties.getRole()).thenReturn(AgentRole.RECEIVER);
        var sender = new ServiceClock().stamp();
        when(peer.exchange(eq("/lnis/api/v1/node/peer/clock"), isNull(),
                eq(NodeClockController.Exchange.class), anyInt())).thenAnswer(call -> {
            var received = java.time.Instant.now().plusMillis(10);
            return new NodeClockController.Exchange(received, received, sender);
        });
        var value = controller.sync().clock();
        assertEquals("TCP_PEER", value.source());
        assertTrue(value.offsetSeconds() > .005);
        assertTrue(value.roundTripSeconds() >= 0);
        verify(peer, atLeast(1)).exchange(eq("/lnis/api/v1/node/peer/clock"), isNull(),
                eq(NodeClockController.Exchange.class), anyInt());
    }
}
