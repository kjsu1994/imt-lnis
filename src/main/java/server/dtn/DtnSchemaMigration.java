package server.dtn;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Hibernate가 기존 H2 시각 컬럼의 정밀도를 변경하지 않는 경우를 보정한다. */
@Component
@DependsOn("entityManagerFactory")
@RequiredArgsConstructor
@Slf4j
public class DtnSchemaMigration implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;

    /** 요청 처리와 스케줄러 시작 전에 실행하며, 이미 보정된 DB는 변경하지 않는다. */
    @Override
    public void afterSingletonsInstantiated() {
        for (String column : List.of("TEST_STARTED_AT", "RECEIVED_AT")) {
            Integer precision =
                    jdbc.queryForObject(
                            """
                            SELECT DATETIME_PRECISION
                            FROM INFORMATION_SCHEMA.COLUMNS
                            WHERE TABLE_SCHEMA = CURRENT_SCHEMA()
                              AND TABLE_NAME = 'DTN_JOB' AND COLUMN_NAME = ?
                            """,
                            Integer.class,
                            column);
            if (precision == null) {
                throw new IllegalStateException("DTN 시각 컬럼 정밀도 확인 실패: " + column);
            }
            if (precision >= 9) {
                continue;
            }

            // 컬럼명은 위의 고정 목록만 사용한다. 기존 행과 저장된 시각은 유지한다.
            jdbc.execute(
                    "ALTER TABLE DTN_JOB ALTER COLUMN "
                            + column
                            + " TIMESTAMP(9) WITH TIME ZONE");
            log.info("DTN 시각 저장 정밀도 보정 · {} · {} → 9자리", column, precision);
        }
    }
}
