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

  @GetMapping("/lnis/api/v1/dtn/example/file")
  public ResponseEntity<byte[]> file() throws IOException {
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
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"f9t-example.graw\"")
        .body(bytes);
  }
}
