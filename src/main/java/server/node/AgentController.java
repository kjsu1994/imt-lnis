package server.node;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import server.common.AgentProtocol.CommandType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/lnis/api/v1/agents")
/** 연결된 Windows Agent와 COM 포트 조회 명령을 제공한다. */
public class AgentController {
    private final AgentRepository agentRepository;
    private final AgentCommandService agentCommandService;

    /* 등록된 Agent 목록 조회 */
    @GetMapping
    public ResponseEntity<List<AgentEntity>> all() {
        List<AgentEntity> response = agentRepository.findAll();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* Agent 단건 조회 */
    @GetMapping("/{agentId}")
    public ResponseEntity<AgentEntity> one(@PathVariable String agentId) {
        AgentEntity response =
                agentRepository
                        .find(agentId)
                        .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* Agent에 COM 포트 목록 갱신 요청 */
    @PostMapping("/{agentId}/serial-ports/refresh")
    public ResponseEntity<Map<String, Object>> ports(@PathVariable String agentId) {
        UUID command = agentCommandService.command(agentId, null, CommandType.LIST_PORTS, null);
        Map<String, Object> response = Map.of("commandId", command, "accepted", true);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
