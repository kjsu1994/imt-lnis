package server.central.dtn;

import server.shared.codec.DtnDelay;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import server.shared.model.DtnModels;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** DTN 담당자에게 공개하는 callback과 화면용 시험 제어 API다. */
@RequiredArgsConstructor
@RestController
@Slf4j
@RequestMapping("/lnis/api/v1/dtn")
public class DtnController {
    private final DtnService dtnService;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DtnLogService logs;
    @org.springframework.beans.factory.annotation.Autowired
    private DtnReceiptService receipts;

    @GetMapping("/receipts")
    public ResponseEntity<?> receipts() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipts.recent());
    }
    @GetMapping("/receipts/{id}/body")
    public ResponseEntity<byte[]> receiptBody(@PathVariable UUID id,
            @RequestParam(defaultValue="false") boolean download) {
        var receipt=receipts.get(id);
        var headers=new HttpHeaders(); headers.setContentType(new MediaType("text","plain",StandardCharsets.UTF_8));
        headers.setCacheControl("no-store"); headers.set("X-Content-Type-Options","nosniff");
        if(download) headers.setContentDisposition(ContentDisposition.attachment().filename("dtn-receipt-"+id+".txt").build());
        return new ResponseEntity<>(receipt.getBody(),headers,HttpStatus.OK);
    }


    public record ScreenLog(UUID scopeId, @NotNull java.time.Instant occurredAt,
            @NotNull @jakarta.validation.constraints.Pattern(regexp="INFO|WARN|ERROR") String level,
            @NotBlank @jakarta.validation.constraints.Size(max=2000) String message) {}

    @PostMapping("/logs/screen")
    public ResponseEntity<Void> screenLog(@Valid @RequestBody ScreenLog request) {
        logs.screen(request.scopeId(),request.occurredAt(),request.level(),request.message());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/logs")
    public ResponseEntity<?> logs(@RequestParam UUID scopeId, @RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="false") boolean download) {
        if (after < 0) throw new IllegalArgumentException("로그 순번 오류");
        var entries = logs.read(scopeId, after);
        if (!download) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
            "entries",entries,"nextSequence",entries.isEmpty()?after:entries.getLast().getSequence(),"hasMore",entries.size()==500));
        var text = new StringBuilder("LNIS local processing log · ").append(scopeId).append("\n");
        var chronological = new java.util.ArrayList<DtnLogEntry>();
        long cursor=0;
        do {
            entries=logs.read(scopeId,cursor);
            chronological.addAll(entries);
            if (!entries.isEmpty()) cursor=entries.getLast().getSequence();
        } while(entries.size()==500);
        chronological.sort(java.util.Comparator.comparing(DtnLogEntry::getOccurredAt).thenComparing(DtnLogEntry::getSequence));
        for(var e:chronological) text.append(e.getOccurredAt()).append(" [").append(e.getLevel()).append("] [")
            .append(e.getStage()).append("] ").append(e.getMessage()).append("\n");
        if(cursor==0) text.append("상세 로그 도입 전 시험 또는 기록된 처리 로그 없음\n");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Content-Disposition","attachment; filename=\"dtn-log-"+scopeId+".txt\"")
            .contentType(new MediaType("text","plain",StandardCharsets.UTF_8)).body(text.toString());
    }

    @Data
    public static class CreateRequest {
        private UUID inputId;
        private UUID iqFileId;
        @NotBlank
        private String senderAgentId;
        @NotBlank
        private String receiverAgentId;
        @jakarta.validation.constraints.Size(max = 2048)
        private String sendUrl;
        private String testType = "AFS_METADATA";
        @jakarta.validation.constraints.Pattern(regexp="RESTORE|DELAY")
        private String comparisonMode;
        private DtnDelay.Epoch selectedEpoch;
        private String senderMode;
        private String receiverMode;
        @Valid
        private DtnModels.HdtnConfig hdtnConfig;
    }

    /* DTN 외부 연동 설정 조회 */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config()
    {
        Map<String, Object> response = dtnService.configuration();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /** 원본 데이터와 PVT 전체를 제외한 최근 50개 시험 요약이다. */
    @GetMapping("/tests")
    public ResponseEntity<List<Map<String, Object>>> recent() throws Exception
    {
        List<Map<String, Object>> response = new ArrayList<>();
        for (DtnJob job : dtnService.recent()) {
            response.add(summary(job));
        }

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* Sender에 수집 종료 요청 */
    @PostMapping("/captures/{id}/stop")
    public ResponseEntity<Map<String, Object>> stop(
            @PathVariable UUID id, @RequestParam String senderAgentId)
    {
        UUID commandId = dtnService.stopCapture(id, senderAgentId);

        Map<String, Object> response = Map.of("commandId", commandId, "accepted", true);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 수집된 입력으로 DTN 시험 시작 */
    @PostMapping("/tests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest request)
            throws Exception
    {
        java.time.Instant startedAt = java.time.Instant.now();
        UUID inputId = request.getInputId();
        if ("IQ_SAMPLE".equals(request.getTestType())) {
            inputId = request.getIqFileId();
        }

        DtnJob dtnJob;
        if ("DELAY".equals(request.getComparisonMode())) {
            String senderMode = request.getSenderMode();
            String receiverMode = request.getReceiverMode();
            if (senderMode == null && receiverMode == null) {
                senderMode = "DTN";
                receiverMode = "HDTN";
            }
            dtnJob = dtnService.createDelay(
                    inputId, request.getSenderAgentId(), request.getReceiverAgentId(),
                    request.getSendUrl(), request.getTestType(), senderMode, receiverMode,
                    request.getHdtnConfig(), request.getSelectedEpoch(), startedAt);
        } else if (request.getHdtnConfig() != null) {
            String senderMode = request.getSenderMode(), receiverMode = request.getReceiverMode();
            if (senderMode == null && receiverMode == null) { senderMode = "DTN"; receiverMode = "HDTN"; }
            dtnJob = dtnService.create(inputId, request.getSenderAgentId(), request.getReceiverAgentId(),
                    request.getSendUrl(), request.getTestType(), senderMode, receiverMode, request.getHdtnConfig());
        } else if (request.getSenderMode() != null || request.getReceiverMode() != null) {
            dtnJob = dtnService.create(inputId, request.getSenderAgentId(), request.getReceiverAgentId(),
                    request.getSendUrl(), request.getTestType(), request.getSenderMode(), request.getReceiverMode());
        } else if (!"AFS_METADATA".equals(request.getTestType())) {
            dtnJob = dtnService.create(inputId, request.getSenderAgentId(), request.getReceiverAgentId(),
                    request.getSendUrl(), request.getTestType());
        } else if (request.getSendUrl() == null || request.getSendUrl().isBlank()) {
            dtnJob = dtnService.create(inputId, request.getSenderAgentId(), request.getReceiverAgentId());
        } else {
            dtnJob = dtnService.create(inputId, request.getSenderAgentId(), request.getReceiverAgentId(),
                    request.getSendUrl());
        }

        Map<String, Object> response = summary(dtnJob);
        return new ResponseEntity<>(response, HttpStatus.ACCEPTED);
    }

    @PostMapping("/tests/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID id) throws Exception
    {
        return ResponseEntity.ok(summary(dtnService.cancel(id)));
    }

    /* DTN 시험 단건 조회 */
    @GetMapping("/tests/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) throws Exception
    {
        DtnJob dtnJob = dtnService.get(id);

        Map<String, Object> response = summary(dtnJob);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /* 송신·수신 PVT와 비교 결과 조회 */
    @GetMapping(value = "/tests/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> report(@PathVariable UUID id) throws Exception
    {
        DtnJob job = dtnService.get(id);
        Map<String, Object> report = new LinkedHashMap<>(summary(job));
        report.put("delayEvidence", job.getDelayEvidenceJson()==null?null:objectMapper.readTree(job.getDelayEvidenceJson()));
        report.put("observations", job.getObservationsJson() == null
                ? null : objectMapper.readTree(job.getObservationsJson()));
        report.put("fileResult", job.getFileResultJson() == null ? null : objectMapper.readTree(job.getFileResultJson()));
        report.put(
                "referencePvt",
                job.getReferenceJson() == null
                        ? null
                        : objectMapper.readTree(job.getReferenceJson()));
        report.put(
                "receivedPvt",
                job.getReceiverJson() == null
                        ? null
                        : objectMapper.readTree(job.getReceiverJson()));
        report.put(
                "comparison",
                job.getComparisonJson() == null
                        ? null
                        : objectMapper.readTree(job.getComparisonJson()));
        // Legacy transfers lack a reference. Never substitute receiver results for it.
        if (job.getReferenceJson() == null && job.getReceivedJson() != null
                && !"IQ_SAMPLE".equals(job.getTestType())) {
            var transfer = objectMapper.readValue(job.getReceivedJson(), DtnModels.Transfer.class);
            var reference = transfer.getReferencePvt();
            report.put("referencePvt", reference);
            if (reference != null && job.getReceiverJson() != null) {
                try {
                    List<DtnModels.Pvt> received = objectMapper.readValue(job.getReceiverJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    report.put("comparison", DtnComparison.compare(reference, received));
                } catch (IllegalArgumentException exception) {
                    report.put("comparison", Map.of("verdict", "INCONCLUSIVE", "message", exception.getMessage()));
                }
            }
        }
        Map<String, Object> response = report;
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /** 송수신 본문을 그대로 반환한다. 다운로드도 같은 바이트를 사용하며 보고서 JSON과 구분한다. */
    @GetMapping(value = "/tests/{id}/payload/{direction}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> payload(@PathVariable UUID id, @PathVariable String direction,
            @RequestParam(defaultValue = "false") boolean download)
    {
        DtnService.PayloadResponse payload = dtnService.payload(id, direction);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-LNIS-Payload-Representation", payload.getRepresentation());
        if (download) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename("dtn-" + id + "-" + direction + ".json").build());
        }
        return new ResponseEntity<>(payload.getBody(), headers, HttpStatus.OK);
    }

    /* 외부 DTN 수신 결과 접수: 인증 후 크기와 JSON을 검증한다. */
    @PostMapping("/receive")
    public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request) throws Exception {
        String authorization=request.getHeader("Authorization");
        dtnService.authenticate(authorization);
        byte[] bytes=request.getInputStream().readNBytes(DtnModels.MAX_JSON_BYTES+1);
        java.time.Instant receivedAt = java.time.Instant.now();
        boolean truncated=bytes.length>DtnModels.MAX_JSON_BYTES;
        if(truncated) bytes=Arrays.copyOf(bytes,DtnModels.MAX_JSON_BYTES);
        var receipt=receipts.capture(bytes,request.getContentType(),truncated,receivedAt);
        if(truncated) {
            String message="본문 16 MiB 초과 · 앞 16 MiB만 저장됨";
            receipts.finish(receipt,"REJECTED",message);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("receiptId",receipt.getId(),"message",message));
        }
        try {
            DtnJob job=dtnService.receive(authorization,bytes,receivedAt);
            receipts.finish(receipt,"ACCEPTED","검증 통과 · 시험 처리 접수");
            return ResponseEntity.accepted().body(Map.of("testId",job.getId(),"accepted",true,"state",job.getState(),"receiptId",receipt.getId()));
        } catch(Exception error) {
            String message=error instanceof JsonProcessingException ? "올바른 JSON 형식이 아닙니다." : error.getMessage();
            receipts.finish(receipt,"REJECTED",message);
            dtnService.rejectReceipt(receipt.getTestId(),message);
            log.warn("DTN_RECEIVE_REJECTED receiptId={} testId={} reason={}",receipt.getId(),receipt.getTestId(),message,error);
            if(error instanceof JsonProcessingException) throw new IllegalArgumentException(message);
            throw error;
        }
    }

    private Map<String, Object> summary(DtnJob job) throws Exception
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testId", job.getId());
        result.put("testType", job.getTestType() == null ? "AFS_METADATA" : job.getTestType());
        result.put("senderMode", job.getSenderMode());
        result.put("receiverMode", job.getReceiverMode());
        result.put("hdtnConfig", job.getHdtnConfigJson() == null ? null : objectMapper.readTree(job.getHdtnConfigJson()));
        result.put("development", Boolean.TRUE.equals(job.getDevelopment()));
        result.put("comparisonMode", job.getComparisonMode()==null?"RESTORE":job.getComparisonMode());
        result.put("testStartedAt",job.getTestStartedAt());
        result.put("selectedEpoch",job.getSelectedEpochJson()==null?null:objectMapper.readTree(job.getSelectedEpochJson()));
        result.put("inputId", job.getInputId());
        result.put("senderAgentId", job.getSenderAgentId());
        result.put("receiverAgentId", job.getReceiverAgentId());
        result.put("state", job.getState());
        result.put("cancelPending", Boolean.TRUE.equals(job.getCancelPending()));
        result.put("sendUrl", job.getSendUrl());
        result.put("message", job.getMessage());
        result.put("createdAt", job.getCreatedAt());
        result.put("updatedAt", job.getUpdatedAt());
        result.put("receivedAt", job.getReceivedAt());
        result.put("dtnReceived", job.getReceivedJson() != null || job.getReceivedAt() != null);
        result.put("sentPayloadAvailable", job.getSentJson() != null);
        result.put("receivedPayloadAvailable", job.getReceivedJson() != null);
        result.put("receivedOriginalAvailable", job.getReceivedRawJson() != null);
        result.put("comparisonOnSender", job.getExpectedPayloadSha256() != null);
        result.put("receivedEpochs", job.getReceiverJson() == null ? 0 : objectMapper.readTree(job.getReceiverJson()).size());
        result.put("fileResult", job.getFileResultJson() == null ? null : objectMapper.readTree(job.getFileResultJson()));
        result.put("referenceEpochs", job.getReferenceJson() == null ? 0 : objectMapper.readTree(job.getReferenceJson()).size());
        if (job.getComparisonJson() != null) {
            JsonNode comparison = objectMapper.readTree(job.getComparisonJson());
            result.put("verdict", comparison.path("verdict").asText());
            result.put("comparableEpochs", comparison.path("comparableEpochs").asInt());
            result.put(
                    "velocityComparableEpochs",
                    comparison.path("velocityComparableEpochs").asInt());
        }
        return result;
    }
}
