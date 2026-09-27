package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import server.gnss.InputBufferService;
import server.management.DataManagementGuard;
import server.node.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class DtnCancellationTest {
    @Test
    void deletedTrialIgnoresLateProgressWithoutRecreatingLogs() {
        DtnRepository repository = mock(DtnRepository.class);
        DtnService service =
                new DtnService(
                        repository,
                        mock(AgentCommandService.class),
                        mock(AgentRepository.class),
                        mock(AgentConnectionRegistry.class),
                        mock(InputBufferService.class),
                        new ObjectMapper());
        var guard = spy(new DataManagementGuard(mock(jakarta.persistence.EntityManager.class)));
        var logs = mock(DtnLogService.class);
        ReflectionTestUtils.setField(service, "managementGuard", guard);
        ReflectionTestUtils.setField(service, "logs", logs);
        UUID id = UUID.randomUUID();
        doAnswer(
                        call -> {
                            assertEquals(1, guard.gate.getReadHoldCount());
                            return true;
                        })
                .when(guard)
                .deleted("DTN", id);

        ReflectionTestUtils.invokeMethod(service, "calculationProgress", id, "PVT", "진행 중");

        verifyNoInteractions(repository, logs);
        assertEquals(0, guard.gate.getReadHoldCount());
    }

    @Test
    void failedTransferCanBeStoppedAndPeerCancellationIsRetried() throws Exception {
        DtnRepository repository = mock(DtnRepository.class);
        DtnService service =
                new DtnService(
                        repository,
                        mock(AgentCommandService.class),
                        mock(AgentRepository.class),
                        mock(AgentConnectionRegistry.class),
                        mock(InputBufferService.class),
                        new ObjectMapper());
        DtnNodeLink link = mock(DtnNodeLink.class);
        when(link.sender()).thenReturn(true);
        service.setNodeLink(link);
        DtnJob job = new DtnJob();
        job.setId(UUID.randomUUID());
        job.setSenderAgentId("sender-1");
        job.setReceiverAgentId("receiver-1");
        job.setState("FAILED");
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.findByCancelPendingTrue()).thenReturn(List.of(job));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        call -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new IllegalStateException("offline");
                            }
                            return null;
                        })
                .when(link)
                .cancel(job.getId());
        assertEquals("CANCELLED", service.cancel(job.getId()).getState());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            service.tick();
            synchronized (service) {
                if (!Boolean.TRUE.equals(job.getCancelPending())) {
                    break;
                }
            }
            Thread.sleep(20);
        }
        synchronized (service) {
            assertFalse(job.getCancelPending());
        }
        assertEquals(2, calls.get());
        service.cancel(job.getId());
        assertEquals("CANCELLED", job.getState());
        job.setState("COMPLETED");
        service.cancel(job.getId());
        assertEquals("COMPLETED", job.getState());
    }
}
