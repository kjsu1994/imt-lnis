package server.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import server.agent.codec.NativeAfsCodec;
import server.agent.config.AgentConfig;
import server.agent.runtime.AgentRuntime;
import server.agent.runtime.LocalCommandEndpoint;
import server.central.agent.AgentConnectionRegistry;
import server.central.agent.AgentEntity;
import server.central.agent.AgentMessageService;
import server.central.agent.AgentRepository;
import server.shared.model.AgentProtocol.Envelope;
import server.shared.model.AgentProtocol.Heartbeat;
import server.shared.model.AgentProtocol.Hello;
import server.shared.model.AgentProtocol.MessageType;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.AgentState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 한 JVM 안에서 실행기의 시작·상태 갱신·종료를 관리한다. 별도 프로그램을 실행하지 않는다. */
@RequiredArgsConstructor
@Slf4j
public class LocalNodeLifecycle implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {
    private final AgentConfig agentConfig;
    private final ObjectMapper objectMapper;
    private final AgentConnectionRegistry connectionRegistry;
    private final AgentMessageService messageService;
    private final AgentRepository agentRepository;
    private final AtomicLong heartbeatSequence = new AtomicLong();
    private AgentRuntime agentRuntime;
    private LocalCommandEndpoint endpoint;

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event)
    {
        if (endpoint != null) {
            return;
        }
        NativeAfsCodec codec = null;
        try {
            codec = NativeAfsCodec.load(agentConfig.nativeDirectory());
            agentRuntime = new AgentRuntime(agentConfig, codec);
            endpoint = new LocalCommandEndpoint(agentConfig, agentRuntime, this::receive);
            connectionRegistry.registerEndpoint(agentConfig.agentId(), endpoint);
            Hello hello = new Hello("1.0.0", agentRuntime.codecAbiVersion(),
                    System.getProperty("os.name"), System.getProperty("os.arch"),
                    Map.of("com", agentConfig.role() == AgentRole.SENDER, "afsTransfer", false,
                            "local", true), localIpv4Addresses());
            receive(Envelope.of(MessageType.HELLO, agentConfig.agentId(), agentConfig.role(),
                    null, objectMapper.valueToTree(hello)));
            log.info("로컬 {} 실행기 시작 완료: {}", agentConfig.role(), agentConfig.agentId());
        } catch (Exception | LinkageError error) {
            // 라이브러리 오류가 있어도 웹과 기존 이력 조회는 유지하고 실행기만 ERROR로 표시한다.
            if (endpoint != null) {
                connectionRegistry.removeEndpoint(agentConfig.agentId(), endpoint);
                endpoint.close();
                endpoint = null;
            } else if (codec != null) {
                codec.close();
            }
            agentRuntime = null;
            agentRepository.save(new AgentEntity(agentConfig.agentId(), agentConfig.role(),
                    AgentState.ERROR, Instant.now(), "1.0.0", 0, System.getProperty("os.name"),
                    System.getProperty("os.arch"), List.of(), "네이티브 실행기 초기화 실패"));
            log.error("로컬 실행기 초기화 실패. 웹 서비스는 유지합니다.", error);
        }
    }

    @Scheduled(fixedDelay = 3000)
    public synchronized void heartbeat()
    {
        if (endpoint == null || !endpoint.online()) {
            return;
        }
        Heartbeat heartbeat = new Heartbeat(agentRuntime.state(), "", heartbeatSequence.incrementAndGet());
        receive(Envelope.of(MessageType.HEARTBEAT, agentConfig.agentId(), agentConfig.role(),
                null, objectMapper.valueToTree(heartbeat)));
    }

    private void receive(Envelope message)
    {
        try {
            messageService.handle(message);
        } catch (Exception error) {
            throw new IllegalStateException("로컬 실행 결과 저장 실패", error);
        }
    }

    /** Linux에서도 노드 연결 정보에 사용할 실제 IPv4 목록을 제공한다. */
    private List<String> localIpv4Addresses() throws java.net.SocketException
    {
        java.util.ArrayList<String> addresses = new java.util.ArrayList<>();
        for (java.net.NetworkInterface network : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            for (java.net.InetAddress address : java.util.Collections.list(network.getInetAddresses())) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                    addresses.add(address.getHostAddress());
                }
            }
        }
        return addresses.stream().distinct().toList();
    }

    @Override
    public synchronized void close()
    {
        if (endpoint != null) {
            connectionRegistry.removeEndpoint(agentConfig.agentId(), endpoint);
            endpoint.close();
            endpoint = null;
            agentRuntime = null;
        }
    }
}
