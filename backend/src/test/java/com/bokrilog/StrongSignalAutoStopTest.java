package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.DevBybitStub.Scenario;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import com.bokrilog.operations.SignalType;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.sync.SyncJobApi;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 3.3: 승인된 강한 신호의 60초 자동 정지.
 *
 * <p>네 신호는 실제 감지 지점에서 일으킨다 — 4는 첫 발행의 계산 행 옆에 결과만 다른 쌍둥이 행을 시험 트리거가 끼워 넣고, 5는
 * portfolio 포인터 UPDATE를 시험 트리거가 건너뛰게 해 행 잠금 아래 CAS를 깨며, 6·7은 Bybit 스텁의 충돌·미인식 enum
 * 시나리오다. 반복·동시·범위 불명·판정표 장애·비신호·삭제는 공개 API {@link OperationalSignalApi#raise}로 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class StrongSignalAutoStopTest extends OperatorTestBase {

    private static final String CALC = "CALC-1.0";

    /** 경보 한 줄의 허용 모양: 신호 종류·정책 버전·`기능:scope`·결과·SLI뿐이다. */
    private static final Pattern ALERT = Pattern.compile("strong signal auto-stop: signal=[A-Z_]+ "
            + "policy=OPS-SIGNAL-1\\.0 restriction=[A-Z_]+:[A-Z_]+ outcome=(CREATED|LINKED) sliMs=[0-9]+");

    @Autowired
    private OperationalSignalApi signals;

    @Autowired
    private OperationalRestrictionApi restrictions;

    @Autowired
    private OperationsDeletionApi deletion;

    @Autowired
    private com.bokrilog.plan.PlanEndApi ends;

    // ------------------------------------------------------------------ 네 감지 지점

    /** 신호 4: 같은 입력·정책의 다른 result hash. 발행은 롤백되고 그 계산 정책의 표시가 막힌다. */
    @Test
    void 결과_hash_불일치는_발행을_롤백하고_그_계산_정책의_표시를_막는다(CapturedOutput output) throws Exception {
        UUID holder = connect(Scenario.UTA2);
        publishedPlan(holder, "signal-hash-holder-" + UUID.randomUUID(), null);
        assertThat(sync.dashboard(holder).displayRestricted()).isFalse();

        UUID user = connect(Scenario.UTA2);
        UUID intent = readyIntent(user, "signal-hash-" + UUID.randomUUID());
        UUID publication;
        installTwinCalculationTrigger(user, false);
        try {
            var admission = sync.admitPlanPublication(user, intent, key("signal-hash-publication"));
            publication = ((SyncJobApi.PublicationAdmission.Accepted) admission).job().jobId();
            pollUntil(Duration.ofSeconds(15), () -> statusOf("sync_plan_publication_job", publication),
                    PlanSyncTestBase::finished);
        } finally {
            dropTwinCalculationTrigger();
        }

        assertThat(jdbc().sql("SELECT status || '|' || failure_code FROM sync_plan_publication_job WHERE job_id=:j")
                .param("j", publication).query(String.class).single()).isEqualTo("FAILED|PUBLICATION_INTERRUPTED");
        assertThat(countOf("sync_plan_calculation", user)).as("발행 롤백").isZero();
        assertThat(signalRows()).containsExactly(
                "RESULT_HASH_MISMATCH|CALCULATION_DISPLAY:CALCULATION_POLICY_VERSION|POLICY|CREATED");
        assertThat(restrictionRows()).containsExactly("CALCULATION_DISPLAY|CALCULATION_POLICY_VERSION|" + CALC
                + "|SIGNAL|-");
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.CALCULATION_DISPLAY),
                RestrictionSubject.none().withCalculationPolicy(CALC))).isTrue();
        // 그 정책의 다른 사용자 공식 결과도 표시가 막힌다. 공식 기록은 그대로다.
        assertThat(sync.dashboard(holder).displayRestricted()).isTrue();
        assertThat(countOf("sync_plan_calculation", holder)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT jsonb_exists_all(evidence, ARRAY['calculationId','jobId']) FROM operations_signal")
                .query(Boolean.class).single()).isTrue();
        assertSli();
        assertAuditMatchesSignals();
        assertAlerts(output, 1);
    }

    /** 신호 5: 행 잠금 아래 portfolio 포인터 CAS 위반. 그 portfolio의 동기화·계산 표시가 막힌다. */
    @Test
    void portfolio_CAS_불변식_위반은_그_portfolio의_동기화와_표시를_막는다(CapturedOutput output) throws Exception {
        UUID user = connect(Scenario.UTA2);
        publishedPlan(user, "signal-cas-" + UUID.randomUUID(), null);
        var v1 = ready(user);
        UUID other = connect(Scenario.UTA2);
        publishedPlan(other, "signal-cas-other-" + UUID.randomUUID(), null);
        UUID portfolioId = jdbc().sql("SELECT portfolio_id FROM portfolio WHERE user_id=:u").param("u", user)
                .query(UUID.class).single();

        UUID job;
        // 그 portfolio의 포인터 UPDATE만 건너뛴다: 잠금·대조는 통과하고 CAS만 0행이 된다.
        installSkipPointerTrigger(portfolioId);
        try {
            job = manual(user, "signal-cas-sync");
            assertThat(awaitSync(job)).isEqualTo("FAILED");
        } finally {
            dropSkipPointerTrigger();
        }

        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SYNC_FAILED");
        assertThat(countOf("sync_plan_calculation", user)).as("발행 롤백").isEqualTo(1);
        assertThat(signalRows()).containsExactlyInAnyOrder(
                "PORTFOLIO_INVARIANT_VIOLATION|SYNC:PORTFOLIO|POLICY|CREATED",
                "PORTFOLIO_INVARIANT_VIOLATION|CALCULATION_DISPLAY:PORTFOLIO|POLICY|CREATED");
        assertThat(restrictionRows()).containsExactlyInAnyOrder(
                "SYNC|PORTFOLIO|" + portfolioId + "|SIGNAL|" + user,
                "CALCULATION_DISPLAY|PORTFOLIO|" + portfolioId + "|SIGNAL|" + user);
        // 한 신호의 판정은 operation 하나로 묶인다.
        assertThat(jdbc().sql("SELECT count(DISTINCT operation_id) FROM operations_signal").query(Integer.class)
                .single()).isEqualTo(1);
        var read = sync.dashboard(user);
        assertThat(read.displayRestricted()).isTrue();
        assertThat(((SyncJobApi.Dashboard.Ready) read.dashboard()).sync().reason()).isEqualTo("RESTRICTED");
        // 다른 사용자는 무영향이다.
        var otherRead = sync.dashboard(other);
        assertThat(otherRead.displayRestricted()).isFalse();
        assertThat(((SyncJobApi.Dashboard.Ready) otherRead.dashboard()).sync().reason()).isNotEqualTo("RESTRICTED");
        assertThat(ready(user).calculationId()).isEqualTo(v1.calculationId());
        assertSli();
        assertAuditMatchesSignals();
        assertAlerts(output, 2);
    }

    /** 정상 경합(계획 포인터가 그사이 움직임)은 신호가 아니다. */
    @Test
    @SuppressWarnings("try")
    void 정상_경합은_신호를_올리지_않는다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        publishedPlan(user, "signal-race-" + UUID.randomUUID(), null);
        UUID job;
        try (AutoCloseable held = holdPlanLock(user)) {
            job = manual(user, "signal-race-sync");
            awaitBlockedOnPlanLock();
            jdbc().sql("UPDATE sync_official_plan_result SET pointer_version=pointer_version+10 WHERE user_id=:u")
                    .param("u", user).update();
        }
        assertThat(awaitSync(job)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("POINTER_CONFLICT");
        assertThat(signalRows()).isEmpty();
        assertThat(restrictionRows()).isEmpty();
    }

    /** 신호 6(계획): 증분 동기화 중 같은 source ID의 의미 충돌. 그 계획만 막히고 작업은 검토 필요로 남는다. */
    @Test
    void 증분_동기화의_ID_충돌은_그_계획만_막는다(CapturedOutput output) throws Exception {
        UUID user = connect(Scenario.ID_CONFLICT);
        BybitStubConfiguration.resetRequestCount();
        publishedPlan(user, "signal-conflict-" + UUID.randomUUID(), null);
        var v1 = ready(user);
        UUID other = connect(Scenario.UTA2);
        publishedPlan(other, "signal-conflict-other-" + UUID.randomUUID(), null);

        UUID job = manual(user, "signal-conflict-sync");
        assertThat(awaitSync(job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SOURCE_REQUIRES_REVIEW");
        assertThat(signalRows()).containsExactly("SOURCE_ID_CONFLICT|SYNC:PLAN|POLICY|CREATED");
        assertThat(restrictionRows()).containsExactly("SYNC|PLAN|" + v1.planId() + "|SIGNAL|" + user);
        assertThat(jdbc().sql("SELECT jsonb_exists_all(evidence, ARRAY['jobId','observationId']) FROM operations_signal")
                .query(Boolean.class).single()).isTrue();
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key("signal-conflict-again")))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        // 다른 계획은 정상이다.
        UUID otherJob = manual(other, "signal-conflict-other-sync");
        assertThat(awaitSync(otherJob)).isEqualTo("SUCCEEDED");
        assertThat(ready(user).calculationId()).isEqualTo(v1.calculationId());
        assertSli();
        assertAuditMatchesSignals();
        assertAlerts(output, 1);
    }

    /** 신호 6(초기): 계획 없는 초기 수집의 의미 충돌은 그 연결의 동기화를 막는다. */
    @Test
    void 초기_수집의_ID_충돌은_그_연결을_막는다() throws Exception {
        UUID user = connect(Scenario.ID_CONFLICT);
        BybitStubConfiguration.resetRequestCount();
        UUID intent = initialIntent(user, "signal-initial-conflict-" + UUID.randomUUID());
        UUID first = sync.admitInitialInputs(user, intent, key("signal-initial-01")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", first, "INPUTS_COMPLETE");
        assertThat(signalRows()).isEmpty();
        UUID second = sync.admitInitialInputs(user, intent, key("signal-initial-02")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", second, "NEEDS_REVIEW");

        UUID connection = connectionOf(user);
        assertThat(signalRows()).containsExactly("SOURCE_ID_CONFLICT|SYNC:PROVIDER_CONNECTION|POLICY|CREATED");
        assertThat(restrictionRows()).containsExactly("SYNC|PROVIDER_CONNECTION|" + connection + "|SIGNAL|" + user);
        assertThat(sync.state(user).orElseThrow().restricted()).isTrue();
        assertSli();
        assertAuditMatchesSignals();
    }

    /** 신호 7: 미인식 Transaction Log enum은 그 연결의 동기화를 막는다. 수집은 검토 필요로 끝난다. */
    @Test
    void 미인식_enum은_그_연결을_막는다() throws Exception {
        UUID user = connect(Scenario.UNKNOWN_LOG_EVENT);
        UUID intent = initialIntent(user, "signal-unknown-" + UUID.randomUUID());
        UUID job = sync.admitInitialInputs(user, intent, key("signal-unknown")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", job, "NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_initial_collection_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("UNKNOWN_OR_MALFORMED_EVENT");

        UUID connection = connectionOf(user);
        assertThat(signalRows()).containsExactly(
                "UNKNOWN_TRANSACTION_ENUM|SYNC:PROVIDER_CONNECTION|POLICY|CREATED");
        assertThat(restrictionRows()).containsExactly("SYNC|PROVIDER_CONNECTION|" + connection + "|SIGNAL|" + user);
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withConnection(connection))).isTrue();
        // 다른 기능(신규 연결)은 막지 않는다 — 전역 정지가 아니다.
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.NEW_CONNECTION),
                RestrictionSubject.none())).isFalse();
        assertSli();
        assertAuditMatchesSignals();
    }

    // ------------------------------------------------------------------ 중복·병합·범위

    /** 같은 fingerprint의 반복은 제한 1행에 연결되고, 신호 행은 각각 남는다. 제한은 다시 오지 않아도 풀리지 않는다. */
    @Test
    void 같은_fingerprint의_반복은_제한_하나에_연결된다() throws Exception {
        UUID user = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        var first = this.signals.raise(unknownEnum(user, connection));
        var second = this.signals.raise(unknownEnum(user, connection));

        assertThat(first.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.outcome()).isEqualTo(OperationalSignalApi.Outcome.CREATED));
        assertThat(second.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.outcome()).isEqualTo(OperationalSignalApi.Outcome.LINKED);
            assertThat(decision.sliMs()).isZero();
            assertThat(decision.restrictionId()).isEqualTo(first.decisions().getFirst().restrictionId());
        });
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(signalRows()).containsExactly(
                "UNKNOWN_TRANSACTION_ENUM|SYNC:PROVIDER_CONNECTION|POLICY|CREATED",
                "UNKNOWN_TRANSACTION_ENUM|SYNC:PROVIDER_CONNECTION|POLICY|LINKED");
        assertThat(jdbc().sql("SELECT count(DISTINCT fingerprint) FROM operations_signal").query(Integer.class).single())
                .isEqualTo(1);
        // 자동 해제가 없다: 효력 끝이 비어 있고 evaluator가 계속 막는다.
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction WHERE effective_until IS NOT NULL")
                .query(Integer.class).single()).isZero();
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withConnection(connection))).isTrue();
        assertAuditMatchesSignals();
    }

    /** 동시에 온 같은 fingerprint는 원장 잠금 아래에서 제한 1행이 된다. */
    @Test
    void 동시에_온_같은_fingerprint는_제한_하나가_된다() throws Exception {
        UUID user = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        var start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    this.signals.raise(unknownEnum(user, connection));
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(20_000);
        }
        assertThat(failures).isEmpty();
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT outcome FROM operations_signal ORDER BY outcome").query(String.class).list())
                .containsExactly("CREATED", "LINKED", "LINKED", "LINKED");
    }

    /**
     * 더 넓거나 좁은 신호는 각자 제한을 더하고(차단은 합집합), 운영자가 이미 건 같은 기능·scope·target에는 연결한다 — 신호는
     * 제한을 줄이거나 출처를 바꾸지 않는다.
     */
    @Test
    void 넓고_좁은_신호는_병합되고_운영자_제한에는_연결된다() throws Exception {
        UUID user = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID operatorMade = restrict(RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION,
                connection.toString());
        this.signals.raise(new Signal(SignalType.SOURCE_ID_CONFLICT,
                RestrictionSubject.none().withPlan(plan).withConnection(connection), user,
                Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()), null));
        var linked = this.signals.raise(unknownEnum(user, connection));

        assertThat(linked.decisions().getFirst().restrictionId()).isEqualTo(operatorMade);
        assertThat(restrictionRows()).containsExactlyInAnyOrder(
                "SYNC|PLAN|" + plan + "|SIGNAL|" + user,
                "SYNC|PROVIDER_CONNECTION|" + connection + "|OPERATOR|-");
        assertThat(signalRows()).containsExactly("SOURCE_ID_CONFLICT|SYNC:PLAN|POLICY|CREATED",
                "UNKNOWN_TRANSACTION_ENUM|SYNC:PROVIDER_CONNECTION|POLICY|LINKED");
    }

    /** 대상 차원을 모르면 판정표의 다음 대안으로 넓힌다 — 마지막은 전역이다(AC-2). */
    @Test
    void 범위를_모르면_전역으로_접는다() throws Exception {
        this.signals.raise(new Signal(SignalType.RESULT_HASH_MISMATCH, RestrictionSubject.none(), null,
                Map.of("calculationId", UUID.randomUUID().toString()), null));
        assertThat(signalRows()).containsExactlyInAnyOrder(
                "RESULT_HASH_MISMATCH|SYNC:GLOBAL|SCOPE_UNKNOWN|CREATED",
                "RESULT_HASH_MISMATCH|CALCULATION_DISPLAY:GLOBAL|SCOPE_UNKNOWN|CREATED");
        jdbc().sql("TRUNCATE TABLE operations_signal, operations_restriction").update();

        // 사용자 귀속 차원은 소유자 없이 쓰지 않는다 — 연결이 있어도 소유자를 모르면 전역이다.
        this.signals.raise(new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM,
                RestrictionSubject.none().withConnection(UUID.randomUUID()), null,
                Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()), null));
        assertThat(signalRows()).containsExactlyInAnyOrder(
                "UNKNOWN_TRANSACTION_ENUM|NEW_CONNECTION:GLOBAL|SCOPE_UNKNOWN|CREATED",
                "UNKNOWN_TRANSACTION_ENUM|SYNC:GLOBAL|SCOPE_UNKNOWN|CREATED");
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.NEW_CONNECTION), RestrictionSubject.none()))
                .isTrue();
    }

    // ------------------------------------------------------------------ 60초·감사·경보

    /** SLI는 DB 시각의 감지→효력 차다. 늦은 감지는 그대로 드러난다(60초 경계를 숨기지 않는다). */
    @Test
    void SLI는_감지부터_효력까지의_DB_시각_차다() throws Exception {
        UUID user = UUID.randomUUID();
        var prompt = this.signals.raise(unknownEnum(user, UUID.randomUUID()));
        assertThat(prompt.decisions().getFirst().sliMs()).isBetween(0L, 59_999L);

        Instant detected = databaseNow().minusSeconds(61);
        var late = this.signals.raise(new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM,
                RestrictionSubject.none().withConnection(UUID.randomUUID()), user,
                Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()),
                detected));
        assertThat(late.decisions().getFirst().sliMs()).isGreaterThanOrEqualTo(60_000L);
        assertThat(jdbc().sql("SELECT sli_ms = floor(extract(epoch FROM effective_at - detected_at) * 1000)::bigint "
                        + "AND detected_at = :d FROM operations_signal WHERE operation_id = :op")
                .param("d", java.time.OffsetDateTime.ofInstant(detected, java.time.ZoneOffset.UTC))
                .param("op", late.operationId()).query(Boolean.class).single()).isTrue();

        // 미래의 감지 시각은 믿지 않는다 — 기록 시작 시각으로 접혀 SLI가 음수가 되지 않는다.
        var future = this.signals.raise(new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM,
                RestrictionSubject.none().withConnection(UUID.randomUUID()), user,
                Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()),
                databaseNow().plusSeconds(3600)));
        assertThat(future.decisions().getFirst().sliMs()).isBetween(0L, 59_999L);
    }

    /** 감사 SYSTEM 행과 경보 한 줄. 사용자 귀속 target은 원장에 digest로만 남고, 경보에는 허용 필드 외 값이 없다. */
    @Test
    void 감사는_SYSTEM_행이고_경보에는_식별자가_없다(CapturedOutput output) throws Exception {
        UUID user = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        var raised = this.signals.raise(unknownEnum(user, connection));

        var audit = jdbc().sql("SELECT actor_kind, actor_user_id IS NULL AS no_actor, actor_digest, command, scope, "
                        + "target, reason, outcome, policy_version, operation_id, decided_at >= requested_at AS ordered, "
                        + "(SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(details) k) AS keys "
                        + "FROM operations_audit_event")
                .query((rs, row) -> Map.of("kind", rs.getString("actor_kind"), "noActor", rs.getBoolean("no_actor"),
                        "digest", rs.getString("actor_digest"), "command", rs.getString("command"),
                        "scope", rs.getString("scope"), "target", rs.getString("target"),
                        "reason", rs.getString("reason"), "outcome", rs.getString("outcome"),
                        "operation", rs.getString("operation_id"),
                        "keys", List.of((String[]) rs.getArray("keys").getArray())))
                .single();
        assertThat(audit).containsEntry("kind", "SYSTEM").containsEntry("noActor", true)
                .containsEntry("command", "auto-restrict").containsEntry("scope", "SYNC:PROVIDER_CONNECTION")
                .containsEntry("reason", "UNKNOWN_TRANSACTION_ENUM").containsEntry("outcome", "COMPLETED")
                .containsEntry("operation", raised.operationId())
                .containsEntry("keys", List.of("outcome", "policyVersion", "scopeDecision", "signalId", "sliMs"));
        assertThat((String) audit.get("target")).matches("[0-9a-f]{64}").isNotEqualTo(connection.toString());
        assertThat((String) audit.get("digest")).matches("[0-9a-f]{64}");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE target = :t")
                .param("t", connection.toString()).query(Integer.class).single()).isZero();
        assertAlerts(output, 1);
        assertThat(output.getAll()).doesNotContain(connection.toString()).doesNotContain(user.toString());
    }

    // ------------------------------------------------------------------ 비신호·판정표 장애·삭제

    /** 필수 증거가 빠진 신호와 창 집계가 판정표에 못 미치는 신호는 승격하지 않는다 — 아무것도 쓰지 않는다. */
    @Test
    void 필수_증거가_빠지거나_창_집계가_판정표에_못_미치면_거절한다() throws Exception {
        assertThatThrownBy(() -> this.signals.raise(new Signal(SignalType.SOURCE_ID_CONFLICT,
                RestrictionSubject.none().withConnection(UUID.randomUUID()), UUID.randomUUID(),
                Map.of("jobId", UUID.randomUUID().toString()), null)))
                .isInstanceOf(IllegalArgumentException.class);
        // 신호 8: 서로 다른 계정 2개 이상에서 각 3회(전체 6회 이상), 5분 창.
        for (Map<String, String> burst : List.of(
                burst("5", "2", "3", "300"), // 전체 관측 수 부족
                burst("6", "1", "6", "300"), // 서로 다른 계정 부족
                burst("6", "2", "2", "300"), // 계정당 관측 수 부족(각 3회가 아니다)
                burst("6", "2", "3", "900"), // 창이 판정표보다 넓다
                burst("6", "2", "3", "0"), // 창이 없다
                burst("0", "0", "0", "300"), // 0건은 승격하지 않는다
                burst(UUID.randomUUID().toString(), "2", "3", "300"))) { // 수가 아니다
            assertThatThrownBy(() -> this.signals.raise(new Signal(SignalType.SCHEMA_VALIDATION_BURST,
                    RestrictionSubject.none(), null, burst, null))).as(burst.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // 증거 값은 내부 참조·집계 수만 받는다 — 금액·이메일 모양은 신호를 만들 수조차 없다.
        for (String value : List.of("12.50", "someone@example.com", "f".repeat(64))) {
            assertThatThrownBy(() -> new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM, RestrictionSubject.none(), null,
                    Map.of("jobId", value, "observationId", UUID.randomUUID().toString()), null))
                    .as(value).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(signalRows()).isEmpty();
        assertThat(restrictionCount()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event").query(Integer.class).single()).isZero();

        // 창 집계가 판정표를 채우면 승격한다(신규 연결+동기화 전역).
        this.signals.raise(new Signal(SignalType.SCHEMA_VALIDATION_BURST, RestrictionSubject.none(), null,
                burst("6", "2", "3", "300"), null));
        assertThat(signalRows()).containsExactlyInAnyOrder("SCHEMA_VALIDATION_BURST|NEW_CONNECTION:GLOBAL|POLICY|CREATED",
                "SCHEMA_VALIDATION_BURST|SYNC:GLOBAL|POLICY|CREATED");
    }

    private static Map<String, String> burst(String observed, String subjects, String perSubject, String window) {
        return Map.of("detectorRunId", UUID.randomUUID().toString(), "observedCount", observed, "distinctSubjects",
                subjects, "minPerSubject", perSubject, "windowSeconds", window);
    }

    /** 계정 삭제의 deletion port는 신호 행의 사용자 귀속도 비운다. 다른 사용자의 행은 그대로다. */
    @Test
    void 삭제_port는_신호_행의_사용자_귀속을_비운다() throws Exception {
        UUID erased = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        this.signals.raise(unknownEnum(erased, UUID.randomUUID()));
        this.signals.raise(new Signal(SignalType.RESULT_HASH_MISMATCH,
                RestrictionSubject.none().withCalculationPolicy(CALC), erased,
                Map.of("calculationId", UUID.randomUUID().toString()), null));
        this.signals.raise(unknownEnum(kept, UUID.randomUUID()));

        assertThat(this.deletion.eraseRestrictionTargets(erased)).isEqualTo(3);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal WHERE target_user_id = :u").param("u", erased)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal WHERE target_user_id = :u").param("u", kept)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction WHERE target_user_id = :u")
                .param("u", kept).query(Integer.class).single()).isEqualTo(1);
        assertThat(this.deletion.eraseRestrictionTargets(erased)).as("멱등").isZero();
    }

    /** 운영자 목록은 출처를 싣는다: 신호가 만든 제한은 `SIGNAL`, 사유는 신호 종류, 수행자는 SYSTEM digest다. */
    @Test
    void 운영자_목록은_자동_정지의_출처를_싣는다() throws Exception {
        Signed operator = operator("signal-list", "command");
        UUID connection = UUID.randomUUID();
        this.signals.raise(unknownEnum(UUID.randomUUID(), connection));
        restrictGlobally(RestrictionFeature.ADVERTISING);

        // 운영자가 건 제한에 신호가 연결되면 출처와 무관하게 수동 검증이 필요하다.
        UUID linkedPlan = UUID.randomUUID();
        restrict(RestrictionFeature.SYNC, RestrictionScope.PLAN, linkedPlan.toString());
        this.signals.raise(new Signal(SignalType.SOURCE_ID_CONFLICT,
                RestrictionSubject.none().withPlan(linkedPlan).withConnection(UUID.randomUUID()), UUID.randomUUID(),
                Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()), null));

        var listed = restrictionsGet(operator);
        assertThat(listed.statusCode()).isEqualTo(200);
        var items = itemsIn(listed.body(), "items");
        assertThat(items).extracting(item -> item.get("scope"))
                .containsExactly("PLAN", "PROVIDER_CONNECTION", "GLOBAL");
        assertThat(items.get(0)).containsEntry("origin", "OPERATOR").containsEntry("linkedSignals", 1)
                .containsEntry("requiresVerification", true);
        assertThat(items.get(1)).containsEntry("origin", "SIGNAL").containsEntry("feature", "SYNC")
                .containsEntry("target", connection.toString()).containsEntry("reason", "UNKNOWN_TRANSACTION_ENUM")
                .containsEntry("linkedSignals", 1).containsEntry("requiresVerification", true);
        assertThat((String) items.get(1).get("actorDigest")).matches("[0-9a-f]{64}");
        assertThat(items.get(2)).containsEntry("origin", "OPERATOR").containsEntry("linkedSignals", 0)
                .containsEntry("requiresVerification", false).doesNotContainKey("reason");
    }

    // ------------------------------------------------------------------ 리뷰 patch: 발행 경로·자기 제한·기록 실패

    /** 신호 4(증분 발행): 증분 동기화의 계산에서도 결과 hash 불일치가 발행을 롤백하고 그 계산 정책의 표시를 막는다. */
    @Test
    void 증분_발행의_결과_hash_불일치도_신호다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        publishedPlan(user, "signal-hash-sync-" + UUID.randomUUID(), null);
        var v1 = ready(user);
        UUID job;
        installTwinCalculationTrigger(user, false);
        try {
            job = manual(user, "signal-hash-sync");
            assertThat(awaitSync(job)).isEqualTo("FAILED");
        } finally {
            dropTwinCalculationTrigger();
        }
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SYNC_FAILED");
        assertThat(countOf("sync_plan_calculation", user)).as("발행 롤백").isEqualTo(1);
        assertThat(ready(user).calculationId()).isEqualTo(v1.calculationId());
        assertThat(signalRows()).containsExactly(
                "RESULT_HASH_MISMATCH|CALCULATION_DISPLAY:CALCULATION_POLICY_VERSION|POLICY|CREATED");
        assertThat(jdbc().sql("SELECT evidence->>'jobId' FROM operations_signal").query(String.class).single())
                .isEqualTo(job.toString());
        assertSli();
        assertAuditMatchesSignals();
    }

    /** 같은 입력의 정상 재발행(결과까지 같다)과 첫 발행 → 증분 발행의 정상 흐름은 신호가 아니다. */
    @Test
    void 같은_입력의_같은_결과와_정상_발행_흐름은_신호가_아니다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        UUID intent = readyIntent(user, "signal-same-" + UUID.randomUUID());
        installTwinCalculationTrigger(user, true);
        try {
            var admission = sync.admitPlanPublication(user, intent, key("signal-same-publication"));
            UUID publication = ((SyncJobApi.PublicationAdmission.Accepted) admission).job().jobId();
            awaitTable("sync_plan_publication_job", publication, "PUBLISHED");
            assertThat(manual(user, "signal-same-sync")).satisfies(job ->
                    assertThat(awaitSync(job)).isEqualTo("SUCCEEDED"));
        } finally {
            dropTwinCalculationTrigger();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).as("두 발행과 각 쌍둥이").isEqualTo(4);
        UUID other = connect(Scenario.UTA2);
        publishedPlan(other, "signal-same-other-" + UUID.randomUUID(), null);
        assertThat(awaitSync(manual(other, "signal-same-other-sync"))).isEqualTo("SUCCEEDED");
        assertThat(signalRows()).isEmpty();
        assertThat(restrictionRows()).isEmpty();
    }

    /** 신호 5(첫 발행): 최초 계획 발행의 portfolio CAS 위반. */
    @Test
    void 첫_발행의_portfolio_CAS_위반도_신호다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        UUID intent = readyIntent(user, "signal-cas-first-" + UUID.randomUUID());
        UUID portfolioId = portfolioOf(user);
        UUID publication;
        installSkipPointerTrigger(portfolioId);
        try {
            var admission = sync.admitPlanPublication(user, intent, key("signal-cas-first-publication"));
            publication = ((SyncJobApi.PublicationAdmission.Accepted) admission).job().jobId();
            pollUntil(Duration.ofSeconds(15), () -> statusOf("sync_plan_publication_job", publication),
                    PlanSyncTestBase::finished);
        } finally {
            dropSkipPointerTrigger();
        }
        assertThat(jdbc().sql("SELECT status || '|' || failure_code FROM sync_plan_publication_job WHERE job_id=:j")
                .param("j", publication).query(String.class).single()).isEqualTo("FAILED|PUBLICATION_INTERRUPTED");
        assertThat(countOf("plan_definition", user)).as("발행 롤백").isZero();
        assertThat(restrictionRows()).containsExactlyInAnyOrder(
                "SYNC|PORTFOLIO|" + portfolioId + "|SIGNAL|" + user,
                "CALCULATION_DISPLAY|PORTFOLIO|" + portfolioId + "|SIGNAL|" + user);
        assertAuditMatchesSignals();
    }

    /** 신호 5(후속 계획 첫 스냅샷): 종단 뒤 새 정상 기준을 portfolio에 올리는 CAS가 깨져도 신호다. */
    @Test
    void 후속_계획_기준의_portfolio_CAS_위반도_신호다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        publishedPlan(user, "signal-cas-next-" + UUID.randomUUID(), null);
        var result = ready(user);
        var request = new com.bokrilog.plan.PlanEndApi.Request(com.bokrilog.plan.PlanEndApi.Reason.USER_REQUEST,
                result.planId(), result.pointerVersion(), result.calculationId());
        new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(status -> this.ends.end(user, request, key("signal-cas-next-end")));
        UUID portfolioId = portfolioOf(user);
        UUID job;
        installSkipPointerTrigger(portfolioId);
        try {
            job = sync.admitNextBaseline(user, key("signal-cas-next")).orElseThrow().jobId();
            awaitTable("sync_job", job, "FAILED");
        } finally {
            dropSkipPointerTrigger();
        }
        assertThat(restrictionRows()).containsExactlyInAnyOrder(
                "SYNC|PORTFOLIO|" + portfolioId + "|SIGNAL|" + user,
                "CALCULATION_DISPLAY|PORTFOLIO|" + portfolioId + "|SIGNAL|" + user);
        assertThat(jdbc().sql("SELECT DISTINCT evidence->>'jobId' FROM operations_signal").query(String.class).list())
                .containsExactly(job.toString());
        assertAuditMatchesSignals();
    }

    /** 판정표가 거절한 신호(필수 증거 누락)는 승격하지 않고 감지 지점의 작업을 실패로 끝낸다(비신호). */
    @Test
    void 판정표가_거절한_신호는_감지_지점의_작업을_실패로_끝낸다() throws Exception {
        jdbc().sql("UPDATE operations_signal_policy SET required_evidence = array_append(required_evidence, "
                + "'detectorRunId') WHERE signal_type = 'UNKNOWN_TRANSACTION_ENUM'").update();
        try {
            UUID user = connect(Scenario.UNKNOWN_LOG_EVENT);
            UUID intent = initialIntent(user, "signal-refused-" + UUID.randomUUID());
            UUID job = sync.admitInitialInputs(user, intent, key("signal-refused")).orElseThrow().jobId();
            awaitTable("sync_initial_collection_job", job, "RETRY_REQUIRED");
            assertThat(jdbc().sql("SELECT failure_code FROM sync_initial_collection_job WHERE job_id=:j")
                    .param("j", job).query(String.class).single()).isEqualTo("PAGE_PERSIST_FAILED");
        } finally {
            jdbc().sql("UPDATE operations_signal_policy SET required_evidence = array_remove(required_evidence, "
                    + "'detectorRunId') WHERE signal_type = 'UNKNOWN_TRANSACTION_ENUM'").update();
        }
        assertThat(signalRows()).isEmpty();
        assertThat(restrictionRows()).isEmpty();
    }

    /**
     * 신호를 낸 수집은 자기 제한에만 막히지 않는다. 그 사이 운영자(또는 다른 신호)가 건 동기화 전역 제한은 그 수집을 그대로
     * 멈춘다 — 초기 수집은 다시 시도로, 증분 동기화는 막힘으로 끝나고 검토 필요가 되지 않는다.
     */
    @Test
    void 운영자_전역_제한은_신호를_낸_수집도_멈춘다() throws Exception {
        // 첫 신호 행이 들어가는 순간 운영자 출처의 동기화 전역 제한이 함께 선다(신호 직후 운영자가 건 것과 같다).
        jdbc().sql("CREATE OR REPLACE FUNCTION test_global_after_signal() RETURNS trigger AS $$ BEGIN "
                + "IF NOT EXISTS (SELECT 1 FROM operations_restriction WHERE feature='SYNC' AND scope='GLOBAL') THEN "
                + "INSERT INTO operations_restriction (restriction_id, feature, scope, target, effective_from) "
                + "VALUES (gen_random_uuid(), 'SYNC', 'GLOBAL', 'ALL', statement_timestamp()); END IF; RETURN NEW; END $$ "
                + "LANGUAGE plpgsql").update();
        jdbc().sql("CREATE TRIGGER test_global_after_signal AFTER INSERT ON operations_signal FOR EACH ROW "
                + "EXECUTE FUNCTION test_global_after_signal()").update();
        try {
            UUID initialUser = connect(Scenario.ID_CONFLICT);
            BybitStubConfiguration.resetRequestCount();
            UUID intent = initialIntent(initialUser, "signal-global-initial-" + UUID.randomUUID());
            jdbc().sql("ALTER TABLE operations_signal DISABLE TRIGGER test_global_after_signal").update();
            UUID first = sync.admitInitialInputs(initialUser, intent, key("signal-global-01")).orElseThrow().jobId();
            awaitTable("sync_initial_collection_job", first, "INPUTS_COMPLETE");
            jdbc().sql("ALTER TABLE operations_signal ENABLE TRIGGER test_global_after_signal").update();
            UUID second = sync.admitInitialInputs(initialUser, intent, key("signal-global-02")).orElseThrow().jobId();
            awaitTable("sync_initial_collection_job", second, "RETRY_REQUIRED");
            assertThat(signalRows()).containsExactly("SOURCE_ID_CONFLICT|SYNC:PROVIDER_CONNECTION|POLICY|CREATED");

            // 증분 동기화: 전역 제한을 거두고 같은 순서를 다시 만든다.
            jdbc().sql("TRUNCATE TABLE operations_signal, operations_restriction").update();
            UUID planUser = connect(Scenario.ID_CONFLICT);
            BybitStubConfiguration.resetRequestCount();
            jdbc().sql("ALTER TABLE operations_signal DISABLE TRIGGER test_global_after_signal").update();
            publishedPlan(planUser, "signal-global-plan-" + UUID.randomUUID(), null);
            jdbc().sql("ALTER TABLE operations_signal ENABLE TRIGGER test_global_after_signal").update();
            UUID job = manual(planUser, "signal-global-sync");
            assertThat(awaitSync(job)).isEqualTo("BLOCKED");
            assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                    .query(String.class).single()).isEqualTo("NOT_PERMITTED");
        } finally {
            jdbc().sql("DROP TRIGGER IF EXISTS test_global_after_signal ON operations_signal").update();
        }
    }

    /** 다시 잡힌 초기 수집은 처음 잡힌 것과 같은 결말이다 — 자기 신호의 제한에 취소되지 않고 검토 필요로 맺는다. */
    @Test
    void 다시_잡힌_수집도_자기_제한에_취소되지_않는다() throws Exception {
        UUID user = connect(Scenario.ID_CONFLICT);
        BybitStubConfiguration.resetRequestCount();
        UUID intent = initialIntent(user, "signal-reclaim-" + UUID.randomUUID());
        UUID first = sync.admitInitialInputs(user, intent, key("signal-reclaim-01")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", first, "INPUTS_COMPLETE");
        UUID second = sync.admitInitialInputs(user, intent, key("signal-reclaim-02")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", second, "NEEDS_REVIEW");
        long generation = generationOf(second);

        // 그 작업이 lease를 잃은 채 다시 잡히는 상황: 진행 중으로 되돌리고 lease를 만료시킨다.
        jdbc().sql("UPDATE sync_initial_collection_job SET status='RUNNING', failure_code=NULL, finished_at=NULL, "
                + "lease_until=now() - interval '1 second' WHERE job_id=:j").param("j", second).update();
        pollUntil(Duration.ofSeconds(15), () -> generationOf(second), found -> found > generation);
        awaitTable("sync_initial_collection_job", second, "NEEDS_REVIEW");
        assertThat(generationOf(second)).isGreaterThan(generation);
        assertThat(restrictionRows()).containsExactly(
                "SYNC|PROVIDER_CONNECTION|" + connectionOf(user) + "|SIGNAL|" + user);
        assertThat(signalRows()).containsExactly("SOURCE_ID_CONFLICT|SYNC:PROVIDER_CONNECTION|POLICY|CREATED",
                "SOURCE_ID_CONFLICT|SYNC:PROVIDER_CONNECTION|POLICY|LINKED");
    }

    /**
     * 신호를 기록하지 못하면 수집을 끝내지 않고 lease에 남긴다. 다시 잡힌 세대가 상한에 닿으면 검토 필요
     * ({@code SIGNAL_UNRECORDED})로 맺는다. 관측은 커밋돼 있고 제한은 없다.
     */
    @Test
    void 기록하지_못한_신호는_상한_세대까지_lease에_남고_그_뒤_맺는다(CapturedOutput output) throws Exception {
        UUID user = connect(Scenario.UNKNOWN_LOG_EVENT);
        UUID intent = initialIntent(user, "signal-unrecorded-" + UUID.randomUUID());
        jdbc().sql("CREATE OR REPLACE FUNCTION test_refuse_signal() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'test refusal'; END $$ LANGUAGE plpgsql").update();
        jdbc().sql("CREATE TRIGGER test_refuse_signal BEFORE INSERT ON operations_signal FOR EACH ROW "
                + "EXECUTE FUNCTION test_refuse_signal()").update();
        try {
            UUID job = sync.admitInitialInputs(user, intent, key("signal-unrecorded")).orElseThrow().jobId();
            for (int attempt = 1; attempt <= 3; attempt++) {
                int observed = attempt;
                pollUntil(Duration.ofSeconds(15), () -> observationsOf(job), found -> found >= observed);
                assertThat(observationsOf(job)).isEqualTo(attempt);
                if (attempt < 3) {
                    // 기록 실패 직후: 작업은 끝나지 않고 lease에 남는다.
                    Thread.sleep(500);
                    assertThat(statusOf("sync_initial_collection_job", job)).as("attempt " + attempt)
                            .isEqualTo("RUNNING");
                    assertThat(generationOf(job)).isEqualTo(attempt);
                    jdbc().sql("UPDATE sync_initial_collection_job SET lease_until=now() - interval '1 second' "
                            + "WHERE job_id=:j").param("j", job).update();
                }
            }
            awaitTable("sync_initial_collection_job", job, "NEEDS_REVIEW");
            assertThat(jdbc().sql("SELECT failure_code FROM sync_initial_collection_job WHERE job_id=:j")
                    .param("j", job).query(String.class).single()).isEqualTo("SIGNAL_UNRECORDED");
        } finally {
            jdbc().sql("DROP TRIGGER IF EXISTS test_refuse_signal ON operations_signal").update();
        }
        assertThat(restrictionRows()).isEmpty();
        assertThat(signalRows()).isEmpty();
        assertThat(output.getAll()).contains("strong signal unrecorded 3 times: job=initial-collection");
    }

    // ------------------------------------------------------------------ 도구

    private static Signal unknownEnum(UUID user, UUID connection) {
        return new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM, RestrictionSubject.none().withConnection(connection),
                user, Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()),
                null);
    }

    private UUID manual(UUID user, String prefix) {
        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key(prefix));
        assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        return ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
    }

    /** 첫 스냅샷과 확정된 계획 조건(intent). 초기 수집은 아직이다. */
    private UUID initialIntent(UUID user, String prefix) throws Exception {
        UUID firstJob = sync.admit(user, prefix + "-seed").orElseThrow().jobId();
        pollUntil(Duration.ofSeconds(10), () -> sync.job(user, firstJob).orElseThrow().status(),
                PlanSyncTestBase::finished);
        assertThat(sync.job(user, firstJob).orElseThrow().status()).isEqualTo("SUCCEEDED");
        var snapshot = plans.state(user).snapshot();
        String end = LocalDateTime.ofInstant(snapshot.measuredAt(), ZoneId.of("Asia/Seoul")).plusDays(30)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview = plans.preview(user, prefix + "-session", new PlanConditionApi.PreviewInput(end, "DAILY", "0.1"))
                .orElseThrow();
        return plans.confirm(user, prefix + "-session", preview.confirmationRef(), prefix + "-confirm-001")
                .intent().intentId();
    }

    /** 발행 직전까지: 초기 수집·분류·환산이 끝난 intent. */
    private UUID readyIntent(UUID user, String prefix) throws Exception {
        UUID intent = initialIntent(user, prefix);
        UUID collection = sync.admitInitialInputs(user, intent, prefix + "-inputs-001").orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", collection, "INPUTS_COMPLETE");
        UUID classification = sync.admitCashflowClassification(user, intent, prefix + "-classification-001")
                .orElseThrow().jobId();
        awaitTable("sync_cashflow_classification_job", classification, "COMPLETE");
        UUID conversion = sync.admitCashflowConversion(user, intent, prefix + "-conversion-001").orElseThrow().jobId();
        awaitTable("sync_cashflow_conversion_job", conversion, "CONVERTED");
        return intent;
    }

    /**
     * 그 사용자의 계산 행이 들어가는 순간 같은 입력·정책의 쌍둥이 행을 같은 트랜잭션에 넣는다. {@code sameResult}가 거짓이면
     * 결과만 다르다 — 계산 구현이 결정적이지 않았다면 앞서 남았을 행이다. 참이면 결과까지 같다 — 같은 입력의 정상 재발행이다.
     * 쌍둥이 자신은 다시 쌍둥이를 낳지 않고, 이전 버전을 가리키지 않는다(이전 버전의 유일 색인).
     */
    private void installTwinCalculationTrigger(UUID user, boolean sameResult) {
        jdbc().sql("CREATE OR REPLACE FUNCTION test_twin_calculation() RETURNS trigger AS $$ "
                + "DECLARE twin sync_plan_calculation%ROWTYPE; BEGIN "
                + "IF pg_trigger_depth() > 1 THEN RETURN NEW; END IF; "
                + "twin := NEW; twin.calculation_id := gen_random_uuid(); twin.created_by_job_id := gen_random_uuid(); "
                + "twin.previous_calculation_id := NULL; "
                + "IF TG_ARGV[0] = 'different' THEN twin.result_hash := repeat('f', 64); END IF; "
                + "INSERT INTO sync_plan_calculation SELECT (twin).*; RETURN NEW; END $$ LANGUAGE plpgsql").update();
        jdbc().sql("CREATE TRIGGER test_twin_calculation AFTER INSERT ON sync_plan_calculation FOR EACH ROW "
                + "WHEN (NEW.user_id = '" + user + "'::uuid) EXECUTE FUNCTION test_twin_calculation('"
                + (sameResult ? "same" : "different") + "')").update();
    }

    private void dropTwinCalculationTrigger() {
        jdbc().sql("DROP TRIGGER IF EXISTS test_twin_calculation ON sync_plan_calculation").update();
    }

    /** 그 portfolio의 포인터 UPDATE만 건너뛴다: 잠금·대조는 통과하고 CAS만 0행이 된다. */
    private void installSkipPointerTrigger(UUID portfolioId) {
        jdbc().sql("CREATE OR REPLACE FUNCTION test_skip_pointer() RETURNS trigger AS $$ BEGIN RETURN NULL; END $$ "
                + "LANGUAGE plpgsql").update();
        jdbc().sql("CREATE TRIGGER test_skip_pointer BEFORE UPDATE ON portfolio_current_snapshot FOR EACH ROW "
                + "WHEN (OLD.portfolio_id = '" + portfolioId + "'::uuid) EXECUTE FUNCTION test_skip_pointer()").update();
    }

    private void dropSkipPointerTrigger() {
        jdbc().sql("DROP TRIGGER IF EXISTS test_skip_pointer ON portfolio_current_snapshot").update();
    }

    private UUID portfolioOf(UUID user) {
        return jdbc().sql("SELECT portfolio_id FROM portfolio WHERE user_id=:u").param("u", user).query(UUID.class)
                .single();
    }

    private String statusOf(String table, UUID job) {
        return jdbc().sql("SELECT status FROM " + table + " WHERE job_id=:j").param("j", job).query(String.class)
                .single();
    }

    private long generationOf(UUID job) {
        return jdbc().sql("SELECT fencing_generation FROM sync_initial_collection_job WHERE job_id=:j").param("j", job)
                .query(Long.class).single();
    }

    private int observationsOf(UUID job) {
        return jdbc().sql("SELECT count(*) FROM sync_initial_event_observation WHERE job_id=:j "
                + "AND diagnostic_code='UNKNOWN_EVENT'").param("j", job).query(Integer.class).single();
    }

    private UUID connectionOf(UUID user) {
        return jdbc().sql("SELECT connection_id FROM source_connection WHERE user_id=:u").param("u", user)
                .query(UUID.class).single();
    }

    /** 신호 행: 종류|`기능:scope`|판정 이유|결과, 기록 순. */
    private List<String> signalRows() {
        return jdbc().sql("SELECT signal_type || '|' || restriction_scope || '|' || scope_decision || '|' || outcome "
                + "FROM operations_signal ORDER BY recorded_at, outcome, restriction_scope").query(String.class).list();
    }

    /** 제한 행: 기능|scope|target|출처|귀속 사용자(없으면 `-`). */
    private List<String> restrictionRows() {
        return jdbc().sql("SELECT feature || '|' || scope || '|' || target || '|' || origin || '|' || "
                + "COALESCE(target_user_id::text, '-') FROM operations_restriction ORDER BY feature, scope")
                .query(String.class).list();
    }

    /** 모든 신호 행의 SLI가 60초 안이고 DB 시각의 차와 같다. */
    private void assertSli() {
        assertThat(jdbc().sql("SELECT bool_and(sli_ms < 60000 AND sli_ms >= 0 AND effective_at >= detected_at) "
                + "FROM operations_signal").query(Boolean.class).single()).isTrue();
    }

    /** 신호 행마다 SYSTEM 감사 행 하나: 같은 operation·`기능:scope`·신호 ID·SLI·결과. */
    private void assertAuditMatchesSignals() {
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal s WHERE NOT EXISTS (SELECT 1 FROM "
                + "operations_audit_event a WHERE a.actor_kind = 'SYSTEM' AND a.actor_user_id IS NULL "
                + "AND a.command = 'auto-restrict' AND a.outcome = 'COMPLETED' AND a.operation_id = s.operation_id "
                + "AND a.scope = s.restriction_scope AND a.details->>'signalId' = s.signal_id::text "
                + "AND (a.details->>'sliMs')::bigint = s.sli_ms AND a.details->>'outcome' = s.outcome "
                + "AND a.details->>'policyVersion' = s.policy_version)").query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE actor_kind = 'SYSTEM'")
                .query(Integer.class).single()).isEqualTo(jdbc().sql("SELECT count(*) FROM operations_signal")
                        .query(Integer.class).single());
        // 사용자 귀속 scope의 원장 target은 keyed digest다.
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE actor_kind = 'SYSTEM' "
                + "AND split_part(scope, ':', 2) IN ('PLAN', 'PORTFOLIO', 'PROVIDER_CONNECTION') "
                + "AND target !~ '^[0-9a-f]{64}$'").query(Integer.class).single()).isZero();
    }

    /** 경보 줄은 정확히 허용 모양이고, 그 수가 판정 수와 같다. */
    private static void assertAlerts(CapturedOutput output, int expected) throws InterruptedException {
        List<String> alerts = pollUntil(Duration.ofSeconds(5), () -> alertsIn(output),
                found -> found.size() >= expected);
        assertThat(alerts).hasSize(expected);
        Pattern uuid = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-");
        Pattern hex = Pattern.compile("[0-9a-f]{32,}");
        Pattern email = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
        for (String alert : alerts) {
            assertThat(alert).matches(ALERT);
            assertThat(uuid.matcher(alert).find()).as(alert).isFalse();
            assertThat(hex.matcher(alert).find()).as(alert).isFalse();
            assertThat(email.matcher(alert).find()).as(alert).isFalse();
        }
    }

    private static List<String> alertsIn(CapturedOutput output) {
        List<String> alerts = new ArrayList<>();
        // 로그는 JSON 한 줄이다(Story 3.7). 경보 문장은 본문(event)에 있다.
        for (String event : LogLines.events(output.getAll(), "strong signal auto-stop:")) {
            Matcher matcher = Pattern.compile("strong signal auto-stop:.*").matcher(event);
            while (matcher.find()) {
                alerts.add(matcher.group().strip());
            }
        }
        return alerts;
    }
}
