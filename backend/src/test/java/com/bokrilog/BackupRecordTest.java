package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.BackupRecordApi;
import com.bokrilog.operations.BackupRecordApi.Batch;
import com.bokrilog.operations.BackupRecordApi.Event;
import com.bokrilog.operations.BackupRecordApi.Row;
import com.bokrilog.operations.BackupRecordApi.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 3.9: 백업 기록의 일회성 역할({@code backup-record}).
 *
 * <p>이 역할은 웹 서버도 Flyway 검사도 없이 떠서 {@code operations_backup_event}에 행을 더한다. 행의 모양(사건마다 채울 수 있는 칸),
 * 파기 실패·기한 초과의 감사 SYSTEM 행({@code command=backup}·{@code scope=BACKUP}·{@code backup:} keyed digest·닫힌 details),
 * 모양 밖 입력의 무기록 거절, 같은 generation의 두 번째 성공 거절을 본다. schema는 시험이 먼저 적용한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backup-record")
@Import(PostgresTestcontainersConfig.class)
class BackupRecordTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ACTOR = "backup-timer-test";

    /** 기동 시 runner가 쓰는 실행. */
    private static final String RUNNER_OPERATION = "backup_runner_" + UUID.randomUUID().toString().replace("-", "");

    private static final String RUNNER_GENERATION = generation();

    /** 시험용 암호문 checksum. 64자 16진은 실행 시점에 만든다(gitleaks). */
    private static final String SHA = hex(RUNNER_OPERATION);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private BackupRecordApi records;

    @Autowired
    private JdbcClient jdbc;

    @BeforeAll
    static void schema() {
        PostgreSQLContainer container = PostgresTestcontainersConfig.shared();
        Flyway.configure().dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @DynamicPropertySource
    static void backupRecordProperties(DynamicPropertyRegistry registry) {
        OperatorTestProperties.registerProperties(registry);
        // 이 컨텍스트는 시험 JVM의 컨텍스트 캐시에 남는다. 일회성 역할은 연결이 거의 필요 없으므로 풀을 작게 둬 공유 PostgreSQL의
        // 연결 한도를 다른 시험 몫으로 남긴다(기본 10개를 쥐고 있으면 뒤의 전용 연결 시험이 "too many clients"로 실패한다).
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("bokrilog.backup.record.operation", () -> RUNNER_OPERATION);
        registry.add("bokrilog.backup.record.actor", () -> ACTOR);
        registry.add("bokrilog.backup.record.rehearsal", () -> "true");
        registry.add("bokrilog.backup.record.events", () -> String.join(";",
                "SUCCEEDED," + RUNNER_GENERATION + ",,,4096,,," + SHA + ",k2026a",
                "PURGED," + RUNNER_GENERATION + ",oci,AGE,2048,2,,,k2025z",
                "PURGED," + RUNNER_GENERATION + ",,LOCAL_TEMP,,1,,,",
                "MEASURED," + RUNNER_GENERATION + ",oci,,8192,3,20000,,",
                "MEASURED," + RUNNER_GENERATION + ",offsite,PURGE_OVERDUE,8192,3,40000,,"));
    }

    @Test
    void 역할은_웹_서버도_Flyway_검사도_없다() {
        assertThat(this.context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(this.context.getBeanProvider(Flyway.class).getIfAvailable()).isNull();
        assertThat(this.context.getBeanNamesForType(BokrilogRole.class)).isEmpty();
    }

    @Test
    void 기동한_runner가_행들을_순서대로_썼다() {
        List<Map<String, Object>> rows = rows(RUNNER_GENERATION);
        assertThat(rows).extracting(row -> row.get("event")).containsExactly("SUCCEEDED", "PURGED", "PURGED",
                "MEASURED", "MEASURED");
        Map<String, Object> success = rows.getFirst();
        assertThat(success).containsEntry("bytes", 4096L).containsEntry("sha256", SHA).containsEntry("key_id", "k2026a");
        assertThat(success.get("store")).isNull();
        assertThat(success.get("recorded_at")).isNotNull();
        assertThat(rows.get(1)).containsEntry("store", "oci").containsEntry("code", "AGE").containsEntry("count", 2);
        assertThat(rows.get(2).get("store")).as("로컬 임시본").isNull();
        assertThat(rows.get(4)).containsEntry("store", "offsite").containsEntry("code", "PURGE_OVERDUE")
                .containsEntry("quota_bytes", 40000L);
        // 감사 행을 남기는 사건(파기 실패·기한 초과)이 없었다.
        assertThat(auditRows(RUNNER_OPERATION)).isEmpty();
    }

    @Test
    void 파기_실패와_기한_초과는_감사_SYSTEM_행을_같이_남긴다() throws Exception {
        String operation = operation();
        String generation = generation();
        var recorded = this.records.record(new Batch(operation, ACTOR, List.of(
                new Row(Event.STARTED, generation(), null, null, null, null, null, null, null),
                new Row(Event.PURGE_FAILED, generation, Store.OFFSITE, "DELETE_FAILED", null, 2, null, null, "k1"),
                new Row(Event.PURGE_OVERDUE, generation, Store.OFFSITE, null, null, null, null, null, "k1")), true));
        assertThat(recorded.rows()).isEqualTo(3);
        assertThat(recorded.auditRows()).isEqualTo(2);
        List<Map<String, Object>> audit = auditRows(operation);
        assertThat(audit).extracting(row -> row.get("reason")).containsExactly("PURGE_FAILED", "PURGE_OVERDUE");
        for (Map<String, Object> row : audit) {
            assertThat(row).containsEntry("actor_kind", "SYSTEM").containsEntry("command", "backup")
                    .containsEntry("scope", "BACKUP").containsEntry("target", generation)
                    .containsEntry("outcome", "FAILED").containsEntry("policy_version", "BACKUP-1.0")
                    .containsEntry("correlation_id", operation);
            assertThat(row.get("actor_user_id")).isNull();
            assertThat(row.get("decided_at")).isNotNull();
            assertThat((String) row.get("actor_digest")).matches("[0-9a-f]{64}").doesNotContain(ACTOR);
            Map<String, Object> details = details(row);
            assertThat(details.keySet()).isSubsetOf("store", "code", "rehearsal");
            assertThat(details).containsEntry("store", "offsite").containsEntry("rehearsal", true);
            assertThat(details.toString()).doesNotContain(ACTOR);
        }
        assertThat(details(audit.getFirst())).containsEntry("code", "DELETE_FAILED");
        // 같은 수행자는 같은 digest로 묶인다.
        assertThat(audit).extracting(row -> row.get("actor_digest")).containsOnly(audit.getFirst().get("actor_digest"));
    }

    @Test
    void 같은_generation의_두_번째_성공은_거절하고_아무것도_쓰지_않는다() {
        String generation = generation();
        Row success = new Row(Event.SUCCEEDED, generation, null, null, 10L, null, null, hex(generation), "k1");
        this.records.record(new Batch(operation(), ACTOR, List.of(success), false));
        long before = rows(generation).size();
        assertThatThrownBy(() -> this.records.record(new Batch(operation(), ACTOR, List.of(
                new Row(Event.STARTED, generation, null, null, null, null, null, null, null), success), false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rows(generation)).hasSize((int) before);
    }

    @Test
    void 모양_밖의_행은_아무것도_쓰지_않고_거절한다() {
        String generation = generation();
        String sha = hex(generation);
        List<Row> refused = List.of(
                new Row(Event.STARTED, "gen_2026101", null, null, null, null, null, null, null),
                new Row(Event.STARTED, generation, Store.OCI, null, null, null, null, null, null),
                // 성공은 저장소 하나의 것이 아니다 — 한쪽 업로드만으로 성공을 기록하지 않는다.
                new Row(Event.SUCCEEDED, generation, Store.OCI, null, 10L, null, null, sha, "k1"),
                new Row(Event.SUCCEEDED, generation, null, null, 0L, null, null, sha, "k1"),
                new Row(Event.SUCCEEDED, generation, null, null, 10L, null, null, null, "k1"),
                new Row(Event.SUCCEEDED, generation, null, null, 10L, null, null, sha, null),
                new Row(Event.SUCCEEDED, generation, null, null, 10L, null, null, sha.toUpperCase(), "k1"),
                new Row(Event.SUCCEEDED, generation, null, null, 10L, null, null, sha, "K.1"),
                new Row(Event.FAILED, generation, null, null, null, null, null, null, null),
                new Row(Event.FAILED, generation, null, "dump", null, null, null, null, null),
                new Row(Event.PURGED, generation, Store.OCI, null, null, null, null, null, null),
                new Row(Event.PURGE_OVERDUE, generation, null, null, null, null, null, null, null),
                new Row(Event.MEASURED, generation, Store.OCI, null, 1L, 1, null, null, null),
                new Row(Event.MEASURED, generation, Store.OCI, "AGE", 1L, 1, 10L, null, null),
                new Row(Event.MEASURED, generation, Store.OCI, null, -1L, 1, 10L, null, null),
                new Row(Event.PURGED, generation, Store.OCI, "AGE", null, null, 10L, null, null));
        for (Row row : refused) {
            // 앞의 정상 행과 함께 보내도 아무것도 쓰지 않는다.
            Batch batch = new Batch(operation(), ACTOR,
                    List.of(new Row(Event.STARTED, generation, null, null, null, null, null, null, null), row), false);
            assertThatThrownBy(() -> this.records.record(batch)).as(row.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> this.records.record(new Batch("op with space", ACTOR,
                List.of(new Row(Event.STARTED, generation, null, null, null, null, null, null, null)), false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.records.record(new Batch(operation(), "actor with space",
                List.of(new Row(Event.STARTED, generation, null, null, null, null, null, null, null)), false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.records.record(new Batch(operation(), ACTOR, List.of(), false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rows(generation)).isEmpty();
    }

    @Test
    void 표의_CHECK가_service와_같은_규칙을_강제한다() {
        String generation = generation();
        // 시험만 표에 직접 쓴다. 성공에 저장소를 달면 CHECK가 거절한다.
        assertThatThrownBy(() -> this.jdbc.sql("INSERT INTO operations_backup_event (generation_id, event, store, bytes, "
                        + "sha256, key_id) VALUES (:g, 'SUCCEEDED', 'oci', 10, :sha, 'k1')")
                .param("g", generation).param("sha", hex(generation)).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> this.jdbc.sql("INSERT INTO operations_backup_event (generation_id, event) "
                        + "VALUES (:g, 'FAILED')").param("g", generation).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> this.jdbc.sql("INSERT INTO operations_backup_event (generation_id, event, store, bytes, "
                        + "count) VALUES (:g, 'MEASURED', 'oci', 1, 1)").param("g", generation).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(rows(generation)).isEmpty();
    }

    @Test
    void runner는_칸_수와_닫힌_목록과_정수_모양을_거절한다() {
        BackupRecordApi unused = batch -> {
            throw new AssertionError("거절해야 할 입력이 기록까지 갔다.");
        };
        String generation = generation();
        for (String events : new String[] {"STARTED," + generation, "BEGUN," + generation + ",,,,,,,",
                "PURGED," + generation + ",moon,AGE,,,,,", "MEASURED," + generation + ",oci,,-1,1,10,,",
                "MEASURED," + generation + ",oci,,1.5,1,10,,", "PURGED," + generation + ",oci,AGE,,x,,,"}) {
            BackupRecordRunner runner = new BackupRecordRunner(unused, operation(), ACTOR, events, false);
            assertThatThrownBy(() -> runner.run(null)).as(events).isInstanceOf(IllegalArgumentException.class);
        }
        // 빈 칸은 값이 없다는 뜻이고 저장소는 소문자로 받는다.
        BackupRecordRunner runner = new BackupRecordRunner(unused, operation(), ACTOR,
                "MEASURED," + generation + ",offsite,,1,2,3,,;", false);
        Row row = runner.batch().rows().getFirst();
        assertThat(row.store()).isEqualTo(Store.OFFSITE);
        assertThat(row.code()).isNull();
        assertThat(row.bytes()).isEqualTo(1L);
        assertThat(row.count()).isEqualTo(2);
        assertThat(row.quotaBytes()).isEqualTo(3L);
    }

    // ------------------------------------------------------------------ helpers

    private static String operation() {
        return "backup_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 시험마다 다른 generation. 날짜 모양이면 되고 실제 날짜일 필요는 없다. */
    private static String generation() {
        return "gen_" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
    }

    private List<Map<String, Object>> rows(String generation) {
        return this.jdbc.sql("SELECT event, store, code, bytes, count, quota_bytes, sha256, key_id, recorded_at "
                        + "FROM operations_backup_event WHERE generation_id = :g ORDER BY sequence")
                .param("g", generation).query().listOfRows();
    }

    private List<Map<String, Object>> auditRows(String operation) {
        return this.jdbc.sql("SELECT actor_kind, actor_user_id, actor_digest, command, scope, target, reason, outcome, "
                        + "policy_version, decided_at, correlation_id, CAST(details AS text) AS details "
                        + "FROM operations_audit_event WHERE operation_id = :op ORDER BY sequence")
                .param("op", operation).query().listOfRows();
    }

    private static Map<String, Object> details(Map<String, Object> row) {
        return JSON.readValue((String) row.get("details"), new TypeReference<Map<String, Object>>() {});
    }

    private static String hex(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
