package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.DeployAuditApi;
import com.bokrilog.operations.DeployAuditApi.Event;
import com.bokrilog.operations.DeployAuditApi.Outcome;
import com.bokrilog.operations.DeployAuditApi.Slot;
import com.bokrilog.operations.DeployAuditApi.Step;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Story 3.8 AC-5: 배포 감사의 일회성 역할({@code deploy-audit}).
 *
 * <p>이 역할은 웹 서버도 Flyway 검사도 없이 떠서 감사 원장에 SYSTEM 행 하나를 더한다. 행의 모양({@code command=deploy}·{@code
 * scope=DEPLOY}·target=digest·reason=단계명·기존 결과 enum), 수행자의 {@code deployer:} keyed digest, 닫힌 details(민감값 없음),
 * 제한 행을 쓰지 않음을 본다. schema는 시험이 먼저 적용한다 — 운영에서 schema를 바꾸는 것은 migrate 역할뿐이고 이 역할은 검사도
 * 하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("deploy-audit")
@Import(PostgresTestcontainersConfig.class)
class DeployAuditTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 기동 시 runner가 쓰는 operation ID. */
    private static final String RUNNER_OPERATION = "deploy_runner_" + UUID.randomUUID().toString().replace("-", "");

    private static final String ACTOR = "deployer-rehearsal";

    /** 시험용 digest 참조. 64자 16진은 실행 시점에 만든다(gitleaks). */
    private static final String TARGET = "registry.test/bokrilog/backend@sha256:" + hex("target");

    private static final String PREVIOUS = "registry.test/bokrilog/backend@sha256:" + hex("previous");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DeployAuditApi audit;

    @Autowired
    private JdbcClient jdbc;

    @BeforeAll
    static void schema() {
        // 이 역할은 Flyway를 쓰지 않으므로 시험이 같은 컨테이너에 schema를 먼저 둔다(다른 시험이 이미 뒀으면 그대로다).
        PostgreSQLContainer container = PostgresTestcontainersConfig.shared();
        Flyway.configure().dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @DynamicPropertySource
    static void deployAuditProperties(DynamicPropertyRegistry registry) {
        OperatorTestProperties.registerProperties(registry);
        registry.add("bokrilog.deploy.audit.operation", () -> RUNNER_OPERATION);
        registry.add("bokrilog.deploy.audit.step", () -> "GATE");
        registry.add("bokrilog.deploy.audit.outcome", () -> "REQUESTED");
        registry.add("bokrilog.deploy.audit.target", () -> TARGET);
        registry.add("bokrilog.deploy.audit.actor", () -> ACTOR);
        registry.add("bokrilog.deploy.audit.slot", () -> "green");
        registry.add("bokrilog.deploy.audit.previous", () -> PREVIOUS);
        registry.add("bokrilog.deploy.audit.rehearsal", () -> "true");
    }

    @Test
    void 역할은_웹_서버도_Flyway_검사도_없다() {
        assertThat(this.context).isNotInstanceOf(WebApplicationContext.class);
        // Flyway 자동 구성이 꺼져 있다 — 검증(validate)도 마이그레이션도 부를 객체가 없다.
        assertThat(this.context.getBeanProvider(Flyway.class).getIfAvailable()).isNull();
        assertThat(this.context.getBeanNamesForType(BokrilogRole.class)).isEmpty();
    }

    @Test
    void 기동한_runner가_GATE_REQUESTED_행_하나를_썼다() throws Exception {
        List<Map<String, Object>> rows = rows(RUNNER_OPERATION);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row).containsEntry("actor_kind", "SYSTEM").containsEntry("command", "deploy")
                .containsEntry("scope", "DEPLOY").containsEntry("target", TARGET).containsEntry("reason", "GATE")
                .containsEntry("outcome", "REQUESTED").containsEntry("policy_version", "DEPLOY-1.0")
                .containsEntry("correlation_id", RUNNER_OPERATION);
        assertThat(row.get("actor_user_id")).isNull();
        assertThat(row.get("decided_at")).as("요청 행은 결정 시각이 없다").isNull();
        assertThat((String) row.get("actor_digest")).matches("[0-9a-f]{64}").doesNotContain(ACTOR);
        Map<String, Object> details = details(row);
        assertThat(details).containsEntry("rehearsal", true).containsEntry("slot", "green")
                .containsEntry("previous", PREVIOUS).containsKey("activeRestrictions");
        assertThat(details.toString()).doesNotContain(ACTOR);
    }

    @Test
    void 한_배포의_단계가_같은_operation으로_순서대로_쌓인다() throws Exception {
        String operation = operation();
        long before = activeRestrictionRows();
        for (Step step : new Step[] {Step.GATE, Step.DRAINED, Step.MIGRATED, Step.INACTIVE_VERIFIED, Step.CUTOVER,
                Step.VERIFIED}) {
            this.audit.record(new Event(operation, step, step == Step.GATE ? Outcome.REQUESTED : Outcome.COMPLETED,
                    TARGET, ACTOR, null, Slot.BLUE, null, false));
        }
        List<Map<String, Object>> rows = rows(operation);
        assertThat(rows).extracting(row -> row.get("reason")).containsExactly("GATE", "DRAINED", "MIGRATED",
                "INACTIVE_VERIFIED", "CUTOVER", "VERIFIED");
        assertThat(rows).extracting(row -> row.get("outcome")).containsExactly("REQUESTED", "COMPLETED", "COMPLETED",
                "COMPLETED", "COMPLETED", "COMPLETED");
        assertThat(rows.subList(1, rows.size())).allSatisfy(row -> assertThat(row.get("decided_at")).isNotNull());
        // 같은 수행자는 같은 digest로 묶이고, 운영자·SYSTEM digest와 겹치지 않는다(접두사가 다르다).
        assertThat(rows).extracting(row -> row.get("actor_digest")).containsOnly(rows.getFirst().get("actor_digest"));
        // 리허설이 아니면 표지가 없다. details는 닫힌 키뿐이다.
        for (Map<String, Object> row : rows) {
            assertThat(details(row).keySet()).isSubsetOf("code", "slot", "previous", "rehearsal",
                    "activeRestrictions").doesNotContain("rehearsal");
        }
        // 배포는 제한 행을 쓰지 않는다.
        assertThat(activeRestrictionRows()).isEqualTo(before);
    }

    @Test
    void 실패와_되돌림은_code를_남기고_활성_제한_수를_센다() throws Exception {
        String operation = operation();
        String restriction = "deploy_restriction_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID restrictionId = UUID.randomUUID();
        int countBefore = this.audit.record(new Event(operation, Step.GATE, Outcome.REQUESTED, TARGET, ACTOR, null,
                null, null, true)).activeRestrictions();
        this.jdbc.sql("INSERT INTO operations_restriction (restriction_id, feature, scope, target, operation_id, "
                        + "origin, effective_from) VALUES (:id, 'SYNC', 'PLAN', :target, :op, 'OPERATOR', "
                        + "statement_timestamp())")
                .param("id", restrictionId).param("target", UUID.randomUUID().toString()).param("op", restriction)
                .update();
        try {
            var failed = this.audit.record(new Event(operation, Step.VERIFIED, Outcome.FAILED, TARGET, ACTOR,
                    "POST_CUTOVER_SMOKE_FAILED", Slot.GREEN, PREVIOUS, true));
            assertThat(failed.activeRestrictions()).isEqualTo(countBefore + 1);
            this.audit.record(new Event(operation, Step.ROLLED_BACK, Outcome.COMPLETED, PREVIOUS, ACTOR, null,
                    Slot.BLUE, TARGET, true));
        } finally {
            this.jdbc.sql("DELETE FROM operations_restriction WHERE restriction_id = :id").param("id", restrictionId)
                    .update();
        }
        List<Map<String, Object>> rows = rows(operation);
        assertThat(rows).extracting(row -> row.get("reason")).containsExactly("GATE", "VERIFIED", "ROLLED_BACK");
        assertThat(details(rows.get(1))).containsEntry("code", "POST_CUTOVER_SMOKE_FAILED")
                .containsEntry("rehearsal", true);
        assertThat(rows.get(2)).containsEntry("target", PREVIOUS);
    }

    @Test
    void 형식이_틀린_대상은_INVALID_REFERENCE로_남는다() throws Exception {
        String operation = operation();
        this.audit.record(new Event(operation, Step.GATE, Outcome.FAILED, null, ACTOR, "IMAGE_REFERENCE_INVALID",
                null, null, true));
        assertThat(rows(operation).getFirst()).containsEntry("target", DeployAuditApi.INVALID_TARGET);
    }

    @Test
    void 모양_밖의_입력은_아무것도_쓰지_않고_거절한다() throws Exception {
        String operation = operation();
        List<Event> refused = List.of(
                // tag 참조는 digest가 아니다.
                new Event(operation, Step.GATE, Outcome.FAILED, "registry.test/bokrilog/backend:latest", ACTOR, null,
                        null, null, false),
                new Event(operation, Step.DRAINED, Outcome.REQUESTED, TARGET, ACTOR, null, null, null, false),
                new Event("op with space", Step.GATE, Outcome.REQUESTED, TARGET, ACTOR, null, null, null, false),
                new Event(operation, Step.GATE, Outcome.FAILED, TARGET, ACTOR, "lower-case", null, null, false),
                new Event(operation, Step.GATE, Outcome.FAILED, TARGET, "deployer with space", null, null, null, false),
                new Event(operation, Step.ROLLED_BACK, Outcome.COMPLETED, TARGET, ACTOR, null, null, "latest", false),
                // 대상을 비울 수 있는 것은 GATE FAILED(형식 거부) 하나다.
                new Event(operation, Step.VERIFIED, Outcome.COMPLETED, null, ACTOR, null, null, null, false),
                new Event(operation, Step.GATE, Outcome.REQUESTED, " ", ACTOR, null, null, null, false),
                new Event(operation, Step.WINDOW_CLOSED, Outcome.COMPLETED, null, ACTOR, null, null, null, false));
        for (Event event : refused) {
            assertThatThrownBy(() -> this.audit.record(event)).as(event.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(rows(operation)).isEmpty();
    }

    @Test
    void runner는_닫힌_목록_밖의_단계와_결과를_거절한다() {
        DeployAuditApi unused = event -> {
            throw new AssertionError("거절해야 할 입력이 기록까지 갔다.");
        };
        for (String[] input : new String[][] {{"DEPLOYED", "COMPLETED", ""}, {"GATE", "DONE", ""},
                {"GATE", "FAILED", "purple"}, {"", "COMPLETED", ""}}) {
            DeployAuditRunner runner = new DeployAuditRunner(unused, operation(), input[0], input[1], TARGET, ACTOR, "",
                    input[2], "", false);
            assertThatThrownBy(() -> runner.run(null)).as(String.join("/", input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String operation() {
        return "deploy_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    private List<Map<String, Object>> rows(String operation) {
        return this.jdbc.sql("SELECT actor_kind, actor_user_id, actor_digest, command, scope, target, reason, outcome, "
                        + "policy_version, decided_at, correlation_id, CAST(details AS text) AS details "
                        + "FROM operations_audit_event WHERE operation_id = :op ORDER BY sequence")
                .param("op", operation).query().listOfRows();
    }

    private long activeRestrictionRows() {
        Long count = this.jdbc.sql("SELECT count(*) FROM operations_restriction WHERE effective_until IS NULL")
                .query(Long.class).single();
        return count == null ? 0 : count;
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
