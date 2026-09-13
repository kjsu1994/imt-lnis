package server.central.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import server.central.dtn.DtnService;
import server.central.frameevidence.FrameEvidenceService;
import server.central.input.InputBufferEntity;
import server.central.input.InputBufferService;
import server.central.realtime.EventService;
import server.central.session.SessionRepository;
import server.central.session.SessionService;
import server.central.session.TestSessionEntity;
import server.shared.model.AgentProtocol.*;
import server.shared.model.LnisModels.*;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
/**
 * Agent protocol 메시지를 기능별 저장소와 브라우저 이벤트로 연결한다.
 *
 * <p>이 클래스가 Agent WebSocket transport와 input/session/realtime 도메인 사이의 경계다. 메시지 종류별 payload 타입을 여기서
 * 확정하고, Agent가 보낸 임의 JSON이 Repository까지 직접 전달되지 않게 한다.
 */
public class AgentMessageService {
    private final ObjectMapper objectMapper;
    private final AgentRepository agentRepository;
    private final InputBufferService inputBufferService;
    private final SessionRepository sessionRepository;
    private final EventService eventService;
    private final SessionService sessionService;
    private final FrameEvidenceService frameEvidenceService;
    private final AgentConnectionRegistry connectionRegistry;
    private DtnService dtnService;

    /** 기존 생성자 계약을 유지하며 DTN 경로만 별도로 주입한다. */
    @Autowired
    public void dtnService(DtnService service)
    {
        this.dtnService = service;
    }

    /** envelope 종류에 따라 Agent 상태, 입력 청크, 진행률 또는 역할 결과 처리로 분기한다. */
    public void handle(Envelope envelope) throws Exception
    {
        // 연결 정보(HELLO/HEARTBEAT), 입력, 진행 이벤트, 최종 결과를 각 도메인 서비스로 분배한다.
        // WebSocket Handler는 인증과 역직렬화만 담당하고 업무 상태 변경은 이 계층에서 시작된다.
        switch (envelope.type()) {
            case AFS_TRANSFER_START, AFS_TRANSFER_BATCH, AFS_TRANSFER_COMPLETE ->
                    forwardAfsTransfer(envelope);
            case DTN_DATA -> dtnService.agentData(envelope);
            case COMMAND_ACK -> {
                if (dtnService != null && !envelope.payload().path("accepted").asBoolean()) {
                    dtnService.rejected(envelope);
                }
            }
            case HELLO -> handleHello(envelope);
            case HEARTBEAT -> handleHeartbeat(envelope);
            case STATUS -> {
                Progress progress = objectMapper.treeToValue(envelope.payload(), Progress.class);
                if (progress.type() == EventType.GNSS_STATUS && "SingleEpochComplete".equals(progress.stage())) {
                    inputBufferService.complete(envelope.sessionId());
                }
                // 구버전 또는 결함 Agent가 RoleResult를 STATUS로 잘못 보낸 경우 type이 null이 된다.
                // 이 메시지 하나 때문에 Agent WebSocket 전체가 종료되지 않도록 오류 이벤트로 격리한다.
                if (progress.type() == null) {
                    eventService.publish(
                            EventType.ERROR,
                            envelope.agentId(),
                            envelope.role(),
                            envelope.sessionId(),
                            Map.of("message", "Agent STATUS event type is missing"));
                    return;
                }
                eventService.publish(
                        progress.type(),
                        envelope.agentId(),
                        envelope.role(),
                        envelope.sessionId(),
                        progress);
            }
            case INPUT_CHUNK -> {
                // rawSerial은 장치 진단용이며 시험 입력에는 Agent가 변환한 canonical GRAW만 누적한다.
                byte[] canonical =
                        Base64.getDecoder()
                                .decode(envelope.payload().path("canonicalBase64").asText());
                if (canonical.length > 0) {
                    InputBufferEntity input = inputBufferService.get(envelope.sessionId());
                    inputBufferService.append(envelope.sessionId(), input.chunkCount(), canonical);
                }
            }
            case PORT_LIST ->
                    eventService.publish(
                            EventType.AGENT_STATUS,
                            envelope.agentId(),
                            envelope.role(),
                            null,
                            objectMapper.treeToValue(envelope.payload(), PortList.class));
            case FRAME_EVIDENCE ->
                    frameEvidenceService.save(
                            envelope.sessionId(),
                            envelope.role(),
                            objectMapper.treeToValue(
                                    envelope.payload(), FrameEvidenceMessage.class));
            case ROLE_RESULT -> {
                RoleResult result = objectMapper.treeToValue(envelope.payload(), RoleResult.class);
                // 조회 API가 즉시 결과를 볼 수 있도록 저장을 먼저 끝낸 뒤 브라우저에 알린다.
                sessionRepository.saveResult(result);
                eventService.publish(
                        EventType.RESULT,
                        envelope.agentId(),
                        envelope.role(),
                        envelope.sessionId(),
                        result);
                sessionService.onResult(envelope.sessionId());
            }
            case ERROR ->
                    eventService.publish(
                            EventType.ERROR,
                            envelope.agentId(),
                            envelope.role(),
                            envelope.sessionId(),
                            envelope.payload());
            default -> {}
        }
    }

    /** Sender가 만든 AFS frame batch를 세션에 등록된 Receiver의 기존 연결로 전달한다. */
    private void forwardAfsTransfer(Envelope envelope)
    {
        TestSessionEntity session = sessionRepository.find(envelope.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + envelope.sessionId()));
        if (!session.senderAgentId().equals(envelope.agentId())
                || envelope.role() != AgentRole.SENDER
                || !session.senderAgentId().equals(envelope.payload().path("senderAgentId").asText())
                || !session.receiverAgentId().equals(envelope.payload().path("receiverAgentId").asText())) {
            throw new IllegalArgumentException("AFS transfer의 세션 참여자가 일치하지 않습니다.");
        }
        Envelope forwarded = Envelope.of(envelope.type(), session.receiverAgentId(),
                AgentRole.RECEIVER, envelope.sessionId(), envelope.payload());
        connectionRegistry.send(session.receiverAgentId(), forwarded);
    }

    /** 최초 접속 정보를 Agent 조회용 JPA 엔티티로 만들고 READY 이벤트를 방송한다. */
    private void handleHello(Envelope envelope) throws Exception
    {
        Hello hello = objectMapper.treeToValue(envelope.payload(), Hello.class);
        agentRepository.save(
                new AgentEntity(
                        envelope.agentId(),
                        envelope.role(),
                        AgentState.READY,
                        Instant.now(),
                        hello.agentVersion(),
                        hello.codecAbiVersion(),
                        hello.os(),
                        hello.architecture(),
                        normalizeAddresses(hello.ipv4Addresses()),
                        null));
        eventService.publish(
                EventType.AGENT_STATUS, envelope.agentId(), envelope.role(), null, "READY");
    }

    /** heartbeat는 동적 상태와 마지막 확인 시각만 갱신하고 HELLO의 장치 정보는 보존한다. */
    private void handleHeartbeat(Envelope envelope) throws Exception
    {
        Heartbeat heartbeat = objectMapper.treeToValue(envelope.payload(), Heartbeat.class);
        AgentEntity old =
                agentRepository
                        .find(envelope.agentId())
                        .orElse(
                                new AgentEntity(
                                        envelope.agentId(),
                                        envelope.role(),
                                        heartbeat.state(),
                                        Instant.now(),
                                        "unknown",
                                        0,
                                        "unknown",
                                        "unknown",
                                        List.of(),
                                        null));
        agentRepository.save(
                new AgentEntity(
                        old.agentId(),
                        old.role(),
                        heartbeat.state(),
                        Instant.now(),
                        old.version(),
                        old.codecAbiVersion(),
                        old.os(),
                        old.architecture(),
                        old.ipv4Addresses(),
                        old.error()));
    }

    private static List<String> normalizeAddresses(List<String> addresses)
    {
        return addresses == null
                ? List.of()
                : addresses.stream()
                        .filter(address -> address != null && !address.isBlank())
                        .distinct()
                        .toList();
    }
}

