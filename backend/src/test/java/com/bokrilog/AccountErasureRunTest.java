package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.AccountDeletionApi.DeletionRequestOutcome;
import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.identity.AccountDeletionRunApi.RunStatus;
import com.bokrilog.identity.AccountDeletionRunApi.StepState;
import com.bokrilog.identity.AccountDeletionRunApi.StepStatus;
import com.bokrilog.identity.DeletionStep;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import com.bokrilog.operations.SignalType;
import com.bokrilog.operations.SupportInquiryApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.WorkerLease;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 5.2: 사용자의 계정 삭제 요청을 Worker가 lease로 집어 단계마다 커밋하고 부분 실패를 추적한다 — 실제 스택(Docker PostgreSQL,
 * local OIDC·Bybit 스텁)에서. 두 사용자 표본의 정상 실행(한 사용자만 0행·다른 사용자 보존·운영 데이터 삭제 완료 시각·tombstone·요청 세션
 * 종료), 단계 실패 주입과 재시도 필요·실패 범위·다시 시도(성공 단계 미실행), Worker가 재시도 필요를 다시 집지 않음, 늦은 쓰기의 잔여
 * 되돌림, 단계 뒤 잔여, fencing 상실, production·키 없음 미집기, drain의 lease 놓기, 삭제 중 문의 접수의 잠금 재검사를 본다.
 */
class AccountErasureRunTest extends PlanSyncTestBase {

    private static final String DELETION_PATH = AccountDeletionController.DELETION_PATH;

    private static final String RETRY_PATH = AccountDeletionController.RETRY_PATH;

    @Autowired AccountErasure erasure;

    @Autowired AccountErasureWorker worker;

    @Autowired AccountDeletionRunApi runs;

    @Autowired DeletionTombstoneApi tombstones;

    @Autowired OperationsDeletionApi operations;

    @Autowired EngagementDeletionApi engagement;

    @Autowired OperationalSignalApi signals;

    @Autowired SupportInquiryApi inquiries;

    @Autowired ApplicationContext context;

    @Autowired AccountDeletionRequestService requests;

    /** 로그 한 줄이 가질 수 있는 키 — encoder가 채우는 넷과 구조 인자의 허용 키뿐이다. */
    private static final List<String> LOG_KEYS = java.util.stream.Stream.concat(
            java.util.stream.Stream.of("ts", "level", "logger", "event"),
            RedactingJsonEncoder.PASS_THROUGH_KEYS.stream()).toList();

    /** 로그인(세션·소셜 연결)·Bybit 연결·첫 공식 발행·문의·광고·감사 귀속·제한 target까지 채운 계정. */
    record Sample(UUID user, String cookie) {}

    @AfterEach
    void restoreEnvironment() {
        this.worker.reinterpret("development");
        this.requests.reinterpret("development");
    }

    // ------------------------------------------------------------------------------------------ 정상 실행

    /**
     * AC-1·AC-2: 두 사용자 가운데 삭제를 요청한 사용자만 모든 모듈에서 0행이 되고 다른 사용자의 행은 그대로다. 실행은 운영 데이터 삭제 완료
     * 시각을 갖고(백업 파기는 이 상태가 말하지 않는다) tombstone이 요청 ID로 한 행 생기며, 요청 세션까지 지워져 진행 화면의 다음 확인이 401이다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 요청한_사용자만_모든_모듈에서_지우고_운영_데이터_삭제_완료를_기록한다(CapturedOutput output) throws Exception {
        Sample kept = sample("erasure-run-kept");
        Sample erased = sample("erasure-run-erased");
        Map<DeletionStep, Long> keptBefore = residues(kept.user());
        Map<DeletionStep, Long> erasedBefore = residues(erased.user());
        for (DeletionStep step : DeletionStep.values()) {
            assertThat(erasedBefore.get(step)).as(step.name()).isPositive();
        }
        String requestId = requestDeletion(erased.user());
        assertThat(lifecycleOf(erased.user())).isEqualTo("DELETION_IN_PROGRESS");

        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);

        assertThat(residues(erased.user())).allSatisfy((step, rows) -> assertThat(rows).as(step.name()).isZero());
        for (String table : List.of("source_provider_account", "source_connection", "portfolio", "portfolio_snapshot", "plan_definition", "sync_plan_calculation", "sync_official_plan_result",
                "support_inquiry", "engagement_ad_consent_decision", "engagement_ad_attempt", "identity_account",
                "identity_social_link", "identity_deletion_request")) {
            assertThat(countOf(table, erased.user())).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE actor_user_id = :u")
                .param("u", erased.user()).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction WHERE target_user_id = :u")
                .param("u", erased.user()).query(Integer.class).single()).isZero();
        assertThat(sessionCountOf(erased.user())).isZero();
        assertThat(lifecycleOf(erased.user())).isEqualTo("DELETED");
        // 다른 사용자는 그대로다.
        assertThat(residues(kept.user())).isEqualTo(keptBefore);
        assertThat(lifecycleOf(kept.user())).isEqualTo("ACTIVE");
        assertThat(ready(kept.user()).planId()).isNotNull();

        // 운영 데이터 삭제 완료: 시각이 있고, 단계마다 건수가 남는다. 백업 파기를 말하는 상태·열은 없다.
        var run = this.runs.runOf(erased.user()).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.OPERATIONAL_DATA_ERASED);
        assertThat(run.operationalErasedAt()).isNotNull();
        assertThat(Duration.between(run.operationalErasedAt(), databaseNow()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(run.failedSteps()).isEmpty();
        assertThat(run.steps()).extracting(StepState::step).containsExactly(DeletionStep.values());
        for (StepState step : run.steps()) {
            assertThat(step.status()).as(step.step().name()).isEqualTo(StepStatus.SUCCEEDED);
            assertThat(step.attempts()).isEqualTo(1);
            // 건수는 그 단계가 시작할 때 남아 있던 수다(앞 단계가 일부를 함께 지울 수 있다 — 자격정보·소셜 연결).
            assertThat(step.erasedRows()).as(step.step().name()).isPositive()
                    .isLessThanOrEqualTo(erasedBefore.get(step.step()));
        }
        assertThat(jdbc().sql("SELECT column_name FROM information_schema.columns WHERE table_name = "
                + "'identity_deletion_run'").query(String.class).list()).noneMatch(name -> name.contains("backup"));
        // tombstone: 요청 ID로 한 행. 원식별자가 없다.
        assertThat(jdbc().sql("SELECT request_id FROM operations_deletion_tombstone").query(String.class).list())
                .containsExactly(requestId);
        // 이 요청은 요청 세션을 기록하지 않았다(identity API 직접 — Story 5.4의 완료 확인 세션은 HTTP 확정만 남긴다,
        // `DeletionCompletionTest`). 그래서 세션은 모두 끝났고 진행 화면의 다음 확인은 401이다.
        assertThat(get(DELETION_PATH, erased.cookie()).statusCode()).isEqualTo(401);
        // 다시 집을 것이 없다.
        assertThat(this.worker.runOnce()).isNull();
        // 로그(Epic 5 회고 D-4): 결말 한 줄은 고정 문장과 허용 키뿐이다 — 끝까지 돈 실행은 결과 `outcome`만 있고 멈춘 단계(`kind`)가 없다.
        assertThat(LogLines.records(output.getAll())).anySatisfy(record -> {
            assertThat(record).containsEntry("event", "account erasure run")
                    .containsEntry("outcome", AccountErasureRun.Result.OPERATIONAL_DATA_ERASED.name())
                    .doesNotContainKey("kind");
            assertThat(record.keySet()).isSubsetOf(LOG_KEYS);
        });
        // 로그에 사용자·요청 ID가 없다.
        assertThat(output.getAll()).doesNotContain(erased.user().toString()).doesNotContain(requestId);
    }

    // ------------------------------------------------------------------------------------------ tombstone(Story 5.3)

    /**
     * Story 5.3 매트릭스 "첫 claim"·"운영 완료": Worker가 실행을 처음 집으면 첫 단계 앞에서 tombstone 한 행이 요청 시각(V30)으로 생긴다 —
     * 첫 단계가 실패해도 남는다. 두 번째 claim도 한 행이다. 실행 행은 요청 ID를 든다. 운영 완료(마지막 단계)가 영향 generation 집합을 닫고
     * 보존을 {@code max(요청, 운영 완료) + 35일}로 늘린다(줄지 않는다). 감사 SYSTEM 행은 기록·연장 한 번씩이고 수와 code뿐이다.
     */
    @Test
    void 첫_claim이_요청_시각으로_tombstone을_남기고_운영_완료가_집합을_닫는다() throws Exception {
        Sample sample = sample("erasure-run-tombstone");
        String requestId = requestDeletion(sample.user());
        Instant requestedAt = jdbc().sql("SELECT date_trunc('milliseconds', requested_at) FROM "
                + "identity_deletion_request WHERE user_id = :u").param("u", sample.user())
                .query(OffsetDateTime.class).single().toInstant();
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone").query(Integer.class).single())
                .as("요청만으로는 tombstone이 없다(5.1 확정 경로는 그대로다)").isZero();

        // 첫 claim: 첫 단계(CREDENTIALS)의 port가 실패해도 tombstone은 그 앞의 자기 트랜잭션에 남는다.
        var credentialsFailing = (SourceConnectionApi) Proxy.newProxyInstance(
                SourceConnectionApi.class.getClassLoader(),
                new Class<?>[] {SourceConnectionApi.class}, (proxy, method, args) -> {
                    if (method.getName().equals("eraseCredentials")) {
                        throw new IllegalStateException("injected");
                    }
                    return invoke(method, this.source, args);
                });
        AccountErasureRun failing = new AccountErasureRun(new AccountErasure(accountDeletion(), this.tombstones,
                this.portfolio, this.sync, this.plans, credentialsFailing, this.operations, this.engagement, this.manager),
                this.runs, this.manager);
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(failing.execute(lease, () -> false)).isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        assertThat(statusesOf(this.runs.runOf(sample.user()).orElseThrow().steps()))
                .startsWith("CREDENTIALS:RETRY_NEEDED:STEP_FAILED");
        var stored = jdbc().sql("SELECT request_id, requested_at, destroy_after, status, closed_at IS NULL AS open, "
                        + "policy_version FROM operations_deletion_tombstone")
                .query((rs, row) -> List.of(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getString(4), rs.getBoolean(5),
                        rs.getString(6)))
                .list();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst()).containsExactly(requestId, requestedAt,
                requestedAt.plus(DeletionTombstoneApi.RETENTION), "PENDING", true, DeletionTombstoneApi.POLICY_VERSION);
        assertThat(jdbc().sql("SELECT request_id FROM identity_deletion_run WHERE user_id = :u").param("u", sample.user())
                .query(String.class).single()).isEqualTo(requestId);
        assertThat(tombstoneAuditReasons()).containsExactly("RECORDED");

        // 다시 시도 → 두 번째 claim: 여전히 한 행, 기록 감사도 한 번. 끝까지 돌면 운영 완료가 집합을 닫는다.
        String csrf = csrfTokenOf(sample.cookie());
        assertThat(retry(sample.cookie(), csrf, "erasure-tombstone-" + UUID.randomUUID()).statusCode()).isEqualTo(202);
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone").query(Integer.class).single())
                .isEqualTo(1);
        var erasedAt = this.runs.runOf(sample.user()).orElseThrow().operationalErasedAt();
        assertThat(erasedAt).isNotNull();
        var closed = jdbc().sql("SELECT closed_at, destroy_after FROM operations_deletion_tombstone")
                .query((rs, row) -> List.of(rs.getObject(1, OffsetDateTime.class).toInstant(),
                        rs.getObject(2, OffsetDateTime.class).toInstant()))
                .single();
        Instant closedAt = closed.get(0);
        assertThat(closedAt).isAfterOrEqualTo(requestedAt);
        assertThat(Duration.between(closedAt, erasedAt).abs()).as("운영 완료와 같은 트랜잭션").isLessThan(Duration.ofSeconds(5));
        assertThat(closed.get(1)).isEqualTo(closedAt.plus(DeletionTombstoneApi.RETENTION))
                .isAfterOrEqualTo(requestedAt.plus(DeletionTombstoneApi.RETENTION));
        assertThat(this.runs.operationalCompletions(List.of(requestId, "erasure-unknown-1")))
                .containsOnlyKeys(requestId).containsEntry(requestId, erasedAt);
        assertThat(tombstoneAuditReasons()).containsExactly("RECORDED", "RETENTION_EXTENDED");
        // 감사 행에는 식별자가 없다 — target은 고정 값, details는 수뿐이다.
        var audits = jdbc().sql("SELECT actor_user_id IS NULL, actor_kind, target, CAST(details AS text) FROM "
                        + "operations_audit_event WHERE command = 'tombstone'")
                .query((rs, row) -> List.<Object>of(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
        assertThat(audits).allSatisfy(row -> {
            assertThat(row.get(0)).isEqualTo(true);
            assertThat(row.get(1)).isEqualTo("SYSTEM");
            assertThat(row.get(2)).isEqualTo("deletion-tombstone");
            assertThat((String) row.get(3)).isEqualTo("{\"count\": 1}").doesNotContain(requestId)
                    .doesNotContain(sample.user().toString());
        });
        // 다시 불러도 보존은 줄지 않고 집합도 다시 열리지 않는다.
        var replay = new TransactionTemplate(this.manager);
        assertThat(replay.<Boolean>execute(status -> this.tombstones.close(requestId))).isFalse();
        assertThat(replay.<Boolean>execute(status -> this.tombstones.record(sample.user(), requestId, requestedAt))).isFalse();
        assertThat(jdbc().sql("SELECT destroy_after FROM operations_deletion_tombstone")
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(closed.get(1));
    }

    /**
     * P16(a)·P11: 첫 단계 앞의 tombstone 기록이 실패하면 실행은 재시도 필요로 멈추고, 아직 성공하지 않은 첫 단계에 {@code TOMBSTONE_FAILED}가
     * 붙는다(그 단계 자체의 실패 {@code STEP_FAILED}와 구분). 커밋된 단계도 tombstone 행도 없다. 화면의 실패 범위는 단계 code 그대로다.
     */
    @Test
    void tombstone_기록이_실패하면_TOMBSTONE_FAILED로_멈추고_아무_단계도_커밋하지_않는다() throws Exception {
        Sample sample = sample("erasure-run-tombstone-fail");
        requestDeletion(sample.user());
        DeletionTombstoneApi failingTombstones = (DeletionTombstoneApi) Proxy.newProxyInstance(
                DeletionTombstoneApi.class.getClassLoader(), new Class<?>[] {DeletionTombstoneApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("record")) {
                        throw new IllegalStateException("injected");
                    }
                    return invoke(method, this.tombstones, args);
                });
        AccountErasureRun failing = new AccountErasureRun(new AccountErasure(accountDeletion(), failingTombstones,
                this.portfolio, this.sync, this.plans, this.source, this.operations, this.engagement, this.manager),
                this.runs, this.manager);
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(failing.execute(lease, () -> false)).isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        var stopped = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(RunStatus.RETRY_NEEDED);
        assertThat(statusesOf(stopped.steps())).containsExactly("CREDENTIALS:RETRY_NEEDED:TOMBSTONE_FAILED",
                "PORTFOLIO:PENDING", "SYNC:PENDING", "PLAN:PENDING", "SOURCE:PENDING", "OPERATIONS:PENDING",
                "ENGAGEMENT:PENDING", "IDENTITY:PENDING");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone").query(Integer.class).single()).isZero();
        assertThat(countOf("identity_social_link", sample.user())).isPositive();
        assertThat(countOf("portfolio", sample.user())).isPositive();
        JsonNode progress = json(get(DELETION_PATH, sample.cookie()).body()).get("request").get("progress");
        assertThat(strings(progress.get("failedSteps"))).containsExactly("CREDENTIALS");
    }

    /** P16(b): 처음부터 종료 중이어도 tombstone 기록은 먼저 남기고(짧은 멱등 트랜잭션), 단계 앞에서 lease를 놓는다({@code QUEUED}). */
    @Test
    void 처음부터_종료_중이어도_tombstone은_남기고_lease를_놓는다() throws Exception {
        Sample sample = sample("erasure-run-stopping");
        String requestId = requestDeletion(sample.user());
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(erasureRun().execute(lease, () -> true)).isEqualTo(AccountErasureRun.Result.RELEASED);
        var released = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(released.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(statusesOf(released.steps())).allMatch(step -> step.endsWith(":PENDING"));
        assertThat(jdbc().sql("SELECT request_id FROM operations_deletion_tombstone").query(String.class).list())
                .containsExactly(requestId);
    }

    /**
     * P10: 단계 모드의 마지막 단계는 payload 삭제(생명주기·세션 행 잠금)를 감사 원장 추가 잠금 없이 시작한다 — 집합 닫기·보존 연장의 감사 행은
     * 그 트랜잭션의 마지막(커밋 직전)에 남는다(3.10 잠금 순서).
     */
    @Test
    void 마지막_단계는_감사_잠금을_payload_삭제_뒤에_쥔다() throws Exception {
        Sample sample = sample("erasure-run-lock-order");
        requestDeletion(sample.user());
        List<String> locks = new ArrayList<>();
        AccountDeletionApi recording = (AccountDeletionApi) Proxy.newProxyInstance(
                AccountDeletionApi.class.getClassLoader(),
                new Class<?>[] {AccountDeletionApi.class}, (proxy, method, args) -> {
                    if (method.getName().equals("erasePayload")) {
                        locks.add(holdsAppendLock() ? "locked" : "unlocked");
                    }
                    return invoke(method, accountDeletion(), args);
                });
        AccountErasureRun run = new AccountErasureRun(new AccountErasure(recording, this.tombstones, this.portfolio,
                this.sync, this.plans, this.source, this.operations, this.engagement, this.manager), this.runs, this.manager);
        assertThat(run.execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(locks).containsExactly("unlocked");
        assertThat(tombstoneAuditReasons()).containsExactly("RECORDED", "RETENTION_EXTENDED");
    }

    // ------------------------------------------------------------------------------------------ 부분 실패와 다시 시도

    /**
     * 중간 실패: SYNC 단계가 예외로 끝나면 앞 단계의 커밋은 남고 SYNC는 재시도 필요(STEP_FAILED)·뒤 단계는 미실행이며 계정은 삭제 진행 중이다.
     * 화면은 실패 범위를 읽는다. Worker는 그 실행을 다시 집지 않는다(AC-3). 요청 세션의 다시 시도는 202이고 같은 키는 같은 202, 대기열에
     * 들어간 뒤 다른 키는 409다. 다시 돌면 실패·미실행 단계만 돌고 성공 단계는 건너뛴다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 단계가_실패하면_멈추고_다시_시도는_실패한_단계부터_돈다(CapturedOutput output) throws Exception {
        Sample sample = sample("erasure-run-failing");
        requestDeletion(sample.user());
        String csrf = csrfTokenOf(sample.cookie());
        AccountErasureRun failing = runnerWith(failingSync(), this.operations, this.engagement);

        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(failing.execute(lease, () -> false)).isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);

        assertThat(countOf("portfolio", sample.user())).isZero();
        assertThat(countOf("plan_definition", sample.user())).isPositive();
        assertThat(countOf("source_connection", sample.user())).isPositive();
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETION_IN_PROGRESS");
        var stopped = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(RunStatus.RETRY_NEEDED);
        assertThat(statusesOf(stopped.steps())).containsExactly("CREDENTIALS:SUCCEEDED", "PORTFOLIO:SUCCEEDED",
                "SYNC:RETRY_NEEDED:STEP_FAILED", "PLAN:PENDING", "SOURCE:PENDING", "OPERATIONS:PENDING",
                "ENGAGEMENT:PENDING", "IDENTITY:PENDING");
        assertThat(jdbc().sql("SELECT lease_until IS NULL FROM identity_deletion_run WHERE user_id = :u")
                .param("u", sample.user()).query(Boolean.class).single()).isTrue();
        // 로그(Epic 5 회고 D-4): 본문은 고정 문장이고 단계·code·예외는 허용 키로만 나온다 — 식별자가 없다.
        assertThat(LogLines.records(output.getAll())).anySatisfy(record -> {
            assertThat(record).containsEntry("event", "account erasure step failed")
                    .containsEntry("code", AccountDeletionRunApi.STEP_FAILED)
                    .containsEntry("kind", "SYNC").containsKey("exception");
            assertThat(record.keySet()).isSubsetOf(LOG_KEYS);
        });
        assertThat(output.getAll()).doesNotContain(sample.user().toString());

        // 화면: 재시도 필요와 실패 범위.
        JsonNode progress = json(get(DELETION_PATH, sample.cookie()).body()).get("request").get("progress");
        assertThat(progress.get("status").asString()).isEqualTo("RETRY_NEEDED");
        assertThat(strings(progress.get("failedSteps"))).containsExactly("SYNC");

        // AC-3: 아무도 다시 시도하지 않으면 Worker는 다시 집지 않는다.
        assertThat(this.worker.runOnce()).isNull();
        assertThat(this.runs.runOf(sample.user()).orElseThrow().status()).isEqualTo(RunStatus.RETRY_NEEDED);

        // 키 없음 422, 같은 키 재전송 202, 대기열 뒤 다른 키 409.
        assertThat(retry(sample.cookie(), csrf, null).statusCode()).isEqualTo(422);
        String key = "erasure-retry-" + UUID.randomUUID();
        var accepted = retry(sample.cookie(), csrf, key);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        assertThat(accepted.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(json(accepted.body()).get("progress").get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(retry(sample.cookie(), csrf, key).statusCode()).isEqualTo(202);
        var refused = retry(sample.cookie(), csrf, "erasure-retry-" + UUID.randomUUID());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", AccountDeletionRequestService.NOT_RETRYABLE);
        // CSRF 없는 다시 시도는 막힌다.
        assertThat(send("POST", RETRY_PATH, sample.cookie(), "Origin", OidcStubConfiguration.PUBLIC_BASE_URL,
                "Idempotency-Key", "erasure-retry-" + UUID.randomUUID()).statusCode()).isEqualTo(403);

        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        var finished = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.OPERATIONAL_DATA_ERASED);
        // 성공 단계는 다시 돌지 않았다(시도 1회), 실패한 SYNC는 두 번.
        assertThat(finished.steps()).extracting(step -> step.step().name() + ":" + step.attempts()).containsExactly(
                "CREDENTIALS:1", "PORTFOLIO:1", "SYNC:2", "PLAN:1", "SOURCE:1", "OPERATIONS:1", "ENGAGEMENT:1",
                "IDENTITY:1");
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETED");
    }

    /**
     * 늦은 쓰기: OPERATIONS가 성공한 뒤 그 사용자의 운영 귀속 행이 다시 생기면 마지막 단계가 잔여를 보고 OPERATIONS를 재시도 필요
     * (RESIDUAL_FOUND)로 되돌린다 — 계정은 {@code DELETED}로 가지 않는다. tombstone은 첫 단계에 생겼고(Story 5.3) 영향 generation 집합은
     * 열려 있다(운영 완료가 아니다). 다시 시도하면 OPERATIONS와 마지막 단계만 돌고 그때 집합이 닫힌다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 늦은_쓰기가_남으면_마지막_단계가_그_단계를_되돌린다(CapturedOutput output) throws Exception {
        Sample sample = sample("erasure-run-late");
        requestDeletion(sample.user());
        // ENGAGEMENT 단계(OPERATIONS 다음) 안에서 운영 귀속 행 하나가 늦게 생긴다.
        EngagementDeletionApi late = (EngagementDeletionApi) Proxy.newProxyInstance(
                EngagementDeletionApi.class.getClassLoader(), new Class<?>[] {EngagementDeletionApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("eraseConsents")) {
                        insertAuditRowOf(sample.user());
                    }
                    return invoke(method, this.engagement, args);
                });
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(runnerWith(this.sync, this.operations, late).execute(lease, () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);

        var run = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.RETRY_NEEDED);
        assertThat(statusesOf(run.steps())).containsExactly("CREDENTIALS:SUCCEEDED", "PORTFOLIO:SUCCEEDED",
                "SYNC:SUCCEEDED", "PLAN:SUCCEEDED", "SOURCE:SUCCEEDED", "OPERATIONS:RETRY_NEEDED:RESIDUAL_FOUND",
                "ENGAGEMENT:SUCCEEDED", "IDENTITY:PENDING");
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETION_IN_PROGRESS");
        // 로그(Epic 5 회고 D-4): 잔여를 본 단계는 `kind`, 잔여 단계 목록은 `.`로 이은 `reason`이다.
        assertThat(LogLines.records(output.getAll())).anySatisfy(record -> {
            assertThat(record).containsEntry("event", "account erasure residue found")
                    .containsEntry("code", AccountDeletionRunApi.RESIDUAL_FOUND)
                    .containsEntry("kind", "IDENTITY").containsEntry("reason", "OPERATIONS");
            assertThat(record.keySet()).isSubsetOf(LOG_KEYS);
        });
        assertThat(openTombstones()).isEqualTo(1);
        assertThat(countOf("identity_account", sample.user())).isEqualTo(1);

        String csrf = csrfTokenOf(sample.cookie());
        assertThat(retry(sample.cookie(), csrf, "erasure-late-" + UUID.randomUUID()).statusCode()).isEqualTo(202);
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(this.operations.remainingRowsOf(sample.user())).isZero();
        assertThat(this.runs.runOf(sample.user()).orElseThrow().steps())
                .extracting(step -> step.step().name() + ":" + step.attempts()).containsExactly("CREDENTIALS:1",
                        "PORTFOLIO:1", "SYNC:1", "PLAN:1", "SOURCE:1", "OPERATIONS:2", "ENGAGEMENT:1", "IDENTITY:2");
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETED");
    }

    /**
     * 늦은 쓰기가 두 모듈에 남으면(ENGAGEMENT가 커밋된 뒤 마지막 단계 앞에서 운영 귀속 행과 광고 동의 행이 하나씩 생긴다) 마지막 단계가
     * 두 단계를 함께 재시도 필요(RESIDUAL_FOUND)로 되돌린다. 로그의 `reason`은 두 단계 이름을 단계 순서대로 `.`로 이은 값이고 encoder가
     * 그 값을 버리지 않는다(Epic 5 회고 D-4).
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 두_모듈의_늦은_쓰기는_잔여_단계를_순서대로_이은_reason으로_남는다(CapturedOutput output) throws Exception {
        Sample sample = sample("erasure-run-late-two");
        requestDeletion(sample.user());
        boolean[] written = {false};
        // 실행자는 단계 사이에서만 종료 판정을 본다 — ENGAGEMENT가 성공한 뒤의 첫 판정(마지막 단계 앞)에서 두 행을 넣는다.
        java.util.function.BooleanSupplier lateWrites = () -> {
            if (!written[0] && this.runs.runOf(sample.user()).orElseThrow().steps().stream().anyMatch(
                    state -> state.step() == DeletionStep.ENGAGEMENT && state.status() == StepStatus.SUCCEEDED)) {
                written[0] = true;
                insertAuditRowOf(sample.user());
                insertConsentOf(sample.user());
            }
            return false;
        };
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(runnerWith(this.sync, this.operations, this.engagement).execute(lease, lateWrites))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        assertThat(written[0]).isTrue();
        assertThat(statusesOf(this.runs.runOf(sample.user()).orElseThrow().steps())).containsExactly(
                "CREDENTIALS:SUCCEEDED", "PORTFOLIO:SUCCEEDED", "SYNC:SUCCEEDED", "PLAN:SUCCEEDED", "SOURCE:SUCCEEDED",
                "OPERATIONS:RETRY_NEEDED:RESIDUAL_FOUND", "ENGAGEMENT:RETRY_NEEDED:RESIDUAL_FOUND", "IDENTITY:PENDING");
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETION_IN_PROGRESS");
        assertThat(LogLines.records(output.getAll())).anySatisfy(record -> {
            assertThat(record).containsEntry("event", "account erasure residue found")
                    .containsEntry("code", AccountDeletionRunApi.RESIDUAL_FOUND)
                    .containsEntry("kind", "IDENTITY").containsEntry("reason", "OPERATIONS.ENGAGEMENT");
            assertThat(record.keySet()).isSubsetOf(LOG_KEYS);
        });
    }

    /**
     * I/O 매트릭스 "늦은 쓰기" 그대로: OPERATIONS가 성공해 문의를 지운 뒤 그 사용자의 문의 행이 생긴다(잠금 재검사를 이미 지난 접수를
     * 흉내 내 SQL로 넣는다). 남은 단계를 돌면 마지막 단계가 그 잔여를 보고 OPERATIONS를 재시도 필요(RESIDUAL_FOUND)로 되돌리고 계정은
     * 삭제 진행 중에 머문다. 사용자의 다시 시도가 실행을 끝내고 그 문의도 사라진다.
     */
    @Test
    void 운영_단계_뒤에_생긴_문의는_마지막_단계가_잔여로_되돌리고_다시_시도가_지운다() throws Exception {
        Sample sample = sample("erasure-run-late-inquiry");
        requestDeletion(sample.user());
        assertThat(countOf("support_inquiry", sample.user())).isEqualTo(1);

        // OPERATIONS까지 돌고 ENGAGEMENT 앞에서 멈춘다(단계 앞 확인 6번은 통과, 7번째에서 lease를 놓는다).
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        int[] checks = {0};
        assertThat(erasureRun().execute(lease, () -> checks[0]++ >= 6)).isEqualTo(AccountErasureRun.Result.RELEASED);
        assertThat(statusesOf(this.runs.runOf(sample.user()).orElseThrow().steps())).containsExactly(
                "CREDENTIALS:SUCCEEDED", "PORTFOLIO:SUCCEEDED", "SYNC:SUCCEEDED", "PLAN:SUCCEEDED", "SOURCE:SUCCEEDED",
                "OPERATIONS:SUCCEEDED", "ENGAGEMENT:PENDING", "IDENTITY:PENDING");
        assertThat(countOf("support_inquiry", sample.user())).isZero();

        // 늦은 문의 하나.
        jdbc().sql("INSERT INTO support_inquiry (inquiry_id, user_id, reference, category, title, description, contact, "
                        + "reply_email, idempotency_key_digest, request_digest, received_at, target_at, target_state, "
                        + "calendar_version, timezone, status) VALUES (:id, :u, :ref, 'ACCOUNT', '늦은 문의', "
                        + "'운영 단계 뒤에 도착한 문의입니다.', 'IN_APP', NULL, :k, :r, statement_timestamp(), NULL, "
                        + "'CALENDAR_PENDING', 'KR-HOLIDAY-2026.1', 'Asia/Seoul', 'RECEIVED')")
                .param("id", UUID.randomUUID()).param("u", sample.user()).param("ref", "Q-LATEINQRY2")
                .param("k", sha256("late-inquiry-key-" + UUID.randomUUID()))
                .param("r", sha256("late-inquiry-request-" + UUID.randomUUID())).update();
        assertThat(countOf("support_inquiry", sample.user())).isEqualTo(1);

        // 남은 단계: ENGAGEMENT는 성공, IDENTITY가 잔여를 보고 OPERATIONS를 되돌린다.
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        var stopped = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(RunStatus.RETRY_NEEDED);
        assertThat(statusesOf(stopped.steps())).containsExactly("CREDENTIALS:SUCCEEDED", "PORTFOLIO:SUCCEEDED",
                "SYNC:SUCCEEDED", "PLAN:SUCCEEDED", "SOURCE:SUCCEEDED", "OPERATIONS:RETRY_NEEDED:RESIDUAL_FOUND",
                "ENGAGEMENT:SUCCEEDED", "IDENTITY:PENDING");
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETION_IN_PROGRESS");
        assertThat(countOf("identity_account", sample.user())).isEqualTo(1);
        // tombstone은 첫 단계에 생겼고(Story 5.3) 운영 완료 전이라 집합은 열려 있다.
        assertThat(openTombstones()).isEqualTo(1);
        JsonNode progress = json(get(DELETION_PATH, sample.cookie()).body()).get("request").get("progress");
        assertThat(progress.get("status").asString()).isEqualTo("RETRY_NEEDED");
        assertThat(strings(progress.get("failedSteps"))).containsExactly("OPERATIONS");
        // 아무도 다시 시도하지 않으면 다시 집지 않는다.
        assertThat(this.worker.runOnce()).isNull();

        // 사용자의 다시 시도가 실행을 끝내고 늦은 문의도 지운다.
        String csrf = csrfTokenOf(sample.cookie());
        assertThat(retry(sample.cookie(), csrf, "erasure-late-inquiry-" + UUID.randomUUID()).statusCode())
                .isEqualTo(202);
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(countOf("support_inquiry", sample.user())).isZero();
        assertThat(this.operations.remainingRowsOf(sample.user())).isZero();
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETED");
        var finished = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.OPERATIONAL_DATA_ERASED);
        assertThat(finished.steps()).extracting(step -> step.step().name() + ":" + step.attempts()).containsExactly(
                "CREDENTIALS:1", "PORTFOLIO:1", "SYNC:1", "PLAN:1", "SOURCE:1", "OPERATIONS:2", "ENGAGEMENT:1",
                // 마지막 단계는 잔여를 본 첫 시도도 자기 행에 센다.
                "IDENTITY:2");
    }

    /** 단계의 port가 자기 행을 남기면 그 단계가 잔여(RESIDUAL_FOUND)로 실패하고 지운 것도 되돌아간다. */
    @Test
    void 단계_뒤_잔여가_있으면_그_단계가_재시도_필요다() throws Exception {
        Sample sample = sample("erasure-run-residue");
        requestDeletion(sample.user());
        OperationsDeletionApi forgetful = (OperationsDeletionApi) Proxy.newProxyInstance(
                OperationsDeletionApi.class.getClassLoader(), new Class<?>[] {OperationsDeletionApi.class},
                (proxy, method, args) -> method.getName().equals("eraseInquiries") ? 0
                        : invoke(method, this.operations, args));
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(runnerWith(this.sync, forgetful, this.engagement).execute(lease, () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        assertThat(statusesOf(this.runs.runOf(sample.user()).orElseThrow().steps()))
                .contains("OPERATIONS:RETRY_NEEDED:RESIDUAL_FOUND", "ENGAGEMENT:PENDING");
        // 같은 단계 트랜잭션의 비식별도 되돌아갔다.
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE actor_user_id = :u")
                .param("u", sample.user()).query(Integer.class).single()).isEqualTo(1);
        assertThat(countOf("support_inquiry", sample.user())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ fencing·환경·drain

    /** lease 상실: 다른 Worker가 fencing을 올렸으면 늦은 Worker의 단계 커밋은 거절되고 아무 결과도 기록되지 않는다. */
    @Test
    void fencing을_잃은_Worker는_아무것도_커밋하지_않는다() throws Exception {
        Sample sample = sample("erasure-run-fenced");
        requestDeletion(sample.user());
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        // lease가 끝나 다른 Worker가 같은 실행을 집었다(세대가 오른다).
        jdbc().sql("UPDATE identity_deletion_run SET lease_until = statement_timestamp() WHERE user_id = :u")
                .param("u", sample.user()).update();
        var taken = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(taken.generation()).isEqualTo(lease.generation() + 1);
        assertThat(this.runs.heartbeat(lease, WorkerLease.DURATION)).isFalse();

        assertThat(this.erasureRun().execute(lease, () -> false)).isEqualTo(AccountErasureRun.Result.LEASE_LOST);
        assertThat(statusesOf(this.runs.runOf(sample.user()).orElseThrow().steps()))
                .allMatch(step -> step.endsWith(":PENDING"));
        assertThat(countOf("source_connection", sample.user())).isPositive();
        assertThat(countOf("identity_social_link", sample.user())).isPositive();
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETION_IN_PROGRESS");

        // 지금 세대의 Worker는 끝까지 간다.
        assertThat(this.erasureRun().execute(taken, () -> false)).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
    }

    /** production(G-5 전)과 tombstone 키가 없는 프로세스는 삭제를 집지 않는다 — 요청은 진행 중으로 남는다. */
    @Test
    void production과_키_없는_프로세스는_삭제를_집지_않는다() throws Exception {
        Sample sample = sample("erasure-run-gated");
        requestDeletion(sample.user());

        this.worker.reinterpret("production");
        assertThat(this.worker.claimsAllowed()).isFalse();
        assertThat(this.worker.runOnce()).isNull();
        assertThat(this.runs.runOf(sample.user())).isEmpty();

        DeletionTombstoneApi keyless = (DeletionTombstoneApi) Proxy.newProxyInstance(
                DeletionTombstoneApi.class.getClassLoader(), new Class<?>[] {DeletionTombstoneApi.class},
                (proxy, method, args) -> method.getName().equals("keyAvailable") ? Boolean.FALSE
                        : invoke(method, this.tombstones, args));
        AccountErasureWorker withoutKey = new AccountErasureWorker(erasureRun(), this.runs, keyless, "development",
                false);
        assertThat(withoutKey.runOnce()).isNull();
        assertThat(this.runs.runOf(sample.user())).isEmpty();
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETION_IN_PROGRESS");
        assertThat(countOf("identity_deletion_request", sample.user())).isEqualTo(1);

        this.worker.reinterpret("development");
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
    }

    /** drain: 종료 중이면 다음 단계 앞에서 lease를 놓고({@code QUEUED}) 다음 Worker가 이어서 돈다. */
    @Test
    void 종료_중이면_단계_사이에서_lease를_놓는다() throws Exception {
        Sample sample = sample("erasure-run-drain");
        requestDeletion(sample.user());
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        int[] steps = {0};
        assertThat(erasureRun().execute(lease, () -> steps[0]++ >= 2)).isEqualTo(AccountErasureRun.Result.RELEASED);
        var released = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(released.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(statusesOf(released.steps())).startsWith("CREDENTIALS:SUCCEEDED", "PORTFOLIO:SUCCEEDED",
                "SYNC:PENDING");
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
    }

    // ------------------------------------------------------------------------------------------ 문의 접수의 재검사

    /** 삭제 중 문의 접수는 생명주기 잠금 아래에서 다시 판정되어 아무것도 쓰지 않는다(컨트롤러의 판정을 지난 경합의 자리). */
    @Test
    void 삭제가_시작된_계정의_문의_접수는_잠금_아래에서_거부된다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        requestDeletion(user);
        assertThat(this.inquiries.submit(user, "erasure-inquiry-" + UUID.randomUUID(), draft()))
                .isInstanceOf(SupportInquiryApi.Submission.NotPermitted.class);
        assertThat(countOf("support_inquiry", user)).isZero();
        // 활성 계정은 그대로 접수된다.
        UUID active = connect(DevBybitStub.Scenario.UTA2);
        assertThat(this.inquiries.submit(active, "erasure-inquiry-" + UUID.randomUUID(), draft()))
                .isInstanceOf(SupportInquiryApi.Submission.Received.class);
    }

    // ------------------------------------------------------------------------------------------ 1차 리뷰 보강

    /**
     * 다시 시도의 응답을 잃은 사이 실행이 또 실패했으면 같은 키의 재전송이 그 실패를 다시 대기열에 넣는다 — 같은 키라도 실행이 재시도
     * 필요면 재생이 아니다.
     */
    @Test
    void 같은_키의_재전송은_다시_실패한_실행도_다시_대기열에_넣는다() throws Exception {
        Sample sample = sample("erasure-run-rekey");
        requestDeletion(sample.user());
        String csrf = csrfTokenOf(sample.cookie());
        AccountErasureRun failing = runnerWith(failingSync(), this.operations, this.engagement);
        assertThat(failing.execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);

        String key = "erasure-rekey-" + UUID.randomUUID();
        assertThat(retry(sample.cookie(), csrf, key).statusCode()).isEqualTo(202);
        // 그 응답을 잃은 사이 다시 돌아 또 실패했다.
        assertThat(failing.execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        assertThat(this.runs.runOf(sample.user()).orElseThrow().status()).isEqualTo(RunStatus.RETRY_NEEDED);

        // 같은 키의 재전송: 다시 대기열에 넣고 Worker가 끝낸다.
        assertThat(retry(sample.cookie(), csrf, key).statusCode()).isEqualTo(202);
        assertThat(this.runs.runOf(sample.user()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(this.runs.runOf(sample.user()).orElseThrow().steps())
                .filteredOn(step -> step.step() == DeletionStep.SYNC).singleElement()
                .extracting(StepState::attempts).isEqualTo(3);
    }

    /** 실패 기록 트랜잭션이 한 번 실패해도 새 트랜잭션으로 다시 기록한다. */
    @Test
    void 실패_기록이_한_번_실패해도_새_트랜잭션으로_기록한다() throws Exception {
        Sample sample = sample("erasure-run-record-retry");
        requestDeletion(sample.user());
        int[] failures = {1};
        AccountDeletionRunApi flaky = runsWith((method, args) -> {
            if (method.getName().equals("stepsNeedRetry") && failures[0]-- > 0) {
                throw new IllegalStateException("record-failed");
            }
            return null;
        });
        AccountErasureRun runner = new AccountErasureRun(erasureWith(failingSync(), this.operations, this.engagement),
                flaky, this.manager);
        assertThat(runner.execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        assertThat(statusesOf(this.runs.runOf(sample.user()).orElseThrow().steps()))
                .contains("SYNC:RETRY_NEEDED:STEP_FAILED");
    }

    /**
     * 실패를 끝내 기록하지 못하면 기록됐다고 보고하지 않는다 — 결과는 {@code UNRECORDED}이고 실행은 {@code RUNNING}으로 남아 lease
     * 만료 뒤 장애 복구에 맡겨지며 error code 한 줄이 남는다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 실패를_기록할_수_없으면_UNRECORDED이고_실행은_RUNNING으로_남는다(CapturedOutput output) throws Exception {
        Sample sample = sample("erasure-run-unrecorded");
        requestDeletion(sample.user());
        AccountDeletionRunApi broken = runsWith((method, args) -> {
            if (method.getName().equals("stepsNeedRetry")) {
                throw new IllegalStateException("record-failed");
            }
            return null;
        });
        AccountErasureRun runner = new AccountErasureRun(erasureWith(failingSync(), this.operations, this.engagement),
                broken, this.manager);
        assertThat(runner.execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.UNRECORDED);
        assertThat(this.runs.runOf(sample.user()).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(statusesOf(this.runs.runOf(sample.user()).orElseThrow().steps())).contains("SYNC:PENDING");
        assertThat(LogLines.records(output.getAll())).anySatisfy(record -> {
            assertThat(String.valueOf(record.get("level"))).isEqualTo("ERROR");
            assertThat(record).containsEntry("event", "account erasure failure unrecorded")
                    .containsEntry("code", AccountErasureRun.FAILURE_UNRECORDED).containsEntry("kind", "SYNC")
                    .containsEntry("exception", "IllegalStateException");
            assertThat(record.keySet()).isSubsetOf(LOG_KEYS);
        });
    }

    /** 단계 처리 밖의 예상하지 못한 예외도 조용히 lease 만료에 맡기지 않고 아직 성공하지 않은 첫 단계를 재시도 필요로 기록한다. */
    @Test
    void 단계_밖_예외는_첫_미완료_단계를_재시도_필요로_기록한다() throws Exception {
        Sample sample = sample("erasure-run-unexpected");
        requestDeletion(sample.user());
        AccountErasureRun exploding = new AccountErasureRun(erasureWith(this.sync, this.operations, this.engagement),
                this.runs, this.manager) {
            @Override
            Result execute(AccountDeletionRunApi.Lease lease, java.util.function.BooleanSupplier stopping) {
                throw new IllegalStateException("outside-steps");
            }
        };
        AccountErasureWorker unexpected = new AccountErasureWorker(exploding, this.runs, this.tombstones,
                "development", false);
        assertThat(unexpected.runOnce()).isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        assertThat(unexpected.inFlight()).isZero();
        var run = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.RETRY_NEEDED);
        assertThat(statusesOf(run.steps())).first().isEqualTo("CREDENTIALS:RETRY_NEEDED:STEP_FAILED");
        // 다시 집지 않는다.
        assertThat(this.worker.runOnce()).isNull();
    }

    /** 실제 Worker의 주기 dispatch(스케줄 켬)는 다른 스레드에서 실행을 끝까지 돈다. */
    @Test
    void 주기_dispatch는_다른_스레드에서_실행을_끝낸다() throws Exception {
        Sample sample = sample("erasure-run-dispatch");
        requestDeletion(sample.user());
        AccountErasureWorker scheduled = new AccountErasureWorker(erasureRun(), this.runs, this.tombstones,
                "development", true);
        scheduled.dispatch();
        awaitTrue(() -> this.runs.runOf(sample.user()).map(run -> run.status() == RunStatus.OPERATIONAL_DATA_ERASED)
                .orElse(false));
        awaitTrue(() -> scheduled.inFlight() == 0);
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETED");
    }

    /**
     * heartbeat는 진행 중 실행의 lease를 밀고(단계 사이), 단계 트랜잭션이 실행 행을 쥐고 있어도 붙들리지 않는다. 종료 drain은 claim을
     * 멈추고 진행 중 실행이 다음 단계 앞에서 lease를 놓게 한다 — 실행은 {@code QUEUED}, 진행 중은 0이다.
     */
    @Test
    void heartbeat는_lease를_밀고_단계_잠금에_붙들리지_않으며_drain은_lease를_놓는다() throws Exception {
        Sample sample = sample("erasure-run-heartbeat");
        requestDeletion(sample.user());
        java.util.concurrent.CountDownLatch atHold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseHold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch inSync = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseSync = new java.util.concurrent.CountDownLatch(1);
        int[] holds = {0};
        // 첫 hold(Story 5.3의 tombstone 기록) 직전(행 잠금 전)에 한 번, SYNC 단계 안(행 잠금 중)에서 한 번 멈춘다.
        AccountDeletionRunApi pausing = runsWith((method, args) -> {
            if (method.getName().equals("hold") && holds[0]++ == 0) {
                atHold.countDown();
                releaseHold.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            return null;
        });
        SyncJobApi blockingSync = (SyncJobApi) Proxy.newProxyInstance(SyncJobApi.class.getClassLoader(),
                new Class<?>[] {SyncJobApi.class}, (proxy, method, args) -> {
                    if (method.getName().equals("eraseSyncData")) {
                        inSync.countDown();
                        releaseSync.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    return invoke(method, this.sync, args);
                });
        AccountErasureWorker worker = new AccountErasureWorker(
                new AccountErasureRun(erasureWith(blockingSync, this.operations, this.engagement), pausing, this.manager),
                this.runs, this.tombstones, "development", true);
        worker.dispatch();
        assertThat(atHold.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(worker.inFlight()).isEqualTo(1);

        java.time.OffsetDateTime before = leaseUntil(sample.user());
        Thread.sleep(50);
        worker.heartbeat();
        assertThat(leaseUntil(sample.user())).isAfter(before);

        releaseHold.countDown();
        assertThat(inSync.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // 단계 트랜잭션이 실행 행을 쥐고 있다 — heartbeat는 짧은 잠금 대기 뒤 건너뛰고 돌아온다.
        long started = System.nanoTime();
        worker.heartbeat();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

        // drain: claim을 멈추고 진행 중 실행은 다음 단계 앞에서 lease를 놓는다.
        worker.stopClaims();
        releaseSync.countDown();
        awaitTrue(() -> worker.inFlight() == 0);
        var released = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(released.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(statusesOf(released.steps())).contains("SYNC:SUCCEEDED", "PLAN:PENDING");
        worker.dispatch();
        assertThat(worker.inFlight()).isZero();
        assertThat(this.runs.runOf(sample.user()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        // 다음 Worker가 이어서 끝낸다.
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
    }

    /** 동시에 두 claim이 와도 같은 실행은 한 번만 집힌다. */
    @Test
    void 동시_claim은_같은_실행을_한_번만_집는다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        requestDeletion(user);
        for (int round = 0; round < 2; round++) {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<Optional<AccountDeletionRunApi.Lease>> claims;
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> {
                    await(start);
                    return this.runs.claim(WorkerLease.DURATION);
                });
                var second = executor.submit(() -> {
                    await(start);
                    return this.runs.claim(WorkerLease.DURATION);
                });
                start.countDown();
                claims = List.of(first.get(), second.get());
            }
            assertThat(claims).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(jdbc().sql("SELECT count(*) FROM identity_deletion_step WHERE user_id = :u").param("u", user)
                    .query(Integer.class).single()).isEqualTo(DeletionStep.values().length);
            // 둘째 판: 놓은 실행(QUEUED)을 두 Worker가 함께 노린다.
            assertThat(this.runs.release(claims.stream().flatMap(Optional::stream).findFirst().orElseThrow())).isTrue();
        }
    }

    /** 실행 도중(단계 사이) fencing을 잃으면 거기서 멈추고 뒤 단계는 커밋하지 않는다. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 실행_도중_fencing을_잃으면_뒤_단계를_커밋하지_않는다(CapturedOutput output) throws Exception {
        Sample sample = sample("erasure-run-midfence");
        requestDeletion(sample.user());
        int[] holds = {0};
        AccountDeletionRunApi fenced = runsWith((method, args) -> {
            if (method.getName().equals("hold") && ++holds[0] == 4) {
                // SYNC 단계 앞(첫 hold는 Story 5.3의 tombstone 기록이다): 다른 Worker가 그 사이 집었다(별도 트랜잭션으로 세대를 올린다).
                Thread bump = Thread.ofVirtual().start(() -> jdbc().sql("UPDATE identity_deletion_run SET "
                        + "fencing_generation = fencing_generation + 1 WHERE user_id = :u")
                        .param("u", sample.user()).update());
                bump.join();
            }
            return null;
        });
        AccountErasureRun runner = new AccountErasureRun(erasureWith(this.sync, this.operations, this.engagement),
                fenced, this.manager);
        assertThat(runner.execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.LEASE_LOST);
        assertThat(statusesOf(this.runs.runOf(sample.user()).orElseThrow().steps())).containsExactly(
                "CREDENTIALS:SUCCEEDED", "PORTFOLIO:SUCCEEDED", "SYNC:PENDING", "PLAN:PENDING", "SOURCE:PENDING",
                "OPERATIONS:PENDING", "ENGAGEMENT:PENDING", "IDENTITY:PENDING");
        assertThat(this.sync.remainingRowsOf(sample.user())).isPositive();
        assertThat(countOf("plan_definition", sample.user())).isPositive();
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETION_IN_PROGRESS");
        // 로그: 결말 `outcome`과 멈춘 단계 `kind`가 허용 키로 나온다.
        assertThat(LogLines.records(output.getAll())).anySatisfy(record -> {
            assertThat(record).containsEntry("event", "account erasure run")
                    .containsEntry("outcome", AccountErasureRun.Result.LEASE_LOST.name()).containsEntry("kind", "SYNC");
            assertThat(record.keySet()).isSubsetOf(LOG_KEYS);
        });
    }

    /** 삭제 요청이 없으면 다시 시도는 없는 경로와 같은 404이고, Worker가 집기 전이면 409·아무것도 쓰지 않으며 조회는 진행 중이다. */
    @Test
    void 요청_없는_다시_시도는_404이고_집기_전에는_409다() throws Exception {
        Sample active = sample("erasure-run-no-request");
        var missing = retry(active.cookie(), csrfTokenOf(active.cookie()), "erasure-none-" + UUID.randomUUID());
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(404);
        assertThat(topLevelFieldsOf(missing.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", ApiErrorHandler.CODE_NOT_FOUND)
                .containsEntry("message", ApiErrorHandler.MESSAGE_NOT_FOUND);

        requestDeletion(active.user());
        JsonNode progress = json(get(DELETION_PATH, active.cookie()).body()).get("request").get("progress");
        assertThat(progress.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(strings(progress.get("failedSteps"))).isEmpty();
        var early = retry(active.cookie(), csrfTokenOf(active.cookie()), "erasure-early-" + UUID.randomUUID());
        assertThat(early.statusCode()).as(early.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(early.body())).containsEntry("code", AccountDeletionRequestService.NOT_RETRYABLE);
        assertThat(this.runs.runOf(active.user())).isEmpty();
    }

    /** 단일 모드(복원 재삭제·리허설 표본)가 지운 계정의 남은 단계 실행은 같은 커밋으로 운영 데이터 삭제 완료로 마감된다. */
    @Test
    void 단일_모드가_지우면_남은_단계_실행을_마감한다() throws Exception {
        Sample sample = sample("erasure-run-single");
        String requestId = requestDeletion(sample.user());
        assertThat(runnerWith(failingSync(), this.operations, this.engagement)
                .execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);

        assertThat(this.erasure.erase(sample.user(), requestId)).isEqualTo(AccountErasure.Outcome.ERASED);
        var run = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.OPERATIONAL_DATA_ERASED);
        assertThat(run.operationalErasedAt()).isNotNull();
        assertThat(run.failedSteps()).isEmpty();
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETED");
        assertThat(this.worker.runOnce()).isNull();
    }

    /** 삭제 요청 행이 없는 실행은 집지 않는다. */
    @Test
    void 요청_행이_없는_실행은_집지_않는다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        requestDeletion(user);
        assertThat(this.runs.release(this.runs.claim(WorkerLease.DURATION).orElseThrow())).isTrue();
        jdbc().sql("DELETE FROM identity_deletion_request WHERE user_id = :u").param("u", user).update();
        assertThat(this.runs.claim(WorkerLease.DURATION)).isEmpty();
    }

    /** 첫 단계의 자격정보 삭제 port는 활성 계정에는 아무것도 하지 않는다. */
    @Test
    void 활성_계정의_자격정보는_지우지_않는다() {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        assertThat(this.source.remainingCredentialsOf(user)).isEqualTo(1);
        assertThat(this.source.eraseCredentials(user)).isZero();
        assertThat(this.source.remainingCredentialsOf(user)).isEqualTo(1);
        assertThat(this.accountDeletion().eraseSocialLinks(user)).isZero();
    }

    /**
     * 컨트롤러의 잠금 없는 판정을 지난 뒤 접수 트랜잭션의 생명주기 잠금 아래에서 삭제 진행을 보면, 접수는 없는 경로와 같은 404이고 아무것도
     * 쓰지 않는다. 삭제 진행 전환을 커밋하지 않은 트랜잭션이 생명주기 행을 쥔 채로 접수를 기다리게 해 그 경합을 만든다.
     */
    @Test
    void 판정_뒤에_삭제가_시작된_문의_접수는_404이고_아무것도_쓰지_않는다() throws Exception {
        Sample sample = sample("erasure-run-inquiry-race");
        String csrf = csrfTokenOf(sample.cookie());
        int before = countOf("support_inquiry", sample.user());
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch commit = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger holderPid = new java.util.concurrent.atomic.AtomicInteger();
        HttpResponse<String> answered;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(
                    this.manager).executeWithoutResult(status -> {
                        holderPid.set(jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        jdbc().sql("UPDATE identity_account_lifecycle SET state = 'DELETION_IN_PROGRESS' "
                                + "WHERE user_id = :u").param("u", sample.user()).update();
                        locked.countDown();
                        await(commit);
                    }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var response = executor.submit(() -> sendJson("POST", SupportInquiryController.INQUIRIES_PATH,
                    "{\"category\":\"ACCOUNT\",\"title\":\"경합 문의\",\"description\":\"판정 뒤 삭제가 시작된 문의입니다.\","
                            + "\"contact\":\"IN_APP\"}", sample.cookie(), "Origin",
                    OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf,
                    "Idempotency-Key", "erasure-race-" + UUID.randomUUID()));
            // 접수가 바로 그 생명주기 행을 쥔 세션(holder)에 막힐 때까지 — 다른 대기 잠금은 세지 않는다.
            awaitTrue(() -> Boolean.TRUE.equals(jdbc().sql("SELECT count(*) > 0 FROM pg_stat_activity "
                            + "WHERE wait_event_type = 'Lock' AND :holder = ANY(pg_blocking_pids(pid)) "
                            + "AND query LIKE '%identity_account_lifecycle%'")
                    .param("holder", holderPid.get()).query(Boolean.class).single()));
            commit.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
            answered = response.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(404);
        assertThat(topLevelFieldsOf(answered.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", ApiErrorHandler.CODE_NOT_FOUND);
        assertThat(countOf("support_inquiry", sample.user())).isEqualTo(before);
    }

    // ------------------------------------------------------------------------------------------ 2차 리뷰 보강

    /**
     * 긴 단계: 단계 트랜잭션이 실행 행을 쥔 동안 heartbeat가 막혀 lease가 이미 지났어도, 그 사이 다른 claim이 세대를 올리지 않았으면 다음
     * 단계의 {@code hold}가 lease를 새로 밀고 이어 간다 — 정확성은 세대가 지킨다.
     */
    @Test
    void lease가_지났어도_세대가_그대로면_다음_단계로_이어_간다() throws Exception {
        Sample sample = sample("erasure-run-long-step");
        requestDeletion(sample.user());
        int[] holds = {0};
        AccountDeletionRunApi expiring = runsWith((method, args) -> {
            if (method.getName().equals("hold") && ++holds[0] == 4) {
                // SYNC 단계 앞(hold 1은 Story 5.3의 tombstone 기록, 2는 CREDENTIALS, 3은 PORTFOLIO): 앞 단계가 오래 걸려 lease가 이미
                // 지났다(세대는 그대로).
                Thread expire = Thread.ofVirtual().start(() -> jdbc().sql("UPDATE identity_deletion_run SET "
                        + "lease_until = statement_timestamp() - interval '1 second' WHERE user_id = :u")
                        .param("u", sample.user()).update());
                expire.join();
            }
            return null;
        });
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(new AccountErasureRun(erasureWith(this.sync, this.operations, this.engagement), expiring,
                this.manager).execute(lease, () -> false)).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(lifecycleOf(sample.user())).isEqualTo("DELETED");
    }

    /** production(G-5 전)에서는 다시 시도도 준비·확정과 같은 403 {@code ACCOUNT_DELETION_UNAVAILABLE}이고 아무것도 쓰지 않는다. */
    @Test
    void production에서는_다시_시도도_403이고_아무것도_쓰지_않는다() throws Exception {
        Sample sample = sample("erasure-run-prod-retry");
        requestDeletion(sample.user());
        assertThat(runnerWith(failingSync(), this.operations, this.engagement)
                .execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        this.requests.reinterpret("production");
        var refused = retry(sample.cookie(), csrfTokenOf(sample.cookie()), "erasure-prod-" + UUID.randomUUID());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", AccountDeletionRequestService.UNAVAILABLE);
        assertThat(this.runs.runOf(sample.user()).orElseThrow().status()).isEqualTo(RunStatus.RETRY_NEEDED);
        assertThat(jdbc().sql("SELECT retry_key_digest IS NULL FROM identity_deletion_run WHERE user_id = :u")
                .param("u", sample.user()).query(Boolean.class).single()).isTrue();
    }

    /** 다시 시도를 받아 대기·실행 중이면 지난 실패 범위를 싣지 않는다 — 실패 범위는 재시도 필요일 때만이다. */
    @Test
    void 다시_시도를_받은_실행의_진행에는_실패_범위가_없다() throws Exception {
        Sample sample = sample("erasure-run-progress-clear");
        requestDeletion(sample.user());
        assertThat(runnerWith(failingSync(), this.operations, this.engagement)
                .execute(this.runs.claim(WorkerLease.DURATION).orElseThrow(), () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        var accepted = retry(sample.cookie(), csrfTokenOf(sample.cookie()), "erasure-clear-" + UUID.randomUUID());
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(strings(json(accepted.body()).get("progress").get("failedSteps"))).isEmpty();
        JsonNode progress = json(get(DELETION_PATH, sample.cookie()).body()).get("request").get("progress");
        assertThat(progress.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(strings(progress.get("failedSteps"))).isEmpty();
        // 단계 행에는 실패 기록이 남아 있다(상태 권위) — 화면에만 싣지 않는다.
        assertThat(this.runs.runOf(sample.user()).orElseThrow().failedSteps()).containsExactly(DeletionStep.SYNC);
    }

    /** drain에서 lease를 놓다가 실패하면 단계 실패로 적지 않는다 — {@code UNRECORDED}이고 실행은 lease 만료에 맡긴다. */
    @Test
    void lease를_놓지_못하면_단계를_실패로_적지_않는다() throws Exception {
        Sample sample = sample("erasure-run-release-fail");
        requestDeletion(sample.user());
        AccountDeletionRunApi unreleasable = runsWith((method, args) -> {
            if (method.getName().equals("release")) {
                throw new IllegalStateException("release-failed");
            }
            return null;
        });
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(new AccountErasureRun(erasureWith(this.sync, this.operations, this.engagement), unreleasable,
                this.manager).execute(lease, () -> true)).isEqualTo(AccountErasureRun.Result.UNRECORDED);
        var run = this.runs.runOf(sample.user()).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(statusesOf(run.steps())).allMatch(step -> step.endsWith(":PENDING"));
    }

    /** dispatch가 실행 스레드를 시작하지 못하면 진행 중에서 빼고 lease를 놓는다 — drain을 영원히 붙들지 않는다. */
    @Test
    void 실행_스레드를_시작하지_못하면_진행_중에서_빼고_lease를_놓는다() throws Exception {
        Sample sample = sample("erasure-run-thread-fail");
        requestDeletion(sample.user());
        AccountErasureWorker worker = new AccountErasureWorker(erasureRun(), this.runs, this.tombstones,
                "development", true);
        worker.threads = task -> {
            throw new IllegalStateException("no-thread");
        };
        worker.dispatch();
        assertThat(worker.inFlight()).isZero();
        assertThat(this.runs.runOf(sample.user()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
    }

    // ------------------------------------------------------------------------------------------ 도구

    /** 실제 실행 상태 API를 감싸고, 위임 앞에 {@code before}를 부른다(값을 돌려주면 그 값으로 끝낸다). */
    private interface Before {
        @Nullable Object call(java.lang.reflect.Method method, @Nullable Object[] args) throws Throwable;
    }

    private AccountDeletionRunApi runsWith(Before before) {
        return (AccountDeletionRunApi) Proxy.newProxyInstance(AccountDeletionRunApi.class.getClassLoader(),
                new Class<?>[] {AccountDeletionRunApi.class}, (proxy, method, args) -> {
                    Object replaced = before.call(method, args);
                    return replaced != null ? replaced : invoke(method, this.runs, args);
                });
    }

    private AccountErasure erasureWith(SyncJobApi syncs, OperationsDeletionApi operationPorts,
            EngagementDeletionApi engagementPorts) {
        return new AccountErasure(accountDeletion(), this.tombstones, this.portfolio, syncs, this.plans, this.source,
                operationPorts, engagementPorts, this.manager);
    }

    private java.time.OffsetDateTime leaseUntil(UUID user) {
        return jdbc().sql("SELECT lease_until FROM identity_deletion_run WHERE user_id = :u").param("u", user)
                .query(java.time.OffsetDateTime.class).single();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("조건이 시간 안에 서지 않았다.");
            }
            Thread.sleep(50);
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private Sample sample(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        publishedPlan(user, prefix, null);
        assertThat(this.inquiries.submit(user, prefix + "-inquiry-" + UUID.randomUUID(), draft()))
                .isInstanceOf(SupportInquiryApi.Submission.Received.class);
        insertConsentOf(user);
        jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, skip_reason) "
                        + "VALUES (:u, :j, 'SKIPPED', 'CONSENT_REQUIRED')")
                .param("u", user).param("j", UUID.randomUUID()).update();
        insertAuditRowOf(user);
        // 사용자 귀속 제한 target과 신호 귀속(Story 3.3) — 강한 신호가 연결 범위의 제한을 연다.
        this.signals.raise(new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM,
                RestrictionSubject.none().withConnection(UUID.randomUUID()), user,
                Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()), null));
        return new Sample(user, cookie);
    }

    private static SupportInquiryApi.Draft draft() {
        return new SupportInquiryApi.Draft("ACCOUNT", "계정 삭제 시험 문의", "계정 삭제 실행 시험에서 남기는 문의 본문입니다.",
                "IN_APP", null);
    }

    /** 그 사용자의 광고 동의 결정 행 하나(ENGAGEMENT 단계가 지우는 행). */
    private void insertConsentOf(UUID user) {
        jdbc().sql("INSERT INTO engagement_ad_consent_decision (user_id, policy_version, choice, idempotency_key_digest, "
                        + "request_digest) VALUES (:u, 'AD-CONSENT-1.0', 'GRANTED', :k, :r)")
                .param("u", user).param("k", sha256("consent-key-" + UUID.randomUUID()))
                .param("r", sha256("consent-request-" + UUID.randomUUID())).update();
    }

    private void insertAuditRowOf(UUID user) {
        jdbc().sql("INSERT INTO operations_audit_event (audit_id, operation_id, actor_user_id, actor_digest, actor_kind, "
                        + "command, scope, target, reason, outcome, policy_version, requested_at, correlation_id) VALUES "
                        + "(gen_random_uuid(), :op, :u, :d, 'OPERATOR', 'restrict-sync', 'GLOBAL', 'GLOBAL', 'test', "
                        + "'REQUESTED', 'X', now(), :op)")
                .param("op", "erasure-run-" + UUID.randomUUID()).param("u", user)
                .param("d", sha256("actor-" + user)).update();
    }

    /** 삭제 요청(Story 5.1의 요청 행과 삭제 진행 전환). 요청 ID를 돌려준다. */
    private String requestDeletion(UUID user) {
        var outcome = accountDeletion().requestDeletion(user, AccountDeletionPolicy.VERSION,
                AccountDeletionPolicy.noticeHash(), sha256("erasure-request-" + UUID.randomUUID()));
        assertThat(outcome).isInstanceOf(DeletionRequestOutcome.Created.class);
        return ((DeletionRequestOutcome.Created) outcome).request().requestId();
    }

    private Map<DeletionStep, Long> residues(UUID user) {
        Map<DeletionStep, Long> rows = new EnumMap<>(DeletionStep.class);
        for (DeletionStep step : DeletionStep.values()) {
            rows.put(step, this.erasure.residueOf(step, user));
        }
        return rows;
    }

    private AccountErasureRun erasureRun() {
        return runnerWith(this.sync, this.operations, this.engagement);
    }

    private AccountErasureRun runnerWith(SyncJobApi syncs, OperationsDeletionApi operationPorts,
            EngagementDeletionApi engagementPorts) {
        return new AccountErasureRun(new AccountErasure(accountDeletion(), this.tombstones, this.portfolio, syncs,
                this.plans, this.source, operationPorts, engagementPorts, this.manager), this.runs, this.manager);
    }

    private SyncJobApi failingSync() {
        return (SyncJobApi) Proxy.newProxyInstance(SyncJobApi.class.getClassLoader(), new Class<?>[] {SyncJobApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("eraseSyncData")) {
                        throw new IllegalStateException("injected");
                    }
                    return invoke(method, this.sync, args);
                });
    }

    private static @Nullable Object invoke(java.lang.reflect.Method method, Object delegate, @Nullable Object[] args)
            throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private HttpResponse<String> retry(String cookie, @Nullable String csrf, @Nullable String key) throws Exception {
        List<String> headers = new ArrayList<>(List.of("Origin", OidcStubConfiguration.PUBLIC_BASE_URL));
        if (csrf != null) {
            headers.add(AuthSecurityConfig.CSRF_HEADER_NAME);
            headers.add(csrf);
        }
        if (key != null) {
            headers.add("Idempotency-Key");
            headers.add(key);
        }
        return send("POST", RETRY_PATH, cookie, headers.toArray(String[]::new));
    }

    private static List<String> statusesOf(List<StepState> steps) {
        return steps.stream().map(step -> step.step().name() + ":" + step.status().name()
                + (step.failureCode() == null ? "" : ":" + step.failureCode())).toList();
    }

    /** 이 스레드의 트랜잭션 backend가 감사 원장 추가 잠금을 쥐었는가. */
    private boolean holdsAppendLock() {
        return Boolean.TRUE.equals(jdbc().sql("SELECT count(*) > 0 FROM pg_locks WHERE locktype = 'advisory' AND granted "
                        + "AND objsubid = 1 AND pid = pg_backend_pid() "
                        + "AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(:name, 0)")
                .param("name", "bokrilog.operations_audit_event.append").query(Boolean.class).single());
    }

    /** 집합이 열린(운영 완료 전) tombstone 수. */
    private int openTombstones() {
        return jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone WHERE closed_at IS NULL")
                .query(Integer.class).single();
    }

    /** tombstone 감사 SYSTEM 행(Story 5.3)의 reason 목록(순서대로). */
    private List<String> tombstoneAuditReasons() {
        return jdbc().sql("SELECT reason FROM operations_audit_event WHERE command = 'tombstone' ORDER BY sequence")
                .query(String.class).list();
    }

    private String lifecycleOf(UUID user) {
        return jdbc().sql("SELECT state FROM identity_account_lifecycle WHERE user_id=:u").param("u", user)
                .query(String.class).single();
    }

    private static JsonNode json(String body) {
        return JsonMapper.builder().build().readTree(body);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asString());
        }
        return values;
    }
}
