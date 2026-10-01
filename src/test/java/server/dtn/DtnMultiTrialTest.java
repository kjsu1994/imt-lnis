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
        when(repository.findTasksByStateIn(any())).thenAnswer(call -> {
            List<String> states = call.getArgument(0);
            var projections = new org.springframework.data.projection.SpelAwareProxyProjectionFactory();
            return jobs.values().stream().filter(job -> states.contains(job.getState()))
                    .map(job -> projections.createProjection(DtnRepository.TaskView.class, job)).toList();
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
    void bulkStopUsesAllPagesAndProtectsActiveReceivedAndNewTrials() throws Exception {
        for (int i = 0; i < 60; i++) waiting("GNSS_RAW");
        DtnJob requesting = waiting("AFS_METADATA");
        requesting.setSendStatus("REQUESTING");
        DtnJob calculating = waiting("AFS_METADATA");
        calculating.setState("CALCULATING");
        DtnJob received = waiting("GNSS_RAW");
        received.setReceivedAt(Instant.now());
        var summary = service.waitingSummary();
        assertEquals(60, summary.count());
        requesting.setSendStatus("ACCEPTED"); // 확인 뒤 새로 대기 상태가 되어도 목록 밖이면 제외
        DtnJob newer = waiting("GNSS_RAW");
        newer.setCreatedAt(summary.asOf().plusSeconds(1));
        var result = service.cancelWaiting(summary.asOf(), summary.testIds());
        assertEquals(60, result.requested());
        assertEquals(60, result.cancelled());
        assertEquals(0, result.skipped());
        assertEquals("WAITING_DTN", requesting.getState());
        assertEquals("CALCULATING", calculating.getState());
        assertEquals("WAITING_DTN", newer.getState());
        assertNotNull(received.getReceivedAt());
        assertEquals(0, service.cancelWaiting(summary.asOf(), summary.testIds()).cancelled());
        DtnJob stopped = jobs.values().iterator().next();
        receive(stopped, Instant.now());
        assertEquals("CANCELLED", stopped.getState());
        assertNotNull(stopped.getLateReceivedAt());
    }

    @Test
    void bulkStopWaitsForPeerAndPreservesAlreadyReceivedRemoteWork() throws Exception {
        DtnJob job = waiting("GNSS_RAW");
        var link = mock(DtnNodeLink.class);
        when(link.sender()).thenReturn(true);
        service.setNodeLink(link);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var received = new DtnRemoteResult();
        received.setTestId(job.getId());
        received.setState("CALCULATING");
        received.setReceivedAt(Instant.now());
        when(link.closeWaiting(job.getId(), "USER_BULK")).thenAnswer(call -> {
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return received;
        });
        var summary = service.waitingSummary();
        try {
            var result = service.cancelWaiting(summary.asOf(), summary.testIds());
            assertEquals(0, result.cancelled());
            assertEquals(1, result.pending());
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("WAITING_DTN", job.getState());
            assertTrue(job.getCancelPending());
            assertTrue(job.getCancelWaitingOnly());
            assertEquals(0, service.waitingSummary().count());
        } finally {
            release.countDown();
        }
        for (int i = 0; i < 100 && Boolean.TRUE.equals(job.getCancelPending()); i++) Thread.sleep(10);
        assertFalse(job.getCancelPending());
        assertFalse(job.getCancelWaitingOnly());
        assertEquals(received.getReceivedAt(), job.getReceivedAt());
        assertEquals("WAITING_DTN", job.getState());
        verify(link, never()).cancel(any());
    }

    @Test
    void bulkPendingSurvivesRestartAndNeverUsesGeneralCancellation() throws Exception {
        DtnJob job = waiting("GNSS_RAW");
        job.setCancelPending(true);
        job.setCancelWaitingOnly(true);
        when(repository.findByCancelPendingTrue()).thenReturn(List.of(job));
        var link = mock(DtnNodeLink.class);
        when(link.sender()).thenReturn(true);
        when(link.closeWaiting(job.getId(), "USER_BULK")).thenThrow(new IllegalStateException("offline"));
        service.setNodeLink(link);
        new NodeRecoveryService(repository).recover();
        assertTrue(job.getCancelWaitingOnly());
        service.tick();
        verify(link, timeout(2000)).closeWaiting(job.getId(), "USER_BULK");
        assertEquals("WAITING_DTN", job.getState());
        assertTrue(job.getCancelPending());
        verify(link, never()).cancel(any());
    }

    @Test
    void bulkStopRejectsMissingOrDuplicateSnapshotIds() {
        UUID id = waiting("GNSS_RAW").getId();
        assertThrows(IllegalArgumentException.class, () -> service.cancelWaiting(Instant.now(), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.cancelWaiting(Instant.now(), List.of(id, id)));
        assertEquals("WAITING_DTN", jobs.get(id).getState());
    }

    @Test
    void bulkStopRechecksReceiptBeforeCancellingEachTrial() {
        DtnJob job = waiting("GNSS_RAW");
        var summary = service.waitingSummary();
        when(repository.findById(job.getId())).thenAnswer(call -> {
            job.setReceivedAt(Instant.now());
            job.setState("WAITING_RECEIVER");
            return Optional.of(job);
        });
        var result = service.cancelWaiting(summary.asOf(), summary.testIds());
        assertEquals(1, result.requested());
        assertEquals(0, result.cancelled());
        assertEquals(1, result.skipped());
        verify(commands, never()).cancel(any(), any());
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
    void rejectedTransferCleanupNeverCancelsReceivedWork() throws Exception {
        DtnJob waiting = waiting("GNSS_RAW");
        service.closeWaiting(waiting.getId());
        assertEquals("CANCELLED", waiting.getState());
        assertTrue(waiting.getMessage().contains("거절"));
        receive(waiting, Instant.now());
        assertNotNull(waiting.getLateReceivedAt());
        for (String state : List.of("WAITING_DTN", "WAITING_RECEIVER", "CALCULATING", "COMPLETED", "FAILED")) {
            DtnJob received = waiting("GNSS_RAW");
            received.setState(state);
            received.setReceivedAt(Instant.now());
            service.closeWaiting(received.getId());
            assertEquals(state, received.getState());
        }
        verify(commands, never()).cancel(any(), any());
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
    void explicitHttpRejectionClosesOnlyRemoteWaitAndRetainsFailure() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transfers", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            DtnJob job = waiting("GNSS_RAW");
            job.setSendUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/transfers");
            ReflectionTestUtils.setField(service, "sendToken", "");
            var link = mock(DtnNodeLink.class);
            when(link.sender()).thenReturn(true);
            var closed = new DtnRemoteResult();
            closed.setTestId(job.getId());
            closed.setState("CANCELLED");
            when(link.closeWaiting(job.getId())).thenReturn(closed);
            service.setNodeLink(link);
            ReflectionTestUtils.invokeMethod(service, "sendExternal", job.getId(), job.getSentJson());
            verify(link, timeout(2000)).closeWaiting(job.getId());
            for (int i = 0; i < 100 && Boolean.TRUE.equals(job.getCancelPending()); i++) Thread.sleep(10);
            assertEquals("FAILED", job.getState());
            assertEquals("REJECTED", job.getSendStatus());
            assertFalse(Boolean.TRUE.equals(job.getCancelPending()));
            assertTrue(job.getMessage().contains("401"));
            verify(link, never()).cancel(any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cleanupRetriesAfterRestartWithoutFallingBackToNormalCancel() throws Exception {
        DtnJob job = waiting("GNSS_RAW");
        job.setState("FAILED");
        job.setSendStatus("REJECTED");
        job.setCancelPending(true);
        job.setMessage("HTTP 401");
        when(repository.findByCancelPendingTrue()).thenReturn(List.of(job));
        var link = mock(DtnNodeLink.class);
        when(link.sender()).thenReturn(true);
        when(link.closeWaiting(job.getId())).thenThrow(new IllegalStateException("HTTP 404"));
        service.setNodeLink(link);
        service.tick();
        verify(link, timeout(2000)).closeWaiting(job.getId());
        assertTrue(job.getCancelPending());
        verify(link, never()).cancel(any());
        new NodeRecoveryService(repository).recover();
        assertEquals("FAILED", job.getState());
        assertTrue(job.getCancelPending());
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
