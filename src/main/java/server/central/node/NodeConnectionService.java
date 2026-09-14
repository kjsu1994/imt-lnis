package server.central.node;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import server.central.dtn.DtnRepository;
import server.central.dtn.DtnService;
import server.central.session.ActiveSessionLockRepository;
import server.central.session.SessionService;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.AgentState;

import java.net.URI;
import java.util.List;

/** 후보 연결 테스트와 주소 적용을 분리하고, 시험 시작과 주소 변경이 겹치지 않도록 조정한다. */
@Service
@Profile("node")
@RequiredArgsConstructor
public class NodeConnectionService {
    private final NodeProperties properties;
    private final NodePeerClient client;
    private final NodePeerSettingRepository settings;
    private final NodePeerConnection connection;
    private final SessionService sessions;
    private final ActiveSessionLockRepository locks;
    private final DtnService dtn;
    private final DtnRepository jobs;

    @PostConstruct
    public void restore()
    {
        // 화면에서 저장한 주소가 환경 기본값보다 우선하며 재시작 후에도 그대로 사용한다.
        settings.findById(1).ifPresent(setting -> properties.applyPeerAddress(URI.create(setting.getBaseUrl())));
    }

    public NodeConnectionDto.Configuration configuration()
    {
        URI address = properties.getPeerBaseUrl();
        NodeConnectionDto.Configuration result = new NodeConnectionDto.Configuration();
        result.setIp(address == null ? "" : address.getHost());
        result.setScheme(address == null ? "http" : address.getScheme());
        result.setPort(address == null ? 8088 : address.getPort() >= 0 ? address.getPort()
                : "https".equals(address.getScheme()) ? 443 : 80);
        result.setBaseUrl(address == null ? "" : address.toString());
        result.setPeerAgentId(properties.getPeerAgentId());
        result.setTokenConfigured(!properties.getManagementToken().isBlank());
        result.setEditable(properties.getRole() == AgentRole.SENDER);
        result.setBusy(busy());
        result.setPeerOnline(connection.online());
        result.setReverseOnline(connection.reverseOnline());
        return result;
    }

    public NodeConnectionDto.ProbeResult test(NodeConnectionDto.AddressRequest request)
    {
        requireSender();
        URI address = address(request);
        long started = System.nanoTime();
        NodeConnectionDto.ProbeResult result = new NodeConnectionDto.ProbeResult();
        try {
            NodeDto.StatusResponse status = client.statusAt(address);
            result.setNode(status);
            result.setConnected(true);
            result.setReady(status.isOnline() && status.getState() == AgentState.READY);
            result.setMessage(result.isReady() ? "수신 서비스 및 실행기 연결 정상 (READY)"
                    : "수신 서비스 연결 정상 / 실행기 상태: " + status.getState()
                    + (status.isOnline() ? "" : " (오프라인)"));
        } catch (IllegalStateException error) {
            // 응답 본문이나 인증 헤더를 오류 응답에 넣지 않는다.
            result.setMessage(error.getMessage());
        }
        result.setElapsedMilliseconds((System.nanoTime() - started) / 1_000_000);
        return result;
    }

    public NodeConnectionDto.Configuration save(NodeConnectionDto.AddressRequest request)
    {
        requireSender();
        URI address = address(request);
        // AFS와 DTN 시작 경로와 같은 모니터를 사용해 busy 확인 직후 새 시험이 끼어들지 못하게 한다.
        synchronized (sessions) {
            synchronized (dtn) {
                if (busy()) {
                    throw new IllegalStateException("시험 진행 중에는 수신 노드 주소를 변경할 수 없습니다.");
                }
                NodeDto.StatusResponse status = client.statusAt(address);
                if (!status.isOnline() || status.getState() != AgentState.READY) {
                    throw new IllegalStateException("수신 실행기가 READY인 경우에만 저장·적용할 수 있습니다.");
                }
                NodePeerSetting setting = new NodePeerSetting();
                setting.setId(1);
                setting.setBaseUrl(address.toString());
                settings.saveAndFlush(setting);
                connection.applyAddress(address);
                return configuration();
            }
        }
    }

    private boolean busy()
    {
        return locks.current().isPresent() || !jobs.findByStateIn(
                List.of("PREPARING", "WAITING_DTN", "WAITING_RECEIVER", "CALCULATING")).isEmpty();
    }

    private void requireSender()
    {
        if (properties.getRole() != AgentRole.SENDER) {
            throw new IllegalStateException("송신 노드에서 수신 연결을 설정하세요.");
        }
    }

    URI address(NodeConnectionDto.AddressRequest request)
    {
        if (request == null || request.getIp() == null || request.getPort() < 1 || request.getPort() > 65535
                || !("http".equals(request.getScheme()) || "https".equals(request.getScheme()))) {
            throw new IllegalArgumentException("IP, 포트(1~65535), http/https를 확인하세요.");
        }
        String ip = request.getIp().trim();
        if (!ip.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) {
            throw new IllegalArgumentException("수신 PC의 IPv4 주소만 입력하세요. URL이나 경로는 입력하지 않습니다.");
        }
        String[] parts = ip.split("\\.");
        for (String part : parts) {
            if (Integer.parseInt(part) > 255) {
                throw new IllegalArgumentException("올바른 IPv4 주소가 아닙니다.");
            }
        }
        int first = Integer.parseInt(parts[0]);
        if (first == 0 || first >= 224 || (first == 169 && "254".equals(parts[1]))) {
            throw new IllegalArgumentException("미지정/멀티캐스트/링크 로컬 주소는 사용할 수 없습니다.");
        }
        URI address = URI.create(request.getScheme() + "://" + ip + ":" + request.getPort());
        if (address.equals(properties.getBaseUrl())) {
            throw new IllegalArgumentException("송신 노드 자신의 주소는 사용할 수 없습니다.");
        }
        return address;
    }
}
