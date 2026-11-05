package server.dtn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import server.gnss.InputBufferService;
import server.node.*;

import java.time.Instant;
import java.util.*;

/** 실제 H2 조회에서 목록 JSON과 원문 컬럼 제외를 함께 검사한다. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=server.dtn.DtnSummaryIntegrationTest$SqlCapture"})
class DtnSummaryIntegrationTest {
    @Autowired DtnRepository jobs;
    @Autowired EntityManager em;
    private static final ThreadLocal<List<String>> statements = new ThreadLocal<>();

    public static class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        @Override
        public String inspect(String sql) {
            var captured = statements.get();
            if (captured != null) captured.add(sql);
            return sql;
        }
    }

    private DtnService service() {
        return new DtnService(jobs, mock(AgentCommandService.class), mock(AgentRepository.class),
                mock(AgentConnectionRegistry.class), mock(InputBufferService.class),
                new ObjectMapper().findAndRegisterModules());
    }

    private DtnJob job(int index) {
        var job = new DtnJob();
        job.setId(new UUID(0, index + 1));
        job.setCreatedAt(Instant.parse("2026-10-28T00:00:00Z").plusSeconds(index / 2));
        job.setState(index % 2 == 0 ? "WAITING_DTN" : "COMPLETED");
        job.setSenderAgentId("sender-1");
        job.setReceiverAgentId("receiver-1");
        job.setTestStartedAt(job.getCreatedAt().plusNanos(123456789));
        if (index % 4 == 0) {
            job.setSentJson("large sent payload ".repeat(8192));
            job.setReceivedJson("large received payload ".repeat(8192));
            job.setReceivedRawJson("large original body ".repeat(8192));
            job.setReferenceSourceBase64("A".repeat(131072));
            job.setObservationsJson("large observations ".repeat(8192));
            job.setDelayEvidenceJson("large delay evidence ".repeat(8192));
            job.setSenderClockJson("{\"source\":\"SYSTEM\"}");
            job.setExpectedPayloadSha256("A".repeat(64));
            job.setHdtnConfigJson("{\"maxNumberOfBundlesInPipeline\":50}");
            job.setDtnConfigJson("{\"routingMode\":\"StaticPlan\"}");
            job.setSelectedEpochJson("{\"recordIndex\":4,\"week\":2400,\"towSeconds\":100000}");
            job.setReferenceJson("[{\"week\":2400},{\"week\":2401}]");
            job.setReceiverJson("[{\"week\":2400}]");
            job.setFileResultJson("{\"match\":true}");
            job.setComparisonJson("{\"verdict\":\"PASS\",\"comparableEpochs\":1,\"velocityComparableEpochs\":0}");
        } else if (index % 4 == 1) {
            job.setReceivedAt(job.getCreatedAt().plusSeconds(1));
        } else if (index % 4 == 2) {
            job.setSentJson("");
            job.setReceivedJson("");
            job.setReceivedRawJson("");
        }
        return job;
    }

    @Test
    void keepsSummaryFieldsPagingFiltersAndDefaultsWithoutLoadingPayloads() throws Exception {
        var ordered = new ArrayList<DtnJob>();
        for (int i = 0; i < 62; i++) ordered.add(jobs.save(job(i)));
        var legacy = job(62);
        legacy.setCreatedAt(null);
        ordered.add(jobs.save(legacy));
        jobs.flush();
        em.clear();
        ordered.sort(Comparator.comparing(DtnJob::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(DtnJob::getId).reversed());
        var service = service();
        var json = new ObjectMapper().findAndRegisterModules();
        var controller = new DtnController(service, json);
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        var sql = new ArrayList<String>();
        List<Map<String, Object>> first;
        statements.set(sql);
        try {
            first = controller.recent(0, null).getBody();
        } finally {
            statements.remove();
        }
        assertEquals(50, first.size());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(1, sql.size(), "목록마다 추가 원문 조회나 개별 시험 조회를 실행하지 않는다.");
        String select = sql.getFirst().toLowerCase(Locale.ROOT);
        for (String excluded : List.of("observations_json", "reference_source_base64", "delay_evidence_json",
                "sender_clock_json", "receiver_clock_json", "receiver_registration_clock_json")) {
            assertFalse(select.contains(excluded), excluded + " must not be selected");
        }
        for (String raw : List.of("sent_json", "received_json", "received_raw_json")) {
            assertTrue(select.contains(raw + " is not null"), raw + " uses an existence check");
            assertFalse(select.matches("(?s).*\\w+\\." + raw + "\\s*,.*"), raw + " must not be returned");
        }
        assertEquals(ordered.subList(0, 50).stream().map(DtnJob::getId).toList(),
                first.stream().map(row -> (UUID) row.get("testId")).toList());
        var complete = first.get(1);
        assertEquals(true, complete.get("sentPayloadAvailable"));
        assertEquals(true, complete.get("receivedPayloadAvailable"));
        assertEquals(true, complete.get("receivedOriginalAvailable"));
        assertEquals(true, complete.get("comparisonOnSender"));
        assertEquals(1, complete.get("receivedEpochs"));
        assertEquals(2, complete.get("referenceEpochs"));
        assertEquals("PASS", complete.get("verdict"));
        assertEquals(1, complete.get("comparableEpochs"));
        assertEquals(0, complete.get("velocityComparableEpochs"));
        assertEquals(true, first.getFirst().get("dtnReceived"));
        assertEquals(false, first.getFirst().get("receivedPayloadAvailable"));
        assertNull(first.getFirst().get("testType"), "누락된 유형을 AFS로 추정하지 않는다.");
        assertEquals("RESTORE", first.getFirst().get("comparisonMode"));
        assertEquals("GPS", first.getFirst().get("pvtConstellation"));
        assertNull(first.getFirst().get("hdtnConfig"));
        assertEquals(false, first.get(2).get("dtnReceived"));
        assertEquals(true, first.get(3).get("receivedOriginalAvailable"), "빈 문자열도 기존과 같이 저장됨으로 판정한다.");
        for (int i = 0; i < first.size(); i++) {
            assertEquals(controller.get(ordered.get(i).getId()).getBody(), first.get(i),
                    "목록과 기존 단건 요약의 필드·null·판정·Epoch 개수가 같아야 한다.");
        }
        var second = controller.recent(1, null).getBody();
        assertEquals(ordered.subList(50, 63).stream().map(DtnJob::getId).toList(),
                second.stream().map(row -> (UUID) row.get("testId")).toList());
        assertTrue(controller.recent(2, null).getBody().isEmpty());
        assertEquals(first, controller.recent(0, " ").getBody());
        var waiting = controller.recent(0, "WAITING_DTN").getBody();
        assertEquals(ordered.stream().filter(job -> "WAITING_DTN".equals(job.getState()))
                        .map(DtnJob::getId).toList(),
                waiting.stream().map(row -> (UUID) row.get("testId")).toList());
        assertTrue(controller.recent(1, "WAITING_DTN").getBody().isEmpty());
        assertEquals("목록 페이지 오류", assertThrows(IllegalArgumentException.class,
                () -> service.recent(-1, null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.recent(100001, null));
        assertEquals("목록 상태 오류", assertThrows(IllegalArgumentException.class,
                () -> service.recent(0, "FAILED")).getMessage());
    }

    @Test
    void fillsOnlyMissingMetadataWithoutChangingStateResultsOrDeletedRecords() {
        DtnJob job = job(0);
        job.setTestType(null);
        job.setSenderMode(null);
        job.setReceiverMode(null);
        job.setState("CANCELLED");
        job.setUpdatedAt(Instant.parse("2026-10-28T01:00:00Z"));
        jobs.saveAndFlush(job);
        em.clear();
        assertTrue(jobs.findMissingTrialMetadata().contains(job.getId()));
        assertEquals(0, jobs.fillTrialMetadata(job.getId(), "b".repeat(64), "IQ_SAMPLE", "DTN", "HDTN"));
        assertEquals(1, jobs.fillTrialMetadata(job.getId(), job.getExpectedPayloadSha256(), "IQ_SAMPLE", "DTN", "HDTN"));
        DtnJob filled = jobs.findById(job.getId()).orElseThrow();
        assertEquals("IQ_SAMPLE", filled.getTestType());
        assertEquals("DTN", filled.getSenderMode());
        assertEquals("HDTN", filled.getReceiverMode());
        assertEquals("CANCELLED", filled.getState());
        assertEquals(job.getUpdatedAt(), filled.getUpdatedAt());
        assertEquals(job.getSentJson(), filled.getSentJson());
        assertEquals(job.getReceivedRawJson(), filled.getReceivedRawJson());
        assertEquals(job.getReceiverJson(), filled.getReceiverJson());
        assertEquals(job.getComparisonJson(), filled.getComparisonJson());
        assertFalse(jobs.findMissingTrialMetadata().contains(job.getId()));
        assertEquals(0, jobs.fillTrialMetadata(job.getId(), job.getExpectedPayloadSha256(), "GNSS_RAW", "DTN", "HDTN"));
        assertEquals(1, jobs.fillTrialMetadata(job.getId(), job.getExpectedPayloadSha256(), null, null, null));
        assertEquals("IQ_SAMPLE", jobs.findById(job.getId()).orElseThrow().getTestType());
        jobs.deleteById(job.getId());
        jobs.flush();
        assertEquals(0, jobs.fillTrialMetadata(job.getId(), job.getExpectedPayloadSha256(), "IQ_SAMPLE", "DTN", "HDTN"));
        assertFalse(jobs.existsById(job.getId()));
    }
    private static String historyPvt(double x) {
        return "[{\"positionValid\":true,\"velocityValid\":false,\"ecefMeters\":["
                + x + ",0,0]}]";
    }

    @Test
    void historyKeyFollowsInputEpochTypeAndReferenceButNotRouteTimeOrSettings() throws Exception {
        var service = service();
        var input = UUID.randomUUID();
        var reference = new server.common.DtnModels.Pvt();
        reference.setPositionValid(true);
        reference.setEcefMeters(new double[]{6378137, 0, 0});
        var a = job(1);
        a.setInputId(input);
        a.setTestType("GNSS_RAW");
        a.setComparisonMode("DELAY");
        a.setSelectedEpochJson("{\"recordIndex\":4,\"week\":2400,\"towSeconds\":100000}");
        service.assignPvtHistory(a, List.of(reference));
        String key = a.getPvtHistoryKey();
        assertNotNull(key);
        assertEquals(64, key.length());

        a.setId(UUID.randomUUID());
        a.setTestStartedAt(Instant.now());
        a.setSenderMode("HDTN");
        a.setHdtnConfigJson("{\"maxNumberOfBundlesInPipeline\":500}");
        service.assignPvtHistory(a, List.of(reference));
        assertEquals(key, a.getPvtHistoryKey());
        for (String epoch : List.of(
                "{\"recordIndex\":5,\"week\":2400,\"towSeconds\":100000}",
                "{\"recordIndex\":4,\"week\":2401,\"towSeconds\":100000}",
                "{\"recordIndex\":4,\"week\":2400,\"towSeconds\":100001}")) {
            a.setSelectedEpochJson(epoch);
            service.assignPvtHistory(a, List.of(reference));
            assertNotEquals(key, a.getPvtHistoryKey());
        }
        a.setSelectedEpochJson("{\"recordIndex\":4,\"week\":2400,\"towSeconds\":100000}");
        service.assignPvtHistory(a, List.of(reference));
        assertEquals(key, a.getPvtHistoryKey(), "다른 Epoch를 선택했다가 돌아오면 같은 묶음이다.");
        a.setInputId(UUID.randomUUID());
        service.assignPvtHistory(a, List.of(reference));
        assertNotEquals(key, a.getPvtHistoryKey());
        a.setInputId(input);
        a.setTestType("AFS_METADATA");
        service.assignPvtHistory(a, List.of(reference));
        assertNotEquals(key, a.getPvtHistoryKey());
        a.setTestType("GNSS_RAW");
        reference.setEcefMeters(new double[]{6378138,0,0});
        service.assignPvtHistory(a, List.of(reference));
        assertNotEquals(key, a.getPvtHistoryKey());
        var iq = job(2);
        iq.setTestType("IQ_SAMPLE");
        service.assignPvtHistory(iq,List.of(reference));
        assertNull(iq.getPvtHistoryKey());
    }

    @Test
    void historyReadsAllRoundsWithoutRawPayloadsAndKeepsInvalidResultsOutOfTheTarget() throws Exception {
        String key = "A".repeat(64);
        var saved = new ArrayList<DtnJob>();
        for (int i = 0; i < 68; i++) {
            var row = job(i);
            row.setPvtHistoryKey(key);
            row.setTestType("GNSS_RAW");
            row.setState("COMPLETED");
            row.setReferenceStatus("COMPLETE");
            row.setReferenceJson(historyPvt(6378137));
            row.setReceiverJson(historyPvt(6378137 + i / 100.0));
            row.setReceivedAt(row.getTestStartedAt().plusNanos(123456789));
            row.setSenderMode(i % 2 == 0 ? "DTN" : "HDTN");
            row.setReceiverMode("HDTN");
            if (i == 1) row.setState("INCONCLUSIVE"); // 속도가 무효여도 위치 비교는 가능하다.
            if (i == 2) { row.setState("WAITING_DTN"); row.setReceiverJson(null); }
            if (i == 3) row.setReferenceStatus("WAITING");
            if (i == 4) row.setState("FAILED");
            if (i == 5) row.setState("CANCELLED");
            if (i == 6) row.setReferenceJson(historyPvt(6378139));
            if (i == 7) row.setReceiverJson("[{\"positionValid\":true,\"ecefMeters\":[null,0,0]}]");
            saved.add(jobs.save(row));
        }
        var legacy = job(70);
        legacy.setTestType("IQ_SAMPLE");
        jobs.save(legacy);
        jobs.flush();
        em.clear();
        var sql = new ArrayList<String>();
        DtnService.PvtHistory history;
        statements.set(sql);
        try {
            history = service().pvtHistory(saved.getFirst().getId());
        } finally {
            statements.remove();
        }
        assertEquals(68,history.total());
        assertEquals(2,history.waiting());
        assertEquals(4,history.unavailable());
        assertEquals(62,history.trials().stream().filter(t -> t.ecefMeters() != null).count());
        assertEquals("REFERENCE_MISMATCH",history.trials().get(6).availability());
        assertEquals("AVAILABLE",history.trials().get(1).availability());
        assertEquals(0.123456789,history.trials().getFirst().delaySeconds(),1e-12);
        assertEquals(saved.stream().map(DtnJob::getId).toList(),history.trials().stream().map(DtnService.PvtHistoryItem::testId).toList());
        assertEquals(2,sql.size(),"묶음 조회는 페이지별·시험별 추가 조회 없이 수행한다.");
        for (var statement : sql) {
            for (var excluded : List.of("sent_json","received_json","received_raw_json","observations_json",
                    "reference_source_base64","delay_evidence_json","hdtn_config_json")) {
                assertFalse(statement.toLowerCase(Locale.ROOT).contains(excluded),excluded);
            }
        }
        jobs.deleteById(saved.getFirst().getId());
        jobs.flush();
        em.clear();
        assertEquals(67,service().pvtHistory(saved.get(1).getId()).total());
        assertNull(service().pvtHistory(legacy.getId()).historyKey());
    }

}
