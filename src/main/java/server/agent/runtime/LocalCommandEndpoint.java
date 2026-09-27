package server.agent.runtime;

import server.agent.config.AgentConfig;
import server.shared.model.AgentProtocol;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.AgentProtocol.MessageType;
import server.shared.model.CommandEndpoint;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 별도 Agent 프로세스나 loopback WebSocket 없이 기존 실행기를 직접 호출하는 어댑터다. */
public final class LocalCommandEndpoint implements CommandEndpoint, AutoCloseable {
    private final AgentConfig agentConfig;
    private final AgentRuntime agentRuntime;
    private final Consumer<Envelope> resultConsumer;
    private final ConcurrentMap<UUID, AtomicReference<Envelope>> pendingCommands = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public LocalCommandEndpoint(AgentConfig agentConfig, AgentRuntime agentRuntime,
            Consumer<Envelope> resultConsumer)
    {
        this.agentConfig = Objects.requireNonNull(agentConfig);
        this.agentRuntime = Objects.requireNonNull(agentRuntime);
        this.resultConsumer = Objects.requireNonNull(resultConsumer);
        agentRuntime.outbound(this::receive);
    }

    @Override
    public boolean online()
    {
        return !closed;
    }

    @Override
    public synchronized void send(Envelope message)
    {
        if (closed) {
            throw new IllegalStateException("로컬 실행기가 종료되었습니다.");
        }
        if (message.protocolVersion() != AgentProtocol.PROTOCOL_VERSION
                || !agentConfig.agentId().equals(message.agentId())
                || agentConfig.role() != message.role()) {
            throw new IllegalArgumentException("로컬 실행기의 역할, ID 또는 프로토콜 버전이 일치하지 않습니다.");
        }
        if (message.type() != MessageType.COMMAND) {
            throw new IllegalArgumentException("실행기에 전달할 수 없는 메시지 종류입니다.");
        }

        AtomicReference<Envelope> acknowledgment = new AtomicReference<>();
        if (message.type() == MessageType.COMMAND) {
            pendingCommands.put(message.messageId(), acknowledgment);
        }
        try {
            agentRuntime.handle(message);
            if (message.type() == MessageType.COMMAND) {
                // 현재 실행기는 handle 반환 전에 수락 여부를 알린다. 거절을 성공으로 간주하지 않는다.
                Envelope response = acknowledgment.get();
                if (response == null) {
                    throw new IllegalStateException("로컬 실행기의 명령 수락 응답이 없습니다.");
                }
                if (!response.payload().path("accepted").asBoolean()) {
                    throw new IllegalStateException(response.payload().path("message")
                            .asText("로컬 실행기가 명령을 거부했습니다."));
                }
            }
        } finally {
            pendingCommands.remove(message.messageId());
        }
    }

    private void receive(Envelope message)
    {
        if (message.type() == MessageType.COMMAND_ACK && message.correlationId() != null) {
            AtomicReference<Envelope> acknowledgment = pendingCommands.get(message.correlationId());
            if (acknowledgment != null) {
                acknowledgment.set(message);
            }
        }
        // 결과의 저장과 브라우저 알림은 조립 시 주입한 서비스가 담당한다.
        resultConsumer.accept(message);
    }

    @Override
    public synchronized void close()
    {
        if (!closed) {
            closed = true;
            agentRuntime.close();
        }
    }
}
