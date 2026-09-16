package server.agent.dtn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import server.shared.codec.DtnChunks;
import server.shared.model.DtnModels;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.AgentState;

/** 조각 전송을 검증한 뒤 전용 작업 스레드에서 네이티브 계산을 실행한다. */
public final class DtnWorker {
  private final DtnProcessor processor;
  private final ObjectMapper json;
  private final AgentRole role;
  private final AtomicReference<AgentState> state;
  private final BiConsumer<UUID, JsonNode> output;
  private UUID active;
  private String mode;
  private DtnChunks chunks;
  private long touched;
  private Thread worker;
  private final java.util.LinkedHashSet<UUID> cancelled = new java.util.LinkedHashSet<>();

  public DtnWorker(DtnProcessor processor, ObjectMapper json, AgentRole role,
      AtomicReference<AgentState> state, BiConsumer<UUID, JsonNode> output) {
    this.processor = processor; this.json = json; this.role = role; this.state = state; this.output = output;
  }

  public synchronized void accept(UUID id, JsonNode args) {
    String requested = args.path("mode").asText();
    if ("CANCEL".equals(requested)) { cancel(id); return; }
    if (cancelled.contains(id)) return;
    if (!(("PREPARE".equals(requested) || "PREPARE_RAW".equals(requested)) && role == AgentRole.SENDER)
        && !("RECEIVE".equals(requested) && role == AgentRole.RECEIVER))
      throw new IllegalArgumentException("DTN 작업과 Agent 역할이 다릅니다.");
    if (active == null) {
      if (!state.compareAndSet(AgentState.READY, AgentState.BUSY))
        throw new IllegalStateException("Agent가 다른 작업을 수행 중입니다.");
      active = id; mode = requested; chunks = new DtnChunks();
    }
    if (!id.equals(active) || !mode.equals(requested)) throw new IllegalStateException("다른 DTN 작업 진행 중");
    if (chunks == null) throw new IllegalStateException("DTN 계산이 이미 시작되었습니다.");
    touched = System.nanoTime();
    try {
      byte[] complete = chunks.append(args.path("index").asInt(-1), args.path("last").asBoolean(),
          Base64.getDecoder().decode(args.path("dataBase64").asText()));
      if (complete != null) {
        chunks = null;
        worker = Thread.ofVirtual().name("dtn-pvt").unstarted(() -> process(id, requested, complete));
        worker.start();
      }
    } catch (Exception e) {
      active = null; chunks = null; state.set(AgentState.READY);
      sendError(id, e);
    }
  }

  /** 미완료 조각 전송만 만료시킨다. 실행 중인 네이티브 계산의 메모리를 강제 해제하지 않는다. */
  public synchronized void expire() {
    if (active != null && chunks != null && System.nanoTime()-touched > 60_000_000_000L) {
      UUID id = active; active = null; chunks = null; state.set(AgentState.READY);
      sendError(id, new IllegalStateException("DTN 입력 전송 시간 초과"));
    }
  }

  public synchronized void cancel(UUID id) {
    cancelled.add(id);
    if (cancelled.size() > 256) cancelled.removeFirst();
    if (!id.equals(active)) return;
    if (worker != null) {
      // 네이티브 호출의 메모리를 강제로 해제하지 않는다. 반환 후 다음 단계와 결과 전송을 막는다.
      worker.interrupt();
    } else {
      active = null; chunks = null; state.set(AgentState.READY);
    }
  }

  private synchronized void checkCancelled(UUID id) {
    if (cancelled.contains(id) || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException("DTN 시험 중지");
  }

  public synchronized boolean active() { return active != null; }

  private void process(UUID id, String requested, byte[] data) {
    try {
      checkCancelled(id);
      java.util.function.BiConsumer<String,String> progress=(stage,message)-> {
        checkCancelled(id);
        output.accept(id, json.createObjectNode().set("progress",json.createObjectNode().put("stage",stage).put("message",message)));
      };
      DtnModels.AgentResult result = requested.startsWith("PREPARE") ? processor.prepare(id, data, "PREPARE_RAW".equals(requested),progress)
          : processor.receive(id, json.readValue(data, DtnModels.Transfer.class),progress);
      send(id, result);
    } catch (Exception | LinkageError e) { sendError(id, new IllegalStateException(e)); }
    finally {
      synchronized (this) { if (id.equals(active)) { active = null; chunks = null; worker = null; state.set(AgentState.READY); } }
    }
  }

  private void sendError(UUID id, Exception error) {
    DtnModels.AgentResult result = new DtnModels.AgentResult();
    result.setError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    send(id, result);
  }

  private void send(UUID id, DtnModels.AgentResult result) {
    synchronized (this) { if (cancelled.contains(id)) return; }
    try {
      byte[] bytes = json.writeValueAsBytes(result);
      if (bytes.length > DtnModels.MAX_JSON_BYTES) throw new IllegalArgumentException("DTN 결과 크기 초과");
      for (int offset = 0, index = 0; offset < bytes.length; offset += DtnChunks.CHUNK_BYTES, index++) {
        synchronized (this) { if (cancelled.contains(id)) return; }
        int end = Math.min(bytes.length, offset+DtnChunks.CHUNK_BYTES);
        output.accept(id, json.createObjectNode().put("index", index).put("last", end == bytes.length)
            .put("dataBase64", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, end))));
      }
    } catch (Exception e) { throw new IllegalStateException("DTN 결과 전달 실패", e); }
  }
}
