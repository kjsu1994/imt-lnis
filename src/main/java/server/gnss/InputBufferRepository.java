package server.gnss;

import lombok.RequiredArgsConstructor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Repository
/** 입력 메타데이터와 청크 위치는 H2에, 실제 바이트는 파일에 저장한다. */
public class InputBufferRepository {
    private final InputMetadataJpaRepository inputMetadataJpaRepository;
    private final InputChunkJpaRepository inputChunkJpaRepository;
    private final GrawFileStorage files;

    public void save(InputBufferEntity value) {
        inputMetadataJpaRepository.save(value);
    }

    public Optional<InputBufferEntity> find(UUID id) {
        return inputMetadataJpaRepository.findById(id);
    }

    public Optional<InputBufferEntity> lockDecision(UUID id) {
        return inputMetadataJpaRepository.lockDecision(id);
    }

    public void putChunk(UUID id, long index, byte[] value) {
        InputBufferEntity input =
                inputMetadataJpaRepository
                        .findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Input not found: " + id));
        files.append(id, input.receivedSize(), value);
        inputChunkJpaRepository.save(
                new InputChunkEntity(id, index, input.receivedSize(), value.length));
    }

    public byte[] getChunk(UUID id, long index) {
        InputBufferEntity input = inputMetadataJpaRepository.findById(id).orElse(null);
        InputChunkEntity chunk =
                inputChunkJpaRepository.findByInputIdAndChunkIndex(id, index).orElse(null);
        if (input == null || chunk == null) {
            return null;
        }
        return files.read(id, input.complete(), chunk.getFileOffset(), chunk.getByteLength());
    }

    public void completeFile(UUID id) {
        files.complete(id);
    }

    public void delete(UUID id) {
        inputChunkJpaRepository.deleteByInputId(id);
        inputMetadataJpaRepository.deleteById(id);
        files.delete(id);
    }

    public List<InputBufferEntity> pendingCaptures() {
        return inputMetadataJpaRepository.findByCaptureDecisionOrderByCreatedAtDesc("AWAITING_DECISION");
    }
}

interface InputMetadataJpaRepository extends JpaRepository<InputBufferEntity, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from InputBufferEntity e where e.inputId = :id")
    Optional<InputBufferEntity> lockDecision(@org.springframework.data.repository.query.Param("id") UUID id);

    List<InputBufferEntity> findByCaptureDecisionOrderByCreatedAtDesc(String decision);
}

interface InputChunkJpaRepository extends JpaRepository<InputChunkEntity, InputChunkEntity.Key> {
    Optional<InputChunkEntity> findByInputIdAndChunkIndex(UUID inputId, long chunkIndex);

    void deleteByInputId(UUID inputId);
}
