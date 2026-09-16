package server.agent.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import server.shared.model.DtnModels;
import server.shared.model.LnisModels.*;

class DtnWorkerCancellationTest {
  @Test
  void cancelledChunksDoNotRestartAndOldCancellationCannotStopNewJob() {
    var json = new ObjectMapper(); var state = new AtomicReference<>(AgentState.READY);
    DtnWorker worker = new DtnWorker(mock(DtnProcessor.class), json, AgentRole.SENDER, state, (id, value) -> {});
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    var chunk = json.createObjectNode().put("mode", "PREPARE").put("index", 0).put("last", false).put("dataBase64", "AQ==");
    worker.accept(first, chunk); assertEquals(AgentState.BUSY, state.get());
    worker.accept(first, json.createObjectNode().put("mode", "CANCEL"));
    assertEquals(AgentState.READY, state.get());
    worker.accept(first, chunk); assertFalse(worker.active());
    worker.accept(second, chunk); worker.cancel(first);
    assertTrue(worker.active()); assertEquals(AgentState.BUSY, state.get());
    worker.cancel(second); assertEquals(AgentState.READY, state.get());
  }

  @Test
  void nativeCallFinishesSafelyButItsCancelledResultIsNeverPublished() throws Exception {
    var json = new ObjectMapper(); var state = new AtomicReference<>(AgentState.READY);
    var started = new CountDownLatch(1); var release = new CountDownLatch(1);
    var output = new AtomicInteger(); var processor = mock(DtnProcessor.class);
    when(processor.prepare(any(), any(), anyBoolean(), any())).thenAnswer(call -> {
      started.countDown();
      while (true) { try { release.await(); break; } catch (InterruptedException ignored) { /* Simulate a native call. */ } }
      return new DtnModels.AgentResult();
    });
    DtnWorker worker = new DtnWorker(processor, json, AgentRole.SENDER, state, (id, value) -> output.incrementAndGet());
    UUID id = UUID.randomUUID();
    try {
      worker.accept(id, json.createObjectNode().put("mode", "PREPARE").put("index", 0).put("last", true).put("dataBase64", "AQ=="));
      assertTrue(started.await(3, TimeUnit.SECONDS)); worker.cancel(id);
      assertEquals(AgentState.BUSY, state.get(), "Native memory must not be reused while the call still runs");
    } finally { release.countDown(); }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (worker.active() && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse(worker.active()); assertEquals(AgentState.READY, state.get()); assertEquals(0, output.get());
  }
}
