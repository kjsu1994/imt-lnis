package server.gnss;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import server.common.AgentProtocol.CommandType;
import server.common.LnisModels.InputKind;
import server.node.AgentCommandService;

import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/lnis/api/v1/captures")
/** Sender Agent의 GNSS 수집 시작·종료와 입력 확정을 담당한다. */
public class CaptureController {
    private final InputBufferService inputBufferService;
    private final AgentCommandService agentCommandService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private server.dtn.DtnLogService logs;

    @GetMapping("/pending")
    public java.util.List<InputBufferEntity> pending() {
        return inputBufferService.pendingCaptures();
    }

    @PostMapping("/{captureId}/accept")
    public InputBufferEntity accept(@PathVariable UUID captureId) {
        return inputBufferService.acceptCapture(captureId);
    }

    @PostMapping("/{captureId}/discard")
    public Map<String, Boolean> discard(@PathVariable UUID captureId) {
        inputBufferService.discardCapture(captureId);
        return Map.of("discarded", true);
    }

    /* GNSS 수집 시작: 명령 실패 시 생성한 입력을 정리한다. */
    @PostMapping
    public ResponseEntity<InputBufferEntity> start(@Valid @RequestBody CaptureRequest request) {
        InputBufferEntity input =
                inputBufferService.create("capture.graw", 0, InputKind.GNSS_CAPTURE);
        if (request.singleEpoch() && logs != null) {
            logs.add(
                    input.inputId(),
                    "INPUT",
                    "COM 수집",
                    false,
                    "수집 요청 · "
                            + request.portName()
                            + " · "
                            + request.baudRate()
                            + " baud · 관측값·항법정보 대기");
        }
        try {
            agentCommandService.command(
                    request.senderAgentId(), input.inputId(), CommandType.START_CAPTURE, request);
            InputBufferEntity response = input;
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (RuntimeException error) {
            if (request.singleEpoch() && logs != null) {
                logs.add(input.inputId(), "INPUT", "ERROR", "COM 수집", false, error.getMessage());
            }
            // Agent 조회 또는 명령 전송이 실패하면 시험에 사용할 수 없는 DB·파일 입력을 남기지 않는다.
            try {
                inputBufferService.remove(input.inputId());
            } catch (RuntimeException cleanupError) {
                // 정리 오류가 원래의 명령 실패 원인을 가리지 않도록 suppressed 예외로 보존한다.
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    /* Sender에 GNSS 수집 종료 요청 */
    @PostMapping("/{captureId}/stop")
    public ResponseEntity<Map<String, Object>> stop(
            @PathVariable UUID captureId, @RequestParam String senderAgentId) {
        UUID command =
                agentCommandService.command(
                        senderAgentId, captureId, CommandType.STOP_CAPTURE, null);
        Map<String, Object> response = Map.of("commandId", command, "accepted", true);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 수집된 입력 검증 및 확정 */
    @PostMapping("/{captureId}/complete")
    public ResponseEntity<InputBufferEntity> complete(@PathVariable UUID captureId) {
        InputBufferEntity response = inputBufferService.complete(captureId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
