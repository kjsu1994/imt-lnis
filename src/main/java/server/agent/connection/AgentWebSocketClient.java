package server.agent.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import server.agent.config.AgentConfig;
import server.agent.runtime.AgentRuntime;
import server.shared.model.AgentProtocol.*;

/**
 * 중앙 서버 연결, 재연결, HELLO 및 heartbeat 전송을 관리한다.
 *
 * <p>HTTP WebSocket client는 Agent ID와 Bearer token을 handshake header에 넣는다. 연결 실패 또는 정상 종료 시 5초 뒤
 * 재접속하고, 연결 중에는 5초마다 현재 AgentState와 증가 sequence를 전송한다.
 */
@Slf4j
public final class AgentWebSocketClient implements WebSocket.Listener, AutoCloseable {
  private final AgentConfig config;
  private final AgentRuntime runtime;
  private final ServerDiscovery discovery = new ServerDiscovery();
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> new Thread(runnable, "lnis-agent-heartbeat"));
  private final AtomicLong heartbeat = new AtomicLong();
  private final AtomicReference<URI> serverUri;
  private final AtomicBoolean recovering = new AtomicBoolean();
  private final StringBuilder text = new StringBuilder();

  /** Java WebSocket은 동시에 두 sendText를 허용하지 않으므로 모든 송신을 직렬화한다. */
  private final Object sendLock = new Object();

  private volatile WebSocket socket;
  private volatile boolean closed;

  public AgentWebSocketClient(AgentConfig config, AgentRuntime runtime) {
    this.config = config;
    this.runtime = runtime;
    this.serverUri = new AtomicReference<>(config.serverUri());
    runtime.outbound(this::send);
  }

  /** 최초 연결을 요청하고 heartbeat scheduler를 시작한다. */
  public void start() {
    connect();
    scheduler.scheduleAtFixedRate(this::heartbeat, 5, 5, TimeUnit.SECONDS);
  }

  /** 비동기 handshake 실패를 scheduler 기반 재시도로 전환한다. */
  private void connect() {
    if (closed) {
      return;
    }
    URI target = serverUri.get();
    log.debug("Connecting LNIS Agent {} to {}", config.agentId(), target);
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
        .newWebSocketBuilder()
        .header("Authorization", "Bearer " + config.token())
        .header("X-LNIS-Agent-Id", config.agentId())
        .buildAsync(target, this)
        .whenComplete(
            (webSocket, error) -> {
              if (error != null) {
                recoverConnection(target, error);
              } else {
                socket = webSocket;
              }
            });
  }

  private void recoverConnection(URI failedTarget, Throwable error) {
    socket = null;
    if (closed || !recovering.compareAndSet(false, true)) {
      return;
    }
    scheduler.execute(
        () -> {
          long retryDelaySeconds = 5;
          try {
            Throwable cause =
                error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            if (cause instanceof WebSocketHandshakeException handshake
                && (handshake.getResponse().statusCode() == 401
                    || handshake.getResponse().statusCode() == 403)) {
              log.error("LNIS Agent authentication failed for {}", failedTarget);
              return;
            }
            log.warn("Unable to connect to {}; discovering LNIS server on LAN", failedTarget);
            Optional<URI> discovered = discovery.discover(discoveryPort(failedTarget));
            if (discovered.isPresent()) {
              serverUri.set(discovered.get());
              if (!discovered.get().equals(failedTarget)) {
                retryDelaySeconds = 0;
              }
              log.info("Discovered LNIS server at {}", discovered.get());
            }
          } finally {
            recovering.set(false);
            if (!closed) {
              scheduler.schedule(this::connect, retryDelaySeconds, TimeUnit.SECONDS);
            }
          }
        });
  }

  private static int discoveryPort(URI configured) {
    if (configured.getPort() > 0) {
      return configured.getPort();
    }
    return "wss".equalsIgnoreCase(configured.getScheme()) ? 443 : 80;
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    socket = webSocket;
    log.info("LNIS Agent connected agentId={} url={}", config.agentId(), serverUri.get());
    Hello hello =
        new Hello(
            "1.0.0",
            1,
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            Map.of("com", config.role().name().equals("SENDER"), "afsTransfer", true),
            localIpv4Addresses(serverUri.get()));
    send(
        Envelope.of(
            MessageType.HELLO, config.agentId(), config.role(), null, json.valueToTree(hello)));
    webSocket.request(1);
  }

  @Override
  public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    text.append(data);
    if (last) {
      try {
        runtime.handle(json.readValue(text.toString(), Envelope.class));
      } catch (Exception error) {
        log.warn("AGENT_MESSAGE_REJECTED agentId={} type={}", config.agentId(), error.getClass().getSimpleName());
        // 잘못된 단일 메시지는 버리고 다음 서버 메시지를 계속 수신한다.
      } finally {
        text.setLength(0);
      }
    }
    webSocket.request(1);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    recoverConnection(serverUri.get(), error);
  }

  @Override
  public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    recoverConnection(serverUri.get(), new IllegalStateException(reason));
    return CompletableFuture.completedFuture(null);
  }

  static List<String> localIpv4Addresses(URI server) {
    LinkedHashSet<String> addresses = new LinkedHashSet<>();
    try {
      NetworkInterface.networkInterfaces()
          .filter(
              network -> {
                try {
                  return network.isUp() && !network.isLoopback() && !network.isVirtual();
                } catch (Exception ignored) {
                  return false;
                }
              })
          .flatMap(NetworkInterface::inetAddresses)
          .filter(Inet4Address.class::isInstance)
          .map(Inet4Address.class::cast)
          .filter(address -> !address.isLoopbackAddress() && !address.isLinkLocalAddress())
          .sorted(
              Comparator.comparing((Inet4Address address) -> !address.isSiteLocalAddress())
                  .thenComparing(Inet4Address::getHostAddress))
          .map(Inet4Address::getHostAddress)
          .distinct()
          .forEach(addresses::add);
    } catch (Exception ignored) {
      // Keep the preferred route address when interface enumeration fails.
    }
    return List.copyOf(addresses);
  }

  private void heartbeat() {
    Heartbeat payload = new Heartbeat(runtime.state(), "", heartbeat.incrementAndGet());
    send(
        Envelope.of(
            MessageType.HEARTBEAT,
            config.agentId(),
            config.role(),
            null,
            json.valueToTree(payload)));
  }

  private void send(Envelope envelope) {
    WebSocket current = socket;
    if (current != null) {
      try {
        /*
         * 프레임 증거 여러 건과 heartbeat가 동시에 전송되면 send pending 예외가 발생할 수 있다.
         * 완료를 기다린 뒤 다음 메시지를 보내야 증거와 최종 결과의 순서 및 전달을 보장할 수 있다.
         */
        synchronized (sendLock) {
          current.sendText(json.writeValueAsString(envelope), true).join();
        }
      } catch (Exception error) {
        log.warn("AGENT_SEND_FAILED agentId={} type={} cause={}", config.agentId(), envelope.type(), error.getClass().getSimpleName());
        // 재연결 스케줄러가 연결을 복구하므로 개별 전송 실패는 무시한다.
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    scheduler.shutdownNow();
    if (socket != null) {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
    }
  }
}
