package server.central.input;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import server.central.config.StorageProperties;
import server.central.session.SessionRepository;
import server.shared.model.LnisModels.InputKind;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
/**
 * GRAW 입력의 생성, 순차 청크 수신, 무결성 검증과 TTL 전환을 담당한다.
 *
 * <p>H2 메타데이터와 GRAW 파일 저장을 조정하는 업무 계층으로, 청크 크기와 순서를 강제한다. complete가 성공하기 전까지 해당 입력은 시험 세션에서 사용할 수
 * 없다.
 */
public class InputBufferService {
    public static final int CHUNK_SIZE = 1024 * 1024;
    private final Duration incompleteRetention;
    private final Duration completedRetention;
    private final InputBufferRepository inputBufferRepository;
    private final SessionRepository sessionRepository;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private server.central.dtn.DtnLogService logs;

    public InputBufferService(
            InputBufferRepository inputBufferRepository,
            SessionRepository sessionRepository,
            StorageProperties properties)
    {
        this.inputBufferRepository = inputBufferRepository;
        this.sessionRepository = sessionRepository;
        this.incompleteRetention = properties.getIncompleteRetention();
        this.completedRetention = properties.getCompletedRetention();
    }

    /** 메타데이터만 먼저 생성하며 미완성 보존 기간은 정리 작업에서 적용한다. */
    @Transactional
    public InputBufferEntity create(String fileName, long declaredSize, InputKind kind)
    {
        UUID id = UUID.randomUUID();
        InputBufferEntity value =
                new InputBufferEntity(
                        id,
                        kind == null ? InputKind.GRAW_UPLOAD : kind,
                        fileName,
                        declaredSize,
                        0,
                        0,
                        0,
                        null,
                        false,
                        Instant.now(),
                        null);
        inputBufferRepository.save(value, incompleteRetention);
        return value;
    }

    /**
     * 다음 순번의 바이너리 청크를 저장한다.
     *
     * <p>동일 입력에 대한 동시 append로 chunkCount가 꼬이지 않도록 method를 동기화한다.
     */
    @Transactional
    public synchronized InputBufferEntity append(UUID id, long index, byte[] bytes)
    {
        if (bytes.length == 0 || bytes.length > CHUNK_SIZE) {
            throw new IllegalArgumentException("Chunk must contain 1 to 1048576 bytes");
        }
        InputBufferEntity current = get(id);
        if (current.complete()) {
            throw new IllegalStateException("Input is already complete");
        }
        if (index != current.chunkCount()) {
            throw new IllegalArgumentException("Expected chunk " + current.chunkCount());
        }
        // 바이너리를 먼저 저장한 뒤 메타데이터의 chunkCount를 올려 조회자가 없는 청크를 참조하지 않게 한다.
        inputBufferRepository.putChunk(id, index, bytes, incompleteRetention);
        InputBufferEntity updated =
                new InputBufferEntity(
                        id,
                        current.kind(),
                        current.fileName(),
                        current.declaredSize(),
                        current.receivedSize() + bytes.length,
                        current.chunkCount() + 1,
                        0,
                        null,
                        false,
                        current.createdAt(),
                        null);
        inputBufferRepository.save(updated, incompleteRetention);
        return updated;
    }

    /** 전체 청크를 순서대로 검증하고 완료 메타데이터 및 최종 GRAW 파일을 확정한다. */
    @Transactional
    public synchronized InputBufferEntity complete(UUID id)
    {
        return complete(id, null);
    }

    /** Store Agent-calculated PVT atomically with input completion. */
    @Transactional
    public synchronized InputBufferEntity complete(UUID id, String capturedPvtJson)
    {
        InputBufferEntity current = get(id);
        boolean recording=logs!=null && logs.exists(id);
        if(recording) logs.add(id,"INPUT","GRAW 검증",true,"구조·CRC·크기·SHA-256 검사 시작");
        try {
        if (capturedPvtJson != null && current.kind() != InputKind.GNSS_CAPTURE)
            throw new IllegalArgumentException("Capture PVT requires a GNSS capture input");
        // 전체 파일을 한 배열로 합치지 않고 청크를 순차 공급해 큰 GRAW에서도 검증 메모리를 제한한다.
        GrawStreamingValidator validator = new GrawStreamingValidator();
        for (long i = 0; i < current.chunkCount(); i++) {
            byte[] chunk = inputBufferRepository.getChunk(id, i);
            if (chunk == null) {
                throw new IllegalStateException("Missing input chunk " + i);
            }
            validator.push(chunk);
        }
        GrawStreamingValidator.Result result = validator.finish();
        // API에 선언된 파일 크기와 실제 wire record 검증 결과를 모두 통과해야 complete=true가 된다.
        if (current.declaredSize() > 0 && current.declaredSize() != result.size()) {
            throw new IllegalArgumentException("Uploaded size does not match declaration");
        }
        InputBufferEntity complete =
                new InputBufferEntity(
                        id,
                        current.kind(),
                        current.fileName(),
                        current.declaredSize(),
                        result.size(),
                        current.chunkCount(),
                        result.records(),
                        result.sha256(),
                        true,
                        current.createdAt(),
                        Instant.now());
        complete.capturedPvtJson = current.capturedPvtJson() != null
                ? current.capturedPvtJson() : capturedPvtJson;
        inputBufferRepository.save(complete, completedRetention);
        // 메타데이터만 먼저 만료돼 고아 청크가 남지 않도록 모든 청크 TTL도 같은 시점으로 연장한다.
        inputBufferRepository.touchChunks(id, current.chunkCount(), completedRetention);
        inputBufferRepository.completeFile(id);
        if(recording) logs.add(id,"INPUT","GRAW 검증",false,"입력 검증 완료 · "+result.records()+" records · "+result.size()+" bytes · SHA-256 "+result.sha256());
        return complete;
        } catch(RuntimeException error) {
            if(recording) logs.add(id,"INPUT","ERROR","GRAW 검증",false,error.getMessage()+" · 입력 파일·수집 상태를 확인하세요.");
            throw error;
        }
    }

    public InputBufferEntity get(UUID id)
    {
        return inputBufferRepository
                .find(id)
                .orElseThrow(() -> new IllegalArgumentException("Input not found: " + id));
    }

    public byte[] chunk(UUID id, long index)
    {
        byte[] value = inputBufferRepository.getChunk(id, index);
        if (value == null) {
            throw new IllegalArgumentException("Input chunk not found");
        }
        return value;
    }

    /** 메모리에 읽는 입력의 상한과 저장된 크기를 검증한다. 완료 여부는 호출 기능에서 확인한다. */
    public byte[] readChunks(InputBufferEntity input, int maximumBytes)
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (long i = 0; i < input.chunkCount(); i++) {
            byte[] chunk = chunk(input.inputId(), i);
            if ((long) bytes.size() + chunk.length > maximumBytes) {
                throw new IllegalArgumentException("입력 크기 초과");
            }
            bytes.writeBytes(chunk);
        }
        if (bytes.size() != input.receivedSize()) {
            throw new IllegalArgumentException("입력 크기 불일치");
        }
        return bytes.toByteArray();
    }

    @Transactional
    public void remove(UUID id)
    {
        InputBufferEntity input = get(id);
        // 기준 버전과 동일하게 사용자의 명시적 삭제는 허용한다. 자동 보존 정리만 세션 참조를 보호한다.
        inputBufferRepository.delete(id, input.chunkCount());
    }

    /** 보존 정리 작업에서 참조 검사를 통과한 입력을 제거한다. */
    @Transactional
    public void removeExpired(InputBufferEntity input)
    {
        if (!sessionRepository.existsByInputId(input.inputId())) {
            inputBufferRepository.delete(input.inputId(), input.chunkCount());
        }
    }
}

