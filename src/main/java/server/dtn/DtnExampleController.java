package server.dtn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import server.common.DtnModels;
import server.gnss.GrawCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Temporary, opt-in development endpoint. The fixture is not bundled in the application. */
@RestController
@ConditionalOnProperty(name = "lnis.dtn.example-enabled", havingValue = "true")
public class DtnExampleController {
    @Value("${lnis.dtn.example-file:}")
    private String file;

    @Value("${lnis.dtn.synthetic-file:}")
    private String syntheticFile;

    @org.springframework.beans.factory.annotation.Autowired
    private server.gnss.InputBufferService inputs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private server.pvt.DtnPvtCalculator calculator;

    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper json;

    @GetMapping("/lnis/api/v1/dtn/example/file")
    public ResponseEntity<byte[]> file() throws IOException {
        return read(file, "f9t-example.graw");
    }

    @GetMapping("/lnis/api/v1/dtn/example/synthetic/file")
    public ResponseEntity<byte[]> synthetic() throws IOException {
        return read(syntheticFile, "synthetic-earth-pvt.graw");
    }

    @Value("${lnis.dtn.real-file:}")
    private String realFile;

    @GetMapping("/lnis/api/v1/dtn/example/real/file")
    public ResponseEntity<byte[]> real() throws IOException {
        return read(realFile, "real-gnss-10epochs.graw");
    }

    /** Saved real receiver data; this endpoint never opens a serial port or uses synthetic fixtures. */
    @PostMapping("/lnis/api/v1/dtn/example/replay")
    public synchronized server.gnss.InputBufferEntity replay() throws IOException {
        byte[] bytes = real().getBody();
        var records = GrawCodec.splitLengthPrefixed(bytes);
        if (records.stream().noneMatch(record -> GrawCodec.decode(record).message() instanceof GrawCodec.ObservationEpoch)) {
            throw new IllegalArgumentException("실측 파일에 관측 에폭이 없습니다.");
        }
        var input = inputs.create("real-gnss-10epochs.graw", bytes.length,
                server.common.LnisModels.InputKind.GRAW_UPLOAD);
        try {
            inputs.append(input.inputId(), 0, bytes);
            return inputs.complete(input.inputId());
        } catch (RuntimeException error) {
            try { inputs.remove(input.inputId()); }
            catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    private ResponseEntity<byte[]> read(String file, String filename) throws IOException {
        if (file.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "예제 파일 미설정");
        }
        Path path = Path.of(file);
        if (!Files.isRegularFile(path) || Files.size(path) > DtnModels.MAX_INPUT_BYTES) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "예제 파일 확인 필요");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(DtnModels.MAX_INPUT_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > DtnModels.MAX_INPUT_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "예제 크기 오류");
        }
        GrawCodec.splitLengthPrefixed(bytes); // Same validation as ordinary uploads.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
