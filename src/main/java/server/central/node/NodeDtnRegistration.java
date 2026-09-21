package server.central.node;

import server.shared.codec.DtnDelay;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** 전송 전 등록 정보다. 입력 원본, AFS 프레임, 기준 PVT는 의도적으로 포함하지 않는다. */
@Data
@NoArgsConstructor
public class NodeDtnRegistration {
    @NotNull
    private UUID testId;
    private String comparisonMode;
    private java.time.Instant testStartedAt;
    private DtnDelay.Epoch selectedEpoch;
    @NotBlank
    private String senderAgentId;
    @NotBlank
    private String receiverAgentId;
    @NotBlank
    private String profile;
    @NotNull
    @Pattern(regexp = "[0-9a-f]{64}")
    private String payloadSha256;
}
