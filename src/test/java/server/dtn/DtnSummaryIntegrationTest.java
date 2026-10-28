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
        assertEquals("AFS_METADATA", first.getFirst().get("testType"));
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
}
