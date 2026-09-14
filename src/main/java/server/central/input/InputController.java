package server.central.input;

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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private server.central.dtn.DtnLogService logs;

    /* GRAW 입력 등록 */
    @PostMapping
    public ResponseEntity<InputBufferEntity> createLogged(
            @Valid @RequestBody CreateInputRequest request, @RequestParam(defaultValue="false") boolean dtn)
    {
        var result=create(request);
        if(dtn && logs!=null) logs.add(result.getBody().inputId(),"INPUT","파일 적용",false,
            "파일 적용 시작 · "+request.fileName()+" · "+request.size()+" bytes");
        return result;
    }

    public ResponseEntity<InputBufferEntity> create(CreateInputRequest request)
    {
        InputBufferEntity response =
                inputBufferService.create(request.fileName(), request.size(), request.kind());
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 순서에 맞춰 GRAW 청크 저장 */
    @PutMapping(
            value = "/{inputId}/chunks/{index}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InputBufferEntity> chunk(
            @PathVariable UUID inputId, @PathVariable long index, @RequestBody byte[] body)
    {
        InputBufferEntity response = inputBufferService.append(inputId, index, body);
        if(logs!=null && logs.exists(inputId)) logs.add(inputId,"INPUT","업로드",true,"청크 저장 완료 · "+response.receivedSize()+" bytes");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 전체 입력 검증 및 수집 완료 처리 */
    @PostMapping("/{inputId}/complete")
    public ResponseEntity<InputBufferEntity> complete(@PathVariable UUID inputId)
    {
        InputBufferEntity response = inputBufferService.complete(inputId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 입력 메타데이터 조회 */
    @GetMapping("/{inputId}")
    public ResponseEntity<InputBufferEntity> get(@PathVariable UUID inputId)
    {
        InputBufferEntity response = inputBufferService.get(inputId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 입력 메타데이터와 저장 파일 삭제 */
    @DeleteMapping("/{inputId}")
    public ResponseEntity<Map<String, Boolean>> delete(@PathVariable UUID inputId)
    {
        inputBufferService.remove(inputId);
        Map<String, Boolean> response = Map.of("removed", true);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
