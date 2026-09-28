package server.dtn;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import server.node.LocalNodeLifecycle;

import java.time.Instant;
import java.util.UUID;

/** 운영 DB와 같은 6자리 컬럼을 먼저 만들고 실제 기동 및 JPA 저장을 검증한다. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=" + DtnSchemaMigrationTest.URL,
            "spring.jpa.hibernate.ddl-auto=update",
            "lnis.storage.data-directory=${java.io.tmpdir}/lnis-migration-tests",
            "lnis.storage.cleanup-delay=PT24H"
        })
@ActiveProfiles("server")
@ContextConfiguration(initializers = DtnSchemaMigrationTest.LegacyDatabase.class)
class DtnSchemaMigrationTest {
    static final String URL = "jdbc:h2:mem:lnis-time-migration;DB_CLOSE_DELAY=-1";
    static final UUID LEGACY_ID = UUID.fromString("7d0c2191-6846-4243-96d5-fe303926d7fd");

    @MockitoBean LocalNodeLifecycle localRuntime;

    @Autowired DtnRepository jobs;
    @Autowired DtnSchemaMigration migration;
    @Autowired JdbcTemplate jdbc;

    static class LegacyDatabase
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var legacy = new JdbcTemplate(new DriverManagerDataSource(URL, "sa", ""));
            legacy.execute(
                    """
                    CREATE TABLE DTN_JOB (
                        ID UUID PRIMARY KEY,
                        STATE VARCHAR(255),
                        TEST_STARTED_AT TIMESTAMP(6) WITH TIME ZONE,
                        RECEIVED_AT TIMESTAMP(6) WITH TIME ZONE
                    )
                    """);
            legacy.update(
                    "INSERT INTO DTN_JOB VALUES (?, 'FAILED', ?, ?)",
                    LEGACY_ID,
                    Instant.parse("2026-09-28T04:19:00.481123Z").atOffset(java.time.ZoneOffset.UTC),
                    Instant.parse("2026-09-28T04:19:09.657263Z").atOffset(java.time.ZoneOffset.UTC));
        }
    }

    @Test
    void upgradesExistingColumnsBeforeJpaWritesAndPreservesHistory() {
        assertEquals(
                java.util.List.of(9, 9),
                jdbc.queryForList(
                        """
                        SELECT DATETIME_PRECISION FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME = 'DTN_JOB'
                          AND COLUMN_NAME IN ('TEST_STARTED_AT', 'RECEIVED_AT')
                        ORDER BY COLUMN_NAME
                        """,
                        Integer.class));

        var old = jobs.findById(LEGACY_ID).orElseThrow();
        assertEquals("FAILED", old.getState());
        assertEquals(Instant.parse("2026-09-28T04:19:00.481123Z"), old.getTestStartedAt());
        assertEquals(Instant.parse("2026-09-28T04:19:09.657263Z"), old.getReceivedAt());

        // 실제 실패 시각과 초 경계의 반올림 가능 값도 DB 왕복 후 정확히 유지해야 한다.
        for (String value :
                java.util.List.of(
                        "2026-09-28T04:19:00.481122676Z",
                        "2026-09-28T04:19:09.999999999Z")) {
            Instant started = Instant.parse(value);
            var job = new DtnJob();
            job.setId(UUID.randomUUID());
            job.setState("COMPLETED");
            job.setTestStartedAt(started);
            job.setReceivedAt(started.plusNanos(9_176_139_324L));
            jobs.saveAndFlush(job);

            // 다른 트랜잭션에서 조회하여 영속성 컨텍스트의 원본 객체를 읽지 않는다.
            var saved = jobs.findById(job.getId()).orElseThrow();
            assertEquals(job.getTestStartedAt(), saved.getTestStartedAt());
            assertEquals(job.getReceivedAt(), saved.getReceivedAt());
        }

        migration.afterSingletonsInstantiated();
        assertEquals(3, jobs.count());
        assertEquals(old.getTestStartedAt(), jobs.findById(LEGACY_ID).orElseThrow().getTestStartedAt());
    }
}
