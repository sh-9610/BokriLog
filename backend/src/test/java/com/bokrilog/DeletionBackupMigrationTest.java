package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.identity.internal.IdentityAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Story 5.3: V32의 되채우기({@code RestrictionMigrationTest}와 같은 관용).
 *
 * <p>V31까지 올린 별도 데이터베이스에 옛 모양 tombstone(Story 3.10·5.2 — 삭제를 끝낸 트랜잭션이 쓴 행), 진행 중 실행(요청 행 있음), 끝난
 * 실행(요청 행 없음)을 넣고 V32를 적용한다. 옛 tombstone은 요청 시각에 닫힌 집합·{@code PENDING}이고, 진행 중 실행은 요청 ID를 받으며 끝난
 * 실행은 비어 있다. 요청 ID가 빈 진행 중 실행은 claim이 요청 행에서 채운다(운영 repository를 그 데이터베이스 위에 세워 부른다).
 */
class DeletionBackupMigrationTest extends PlanSyncTestBase {

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void v32는_옛_tombstone을_요청_시각에_닫고_진행_중_실행에_요청_ID를_채운다() throws Exception {
        String probe = "v31_probe_" + UUID.randomUUID().toString().replace("-", "");
        jdbc().sql("CREATE DATABASE " + probe).update();
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + probe;
            var dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("31").load().migrate();
            JdbcClient probeJdbc = JdbcClient.create(dataSource);
            UUID running = UUID.randomUUID();
            UUID finished = UUID.randomUUID();
            probeJdbc.sql("INSERT INTO identity_account_lifecycle (user_id, state) VALUES (:a, 'DELETION_IN_PROGRESS'), "
                    + "(:b, 'DELETED')").param("a", running).param("b", finished).update();
            probeJdbc.sql("INSERT INTO identity_deletion_request (request_id, user_id, policy_version, notice_hash, "
                            + "idempotency_key_digest) VALUES ('del_migration_running', :a, 'ACCOUNT-DELETION-1.0', :n, :k)")
                    .param("a", running).param("n", sha256("migration-notice")).param("k", sha256("migration-key")).update();
            probeJdbc.sql("INSERT INTO identity_deletion_run (user_id, status) VALUES (:a, 'QUEUED')")
                    .param("a", running).update();
            probeJdbc.sql("INSERT INTO identity_deletion_run (user_id, status, operational_erased_at) VALUES "
                    + "(:b, 'OPERATIONAL_DATA_ERASED', now())").param("b", finished).update();
            probeJdbc.sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, "
                            + "origin) SELECT 'del_migration_old', :d, t.at, t.at + interval '840 hours', 'DELETION' "
                            + "FROM (SELECT date_trunc('milliseconds', now()) - interval '3 days' AS at) t")
                    .param("d", sha256("migration-digest")).update();

            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load()
                    .migrate();

            assertThat(probeJdbc.sql("SELECT status || '|' || (closed_at = requested_at) || '|' || policy_version || '|' "
                            + "|| (overdue_at IS NULL) || '|' || (destroyed_at IS NULL) FROM operations_deletion_tombstone")
                    .query(String.class).single()).isEqualTo("PENDING|true|DELETION-TOMBSTONE-1.0|true|true");
            assertThat(probeJdbc.sql("SELECT coalesce(request_id, '-') FROM identity_deletion_run ORDER BY status")
                    .query(String.class).list()).containsExactly("-", "del_migration_running");

            // 요청 ID가 빈 진행 중 실행(되채우기 뒤 지워진 경우를 흉내 낸다)은 claim이 요청 행에서 채운다.
            probeJdbc.sql("UPDATE identity_deletion_run SET request_id = NULL WHERE user_id = :a").param("a", running)
                    .update();
            Class<?> repositoryType = IdentityAccountRepository.class;
            var constructor = repositoryType.getDeclaredConstructor(JdbcClient.class);
            constructor.setAccessible(true);
            Object repository = constructor.newInstance(probeJdbc);
            Object claimed = repositoryType.getMethod("claimExistingRun", long.class).invoke(repository, 30_000L);
            assertThat(claimed).isEqualTo(Optional.of(new IdentityAccountRepository.ClaimedRun(running, 1)));
            assertThat(probeJdbc.sql("SELECT request_id FROM identity_deletion_run WHERE user_id = :a").param("a", running)
                    .query(String.class).single()).isEqualTo("del_migration_running");
            // 새 단계 실패 code(TOMBSTONE_FAILED)를 받는다.
            probeJdbc.sql("INSERT INTO identity_deletion_step (user_id, step, position, status, failure_code) VALUES "
                    + "(:a, 'CREDENTIALS', 1, 'RETRY_NEEDED', 'TOMBSTONE_FAILED')").param("a", running).update();
        } finally {
            jdbc().sql("DROP DATABASE IF EXISTS " + probe + " WITH (FORCE)").update();
        }
    }
}
