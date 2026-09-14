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

  @GetMapping("/{id}/observations")
  public DtnObservationView observations(@PathVariable UUID id) {
    return DtnObservationView.fromRecords(records(id));
  }

  @GetMapping("/{id}/pvt")
  public java.util.List<DtnModels.Pvt> pvt(@PathVariable UUID id) throws java.io.IOException {
    var records = records(id);
    String captured = inputs.get(id).capturedPvtJson();
    if (captured != null)
      return json.readValue(captured, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    if (calculator == null) throw new IllegalStateException("PVT 미리보기는 통합 노드 실행에서 지원됩니다.");
    return calculator.calculate(records);
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
