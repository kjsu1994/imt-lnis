package server.central.dtn;

import lombok.Data;
import lombok.NoArgsConstructor;
import server.shared.model.DtnModels.Pvt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 관리 채널에는 수신 여부와 계산 결과만 반환한다. 송수신 JSON 본문은 각 PC에 보관한다. */
@Data
@NoArgsConstructor
public class DtnRemoteResult {
    public record AdapterLog(Instant occurredAt, String level, String message) {}
    private List<AdapterLog> adapterLogs;
    private UUID testId;
    private String state;
    private String message;
    private Instant receivedAt;
    private List<Pvt> pvt;
    private com.fasterxml.jackson.databind.JsonNode fileResult;
}
