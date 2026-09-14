package server.central.dtn.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import server.shared.codec.GrawCodec;
import server.shared.model.DtnModels;

/** Temporary, opt-in development endpoint. The fixture is not bundled in the application. */
@RestController
@ConditionalOnProperty(name = "lnis.dtn.example-enabled", havingValue = "true")
public class DtnExampleController {
  @Value("${lnis.dtn.example-file:}") private String file;
  @Value("${lnis.dtn.synthetic-file:}") private String syntheticFile;
  @org.springframework.beans.factory.annotation.Autowired private server.central.input.InputBufferService inputs;
  @org.springframework.beans.factory.annotation.Autowired(required = false) private server.central.dtn.DtnPvtCalculator calculator;
  @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper json;

  @GetMapping("/lnis/api/v1/dtn/example/file")
  public ResponseEntity<byte[]> file() throws IOException {
    return read(file, "f9t-example.graw");
  }

  @GetMapping("/lnis/api/v1/dtn/example/synthetic/file")
  public ResponseEntity<byte[]> synthetic() throws IOException {
    return read(syntheticFile, "synthetic-earth-pvt.graw");
  }

  /** Canonical record replay, not a serial-port or UBX parser simulation. */
  @PostMapping("/lnis/api/v1/dtn/example/replay")
  public synchronized server.central.input.InputBufferEntity replay() throws IOException {
    if (calculator == null) throw new IllegalStateException("통합 송신 노드에서 재생하세요.");
    var selector = new server.shared.codec.SingleEpochCapture(records -> {
      var pvt = calculator.calculate(records).getFirst();
      return pvt.isPositionValid() && pvt.isVelocityValid();
    });
    var records = GrawCodec.splitLengthPrefixed(synthetic().getBody());
    // Exercise the same navigation wait as capture: this epoch alone cannot solve.
    for (var record : records) if (GrawCodec.decode(record).message() instanceof GrawCodec.ObservationEpoch) {
      if (selector.accept(record) != null) throw new IllegalStateException("항법정보 없이 재생이 완료되었습니다.");
      break;
    }
    for (var record : records) {
      var selected = selector.accept(record);
      if (selected == null) continue;
      var bytes = new java.io.ByteArrayOutputStream();
      for (var item : selected) {
        bytes.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(item.length).array());
        bytes.writeBytes(item);
      }
      var input = inputs.create("SYNTHETIC-replay.graw", bytes.size(), server.shared.model.LnisModels.InputKind.GNSS_CAPTURE);
      inputs.append(input.inputId(), 0, bytes.toByteArray());
      return inputs.complete(input.inputId(), json.writeValueAsString(calculator.calculate(selected)));
    }
    throw new IllegalStateException("합성 입력에서 유효한 PVT를 확보하지 못했습니다.");
  }

  private ResponseEntity<byte[]> read(String file, String filename) throws IOException {
    if (file.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "예제 파일 미설정");
    Path path = Path.of(file);
    if (!Files.isRegularFile(path) || Files.size(path) > DtnModels.MAX_INPUT_BYTES)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "예제 파일 확인 필요");
    byte[] bytes;
    try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(DtnModels.MAX_INPUT_BYTES + 1); }
    if (bytes.length == 0 || bytes.length > DtnModels.MAX_INPUT_BYTES)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "예제 크기 오류");
    GrawCodec.splitLengthPrefixed(bytes); // Same validation as ordinary uploads.
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .body(bytes);
  }
}
