package server.agent.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import server.agent.afs.AfsRawFragmentCodec;
import server.agent.codec.NativeAfsCodec;
import server.shared.codec.GrawCodec;
import server.shared.model.AgentProtocol.*;
import server.shared.model.LnisModels.*;

@Timeout(15)
class AfsCancellationTest {
  private static final AfsSessionService.SessionCommand COMMAND =
      new AfsSessionService.SessionCommand("sender", "receiver", UUID.randomUUID(),
          new AfsSettings(1), new TestOptions(TestType.TEST_A_NORMAL, 0, 0, 0, Map.of()));

  @Test void cancellationDuringEncodingStopsOldFramesAndAllowsNextSession() throws Exception {
    var codec = mock(NativeAfsCodec.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(codec.encode(anyInt(), any(), any(), any())).thenAnswer(call -> {
      if (calls.incrementAndGet() == 1) { entered.countDown(); awaitNative(release); }
      return new byte[750];
    });
    var executor = Executors.newSingleThreadExecutor();
    try (var service = new AfsSessionService(codec, executor)) {
      var results = new CopyOnWriteArrayList<RoleResult>();
      var oldTransfer = mock(AfsSessionService.TransferSink.class);
      UUID oldId = UUID.randomUUID(), nextId = UUID.randomUUID();
      service.send(oldId, COMMAND, input(80), (t, p) -> {}, e -> {}, results::add, oldTransfer);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      service.cancel();
      service.send(nextId, COMMAND, input(1), (t, p) -> {}, e -> {}, results::add,
          mock(AfsSessionService.TransferSink.class));
      release.countDown();
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(List.of(nextId), results.stream().map(RoleResult::sessionId).toList());
      assertEquals(Verdict.PASS, results.getFirst().verdict());
      int nextFrames = (AfsRawFragmentCodec.fragment(0, record()).size() + 1) / 2;
      assertEquals(1 + nextFrames, calls.get());
      verifyNoInteractions(oldTransfer);
    } finally { release.countDown(); }
  }

  @Test void cancellationAfterFirstBatchSuppressesRemainingBatchesAndCompletion() throws Exception {
    var codec = mock(NativeAfsCodec.class);
    when(codec.encode(anyInt(), any(), any(), any())).thenAnswer(call -> new byte[750]);
    var executor = Executors.newSingleThreadExecutor();
    try (var service = new AfsSessionService(codec, executor)) {
      var transfer = mock(AfsSessionService.TransferSink.class);
      var results = new CopyOnWriteArrayList<RoleResult>();
      doAnswer(call -> { service.cancel(); return null; }).when(transfer).batch(any());
      service.send(UUID.randomUUID(), COMMAND, input(80), (t, p) -> {}, e -> {}, results::add, transfer);
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      verify(transfer, times(1)).batch(any());
      verify(transfer, never()).complete(any());
      assertTrue(results.isEmpty());
    }
  }

  @Test void cancellationDuringNativeDecodeSuppressesOldResultAndKeepsNextReceiver() throws Exception {
    var codec = mock(NativeAfsCodec.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    when(codec.decode(anyInt(), any())).thenAnswer(call -> {
      if (calls.incrementAndGet() == 1) { entered.countDown(); awaitNative(release); }
      return new NativeAfsCodec.Decoded(new byte[1176], new byte[846], new byte[846],
          false, false, false, 0, 0, 0);
    });
    when(codec.encode(anyInt(), any(), any(), any())).thenReturn(new byte[750]);
    var executor = Executors.newSingleThreadExecutor();
    try (var service = new AfsSessionService(codec, executor)) {
      var results = new CopyOnWriteArrayList<RoleResult>();
      var evidence = new CopyOnWriteArrayList<FrameEvidenceMessage>();
      UUID oldId = UUID.randomUUID(), nextId = UUID.randomUUID();
      receive(service, oldId, results, evidence);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      service.cancel();
      receive(service, nextId, results, evidence);
      release.countDown();
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(3, calls.get(), "Only one old frame and two new frames may be decoded");
      assertEquals(List.of(nextId), results.stream().map(RoleResult::sessionId).toList());
      assertEquals(2, evidence.size(), "Cancelled decoding must not publish frame evidence");
    } finally { release.countDown(); }
  }

  private static void receive(AfsSessionService service, UUID id, List<RoleResult> results,
      List<FrameEvidenceMessage> evidence) {
    service.arm(id, COMMAND, (t, p) -> {}, evidence::add, results::add);
    service.receiveStart(id, new AfsTransferStart("sender", "receiver", 1, "0".repeat(64),
        1, 2, 1, TestType.TEST_A_NORMAL, 0, 0, 0, 0));
    service.receiveBatch(id, new AfsTransferBatch("sender", "receiver", List.of(
        new AfsTransferFrame(0, 1, 2400, 0, 0, new byte[750]),
        new AfsTransferFrame(1, 1, 2400, 0, 1, new byte[750]))));
    service.receiveComplete(id, new AfsTransferComplete("sender", "receiver", 2));
  }

  // Native calls cannot be interrupted mid-call; cancellation must be checked on return.
  private static void awaitNative(CountDownLatch release) {
    boolean interrupted = false;
    while (true) {
      try { release.await(); break; }
      catch (InterruptedException ignored) { interrupted = true; }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static byte[] record() {
    return GrawCodec.encode(new GrawCodec.Envelope(new UUID(0, 1), new UUID(0, 2), 0,
        Instant.parse("2026-01-01T00:00:00Z"),
        new GrawCodec.ObservationEpoch(123, 2400, 18, 1, 1, List.of())));
  }

  private static byte[] input(int count) {
    var bytes = new ByteArrayOutputStream();
    byte[] record = record();
    for (int i = 0; i < count; i++) {
      bytes.writeBytes(ByteBuffer.allocate(4).putInt(record.length).array());
      bytes.writeBytes(record);
    }
    return bytes.toByteArray();
  }
}
