package server.node;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import server.common.AgentProtocol.*;
import server.common.LnisModels.*;
import server.dtn.DtnLogService;
import server.gnss.InputBufferService;
import server.management.DataManagementGuard;
import server.realtime.EventService;

import java.util.Map;
import java.util.UUID;

/** 로컬 수집 결과를 저장하고 기존 브라우저 이벤트 형식으로 알린다. */
@RequiredArgsConstructor
@Service
public class AgentMessageService {
    private final ObjectMapper objectMapper;
    private final InputBufferService inputBufferService;
    private final EventService eventService;

    @Autowired(required = false)
    private DtnLogService logs;

    @Autowired(required = false)
    private DataManagementGuard managementGuard;

    private void withInput(UUID id, Runnable action) {
        if (managementGuard == null) {
            action.run();
            return;
        }
        var lock = managementGuard.gate.readLock();
        lock.lock();
        try {
            if (!managementGuard.deleted("DTN", id) && !managementGuard.deleted("INPUT", id)) {
                action.run();
            }
        } finally {
            lock.unlock();
        }
    }

    public void input(UUID id, byte[] canonical) {
        withInput(
                id,
                () -> {
                    if (canonical.length > 0) {
                        var input = inputBufferService.get(id);
                        inputBufferService.append(id, input.chunkCount(), canonical);
                    }
                });
    }

    public void ports(String agentId, AgentRole role, PortList ports) {
        eventService.publish(EventType.AGENT_STATUS, agentId, role, null, ports);
    }

    public void status(String agentId, AgentRole role, UUID id, Progress progress) {
        withInput(
                id,
                () -> {
                    if (progress.type() == null) {
                        eventService.publish(
                                EventType.ERROR,
                                agentId,
                                role,
                                id,
                                Map.of("message", "Capture STATUS event type is missing"));
                        return;
                    }
                    boolean written = false;
                    if (progress.type() == EventType.GNSS_STATUS
                            && "CaptureDecisionRequired".equals(progress.stage())) {
                        inputBufferService.awaitCaptureDecision(id,
                                objectMapper.valueToTree(progress.counters().get("pvt")).toString());
                    }
                    if (logs != null && logs.exists(id)) {
                        if (progress.type() == EventType.GNSS_STATUS) {
                            written = logs.capture(id, progress.stage(), progress.message());
                        } else if (progress.type() == EventType.ERROR) {
                            logs.add(id, "INPUT", "ERROR", "COM 수집", false, progress.message());
                            written = true;
                        }
                    }
                    if (progress.type() == EventType.GNSS_STATUS
                            && "SingleEpochComplete".equals(progress.stage())) {
                        Object pvt =
                                progress.counters() == null ? null : progress.counters().get("pvt");
                        if (pvt == null) {
                            inputBufferService.complete(id);
                        } else {
                            var values =
                                    objectMapper.convertValue(
                                            pvt, server.common.DtnModels.Pvt[].class);
                            if (values.length != 1
                                    || values[0] == null
                                    || !values[0].isPositionValid()
                                    || !values[0].isVelocityValid()) {
                                throw new IllegalArgumentException(
                                        "A valid single-epoch capture PVT is required");
                            }
                            try {
                                inputBufferService.complete(
                                        id, objectMapper.writeValueAsString(values));
                            } catch (java.io.IOException error) {
                                throw new IllegalStateException("수집 PVT 저장 실패", error);
                            }
                        }
                    }
                    eventService.publish(progress.type(), agentId, role, id, progress, written);
                });
    }
}
