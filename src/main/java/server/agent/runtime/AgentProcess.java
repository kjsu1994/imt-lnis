package server.agent.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import server.agent.config.AgentConfig;
import server.agent.connection.AgentWebSocketClient;

/** Spring Context와 Agent WebSocket·장치 자원의 시작 및 종료 시점을 일치시킨다. */
@Slf4j
public final class AgentProcess implements AutoCloseable {
  private final AgentConfig config;
  private final AgentRuntime runtime;
  private final AgentWebSocketClient client;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final AtomicBoolean closed = new AtomicBoolean();

  public AgentProcess(AgentConfig config, AgentRuntime runtime, AgentWebSocketClient client) {
    this.config = config;
    this.runtime = runtime;
    this.client = client;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    client.start();
    log.info(
        "LNIS {} agent {} started; native codec ABI {}",
        config.role(),
        config.agentId(),
        runtime.codecAbiVersion());
  }

  public void await() throws InterruptedException {
    stopped.await();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    client.close();
    runtime.close();
    stopped.countDown();
  }
}
