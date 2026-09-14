package server.central.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import server.central.agent.AgentEntity;
import server.central.agent.AgentRepository;
import server.central.frameevidence.FrameEvidenceRepository;
import server.central.input.GrawFileStorage;
import server.central.input.InputBufferService;
import server.central.realtime.RealtimeEventRepository;
import server.central.realtime.EventService;
import server.central.session.TestSessionEntity;
import server.central.session.ActiveSessionLockRepository;
import server.central.session.SessionRepository;
import server.shared.codec.GrawCodec;
import server.shared.model.AgentProtocol.FrameEvidenceMessage;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.AgentState;
import server.shared.model.LnisModels.InputKind;
import server.shared.model.LnisModels.SessionState;
import server.shared.model.LnisModels.TestType;
import server.shared.model.LnisModels.Verdict;

/** Redis 제거 후 핵심 메타데이터, BLOB 증거, GRAW 파일 저장이 함께 동작하는지 검증한다. */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:lnis-persistence;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "lnis.storage.data-directory=${java.io.tmpdir}/lnis-server-tests",
      "lnis.storage.cleanup-delay=PT24H"
    })
@ActiveProfiles("server")
class H2PersistenceIntegrationTest {
  @Autowired private AgentRepository agents;
  @Autowired private FrameEvidenceRepository evidence;
  @Autowired private ActiveSessionLockRepository locks;
  @Autowired private InputBufferService inputs;
  @Autowired private GrawFileStorage files;
  @Autowired private RealtimeEventRepository realtimeEvents;
  @Autowired private EventService eventService;
  @Autowired private SessionRepository sessions;
  @Autowired private server.central.dtn.DtnLogService dtnLogs;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void dtnPreparationSnapshotsSurviveExpiryAndRemainLocal() {
    UUID input=UUID.randomUUID(), trial=UUID.randomUUID(), second=UUID.randomUUID();
    dtnLogs.add(input,"INPUT","PVT",true,"계산 시작");
    dtnLogs.add(input,"INPUT","WARN","PVT",false,"항법정보 부족");
    dtnLogs.copy(input,trial,"TEST");
    dtnLogs.copy(input,second,"TEST");
    assertEquals(2,dtnLogs.read(trial,0).size());
    assertEquals(2,dtnLogs.read(second,0).size());
    var first=dtnLogs.read(input,0).getFirst();
    assertEquals(1,dtnLogs.read(input,first.getSequence()).size());
    assertEquals(first.getOccurredAt(),dtnLogs.read(trial,0).getFirst().getOccurredAt());
    jdbc.update("update dtn_log set occurred_at=? where scope_id in (?,?,?)",
        java.sql.Timestamp.from(Instant.now().minusSeconds(8*86400)),input,trial,second);
    dtnLogs.cleanup();
    assertTrue(dtnLogs.read(input,0).isEmpty());
    assertEquals(2,dtnLogs.read(trial,0).size());
    assertTrue(dtnLogs.read(UUID.randomUUID(),0).isEmpty());
  }

  @Test
  void failedInputValidationKeepsDiagnosticLogAfterRollback() {
    var input=inputs.create("broken.graw",3,InputKind.GRAW_UPLOAD);
    dtnLogs.add(input.inputId(),"INPUT","파일 적용",false,"시작");
    inputs.append(input.inputId(),0,new byte[]{1,2,3});
    assertThrows(RuntimeException.class,()->inputs.complete(input.inputId()));
    assertTrue(dtnLogs.read(input.inputId(),0).stream().anyMatch(e->"ERROR".equals(e.getLevel())));
    dtnLogs.add(input.inputId(),"INPUT","ERROR","인증",false,"Bearer secret-token\nnext line");
    assertFalse(dtnLogs.read(input.inputId(),0).getLast().getMessage().contains("secret-token"));
    inputs.remove(input.inputId());
  }

  @Test
  void storesAgentAndMaintainsSingleActiveSessionLock() {
    var agent =
        new AgentEntity(
            "sender-integration",
            AgentRole.SENDER,
            AgentState.READY,
            Instant.now(),
            "test",
            1,
            "Windows",
            "amd64",
            List.of("192.0.2.10"),
            null);
    agents.save(agent);

    assertEquals(List.of("192.0.2.10"), agents.find(agent.agentId()).orElseThrow().ipv4Addresses());

    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    assertTrue(locks.tryAcquire(first));
    assertFalse(locks.tryAcquire(second));
    assertEquals(first, locks.current().orElseThrow());
    locks.release(second);
    assertEquals(first, locks.current().orElseThrow());
    locks.release(first);
    assertTrue(locks.current().isEmpty());
  }

  @Test
  void persistsIncreasingEventSequenceAndSessionStream() {
    UUID sessionId = UUID.randomUUID();
    var first =
        eventService.publish(
            server.shared.model.AgentProtocol.EventType.SESSION_STATUS,
            null,
            null,
            sessionId,
            "first");
    var second =
        eventService.publish(
            server.shared.model.AgentProtocol.EventType.RESULT, null, null, sessionId, "second");

    assertTrue(second.sequence() > first.sequence());
    assertEquals(2, realtimeEvents.count(sessionId.toString()));
  }

  @Test
  void storesFrameEvidenceAsBlobAndUpdatesRetransmission() {
    UUID sessionId = UUID.randomUUID();
    evidence.save(sessionId, AgentRole.SENDER, frame("first"));
    evidence.save(sessionId, AgentRole.SENDER, frame("retransmitted"));

    var stored = evidence.find(sessionId, AgentRole.SENDER, 0).orElseThrow();
    assertEquals("retransmitted", stored.evidence().note());
    assertArrayEquals(new byte[750], stored.evidence().referenceFrame());
    assertEquals(1, evidence.findAll(sessionId).size());
  }

  @Test
  void storesGrawBytesInFileAndMetadataInH2() {
    var input = inputs.create("integration.graw", 3, InputKind.GRAW_UPLOAD);
    inputs.append(input.inputId(), 0, new byte[] {1, 2, 3});

    assertArrayEquals(new byte[] {1, 2, 3}, inputs.chunk(input.inputId(), 0));
    assertEquals(3, inputs.get(input.inputId()).receivedSize());

    inputs.remove(input.inputId());
    assertThrows(IllegalArgumentException.class, () -> inputs.get(input.inputId()));
  }

  @Test
  void explicitlyRemovesInputReferencedBySessionLikeBaseline() {
    var input = inputs.create("explicit-remove.graw", 3, InputKind.GRAW_UPLOAD);
    UUID sessionId = UUID.randomUUID();
    Instant now = Instant.now();
    sessions.save(
        new TestSessionEntity(
            sessionId,
            SessionState.CREATED,
            TestType.TEST_A_NORMAL,
            "sender",
            "receiver",
            input.inputId(),
            0,
            "created",
            Verdict.INCONCLUSIVE,
            "{}",
            now,
            now));

    inputs.remove(input.inputId());

    assertThrows(IllegalArgumentException.class, () -> inputs.get(input.inputId()));
    sessions.delete(sessionId);
  }

  @Test
  void completesValidGrawAndReadsChunksFromFinalFile() {
    byte[] graw = validGraw();
    var input = inputs.create("complete-integration.graw", graw.length, InputKind.GRAW_UPLOAD);
    inputs.append(input.inputId(), 0, graw);
    var completed = inputs.complete(input.inputId());

    assertTrue(completed.complete());
    assertEquals(1, completed.recordCount());
    assertNotNull(completed.sha256());
    assertArrayEquals(graw, inputs.chunk(input.inputId(), 0));
    inputs.remove(input.inputId());
  }

  @Test
  void retriesCompletionAfterFileWasRenamedBeforeDatabaseCommit() {
    byte[] graw = validGraw();
    var input = inputs.create("interrupted-complete.graw", graw.length, InputKind.GRAW_UPLOAD);
    inputs.append(input.inputId(), 0, graw);
    files.complete(input.inputId());

    var completed = inputs.complete(input.inputId());

    assertTrue(completed.complete());
    assertArrayEquals(graw, inputs.chunk(input.inputId(), 0));
    inputs.remove(input.inputId());
  }

  private static byte[] validGraw() {
    byte[] record =
        GrawCodec.encode(
            new GrawCodec.Envelope(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                new GrawCodec.ReceiverMetadata("F9P", "1", "COM3", 115200, "test")));
    return ByteBuffer.allocate(record.length + Integer.BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(record.length)
        .put(record)
        .array();
  }

  private static FrameEvidenceMessage frame(String note) {
    return new FrameEvidenceMessage(
        0,
        new byte[750],
        null,
        null,
        null,
        List.of(),
        false,
        false,
        false,
        false,
        false,
        0,
        0,
        0,
        false,
        null,
        null,
        note);
  }
}
