package server.dtn;

import com.fasterxml.jackson.databind.ObjectMapper;

import server.common.DtnModels;
import server.common.DtnModels.AgentResult;
import server.common.DtnModels.Transfer;
import server.common.LnisModels.AgentRole;
import server.common.LnisModels.AgentState;
import server.pvt.DtnDelay;

import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 로컬 데이터를 직접 전달받아 전용 스레드에서 계산한다. 네이티브 호출 중에는 자원을 재사용하지 않는다. */
public final class DtnWorker implements AutoCloseable {
    private final DtnProcessor processor;
    private final ObjectMapper json;
    private final AgentRole role;
    private final AtomicReference<AgentState> state;
    private final LinkedHashSet<UUID> cancelled = new LinkedHashSet<>();
    private UUID active;
    private Thread worker;
    private boolean closed;

    public DtnWorker(
            DtnProcessor processor,
            ObjectMapper json,
            AgentRole role,
            AtomicReference<AgentState> state) {
        this.processor = processor;
        this.json = json;
        this.role = role;
        this.state = state;
    }

    public void prepare(
            UUID id,
            byte[] data,
            boolean raw,
            BiConsumer<String, String> progress,
            Consumer<AgentResult> output) {
        prepare(id, data, raw, null, progress, output);
    }

    public void prepare(UUID id, byte[] data, boolean raw, java.time.Instant startedAt,
            BiConsumer<String, String> progress, Consumer<AgentResult> output) {
        prepare(id, data, raw, startedAt, server.pvt.PvtConstellation.GPS, progress, output);
    }

    public void prepare(UUID id, byte[] data, boolean raw, java.time.Instant startedAt,
            server.pvt.PvtConstellation constellation,
            BiConsumer<String, String> progress, Consumer<AgentResult> output) {
        if (role != AgentRole.SENDER) {
            throw new IllegalArgumentException("DTN 작업과 실행기 역할이 다릅니다.");
        }
        if (data.length == 0 || data.length > DtnModels.MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("DTN 수집 입력은 1 MiB 이하로 제한됩니다.");
        }
        byte[] snapshot = data.clone();
        if (constellation == null || constellation == server.pvt.PvtConstellation.GPS) {
            submit(id, () -> processor.prepare(id, snapshot, raw, startedAt, progress(id, progress)), output);
        } else {
            submit(id, () -> processor.prepare(id, snapshot, raw, startedAt, progress(id, progress), constellation), output);
        }
    }

    public void receive(
            UUID id,
            Transfer transfer,
            DtnDelay.Timing timing,
            BiConsumer<String, String> progress,
            Consumer<AgentResult> output) {
        if (role != AgentRole.RECEIVER) {
            throw new IllegalArgumentException("DTN 작업과 실행기 역할이 다릅니다.");
        }
        submit(id, () -> processor.receive(id, transfer, progress(id, progress), timing), output);
    }

    private synchronized void submit(
            UUID id, Callable<AgentResult> action, Consumer<AgentResult> output) {
        if (closed) {
            throw new IllegalStateException("실행기가 종료되었습니다.");
        }
        if (cancelled.contains(id)) {
            return;
        }
        if (active != null || !state.compareAndSet(AgentState.READY, AgentState.BUSY)) {
            throw new IllegalStateException("다른 작업 진행 중");
        }
        active = id;
        worker = Thread.ofVirtual().name("dtn-pvt").unstarted(() -> process(id, action, output));
        worker.start();
    }

    private BiConsumer<String, String> progress(UUID id, BiConsumer<String, String> output) {
        return (stage, message) -> {
            checkCancelled(id);
            output.accept(stage, message);
        };
    }

    private void process(UUID id, Callable<AgentResult> action, Consumer<AgentResult> output) {
        try {
            checkCancelled(id);
            AgentResult result = action.call();
            // 내부 청크를 없애더라도 결과 크기 제한은 유지한다.
            if (json.writeValueAsBytes(result).length > DtnModels.MAX_JSON_BYTES) {
                throw new IllegalArgumentException("DTN 결과 크기 초과");
            }
            checkCancelled(id);
            output.accept(result);
        } catch (Exception | LinkageError error) {
            if (!isCancelled(id)) {
                AgentResult result = new AgentResult();
                result.setError(
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage());
                output.accept(result);
            }
        } finally {
            synchronized (this) {
                if (id.equals(active)) {
                    active = null;
                    worker = null;
                    state.set(AgentState.READY);
                }
            }
        }
    }

    public synchronized void cancel(UUID id) {
        cancelled.add(id);
        if (cancelled.size() > 256) {
            cancelled.removeFirst();
        }
        if (id.equals(active) && worker != null) {
            worker.interrupt();
        }
    }

    private synchronized boolean isCancelled(UUID id) {
        return closed || cancelled.contains(id) || Thread.currentThread().isInterrupted();
    }

    private void checkCancelled(UUID id) {
        if (isCancelled(id)) {
            throw new java.util.concurrent.CancellationException("DTN 시험 중지");
        }
    }

    public synchronized boolean active() {
        return active != null;
    }

    @Override
    public void close() {
        Thread running;
        synchronized (this) {
            closed = true;
            running = worker;
            if (active != null) {
                cancel(active);
            }
        }
        // 네이티브 호출이 반환하기 전에 codec을 해제하지 않는다. 작업 잠금 밖에서 기다린다.
        boolean interrupted = false;
        while (running != null && running.isAlive()) {
            try {
                running.join();
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
