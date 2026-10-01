package server.gnss;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import server.common.LnisModels.InputKind;
import server.dtn.DtnRepository;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;

@Service
/**
 * GRAW 입력의 생성, 순차 청크 수신, 무결성 검증과 완료 처리을 담당한다.
 *
 * <p>H2 메타데이터와 GRAW 파일 저장을 조정하는 업무 계층으로, 청크 크기와 순서를 강제한다. complete가 성공하기 전까지 해당 입력은 시험 세션에서 사용할 수
 * 없다.
 */
public class InputBufferService {
    public static final int CHUNK_SIZE = 1024 * 1024;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private server.management.DataManagementGuard managementGuard;

    private final InputBufferRepository inputBufferRepository;
    private final DtnRepository dtnRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private server.dtn.DtnLogService logs;

    public InputBufferService(
            InputBufferRepository inputBufferRepository,
            DtnRepository dtnRepository) {
        this.inputBufferRepository = inputBufferRepository;
        this.dtnRepository = dtnRepository;
    }

    @Transactional
    public synchronized void receiverInfo(UUID id, String receiverInfoJson) {
        var input = get(id);
        if (!input.complete()) throw new IllegalStateException("Complete input first");
        input.receiverInfoJson = receiverInfoJson;
        inputBufferRepository.save(input);
    }

    /** 메타데이터만 먼저 생성하며 미완성 보존 기간은 정리 작업에서 적용한다. */
    @Transactional
    public synchronized InputBufferEntity create(String fileName, long declaredSize, InputKind kind) {
        if (kind == InputKind.GNSS_CAPTURE && !pendingCaptures().isEmpty()) {
            throw new IllegalStateException("확보한 관측 데이터의 사용 여부를 먼저 선택하세요.");
        }
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
        inputBufferRepository.save(value);
        return value;
    }

    /**
     * 다음 순번의 바이너리 청크를 저장한다.
     *
     * <p>동일 입력에 대한 동시 append로 chunkCount가 꼬이지 않도록 method를 동기화한다.
     */
    @Transactional
    public synchronized InputBufferEntity append(UUID id, long index, byte[] bytes) {
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
        inputBufferRepository.putChunk(id, index, bytes);
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
        inputBufferRepository.save(updated);
        return updated;
    }

    /** 전체 청크를 순서대로 검증하고 완료 메타데이터 및 최종 GRAW 파일을 확정한다. */
    @Transactional
    public synchronized InputBufferEntity complete(UUID id) {
        return complete(id, null);
    }

    /** Store Agent-calculated PVT atomically with input completion. */
    @Transactional
    public synchronized InputBufferEntity complete(UUID id, String capturedPvtJson) {
        InputBufferEntity current = get(id);
        if (current.complete()) {
            return current;
        }
        boolean recording = logs != null && logs.exists(id);
        if (recording) {
            logs.add(id, "INPUT", "GRAW 검증", true, "구조·CRC·크기·SHA-256 검사 시작");
        }
        try {
            if (capturedPvtJson != null && current.kind() != InputKind.GNSS_CAPTURE) {
                throw new IllegalArgumentException("Capture PVT requires a GNSS capture input");
            }
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
            complete.capturedPvtJson =
                    current.capturedPvtJson() != null ? current.capturedPvtJson() : capturedPvtJson;
            complete.captureDecision = current.captureDecision();
            inputBufferRepository.save(complete);
            inputBufferRepository.completeFile(id);
            if (recording) {
                logs.add(
                        id,
                        "INPUT",
                        "GRAW 검증",
                        false,
                        "입력 검증 완료 · "
                                + result.records()
                                + " records · "
                                + result.size()
                                + " bytes · SHA-256 "
                                + result.sha256());
            }
            return complete;
        } catch (RuntimeException error) {
            if (recording) {
                logs.add(
                        id,
                        "INPUT",
                        "ERROR",
                        "GRAW 검증",
                        false,
                        error.getMessage() + " · 입력 파일·수집 상태를 확인하세요.");
            }
            throw error;
        }
    }

    public InputBufferEntity get(UUID id) {
        return inputBufferRepository
                .find(id)
                .orElseThrow(() -> new IllegalArgumentException("Input not found: " + id));
    }

    public java.util.List<InputBufferEntity> pendingCaptures() {
        return inputBufferRepository.pendingCaptures();
    }

    /** 파일 검증 완료와 시험 사용 승인을 구분한다. 정상 수집 완료 경로는 변경하지 않는다. */
    @Transactional
    public synchronized InputBufferEntity awaitCaptureDecision(UUID id, String pvtJson) {
        var input = get(id);
        if (input.kind() != InputKind.GNSS_CAPTURE || input.complete()) {
            throw new IllegalStateException("선택 대기로 전환할 수 없는 입력입니다.");
        }
        var records = GrawCodec.splitLengthPrefixed(readChunks(input, server.common.DtnModels.MAX_INPUT_BYTES));
        var epochs = records.stream().map(GrawCodec::decode).map(GrawCodec.Envelope::message)
                .filter(GrawCodec.ObservationEpoch.class::isInstance)
                .map(GrawCodec.ObservationEpoch.class::cast).toList();
        if (epochs.size() != 1 || epochs.getFirst().observations().isEmpty()) {
            throw new IllegalArgumentException("확보한 관측 에폭이 없습니다.");
        }
        input.captureDecision = "AWAITING_DECISION";
        inputBufferRepository.save(input);
        return complete(id, pvtJson);
    }

    @Transactional
    public synchronized InputBufferEntity acceptCapture(UUID id) {
        var input = inputBufferRepository.lockDecision(id)
                .orElseThrow(() -> new IllegalArgumentException("Input not found: " + id));
        if ("ACCEPTED".equals(input.captureDecision())) {
            return input;
        }
        if (!input.complete() || !"AWAITING_DECISION".equals(input.captureDecision())) {
            throw new IllegalStateException("사용 여부 선택 대기 상태가 아닙니다.");
        }
        input.captureDecision = "ACCEPTED";
        input.completedAt = Instant.now();
        inputBufferRepository.save(input);
        if (logs != null) {
            logs.add(id, "INPUT", "WARN", "COM 수집", false, "사용자 승인 · PVT 조건 미충족 관측 데이터를 시험에 사용");
        }
        return input;
    }

    public void requireApproved(UUID id) {
        if ("AWAITING_DECISION".equals(get(id).captureDecision())) {
            throw new IllegalStateException("확보한 관측 데이터의 사용 여부를 먼저 선택하세요.");
        }
    }

    @Transactional
    public synchronized void discardCapture(UUID id) {
        var input = inputBufferRepository.lockDecision(id).orElse(null);
        if (input == null) {
            return;
        }
        if (!"AWAITING_DECISION".equals(input.captureDecision())) {
            throw new IllegalStateException("이미 다른 화면에서 사용 여부가 결정되었습니다.");
        }
        if (logs != null) {
            logs.add(id, "INPUT", "COM 수집", false, "사용자 결정 · 시간 초과 관측 데이터 폐기");
        }
        remove(id);
    }

    public byte[] chunk(UUID id, long index) {
        byte[] value = inputBufferRepository.getChunk(id, index);
        if (value == null) {
            throw new IllegalArgumentException("Input chunk not found");
        }
        return value;
    }

    /** 메모리에 읽는 입력의 상한과 저장된 크기를 검증한다. 완료 여부는 호출 기능에서 확인한다. */
    public byte[] readChunks(InputBufferEntity input, int maximumBytes) {
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
    public synchronized void remove(UUID id) {
        if (managementGuard != null) {
            managementGuard.requireUnpinned("INPUT", id);
        }
        InputBufferEntity input = get(id);
        // 기준 버전과 동일하게 사용자의 명시적 삭제는 허용한다. 자동 보존 정리만 세션 참조를 보호한다.
        inputBufferRepository.delete(id);
    }

    /** 보존 정리 작업에서 참조 검사를 통과한 입력을 제거한다. */
    @Transactional
    public void removeExpired(InputBufferEntity input) {
        if (!"AWAITING_DECISION".equals(input.captureDecision())
                && !dtnRepository.existsByInputId(input.inputId())) {
            inputBufferRepository.delete(input.inputId());
        }
    }
}
