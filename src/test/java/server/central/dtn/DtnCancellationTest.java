package server.central.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import server.central.agent.*;
import server.central.input.InputBufferService;

class DtnCancellationTest {
    @Test
    void failedTransferCanBeStoppedAndPeerCancellationIsRetried() throws Exception {
        DtnRepository repository = mock(DtnRepository.class);
        DtnService service = new DtnService(repository, mock(AgentCommandService.class), mock(AgentRepository.class),
                mock(AgentConnectionRegistry.class), mock(InputBufferService.class), new ObjectMapper());
        DtnNodeLink link = mock(DtnNodeLink.class); when(link.sender()).thenReturn(true); service.setNodeLink(link);
        DtnJob job = new DtnJob(); job.setId(UUID.randomUUID()); job.setSenderAgentId("sender-1"); job.setReceiverAgentId("receiver-1");
        job.setState("FAILED");
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.findByCancelPendingTrue()).thenReturn(List.of(job));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> { if (calls.incrementAndGet() == 1) throw new IllegalStateException("offline"); return null; }).when(link).cancel(job.getId());
        assertEquals("CANCELLED", service.cancel(job.getId()).getState());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            service.tick();
            synchronized(service) { if (!Boolean.TRUE.equals(job.getCancelPending())) break; }
            Thread.sleep(20);
        }
        synchronized(service) { assertFalse(job.getCancelPending()); }
        assertEquals(2, calls.get());
        service.cancel(job.getId()); assertEquals("CANCELLED", job.getState());
        job.setState("COMPLETED"); service.cancel(job.getId()); assertEquals("COMPLETED", job.getState());
    }
}
