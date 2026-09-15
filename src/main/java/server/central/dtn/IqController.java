package server.central.dtn;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import server.shared.model.DtnModels;

@RestController
@RequestMapping("/lnis/api/v1/dtn/iq")
@RequiredArgsConstructor
public class IqController {
  private final IqService iq;
  private final DtnService dtn;
  private final server.central.input.InputBufferService inputs;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private DtnPvtCalculator calculator;
  @org.springframework.beans.factory.annotation.Autowired(required=false) private DtnLogService logs;
  @GetMapping public List<Map<String,Object>> recent() throws Exception { return iq.recent(); }
  @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable UUID id) throws Exception { dtn.deleteIq(id); return Map.of("deleted",true); }
  public record GenerateRequest(@jakarta.validation.constraints.NotNull UUID inputId) {}
  @PostMapping public Map<String,Object> start(@jakarta.validation.Valid @RequestBody GenerateRequest request) throws Exception {
    if(logs!=null) logs.add(request.inputId(),"INPUT","I/Q 준비",true,"I/Q 생성 요청 · 입력 및 지구 PVT 확인");
    try {
    var input=inputs.get(request.inputId());
    if(!input.complete() || input.receivedSize()<=0 || input.receivedSize()>DtnModels.MAX_INPUT_BYTES)
      throw new IllegalArgumentException("완료된 1 MiB 이하 GNSS 입력이 필요합니다.");
    var records=server.shared.codec.GrawCodec.splitLengthPrefixed(inputs.readChunks(input,DtnModels.MAX_INPUT_BYTES));
    if(calculator==null) throw new IllegalStateException("지구 PVT 계산기가 필요합니다.");
    long started=System.nanoTime();
    if(logs!=null) logs.add(request.inputId(),"INPUT","PVT",true,"I/Q 입력용 지구 PVT 계산 시작");
    var results=calculator.calculate(records);
    if(logs!=null) logs.pvt(request.inputId(),"INPUT",results,started);
    var pvt=results.stream().filter(p->p.isPositionValid() && p.isVelocityValid()).findFirst()
        .orElseThrow(()->new IllegalArgumentException("유효한 지구 위치·속도와 GPS 항법정보가 필요합니다."));
    return iq.start(IqService.earthInput(records,pvt),request.inputId());
    } catch(Exception error) {
      if(logs!=null) logs.add(request.inputId(),"INPUT","ERROR","I/Q 준비",false,error.getMessage()+" · 유효 위치·속도, GPS 항법정보 및 디스크 공간을 확인하세요.");
      throw error;
    }
  }
  @GetMapping("/{id}") public Map<String,Object> status(@PathVariable UUID id) throws Exception { return iq.status(id); }
  @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id) throws Exception { iq.cancel(id); return iq.status(id); }
}
