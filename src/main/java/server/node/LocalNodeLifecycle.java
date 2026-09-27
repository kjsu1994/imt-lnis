package server.node;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;

import server.afs.NativeAfsCodec;
import server.common.LnisModels.AgentState;

import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 로컬 실행기의 수명주기를 관리한다. 내부 등록/heartbeat 통신 없이 상태를 직접 읽는다. */
@RequiredArgsConstructor
@Slf4j
public class LocalNodeLifecycle
        implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable, BooleanSupplier {
    private final AgentConfig agentConfig;
    private final AgentConnectionRegistry connectionRegistry;
    private final AgentMessageService messageService;
    private final AgentRepository agentRepository;
    private volatile AgentRuntime agentRuntime;
    private List<String> addresses = List.of();

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (agentRuntime != null) {
            return;
        }
        NativeAfsCodec codec = null;
        try {
            codec = NativeAfsCodec.load(agentConfig.nativeDirectory());
            addresses = localIpv4Addresses();
            agentRuntime = new AgentRuntime(agentConfig, codec, messageService);
            connectionRegistry.registerEndpoint(agentConfig.agentId(), this);
            refreshState();
            log.info("로컬 {} 실행기 시작 완료: {}", agentConfig.role(), agentConfig.agentId());
        } catch (Exception | LinkageError error) {
            connectionRegistry.removeEndpoint(agentConfig.agentId(), this);
            if (agentRuntime != null) {
                agentRuntime.close();
                agentRuntime = null;
            } else if (codec != null) {
                codec.close();
            }
            agentRepository.save(snapshot(AgentState.ERROR, 0, "네이티브 실행기 초기화 실패"));
            log.error("로컬 실행기 초기화 실패. 웹 서비스는 유지합니다.", error);
        }
    }

    public AgentRuntime runtime(String id) {
        if (!agentConfig.agentId().equals(id)) {
            throw new IllegalArgumentException("로컬 실행기 ID가 아닙니다: " + id);
        }
        AgentRuntime runtime = agentRuntime;
        if (runtime == null || runtime.state() == AgentState.OFFLINE) {
            throw new IllegalStateException("로컬 실행기가 준비되지 않았습니다.");
        }
        return runtime;
    }

    public boolean busy() {
        AgentRuntime runtime = agentRuntime;
        return runtime != null && runtime.state() == AgentState.BUSY;
    }

    @Override
    public boolean getAsBoolean() {
        AgentRuntime runtime = agentRuntime;
        return runtime != null && runtime.state() != AgentState.OFFLINE;
    }

    /** 기존 상태 조회 API와 DB 형식을 유지하며 실제 실행기 상태만 저장한다. */
    @Scheduled(fixedDelay = 3000)
    public synchronized void refreshState() {
        AgentRuntime runtime = agentRuntime;
        if (runtime != null) {
            agentRepository.save(snapshot(runtime.state(), runtime.codecAbiVersion(), null));
        }
    }

    private AgentEntity snapshot(AgentState state, int abi, String error) {
        return new AgentEntity(
                agentConfig.agentId(),
                agentConfig.role(),
                state,
                Instant.now(),
                "1.0.0",
                abi,
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                addresses,
                error);
    }

    private List<String> localIpv4Addresses() throws java.net.SocketException {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (var network :
                java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            for (var address : java.util.Collections.list(network.getInetAddresses())) {
                if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                    result.add(address.getHostAddress());
                }
            }
        }
        return result.stream().distinct().toList();
    }

    @Override
    public void close() {
        AgentRuntime runtime;
        synchronized (this) {
            runtime = agentRuntime;
            agentRuntime = null;
            connectionRegistry.removeEndpoint(agentConfig.agentId(), this);
        }
        if (runtime != null) {
            runtime.close();
        }
    }
}
