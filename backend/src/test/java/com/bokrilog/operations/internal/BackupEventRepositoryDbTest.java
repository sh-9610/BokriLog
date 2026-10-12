package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.bokrilog.PostgresTestcontainersConfig;
import com.bokrilog.operations.BackupRecordApi.Event;
import com.bokrilog.operations.BackupRecordApi.Row;
import com.bokrilog.operations.BackupRecordApi.Store;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Story 3.9: {@link BackupEventRepository}의 읽기 SQL을 실제 PostgreSQL에서 본다 — 마지막 성공 시각(성공이 없으면 비어 있음, 값은 DB
 * 시각), 저장소별 최신 측정({@code DISTINCT ON}이 저장소마다 가장 늦은 행을 고르고, code가 비면 기한 초과가 아니며, 측정이 없는 저장소는
 * 빠진다), 읽기 트랜잭션의 {@code statement_timeout}.
 *
 * <p>공유 시험 컨테이너 안의 새 database를 쓴다 — 다른 시험이 같은 표에 쓴 행과 섞이지 않는다.
 */
class BackupEventRepositoryDbTest {

    private static JdbcClient jdbc;

    private static TransactionTemplate transaction;

    private BackupEventRepository repository;

    @BeforeAll
    static void database() throws Exception {
        PostgreSQLContainer container = PostgresTestcontainersConfig.shared();
        String database = "backup_repository_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword()); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = container.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        Flyway.configure().dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, container.getUsername(),
                container.getPassword());
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE TABLE operations_backup_event").update();
        this.repository = new BackupEventRepository(jdbc);
    }

    @Test
    void 성공이_없으면_마지막_성공_시각이_비어_있다() {
        append(new Row(Event.STARTED, "gen_20261010", null, null, null, null, null, null, null));
        append(new Row(Event.FAILED, "gen_20261010", Store.OFFSITE, "UPLOAD_OFFSITE", null, null, null, null, null));
        assertThat(this.repository.lastSuccessEpochSeconds()).isEmpty();
        assertThat(this.repository.latestMeasured()).isEmpty();
    }

    @Test
    void 마지막_성공_시각은_가장_늦은_성공_기록의_DB_시각이다() {
        append(success("gen_20261010"));
        append(success("gen_20261011"));
        double recorded = jdbc.sql("SELECT CAST(extract(epoch FROM max(recorded_at)) AS double precision) "
                + "FROM operations_backup_event WHERE event = 'SUCCEEDED'").query(Double.class).single();
        double now = jdbc.sql("SELECT CAST(extract(epoch FROM statement_timestamp()) AS double precision)")
                .query(Double.class).single();
        assertThat(this.repository.lastSuccessEpochSeconds()).hasValueSatisfying(seconds -> {
            assertThat(seconds).isEqualTo(recorded, within(0.001));
            assertThat(seconds).isLessThanOrEqualTo(now).isGreaterThan(now - 60);
        });
        assertThat(this.repository.succeeded("gen_20261011")).isTrue();
        assertThat(this.repository.succeeded("gen_20261012")).isFalse();
    }

    @Test
    void 측정은_저장소마다_가장_늦은_행이고_code가_비면_기한_초과가_아니다() {
        append(measured(Store.OCI, 100, null));
        append(measured(Store.OCI, 300, "PURGE_OVERDUE"));
        append(measured(Store.OCI, 200, null));
        append(measured(Store.OFFSITE, 50, "PURGE_OVERDUE"));
        Map<Store, BackupEventRepository.Measured> latest = this.repository.latestMeasured();
        assertThat(latest).containsOnlyKeys(Store.OCI, Store.OFFSITE);
        assertThat(latest.get(Store.OCI).bytes()).as("가장 늦은 측정").isEqualTo(200);
        assertThat(latest.get(Store.OCI).overdue()).isFalse();
        assertThat(latest.get(Store.OCI).quotaBytes()).isEqualTo(1_000);
        assertThat(latest.get(Store.OFFSITE).overdue()).isTrue();
        assertThat(latest.get(Store.OFFSITE).measuredAtEpochSeconds()).isPositive();

        // 측정이 없는 저장소는 빠진다.
        jdbc.sql("TRUNCATE TABLE operations_backup_event").update();
        append(measured(Store.OFFSITE, 10, null));
        assertThat(this.repository.latestMeasured()).containsOnlyKeys(Store.OFFSITE);
    }

    @Test
    void 읽기_트랜잭션의_statement_timeout은_그_트랜잭션에만_걸린다() {
        String inside = transaction.execute(status -> {
            this.repository.limitStatementTime("2s");
            this.repository.lastSuccessEpochSeconds();
            this.repository.latestMeasured();
            return jdbc.sql("SHOW statement_timeout").query(String.class).single();
        });
        assertThat(inside).isEqualTo("2s");
        String after = jdbc.sql("SHOW statement_timeout").query(String.class).single();
        assertThat(after).isNotEqualTo("2s");
    }

    // ------------------------------------------------------------------ helpers

    private void append(Row row) {
        this.repository.append(row);
    }

    private static Row success(String generation) {
        return new Row(Event.SUCCEEDED, generation, null, null, 10L, null, null, "a".repeat(64), "k1");
    }

    private static Row measured(Store store, long bytes, String code) {
        return new Row(Event.MEASURED, "gen_20261010", store, code, bytes, 1, 1_000L, null, null);
    }
}
