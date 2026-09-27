package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import server.common.DtnModels;
import server.common.LnisModels.*;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class DtnWorkerCancellationTest {
    @Test
    void cancellationRejectsLateInputAndWrongRole() {
        var processor = mock(DtnProcessor.class);
        var state = new AtomicReference<>(AgentState.READY);
        try (var worker = new DtnWorker(processor, new ObjectMapper(), AgentRole.SENDER, state)) {
            UUID id = UUID.randomUUID();
            worker.cancel(id);
            worker.prepare(id, new byte[] {1}, false, (s, m) -> {}, r -> fail("Cancelled result"));
            assertFalse(worker.active());
            verifyNoInteractions(processor);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            worker.receive(
                                    UUID.randomUUID(),
                                    new DtnModels.Transfer(),
                                    null,
                                    (s, m) -> {},
                                    r -> {}));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            worker.prepare(
                                    UUID.randomUUID(),
                                    new byte[DtnModels.MAX_INPUT_BYTES + 1],
                                    false,
                                    (s, m) -> {},
                                    r -> {}));
        }
    }

    @Test
    void nativeCallFinishesSafelyButCancelledResultIsNeverPublished() throws Exception {
        var state = new AtomicReference<>(AgentState.READY);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var output = new AtomicInteger();
        var processor = mock(DtnProcessor.class);
        when(processor.prepare(any(), any(), anyBoolean(), any()))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            while (true) {
                                try {
                                    release.await();
                                    break;
                                } catch (InterruptedException ignored) {
                                    // 네이티브 호출은 인터럽트로 즉시 종료되지 않는다.
                                }
                            }
                            return new DtnModels.AgentResult();
                        });
        var worker = new DtnWorker(processor, new ObjectMapper(), AgentRole.SENDER, state);
        UUID id = UUID.randomUUID();
        try {
            worker.prepare(id, new byte[] {1}, false, (s, m) -> {}, r -> output.incrementAndGet());
            assertTrue(started.await(3, TimeUnit.SECONDS));
            worker.cancel(UUID.randomUUID());
            assertTrue(worker.active());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            worker.prepare(
                                    UUID.randomUUID(),
                                    new byte[] {1},
                                    false,
                                    (s, m) -> {},
                                    r -> {}));
            worker.cancel(id);
            assertEquals(AgentState.BUSY, state.get());
        } finally {
            release.countDown();
            worker.close();
        }
        assertFalse(worker.active());
        assertEquals(AgentState.READY, state.get());
        assertEquals(0, output.get());
    }
}
