package server.central.dtn;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/lnis/api/v1/dtn/iq")
@RequiredArgsConstructor
public class IqController {
  private final IqService iq;
  private final DtnService dtn;
  private final server.central.input.InputBufferService inputs;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private DtnPvtCalculator calculator;
  @GetMapping public List<Map<String,Object>> recent() throws Exception { return iq.recent(); }
  @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable UUID id) throws Exception { dtn.deleteIq(id); return Map.of("deleted",true); }
  public record GenerateRequest(@jakarta.validation.constraints.NotNull UUID inputId) {}
  @PostMapping public Map<String,Object> start(@jakarta.validation.Valid @RequestBody GenerateRequest request) throws Exception {
    var input=inputs.get(request.inputId());
    if(!input.complete() || input.receivedSize()<=0 || input.receivedSize()>1048576)
      throw new IllegalArgumentException("완료된 1 MiB 이하 GNSS 입력이 필요합니다.");
    var bytes=new java.io.ByteArrayOutputStream();
    for(long i=0;i<input.chunkCount();i++) {
      byte[] chunk=inputs.chunk(request.inputId(),i);
      if(bytes.size()+chunk.length>1048576) throw new IllegalArgumentException("입력 크기 초과");
      bytes.writeBytes(chunk);
    }
    if(bytes.size()!=input.receivedSize()) throw new IllegalArgumentException("입력 크기 불일치");
    var records=server.shared.codec.GrawCodec.splitLengthPrefixed(bytes.toByteArray());
    if(calculator==null) throw new IllegalStateException("지구 PVT 계산기가 필요합니다.");
    var results=calculator.calculate(records);
    var pvt=results.stream().filter(p->p.isPositionValid() && p.isVelocityValid()).findFirst()
        .orElseThrow(()->new IllegalArgumentException("유효한 지구 위치·속도와 GPS 항법정보가 필요합니다."));
    return iq.start(IqService.earthInput(records,pvt));
  }
  @GetMapping("/{id}") public Map<String,Object> status(@PathVariable UUID id) throws Exception { return iq.status(id); }
  @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id) throws Exception { iq.cancel(id); return iq.status(id); }
}
