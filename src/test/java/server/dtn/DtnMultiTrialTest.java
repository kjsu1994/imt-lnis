package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import server.gnss.InputBufferService;
import server.node.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

class DtnMultiTrialTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DtnRepository repository = mock(DtnRepository.class);
    private final AgentCommandService commands = mock(AgentCommandService.class);
    private final DtnService service = new DtnService(repository, commands,
            mock(AgentRepository.class), mock(AgentConnectionRegistry.class),
            mock(InputBufferService.class), mapper);
    private final Map<UUID, DtnJob> jobs = new LinkedHashMap<>();

    DtnMultiTrialTest() {
        ReflectionTestUtils.setField(service, "receiveToken", "test-token");
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(jobs.get(call.getArgument(0))));
        when(repository.existsByState(any())).thenAnswer(call -> jobs.values().stream()
                .anyMatch(job -> job.getState().equals(call.getArgument(0))));
        when(repository.existsByStateInAndSendStatusIn(any(), any())).thenAnswer(call -> {
            List<String> states = call.getArgument(0), statuses = call.getArgument(1);
            return jobs.values().stream().anyMatch(job -> states.contains(job.getState())
                    && job.getSendStatus() != null && statuses.contains(job.getSendStatus()));
        });
        when(repository.findByStateIn(any())).thenAnswer(call -> {
            List<String> states = call.getArgument(0);
            return jobs.values().stream().filter(job -> states.contains(job.getState())).toList();
        });
    }

    private DtnJob waiting(String type) {
        DtnJob job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setTestType(type);
        job.setState("WAITING_DTN");
        job.setSendStatus("ACCEPTED");
        job.setSenderAgentId("sender-1");
        job.setReceiverAgentId("receiver-1");
        job.setCreatedAt(Instant.now().minusSeconds(86400));
        job.setSentJson("{\"testId\":\"" + job.getId() + "\"}");
        jobs.put(job.getId(), job);
        return job;
    }

    private void receive(DtnJob job, Instant arrival) throws Exception {
        service.receive("Bearer test-token", job.getSentJson().getBytes(StandardCharsets.UTF_8), arrival);
    }

    @Test
    void lateOutOfOrderReceiptsPreserveEachFirstArrival() throws Exception {
        DtnJob a = waiting("AFS_METADATA"), b = waiting("GNSS_RAW"), c = waiting("AFS_METADATA");
        service.tick();
        assertFalse(service.sendBusy());
        Instant arrival = Instant.now();
        receive(c, arrival);
        receive(a, arrival.plusSeconds(1));
        receive(b, arrival.plusSeconds(2));
        receive(c, arrival.plusSeconds(3));
        for (DtnJob job : jobs.values()) {
            assertEquals("WAITING_RECEIVER", job.getState());
            assertNotNull(job.getReceivedRawJson());
        }
        assertEquals(arrival, c.getReceivedAt());
        assertEquals(arrival.plusSeconds(1), a.getReceivedAt());
        assertEquals(arrival.plusSeconds(2), b.getReceivedAt());
        service.tick();
        assertEquals("WAITING_RECEIVER", c.getState());
    }

    @Test
    void adapterRequestBlocksNewSendButFinalReceiptWaitDoesNot() {
        DtnJob a = waiting("AFS_METADATA");
        assertFalse(service.sendBusy());
        a.setSendStatus("REQUESTING");
        assertTrue(service.sendBusy());
        a.setSendStatus("UNKNOWN");
        assertFalse(service.sendBusy());
        a.setState("PREPARING");
        assertTrue(service.sendBusy());
    }

    @Test
    void invalidReceiptDoesNotPreventCorrectRetry() throws Exception {
        DtnJob job = waiting("GNSS_RAW");
        byte[] changed = job.getSentJson().replace("}", ",\"unexpected\":true}").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> service.receive("Bearer test-token", changed));
        service.rejectReceipt(job.getId(), "hash mismatch");
        assertEquals("WAITING_DTN", job.getState());
        receive(job, Instant.now());
        assertEquals("WAITING_RECEIVER", job.getState());
    }

    @Test
    void cancellationOnlyAffectsSelectedTrialAndLateArrivalNeverRestartsIt() throws Exception {
        DtnJob a = waiting("AFS_METADATA"), b = waiting("GNSS_RAW");
        service.cancel(a.getId());
        Instant arrival = Instant.now();
        receive(a, arrival);
        assertEquals("CANCELLED", a.getState());
        assertEquals(arrival, a.getLateReceivedAt());
        assertNull(a.getReceivedJson());
        assertNull(a.getReceivedAt());
        assertEquals("WAITING_DTN", b.getState());
        receive(b, arrival);
        assertEquals("WAITING_RECEIVER", b.getState());
        verify(commands, never()).cancel(any(), eq(b.getId()));
    }

    @Test
    void timeoutCountsOnlyActualWorkAndIqDeliveryDoesNotExpire() {
        DtnJob iq = waiting("IQ_SAMPLE");
        DtnJob work = waiting("AFS_METADATA");
        work.setState("CALCULATING");
        work.setStageStartedAt(Instant.now());
        service.tick();
        assertEquals("WAITING_DTN", iq.getState());
        assertEquals("CALCULATING", work.getState());
        work.setStageStartedAt(Instant.now().minusSeconds(601));
        service.tick();
        assertEquals("FAILED", work.getState());
        verify(commands).cancel("receiver-1", work.getId());
        assertEquals("WAITING_DTN", iq.getState());
    }

    @Test
    void restartPreservesWaitingAndReleasesUnknownAdapterRequest() {
        DtnJob job = waiting("GNSS_RAW");
        job.setSendStatus("REQUESTING");
        new NodeRecoveryService(repository).recover();
        assertEquals("WAITING_DTN", job.getState());
        assertEquals("UNKNOWN", job.getSendStatus());
        assertFalse(service.sendBusy());
    }

    @Test
    void slowPeerQueriesDoNotHoldTrialLockAndConcurrencyIsBounded() throws Exception {
        waiting("AFS_METADATA"); waiting("GNSS_RAW"); waiting("IQ_SAMPLE");
        var link = mock(DtnNodeLink.class);
        when(link.sender()).thenReturn(true);
        service.setNodeLink(link);
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(link.result(any())).thenAnswer(call -> {
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        });
        try {
            service.tick();
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), service::tick);
            assertFalse(service.sendBusy());
            verify(link, times(2)).result(any());
        } finally {
            release.countDown();
        }
    }
}
