package server.node;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import server.common.AgentProtocol.CommandType;
import server.common.DtnModels.AgentResult;
import server.common.DtnModels.Transfer;
import server.pvt.DtnDelay;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 기존 REST 계약은 유지하되 로컬 실행기를 직접 호출한다. */
@RequiredArgsConstructor
@Service
public class AgentCommandService {
    private final LocalNodeLifecycle local;

    public UUID command(String agentId, UUID sessionId, CommandType type, Object arguments) {
        local.runtime(agentId).execute(sessionId, type, arguments);
        return UUID.randomUUID();
    }

    public void prepare(
            String agentId,
            UUID id,
            byte[] data,
            boolean raw,
            BiConsumer<String, String> progress,
            Consumer<AgentResult> result) {
        local.runtime(agentId).worker().prepare(id, data, raw, progress, result);
    }

    public void receive(
            String agentId,
            UUID id,
            Transfer transfer,
            DtnDelay.Timing timing,
            BiConsumer<String, String> progress,
            Consumer<AgentResult> result) {
        local.runtime(agentId).worker().receive(id, transfer, timing, progress, result);
    }

    public void cancel(String agentId, UUID id) {
        local.runtime(agentId).worker().cancel(id);
    }

    public boolean busy() {
        return local.busy();
    }
}
