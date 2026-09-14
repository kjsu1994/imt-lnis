package server.central.dtn;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import server.central.input.InputBufferService;
import server.shared.codec.GrawCodec;
import server.shared.model.DtnModels;
import server.shared.model.DtnObservationView;

/** Read-only display of an existing, validated input. Does not start a trial. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/lnis/api/v1/dtn/inputs")
public class DtnInputViewController {
  private final InputBufferService inputs;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private DtnPvtCalculator calculator;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private DtnLogService logs;

  @GetMapping("/{id}/observations")
  public DtnObservationView observations(@PathVariable UUID id) {
    var view=DtnObservationView.fromRecords(records(id));
    if(logs!=null && !logs.hasStage(id,"관측 요약")) logs.add(id,"INPUT","관측 요약",true,
        "GRAW 해석 완료 · 관측 "+view.epochs().size()+"시점 · 항법정보 "+view.navigationCount()+"건 · 신호 "+view.epochs().stream().mapToInt(e->e.observation().observations().size()).sum()+"개");
    return view;
  }

  @GetMapping("/{id}/pvt")
  public java.util.List<DtnModels.Pvt> pvt(@PathVariable UUID id) throws java.io.IOException {
    var records = records(id);
    long started=System.nanoTime();
    if(logs!=null) logs.add(id,"INPUT","PVT",true,"지구 PVT 계산·수집 결과 조회 시작");
    try {
    String captured = inputs.get(id).capturedPvtJson();
    if (captured != null) {
      java.util.List<DtnModels.Pvt> result=json.readValue(captured,new com.fasterxml.jackson.core.type.TypeReference<>() {});
      if(logs!=null) { logs.add(id,"INPUT","PVT",true,"수집 시 계산해 저장한 PVT 사용 · 재계산 아님"); logs.pvt(id,"INPUT",result,0); }
      return result;
    }
    if (calculator == null) throw new IllegalStateException("PVT 미리보기는 통합 노드 실행에서 지원됩니다.");
    var result=calculator.calculate(records);
    if(logs!=null) logs.pvt(id,"INPUT",result,started);
    return result;
    } catch(RuntimeException | java.io.IOException error) {
      if(logs!=null) logs.add(id,"INPUT","WARN","PVT",false,"미리보기 불가 · "+error.getMessage());
      throw error;
    }
  }

  private java.util.List<byte[]> records(UUID id) {
    var input = inputs.get(id);
    if (!input.complete() || input.receivedSize() <= 0
        || input.receivedSize() > DtnModels.MAX_INPUT_BYTES)
      throw new IllegalArgumentException("완료된 1 MiB 이하 GRAW 입력이 필요합니다.");
    var bytes = new ByteArrayOutputStream();
    for (long i = 0; i < input.chunkCount(); i++) {
      byte[] chunk = inputs.chunk(id, i);
      if ((long) bytes.size() + chunk.length > DtnModels.MAX_INPUT_BYTES)
        throw new IllegalArgumentException("입력 크기 초과");
      bytes.writeBytes(chunk);
    }
    if (bytes.size() != input.receivedSize()) throw new IllegalArgumentException("입력 크기 불일치");
    return GrawCodec.splitLengthPrefixed(bytes.toByteArray());
  }
}
