package server.central.dtn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import server.shared.model.DtnModels.HdtnConfig;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DtnPresetService {
    private final DtnPresetRepository repository;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final PlatformTransactionManager transactions;

    public record Settings(
            @NotNull @Pattern(regexp = "GNSS_RAW|AFS_METADATA|IQ_SAMPLE") String testType,
            @NotNull @Pattern(regexp = "DTN|HDTN") String senderMode,
            @NotNull @Pattern(regexp = "DTN|HDTN") String receiverMode,
            @NotNull Boolean delayEnabled,
            @NotNull @Valid @JsonDeserialize(using = HdtnConfigRequestDeserializer.class) HdtnConfig hdtnConfig) {}
    public record Save(@NotBlank @Size(max = 40) String name, Long version, @NotNull @Valid Settings settings) {}
    public record View(UUID id, String name, Long version, Instant updatedAt, Settings settings) {}

    public synchronized List<View> list() {
        return repository.findAll().stream().sorted(Comparator.comparing(DtnPreset::getUpdatedAt).reversed())
                .map(this::view).toList();
    }

    // Each PC runs one LNIS JVM. Keep the capacity check and commit under the same lock.
    public synchronized View save(UUID id, Save request) {
        validate(request);
        return new TransactionTemplate(transactions).execute(status -> {
            List<DtnPreset> presets = repository.findAll();
            String name = request.name().strip();
            if (name.isEmpty()) throw bad("프리셋 이름을 입력하세요.");
            if (presets.stream().anyMatch(p -> !p.getId().equals(id) && p.getName().equalsIgnoreCase(name)))
                throw conflict("같은 이름의 프리셋이 있습니다.");
            DtnPreset preset;
            if (id == null) {
                if (presets.size() >= 5) throw conflict("프리셋은 최대 5개입니다. 기존 항목을 수정하거나 삭제하세요.");
                preset = new DtnPreset();
                preset.setId(UUID.randomUUID());
            } else {
                preset = repository.findById(id).orElseThrow(() -> missing());
                checkVersion(preset, request.version());
            }
            preset.setName(name);
            try { preset.setSettingsJson(mapper.writeValueAsString(request.settings())); }
            catch (Exception error) { throw new IllegalStateException("프리셋 직렬화 실패", error); }
            preset.setUpdatedAt(Instant.now());
            return view(repository.saveAndFlush(preset));
        });
    }

    public synchronized void delete(UUID id, long version) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            DtnPreset preset = repository.findById(id).orElseThrow(() -> missing());
            checkVersion(preset, version);
            repository.delete(preset);
            repository.flush();
        });
    }

    private void validate(Save request) {
        if (request == null || !validator.validate(request).isEmpty()) throw bad("프리셋 이름과 설정 범위를 확인하세요.");
        HdtnConfig config = request.settings().hdtnConfig();
        if (config.getTcpclMaxSegmentSizeBytes() == null || config.getTotalStorageCapacityBytes() == null
                || config.getMaxLtpReceiveUdpPacketSizeBytes() == null || config.getAcsSendPeriodMilliseconds() == null)
            throw bad("고급 설정을 포함한 HDTN 전체 값을 입력하세요.");
    }

    private View view(DtnPreset preset) {
        try {
            return new View(preset.getId(), preset.getName(), preset.getVersion(), preset.getUpdatedAt(),
                    mapper.readValue(preset.getSettingsJson(), Settings.class));
        } catch (Exception error) { throw new IllegalStateException("저장된 프리셋 읽기 실패", error); }
    }

    private static void checkVersion(DtnPreset preset, Long version) {
        if (!Objects.equals(preset.getVersion(), version))
            throw conflict("다른 화면에서 변경되었습니다. 목록을 새로 불러온 뒤 다시 시도하세요.");
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "프리셋이 삭제되었거나 없습니다."); }
}
