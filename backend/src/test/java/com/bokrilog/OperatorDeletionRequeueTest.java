package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.identity.AccountDeletionApi.DeletionRequestOutcome;
import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.identity.AccountDeletionRunApi.RunStatus;
import com.bokrilog.identity.AccountDeletionRunApi.StepState;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.WorkerLease;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Epic 5 회고 AI-1: 재시도 필요로 멈춘 계정 삭제 실행을 운영 command {@code requeue-deletion}으로 다시 대기열에 넣는다 — 실제 스택(Docker
 * PostgreSQL, local OIDC 스텁)에서. 요청 세션을 잃은 계정이 {@code DELETION_IN_PROGRESS}로 고착되지 않게, 운영자가 목록에서 그 실행을 골라
 * 재인증·문구 재입력으로 확정하면 Worker가 실패 단계부터 다시 돌아 생명주기가 {@code DELETED}가 된다. SPEC I/O 매트릭스 여덟 행(정상·상태
 * 다름·없는 대상·형식 오류·준비 뒤 경합·production·재전송·목록 권한)과 실행 행을 쥔 트랜잭션이 있을 때 기다리지 않는 거절, 원장에 요청 ID
 * 원문·사용자 식별자가 남지 않음을 본다.
 */
class OperatorDeletionRequeueTest extends OperatorTestBase {

    private static final String COMMAND = OperatorCommandApi.REQUEUE_DELETION;

    @Autowired AccountErasureWorker worker;

    @Autowired AccountDeletionRunApi runs;

    @Autowired DeletionTombstoneApi tombstones;

    @Autowired OperationsDeletionApi operations;

    @Autowired EngagementDeletionApi engagement;

    @Autowired AccountDeletionRequestService requests;

    @AfterEach
    void restoreEnvironment() {
        this.requests.reinterpret("development");
    }

    // ------------------------------------------------------------------------------------------ 정상·재전송

    /**
     * 정상(AC-1): 목록에 재시도 필요 실행이 요청 ID·시각·실패 단계로만 보이고, 준비 → 확정이 실행을 {@code QUEUED}로 되돌리며 단계 상태는
     * 그대로다. 원장은 {@code REQUESTED}·{@code COMPLETED}이고 target은 요청 ID의 digest다. 같은 키의 확정 재전송은 같은 본문이다. Worker가
     * 다시 집어 실패한 단계부터 돌고(성공 단계는 다시 돌지 않는다) 생명주기가 {@code DELETED}가 된다.
     */
    @Test
    void 재시도_필요_실행을_다시_대기열에_넣으면_Worker가_실패_단계부터_돌아_삭제를_끝낸다() throws Exception {
        Stopped stopped = stoppedDeletion("requeue-normal");
        Signed operator = operator("requeue-op", "command");

        // 목록: 요청 ID·요청 시각·멈춘 시각·실패 단계뿐이다. 사용자 식별자가 없다.
        var listed = deletionsGet(operator);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(listed.body()).doesNotContain(stopped.user().toString());
        assertThat(topLevelFieldsOf(listed.body())).containsOnlyKeys("available", "truncated", "items")
                .containsEntry("available", true).containsEntry("truncated", false);
        List<Map<String, Object>> items = itemsIn(listed.body(), "items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsOnlyKeys("requestId", "requestedAt", "stoppedAt", "failedSteps")
                .containsEntry("requestId", stopped.requestId());
        assertThat(items.getFirst().get("requestedAt")).isInstanceOf(String.class);
        assertThat(items.getFirst().get("stoppedAt")).isInstanceOf(String.class);
        assertThat(items.getFirst().get("failedSteps"))
                .isEqualTo(List.of(Map.of("step", "SYNC", "failureCode", "STEP_FAILED", "attempts", 1)));

        String key = key("requeue");
        var prepared = prepare(operator, stopped.requestId(), "요청 세션을 잃은 삭제 재시도", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        Map<String, Object> fields = topLevelFieldsOf(prepared.body());
        assertThat(fields).containsEntry("command", COMMAND).containsEntry("scope", OperatorCommandApi.DELETION_SCOPE)
                .containsEntry("target", stopped.requestId())
                .containsEntry("confirmationPhrase", "삭제 재시도 " + stopped.requestId())
                .containsEntry("policyVersion", "OPERATOR-COMMAND-1.5");
        @SuppressWarnings("unchecked")
        Map<String, Object> impact = (Map<String, Object>) fields.get("impact");
        assertThat(impact).containsOnlyKeys("requestId", "requestedAt", "stoppedAt", "failedSteps")
                .containsEntry("requestId", stopped.requestId());
        assertThat(prepared.body()).doesNotContain(stopped.user().toString());

        String operationId = operationIdIn(prepared.body());
        var confirmed = confirmRestriction(operator, COMMAND, operationId, phraseIn(prepared.body()), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(confirmed.body())).containsOnlyKeys("operationId", "outcome", "command", "status",
                "requeuedAt", "retrySteps").containsEntry("outcome", "COMPLETED").containsEntry("command", COMMAND)
                .containsEntry("status", "QUEUED").containsEntry("retrySteps", 1);
        Signed rotated = after(operator, confirmed);

        // 실행만 QUEUED로 돌아갔다. 단계 상태는 그대로이고 키 digest는 운영자 전용 접두사의 것이다.
        var queued = this.runs.runOf(stopped.user()).orElseThrow();
        assertThat(queued.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(queued.failedSteps()).containsExactly(com.bokrilog.identity.DeletionStep.SYNC);
        assertThat(jdbc().sql("SELECT retry_key_digest FROM identity_deletion_run WHERE user_id = :u")
                .param("u", stopped.user()).query(String.class).single())
                .isEqualTo(sha256("account-deletion-operator-requeue\n" + operationId));

        // 원장: 요청·완료 두 행, target은 digest다. 요청 ID 원문과 삭제 계정의 user_id는 원장 어디에도 없다(AC-4).
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");
        assertThat(jdbc().sql("SELECT DISTINCT target FROM operations_audit_event WHERE operation_id = :o")
                .param("o", operationId).query(String.class).list()).singleElement()
                .satisfies(target -> assertThat(target).matches("[0-9a-f]{64}"));
        // details: 멱등 키 digest(엔진)와 상태·단계 수·시각뿐이다.
        assertThat(jdbc().sql("SELECT string_agg(key, ',' ORDER BY key) || '|' || (details->>'status') || '|' "
                        + "|| (details->>'retrySteps') FROM operations_audit_event, jsonb_object_keys(details) AS key "
                        + "WHERE operation_id = :o AND outcome = 'COMPLETED' GROUP BY details")
                .param("o", operationId).query(String.class).single())
                .isEqualTo("keyDigest,requeuedAt,retrySteps,status|QUEUED|1");
        assertNoIdentifiersInLedger(stopped);

        // 재전송: 같은 키의 확정은 원장에서 같은 본문이다(실행 행은 그대로).
        var replayed = confirmRestriction(rotated, COMMAND, operationId, phraseIn(prepared.body()), key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body())).isEqualTo(topLevelFieldsOf(confirmed.body()));
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");

        // Worker가 다시 집어 실패 단계부터 돈다 — 앞서 성공한 단계는 다시 돌지 않는다.
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(lifecycleOf(stopped.user())).isEqualTo("DELETED");
        var finished = this.runs.runOf(stopped.user()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.OPERATIONAL_DATA_ERASED);
        assertThat(finished.steps()).extracting(step -> step.step().name() + ":" + step.attempts())
                .containsExactly("CREDENTIALS:1", "PORTFOLIO:1", "SYNC:2", "PLAN:1", "SOURCE:1", "OPERATIONS:1",
                        "ENGAGEMENT:1", "IDENTITY:1");
        assertThat(itemsIn(deletionsGet(rotated).body(), "items")).isEmpty();
        assertNoIdentifiersInLedger(stopped);
    }

    // ------------------------------------------------------------------------------------------ 상태 다름

    /**
     * 상태 다름: {@code QUEUED}·{@code RUNNING}·{@code OPERATIONAL_DATA_ERASED}·{@code DELETION_COMPLETED} 실행은 준비가 409
     * {@code DELETION_NOT_RETRYABLE}이고 거절 행을 남기며 실행을 바꾸지 않는다.
     */
    @Test
    void 재시도_필요가_아닌_실행은_바꾸지_않고_DELETION_NOT_RETRYABLE이다() throws Exception {
        Stopped stopped = stoppedDeletion("requeue-states");
        Signed operator = operator("requeue-states-op", "command");
        // QUEUED: 사용자의 다시 시도로 이미 대기열에 들어갔다.
        assertThat(this.runs.acceptRetry(stopped.user(), sha256("user-retry-" + UUID.randomUUID())))
                .isInstanceOf(AccountDeletionRunApi.RetryOutcome.Accepted.class);
        assertNotRetryable(operator, stopped, RunStatus.QUEUED);
        // RUNNING: Worker가 집었다.
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(lease.userId()).isEqualTo(stopped.user());
        assertNotRetryable(operator, stopped, RunStatus.RUNNING);
        assertThat(this.runs.release(lease)).isTrue();
        // OPERATIONAL_DATA_ERASED: 끝까지 돌았다.
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertNotRetryable(operator, stopped, RunStatus.OPERATIONAL_DATA_ERASED);
        // DELETION_COMPLETED: 전체 삭제 완료.
        assertThat(this.runs.recordDeletionCompleted(List.of(stopped.requestId()))).isEqualTo(1);
        assertNotRetryable(operator, stopped, RunStatus.DELETION_COMPLETED);
        assertThat(decisionsOf(operator.user())).filteredOn(row -> row.startsWith("DENIED"))
                .containsOnly("DENIED:" + OperatorCommandApi.DELETION_NOT_RETRYABLE).hasSize(4);
        assertNoIdentifiersInLedger(stopped);
    }

    // ------------------------------------------------------------------------------------------ 없는 대상·형식 오류

    /**
     * 없는 대상과 형식 오류: 요청이 없는 ID, 요청은 있는데 실행 행이 없는(아직 집기 전) ID는 422 {@code TARGET_INVALID}(이 command의
     * 문장)이고, {@code del_} 모양이 아니면 같은 code, 사유가 없거나 이메일을 담으면 {@code REASON_REQUIRED}·{@code REASON_INVALID}다. 거절
     * 행의 target은 요청 ID 모양일 때만 digest이고 원문은 남지 않는다.
     */
    @Test
    void 없는_대상과_형식_오류는_422로_거절하고_원장에_원문을_남기지_않는다() throws Exception {
        Signed operator = operator("requeue-invalid-op", "command");
        Signed unclaimed = signedIn("requeue-unclaimed");
        String unclaimedId = requestDeletion(unclaimed.user());
        String missing = "del_" + UUID.randomUUID().toString().replace("-", "");

        for (String target : List.of(missing, unclaimedId)) {
            var refused = prepare(operator, target, "삭제 재시도", key("requeue-missing"));
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", OperatorCommandApi.TARGET_INVALID)
                    .containsEntry("message", OperatorController.DELETION_TARGET_INVALID);
        }
        assertThat(this.runs.runOf(unclaimed.user())).isEmpty();
        assertCode(prepare(operator, "abc", "삭제 재시도", key("requeue-shape")), 422,
                OperatorCommandApi.TARGET_INVALID);
        assertCode(prepare(operator, "del_" + "x".repeat(61), "삭제 재시도", key("requeue-long")), 422,
                OperatorCommandApi.TARGET_INVALID);
        assertCode(prepare(operator, missing, null, key("requeue-reason")), 422, OperatorCommandApi.REASON_REQUIRED);
        assertCode(prepare(operator, missing, "  ", key("requeue-blank")), 422, OperatorCommandApi.REASON_REQUIRED);
        assertCode(prepare(operator, missing, "연락 someone@example.com", key("requeue-email")), 422,
                OperatorCommandApi.REASON_INVALID);

        // 거절 행: 모두 같은 고정 scope, target은 요청 ID 모양이면 digest·아니면 "-"다. 요청 ID 원문은 어디에도 없다.
        List<String> targets = jdbc().sql("SELECT target FROM operations_audit_event WHERE command = :c "
                        + "ORDER BY sequence").param("c", COMMAND).query(String.class).list();
        assertThat(targets).hasSize(7).allSatisfy(target -> assertThat(target).matches("[0-9a-f]{64}|-"));
        assertThat(jdbc().sql("SELECT DISTINCT scope FROM operations_audit_event WHERE command = :c")
                .param("c", COMMAND).query(String.class).list()).containsExactly(OperatorCommandApi.DELETION_SCOPE);
        assertThat(ledgerMentions(missing)).isZero();
        assertThat(ledgerMentions(unclaimedId)).isZero();
        assertThat(ledgerMentions(unclaimed.user().toString())).isZero();
    }

    // ------------------------------------------------------------------------------------------ 준비 뒤 경합

    /**
     * 준비 뒤 경합: 준비와 확정 사이에 사용자 재시도와 Worker claim으로 실행이 {@code RUNNING}이 되면 확정은 0행이라 409
     * {@code DELETION_NOT_RETRYABLE}이고 실행·단계를 바꾸지 않는다.
     */
    @Test
    void 준비_뒤_실행이_RUNNING이_되면_확정은_거절한다() throws Exception {
        Stopped stopped = stoppedDeletion("requeue-race");
        Signed operator = operator("requeue-race-op", "command");
        String key = key("requeue-race");
        var prepared = prepare(operator, stopped.requestId(), "삭제 재시도", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);

        assertThat(this.runs.acceptRetry(stopped.user(), sha256("user-retry-" + UUID.randomUUID())))
                .isInstanceOf(AccountDeletionRunApi.RetryOutcome.Accepted.class);
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        long generation = lease.generation();

        String operationId = operationIdIn(prepared.body());
        var refused = confirmRestriction(operator, COMMAND, operationId, phraseIn(prepared.body()), key);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", OperatorCommandApi.DELETION_NOT_RETRYABLE);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        // 실행은 그 Worker의 것 그대로다(세대·상태·단계).
        assertThat(jdbc().sql("SELECT status || ':' || fencing_generation FROM identity_deletion_run "
                + "WHERE user_id = :u").param("u", stopped.user()).query(String.class).single())
                .isEqualTo("RUNNING:" + generation);
        assertThat(this.runs.runOf(stopped.user()).orElseThrow().failedSteps())
                .containsExactly(com.bokrilog.identity.DeletionStep.SYNC);
        assertThat(this.runs.release(lease)).isTrue();
    }

    /**
     * 실행 행을 다른 트랜잭션(단일 모드 삭제·사용자 재시도의 잠금)이 쥐고 있으면 확정은 기다리지 않는다 — {@code SKIP LOCKED}로 0행이라 바로
     * 409이고, 그 잠금이 풀린 뒤의 새 준비·확정은 성공한다. 원장 잠금을 쥔 확정이 실행 행에서 기다리면 단계 트랜잭션(실행 행 → 원장 순서)과
     * 교착할 수 있다.
     */
    @Test
    void 실행_행을_다른_트랜잭션이_쥐고_있으면_기다리지_않고_거절한다() throws Exception {
        Stopped stopped = stoppedDeletion("requeue-locked");
        Signed operator = operator("requeue-locked-op", "command");
        String key = key("requeue-locked");
        var prepared = prepare(operator, stopped.requestId(), "삭제 재시도", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);

        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> new TransactionTemplate(this.manager)
                .executeWithoutResult(status -> {
                    jdbc().sql("SELECT 1 FROM identity_deletion_run WHERE user_id = :u FOR UPDATE")
                            .param("u", stopped.user()).query(Integer.class).single();
                    acquired.countDown();
                    try {
                        if (!release.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("실행 행 잠금이 풀리지 않았다.");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
        HttpResponse<String> refused;
        long started = System.nanoTime();
        try {
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            refused = confirmRestriction(operator, COMMAND, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                    key);
        } finally {
            release.countDown();
            holder.join(10_000);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", OperatorCommandApi.DELETION_NOT_RETRYABLE);
        assertThat(this.runs.runOf(stopped.user()).orElseThrow().status()).isEqualTo(RunStatus.RETRY_NEEDED);

        // 잠금이 풀린 뒤에는 같은 실행을 다시 대기열에 넣는다(거절은 재인증 시각을 지우지 않는다).
        String next = key("requeue-locked-next");
        var again = prepare(operator, stopped.requestId(), "삭제 재시도", next);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        var confirmed = confirmRestriction(operator, COMMAND, operationIdIn(again.body()), phraseIn(again.body()), next);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(this.runs.runOf(stopped.user()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
    }

    // ------------------------------------------------------------------------------------------ production

    /**
     * production(G-5 전): 목록은 {@code available:false}·빈 items이고 준비는 403 {@code DELETION_REQUEUE_UNAVAILABLE}이다. 개발 환경에서
     * 준비한 것도 production에서 확정하면 같은 거절이고 실행은 그대로다.
     */
    @Test
    void production에서는_목록을_비우고_준비와_확정을_거절한다() throws Exception {
        Stopped stopped = stoppedDeletion("requeue-production");
        Signed operator = operator("requeue-production-op", "command");
        String key = key("requeue-production");
        var prepared = prepare(operator, stopped.requestId(), "삭제 재시도", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);

        this.requests.reinterpret(BokrilogRoleConfig.PRODUCTION);
        var listed = deletionsGet(operator);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(listed.body())).containsEntry("available", false).containsEntry("items", List.of());
        String operationId = operationIdIn(prepared.body());
        var refused = confirmRestriction(operator, COMMAND, operationId, phraseIn(prepared.body()), key);
        assertCode(refused, 403, OperatorCommandApi.DELETION_REQUEUE_UNAVAILABLE);
        // 확정 순간의 거절도 원장에 DENIED 행을 남긴다 — code만 있고 요청 ID 원문은 없다.
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        assertThat(jdbc().sql("SELECT details->>'code' FROM operations_audit_event WHERE operation_id = :o "
                + "AND outcome = 'DENIED'").param("o", operationId).query(String.class).single())
                .isEqualTo(OperatorCommandApi.DELETION_REQUEUE_UNAVAILABLE);
        assertNoIdentifiersInLedger(stopped);
        assertCode(prepare(operator, stopped.requestId(), "삭제 재시도", key("requeue-production-again")), 403,
                OperatorCommandApi.DELETION_REQUEUE_UNAVAILABLE);
        assertThat(this.runs.runOf(stopped.user()).orElseThrow().status()).isEqualTo(RunStatus.RETRY_NEEDED);
    }

    // ------------------------------------------------------------------------------------------ 목록 권한

    /** 목록 권한: audit 권한만 있는 운영자와 비운영자에게 목록·준비는 없는 경로와 같은 404다. */
    @Test
    void 목록과_준비는_command_권한이_없으면_없는_경로와_같은_404다() throws Exception {
        stoppedDeletion("requeue-permission");
        Signed auditor = operator("requeue-auditor", "audit");
        assertThat(deletionsGet(auditor).statusCode()).isEqualTo(404);
        assertThat(prepare(auditor, "del_" + "a".repeat(22), "삭제 재시도", key("requeue-auditor")).statusCode())
                .isEqualTo(404);
        Signed stranger = signedIn("requeue-stranger");
        var notFound = deletionsGet(stranger);
        var nope = get("/api/operator/nope", stranger.cookie());
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(nope.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(notFound.body())).containsEntry("code", topLevelFieldsOf(nope.body()).get("code"))
                .containsEntry("message", topLevelFieldsOf(nope.body()).get("message"));
    }

    // ------------------------------------------------------------------------------------------ 목록의 실행별 사실

    /**
     * 목록: 다른 단계에서 다른 시각에 멈춘 두 실행이 요청 시각 순으로 보이고, 요청 ID마다 자기 실패 단계를 가지며, 멈춘 시각은 그 실행의 재시도
     * 필요 단계 {@code finished_at} 최댓값이다.
     */
    @Test
    void 목록은_실행마다_자기_실패_단계와_멈춘_시각을_요청_시각_순으로_싣는다() throws Exception {
        Stopped first = stoppedDeletion("requeue-list-first", com.bokrilog.identity.DeletionStep.SYNC);
        Stopped second = stoppedDeletion("requeue-list-second", com.bokrilog.identity.DeletionStep.ENGAGEMENT);
        Signed operator = operator("requeue-list-op", "command");

        var listed = deletionsGet(operator);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        List<Map<String, Object>> items = itemsIn(listed.body(), "items");
        assertThat(items).extracting(item -> item.get("requestId"))
                .containsExactly(first.requestId(), second.requestId());
        assertThat(items.get(0).get("failedSteps"))
                .isEqualTo(List.of(Map.of("step", "SYNC", "failureCode", "STEP_FAILED", "attempts", 1)));
        assertThat(items.get(1).get("failedSteps"))
                .isEqualTo(List.of(Map.of("step", "ENGAGEMENT", "failureCode", "STEP_FAILED", "attempts", 1)));
        List<Stopped> stopped = List.of(first, second);
        for (int index = 0; index < stopped.size(); index++) {
            UUID user = stopped.get(index).user();
            assertThat(Instant.parse((String) items.get(index).get("stoppedAt")))
                    .isEqualTo(jdbc().sql("SELECT max(step.finished_at) FROM identity_deletion_step AS step "
                                    + "WHERE step.user_id = :u AND step.status = 'RETRY_NEEDED'")
                            .param("u", user).query(OffsetDateTime.class).single().toInstant());
            assertThat(Instant.parse((String) items.get(index).get("requestedAt")))
                    .isEqualTo(jdbc().sql("SELECT requested_at FROM identity_deletion_request WHERE request_id = :r")
                            .param("r", stopped.get(index).requestId()).query(OffsetDateTime.class).single()
                            .toInstant());
        }
        assertThat(Instant.parse((String) items.get(0).get("stoppedAt")))
                .isBefore(Instant.parse((String) items.get(1).get("stoppedAt")));
    }

    // ------------------------------------------------------------------------------------------ 사용자 재시도 키

    /**
     * 운영자 재대기열은 실행의 재시도 키 digest를 운영자 것으로 덮는다. 그래서 요청 세션이 남아 있는 사용자가 그 전에 쓴 재시도 키를 재전송하면
     * 같은 접수의 재생이 아니라 409 {@code ACCOUNT_DELETION_NOT_RETRYABLE}이다 — 실행은 이미 다시 대기열에 있으므로 수용한 계약이다(SPEC
     * Design Notes의 사용자 키 재전송).
     */
    @Test
    void 운영자_재대기열_뒤_사용자의_앞선_재시도_키_재전송은_409다() throws Exception {
        Stopped stopped = stoppedDeletion("requeue-user-key");
        String userKey = key("requeue-user-retry");
        String csrf = csrfTokenOf(stopped.cookie());
        var accepted = userRetry(stopped.cookie(), csrf, userKey);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        // 다시 돌았지만 같은 단계가 또 실패했다.
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(failingRunner(com.bokrilog.identity.DeletionStep.SYNC).execute(lease, () -> false))
                .isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);

        Signed operator = operator("requeue-user-key-op", "command");
        String key = key("requeue-user-key-op");
        var prepared = prepare(operator, stopped.requestId(), "삭제 재시도", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        var confirmed = confirmRestriction(operator, COMMAND, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);

        var replayed = userRetry(stopped.cookie(), csrf, userKey);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(replayed.body())).containsEntry("code",
                AccountDeletionRequestService.NOT_RETRYABLE);
        assertThat(this.runs.runOf(stopped.user()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
    }

    // ------------------------------------------------------------------------------------------ 도구

    /** 단계 하나(SYNC)가 실패해 재시도 필요로 멈춘 삭제 요청 계정. 요청 세션은 쓰지 않는다(잃은 것과 같다). */
    record Stopped(UUID user, String requestId, String cookie) {}

    private Stopped stoppedDeletion(String prefix) throws Exception {
        return stoppedDeletion(prefix, com.bokrilog.identity.DeletionStep.SYNC);
    }

    /** 그 단계(SYNC·ENGAGEMENT)가 실패해 멈춘 삭제 요청 계정. 요청 세션 쿠키도 돌려준다(사용자 재시도 시험만 쓴다). */
    private Stopped stoppedDeletion(String prefix, com.bokrilog.identity.DeletionStep failing) throws Exception {
        Signed signed = signedIn(prefix);
        UUID user = signed.user();
        String requestId = requestDeletion(user);
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(lease.userId()).isEqualTo(user);
        assertThat(failingRunner(failing).execute(lease, () -> false)).isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        var run = this.runs.runOf(user).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.RETRY_NEEDED);
        assertThat(run.steps()).extracting(StepState::failureCode).contains("STEP_FAILED");
        return new Stopped(user, requestId, signed.cookie());
    }

    /** 삭제 요청(Story 5.1의 요청 행과 삭제 진행 전환). 요청 ID를 돌려준다. */
    private String requestDeletion(UUID user) {
        var outcome = accountDeletion().requestDeletion(user, AccountDeletionPolicy.VERSION,
                AccountDeletionPolicy.noticeHash(), sha256("requeue-request-" + UUID.randomUUID()));
        assertThat(outcome).isInstanceOf(DeletionRequestOutcome.Created.class);
        return ((DeletionRequestOutcome.Created) outcome).request().requestId();
    }

    /** 그 단계(SYNC·ENGAGEMENT)의 삭제가 예외로 끝나는 실행자. 나머지 port는 실제 것이다. */
    private AccountErasureRun failingRunner(com.bokrilog.identity.DeletionStep step) {
        SyncJobApi syncs = step == com.bokrilog.identity.DeletionStep.SYNC ? failing(SyncJobApi.class, this.sync, "eraseSyncData") : this.sync;
        EngagementDeletionApi engagements = step == com.bokrilog.identity.DeletionStep.ENGAGEMENT
                ? failing(EngagementDeletionApi.class, this.engagement, "eraseConsents") : this.engagement;
        return new AccountErasureRun(new AccountErasure(accountDeletion(), this.tombstones, this.portfolio, syncs,
                this.plans, this.source, this.operations, engagements, this.manager), this.runs, this.manager);
    }

    /** 그 메서드만 예외로 끝나고 나머지는 실제 port에 넘기는 대리. */
    private static <T> T failing(Class<T> type, T delegate, String method) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, called, args) -> {
                    if (called.getName().equals(method)) {
                        throw new IllegalStateException("injected");
                    }
                    return invoke(called, delegate, args);
                }));
    }

    /** 요청 세션의 다시 시도(Story 5.2). */
    private HttpResponse<String> userRetry(String cookie, @Nullable String csrf, String key) throws Exception {
        return send("POST", AccountDeletionController.RETRY_PATH, cookie, headers(csrf, key));
    }

    private static @Nullable Object invoke(java.lang.reflect.Method method, Object delegate, @Nullable Object[] args)
            throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private HttpResponse<String> prepare(Signed signed, String target, @Nullable String reason, String key)
            throws Exception {
        return prepareRestriction(signed, COMMAND, null, target, reason, key);
    }

    private HttpResponse<String> deletionsGet(Signed signed) throws Exception {
        return get(OperatorController.DELETIONS_PATH, signed.cookie());
    }

    private void assertNotRetryable(Signed operator, Stopped stopped, RunStatus expected) throws Exception {
        assertThat(this.runs.runOf(stopped.user()).orElseThrow().status()).isEqualTo(expected);
        assertCode(prepare(operator, stopped.requestId(), "삭제 재시도", key("requeue-state")), 409,
                OperatorCommandApi.DELETION_NOT_RETRYABLE);
        assertThat(this.runs.runOf(stopped.user()).orElseThrow().status()).isEqualTo(expected);
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(topLevelFieldsOf(response.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", code);
    }

    /** 원장 행 전체(모든 열의 JSON)에 그 문자열이 나오는 행 수. */
    private int ledgerMentions(String text) {
        return jdbc().sql("SELECT count(*) FROM operations_audit_event AS e WHERE strpos(CAST(row_to_json(e) AS text), "
                + ":t) > 0").param("t", text).query(Integer.class).single();
    }

    private void assertNoIdentifiersInLedger(Stopped stopped) {
        assertThat(ledgerMentions(stopped.requestId())).as("요청 ID 원문").isZero();
        assertThat(ledgerMentions(stopped.user().toString())).as("삭제 계정의 user_id").isZero();
    }

    private String lifecycleOf(UUID user) {
        return jdbc().sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :u").param("u", user)
                .query(String.class).single();
    }
}
