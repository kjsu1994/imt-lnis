package server.gnss;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/lnis/api/v1/inputs")
/** GRAW 입력을 청크 단위로 등록하고 검증하는 API를 제공한다. */
public class InputController {
    private final InputBufferService inputBufferService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private server.dtn.DtnLogService logs;

    /** UBX를 기존 입력 구조로 변환한 뒤 동일한 CRC·크기 검증과 PVT 경로를 사용한다. */
    @PostMapping(value = "/ubx", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public InputBufferEntity uploadUbx(
            @RequestParam String fileName,
            @RequestParam(required = false) java.time.Instant archiveTime,
            jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        if (fileName.isBlank() || fileName.length() > 255) {
            throw new IllegalArgumentException("파일 이름을 확인하세요.");
        }
        int maximum = 64 * 1024 * 1024;
        if (request.getContentLengthLong() > maximum) {
            throw new IllegalArgumentException("UBX 파일은 64 MiB 이하여야 합니다.");
        }
        byte[] raw = request.getInputStream().readNBytes(maximum + 1);
        if (raw.length == 0 || raw.length > maximum) {
            throw new IllegalArgumentException("비어 있지 않은 64 MiB 이하 UBX 파일을 선택하세요.");
        }
        byte[] data = UbxGrawImport.convertAll(raw,
                archiveTime == null ? java.time.Instant.now() : archiveTime, fileName);
        var input = inputBufferService.create(fileName, data.length, server.common.LnisModels.InputKind.GRAW_UPLOAD);
        try {
            if (logs != null) {
                logs.add(input.inputId(), "INPUT", "UBX 해석", false,
                        "UBX RAWX·SFRBX 해석 완료 · " + raw.length + " bytes → " + data.length + " bytes · " + fileName);
            }
            inputBufferService.append(input.inputId(), 0, data);
            return inputBufferService.complete(input.inputId());
        } catch (RuntimeException error) {
            try {
                inputBufferService.remove(input.inputId());
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    /* GRAW 입력 등록 */
    @PostMapping
    public ResponseEntity<InputBufferEntity> createLogged(
            @Valid @RequestBody CreateInputRequest request,
            @RequestParam(defaultValue = "false") boolean dtn) {
        var result = create(request);
        if (dtn && logs != null) {
            logs.add(
                    result.getBody().inputId(),
                    "INPUT",
                    "파일 적용",
                    false,
                    "파일 적용 시작 · " + request.fileName() + " · " + request.size() + " bytes");
        }
        return result;
    }

    public ResponseEntity<InputBufferEntity> create(CreateInputRequest request) {
        InputBufferEntity response =
                inputBufferService.create(request.fileName(), request.size(), request.kind());
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 순서에 맞춰 GRAW 청크 저장 */
    @PutMapping(
            value = "/{inputId}/chunks/{index}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InputBufferEntity> chunk(
            @PathVariable UUID inputId, @PathVariable long index, @RequestBody byte[] body) {
        InputBufferEntity response = inputBufferService.append(inputId, index, body);
        if (logs != null && logs.exists(inputId)) {
            logs.add(
                    inputId,
                    "INPUT",
                    "업로드",
                    true,
                    "청크 저장 완료 · " + response.receivedSize() + " bytes");
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 전체 입력 검증 및 수집 완료 처리 */
    @PostMapping("/{inputId}/complete")
    public ResponseEntity<InputBufferEntity> complete(@PathVariable UUID inputId) {
        InputBufferEntity response = inputBufferService.complete(inputId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 입력 메타데이터 조회 */
    @GetMapping("/{inputId}")
    public ResponseEntity<InputBufferEntity> get(@PathVariable UUID inputId) {
        InputBufferEntity response = inputBufferService.get(inputId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 입력 메타데이터와 저장 파일 삭제 */
    @DeleteMapping("/{inputId}")
    public ResponseEntity<Map<String, Boolean>> delete(@PathVariable UUID inputId) {
        inputBufferService.remove(inputId);
        Map<String, Boolean> response = Map.of("removed", true);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
