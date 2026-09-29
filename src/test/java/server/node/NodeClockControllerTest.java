package server.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import server.common.ServiceClock;
import server.dtn.*;
import server.gnss.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeClockControllerTest {
    private final ServiceClock clock = new ServiceClock();
    private final DtnService dtn = mock(DtnService.class);
    private final DtnRepository jobs = mock(DtnRepository.class);
    private final NodePeerClient peer = mock(NodePeerClient.class);
    private final NodeAuthenticationService auth = mock(NodeAuthenticationService.class);
    private final LocalNodeLifecycle lifecycle = mock(LocalNodeLifecycle.class);
    private final NodeProperties properties = mock(NodeProperties.class);
    private final GnssConnection gnss = mock(GnssConnection.class);
    private final NodeClockController controller = new NodeClockController(clock, dtn, jobs, peer, auth, lifecycle, properties);

    NodeClockControllerTest() {
        when(properties.getAgentId()).thenReturn("receiver-1");
        var runtime = mock(AgentRuntime.class);
        when(lifecycle.runtime("receiver-1")).thenReturn(runtime);
        when(runtime.gnss()).thenReturn(gnss);
        when(gnss.status()).thenReturn(new GnssConnection.Status("CONNECTED", "COM4", 38400,
                false, "VALID", Instant.now(), Instant.now(), 50L, "", false, false));
        when(gnss.timeReference()).thenReturn(Map.of("ready", false));
        when(jobs.findByStateIn(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(controller, "ntpServer", "test.invalid");
    }

    @Test
    void localGnssPreferredAndApplicationNeverSetsOsTime() throws Exception {
        when(gnss.timeReference()).thenAnswer(invocation -> {
            Instant now = Instant.now();
            return Map.of("ready", true, "receivedAt", now.toString(), "sentAt", now.toString(),
                    "utc", now.plusMillis(250).toString());
        });
        var proposal = controller.prepare();
        assertEquals("GNSS_USB", proposal.source());
        assertEquals(.25, proposal.offsetSeconds(), .02);
        when(peer.exchange(contains("reservation"), any(), eq(com.fasterxml.jackson.databind.JsonNode.class), anyInt()))
                .thenReturn(new ObjectMapper().readTree("{\"reserved\":true}"));
        var result = controller.apply(new NodeClockController.ApplyRequest(proposal.ticket()));
        assertEquals("GNSS_USB", result.source());
        assertEquals(.25, ServiceClock.seconds(result.rawAt(), result.trialAt()), .02);
        assertDoesNotThrow(clock::requireAvailable);
    }

    @Test
    void oneGnssUsesRecentlyCalibratedPeer() throws Exception {
        when(peer.exchange(eq("/lnis/api/v1/node/peer/clock"), isNull(), eq(NodeClockController.Probe.class), anyInt()))
                .thenAnswer(invocation -> {
                    Instant now = Instant.now();
                    var stamp = new ServiceClock.Stamp(now, now.plusMillis(100), "GNSS_USB", now,
                            .1, 1, .01, "peer-session", 1, 0);
                    return new NodeClockController.Probe(now, now, stamp, "VALID", false);
                });
        assertEquals("PEER_GNSS", controller.prepare().source());
    }

    @Test
    void noGnssUsesNtpAndFailurePreservesExistingClock() throws Exception {
        try (var ntp = mockStatic(TimeReference.class, CALLS_REAL_METHODS)) {
            ntp.when(() -> TimeReference.ntp("test.invalid")).thenReturn(new TimeReference.NetworkSample(-.2, .02));
            assertEquals("NTP", controller.prepare().source());
            ntp.when(() -> TimeReference.ntp("test.invalid")).thenThrow(new IllegalStateException("offline"));
            assertThrows(IllegalStateException.class, controller::prepare);
            assertEquals("SYSTEM", clock.stamp().source());
        }
    }

    @Test
    void activeTrialsAndUnauthenticatedPeerCannotReserve() {
        when(dtn.managementBusy()).thenReturn(true);
        assertThrows(IllegalStateException.class, controller::prepare);
        assertThrows(IllegalStateException.class, () -> controller.reservation("token",
                new NodeClockController.LockRequest(UUID.randomUUID().toString(), false)));
        verify(auth).authenticate("token");
        assertDoesNotThrow(clock::requireAvailable);
    }

    @Test
    void unreachablePeerBlocksApplyAndReleasesLocalReservation() throws Exception {
        try (var ntp = mockStatic(TimeReference.class, CALLS_REAL_METHODS)) {
            ntp.when(() -> TimeReference.ntp("test.invalid")).thenReturn(new TimeReference.NetworkSample(.01, .02));
            var proposal = controller.prepare();
            when(peer.status()).thenThrow(new IllegalStateException("offline"));
            assertThrows(IllegalStateException.class, () -> controller.apply(new NodeClockController.ApplyRequest(proposal.ticket())));
            assertEquals("SYSTEM", clock.stamp().source());
            assertDoesNotThrow(clock::requireAvailable);
        }
    }
}
