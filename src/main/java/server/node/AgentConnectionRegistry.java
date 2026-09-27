package server.node;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** 로컬 실행기와 상대 REST 상태만 조회한다. 명령 라우팅이나 소켓은 보유하지 않는다. */
@Component
public class AgentConnectionRegistry {
    private final ConcurrentHashMap<String, BooleanSupplier> endpoints = new ConcurrentHashMap<>();

    public void registerEndpoint(String id, BooleanSupplier endpoint) {
        if (endpoints.putIfAbsent(id, endpoint) != null) {
            throw new IllegalStateException("이미 등록된 실행기 ID입니다: " + id);
        }
    }

    public void removeEndpoint(String id, BooleanSupplier endpoint) {
        endpoints.remove(id, endpoint);
    }

    public boolean online(String id) {
        BooleanSupplier endpoint = endpoints.get(id);
        return endpoint != null && endpoint.getAsBoolean();
    }
}
