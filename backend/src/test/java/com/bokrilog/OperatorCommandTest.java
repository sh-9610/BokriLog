package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 3.1 AC-2·AC-3·AC-6: 운영 command의 준비 → 확정 → 취소, 확인 문구, 멱등, 권한 회수 경합, 동시 확정.
 *
 * <p>{@code audit-export}가 3.1의 유일한 command다. 실제 HTTP·실제 세션·실제 허가 저장소를 지난다.
 */
// 잠금은 본문이 이름으로 부르지 않는 자원이 쥔다. 쥐고 있는 것 자체가 요점이다.
@SuppressWarnings("try")
class OperatorCommandTest extends OperatorTestBase {

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 준비와_확정이_같은_operation의_요청과_완료를_남기고_세션을_회전한다(CapturedOutput output) throws Exception {
        Signed operator = operator("export-ok", "command,audit");
        String target = recentTarget();
        String key = key("export-ok");

        var prepared = prepareExport(operator, target, "분기 감사 점검", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(prepared.body())).containsOnlyKeys("operationId", "command", "scope", "target",
                        "reason", "confirmationPhrase", "policyVersion", "requestedAt")
                .containsEntry("confirmationPhrase", phraseOf(target))
                .containsEntry("policyVersion", "OPERATOR-COMMAND-1.0");
        String operationId = operationIdIn(prepared.body());
        assertThat(operationId).startsWith("op_");
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED");
        String grant = operatorGrantOf(operator.user());
        assertThat(grant).isNotNull();
        assertThat(prepared.body()).doesNotContain(grant);
        assertThat(actionGrantConsumed(grant)).isFalse();

        var confirmed = confirmExport(operator, operationId, phraseOf(target), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(confirmed.body())).containsOnlyKeys("operationId", "outcome", "command", "target",
                "upperSequence", "rowCount", "truncated", "rows").containsEntry("outcome", "COMPLETED");
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");
        assertThat(actionGrantConsumed(grant)).isTrue();

        // 행 묶음은 allowlist 필드뿐이고 이 operation의 요청 행을 담는다(완료 행은 상한 뒤라 없다).
        var rows = itemsIn(confirmed.body(), "rows");
        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst()).containsOnlyKeys("sequence", "operationId", "actorKind", "actorDigest",
                "command", "scope", "target", "reason", "outcome", "policyVersion", "requestedAt", "recordedAt",
                "correlationId");
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("operationId", operationId)
                .containsEntry("outcome", "REQUESTED"));
        assertThat(confirmed.body()).doesNotContain(operator.user().toString(), operator.subject(), key);

        // 세션 회전 + 재인증 시각 제거(본인 확인 1회는 command 1건).
        String rotated = sessionCookieOf(confirmed).orElseThrow();
        assertThat(rotated).isNotEqualTo(operator.cookie());
        assertThat(sessionAttribute(sessionIdOf(rotated), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE)).isNull();
        assertThat(sessionAttribute(sessionIdOf(rotated), OperatorCommandFlow.FLOW_ATTRIBUTE)).isNull();

        // 같은 세션으로 다음 command를 준비하려면 다시 본인 확인이 필요하다.
        Signed after = after(operator, confirmed);
        var next = prepareExport(after, target, "두 번째 추출", key("export-ok-next"));
        assertThat(next.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(next.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");

        // 원장 행에 원식별자·키·문구가 없다. 로그에도 없다.
        String ledger = String.join("\n", jdbc().sql("SELECT row_to_json(e)::text FROM operations_audit_event e")
                .query(String.class).list());
        assertThat(ledger).doesNotContain(operator.subject(), key, phraseOf(target), grant);
        assertThat(output.getAll()).doesNotContain(operator.subject(), key, grant, operationId, "분기 감사 점검");
    }

    @Test
    void 같은_키의_재전송은_같은_묶음이고_회전하지_않으며_다른_operation은_충돌이다() throws Exception {
        Signed operator = operator("export-replay", "command,audit");
        String target = recentTarget();
        String key = key("export-replay");
        var first = export(operator, target, key);
        String operationId = operationIdIn(first.body());
        Signed after = after(operator, first);

        var replayed = confirmExport(after, operationId, phraseOf(target), key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body())).isEqualTo(topLevelFieldsOf(first.body()));
        assertThat(sessionCookieOf(replayed)).as("재전송은 회전하지 않는다").isEmpty();
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");

        var conflict = confirmExport(after, operationId, phraseOf(target), key("export-replay-other"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(conflict.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");

        // 끝난 operation의 키로 다시 준비하는 것도 충돌이다.
        Signed fresh = reauthenticated(after);
        var reused = prepareExport(fresh, target, "정기 점검 추출", key);
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(reused.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void 같은_키_다른_입력의_준비는_충돌이고_같은_입력은_같은_operation이다() throws Exception {
        Signed operator = operator("export-key", "command,audit");
        String key = key("export-key");
        var first = prepareExport(operator, recentTarget(), "첫 사유", key);
        assertThat(first.statusCode()).isEqualTo(200);
        var again = prepareExport(operator, recentTarget(), "첫 사유", key);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(operationIdIn(again.body())).isEqualTo(operationIdIn(first.body()));
        assertThat(outcomesOf(operationIdIn(first.body()))).containsExactly("REQUESTED");

        var other = prepareExport(operator, recentTarget(), "다른 사유", key);
        assertThat(other.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(other.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");
        assertThat(decisionsOf(operator.user())).contains("DENIED:IDEMPOTENCY_CONFLICT");
    }

    @Test
    void envelope_검증은_빈_사유_미지원_scope_대상_수행자_주장_키를_거절한다() throws Exception {
        Signed operator = operator("export-envelope", "command,audit");
        String target = recentTarget();
        assertRefused(prepareExport(operator, exportBody("AUDIT_LEDGER", target, "   "), key("e1")), 422,
                "REASON_REQUIRED");
        assertRefused(prepareExport(operator, exportBody("AUDIT_LEDGER", target, null), key("e2")), 422,
                "REASON_REQUIRED");
        assertRefused(prepareExport(operator, exportBody("AUDIT_LEDGER", target, "문의 ops@example.com"), key("e3")),
                422, "REASON_INVALID");
        assertRefused(prepareExport(operator, exportBody("GLOBAL", target, "사유"), key("e4")), 422,
                "UNSUPPORTED_SCOPE");
        assertRefused(prepareExport(operator, exportBody(null, target, "사유"), key("e5")), 422, "UNSUPPORTED_SCOPE");
        assertRefused(prepareExport(operator, exportBody("AUDIT_LEDGER", "2026-10-07~2026-10-01", "사유"), key("e6")),
                422, "TARGET_INVALID");
        assertRefused(prepareExport(operator, exportBody("AUDIT_LEDGER", "2024-01-01~2026-01-01", "사유"), key("e7")),
                422, "TARGET_INVALID");
        assertRefused(prepareExport(operator, "{\"scope\":\"AUDIT_LEDGER\",\"target\":\"" + target
                + "\",\"reason\":\"사유\",\"actorUserId\":\"" + operator.user() + "\"}", key("e8")), 422,
                "ACTOR_NOT_ACCEPTED");
        assertRefused(prepareExport(operator, exportBody("AUDIT_LEDGER", target, "사유"), null), 422,
                "IDEMPOTENCY_KEY_REQUIRED");
        assertRefused(prepareExport(operator, exportBody("AUDIT_LEDGER", target, "사유"), "short"), 422,
                "IDEMPOTENCY_KEY_REQUIRED");
        assertRefused(prepareExport(operator, "[1]", key("e9")), 400, "BAD_REQUEST");
        assertRefused(prepareExport(operator, "{\"reason\":3}", key("e10")), 400, "BAD_REQUEST");
        assertThat(actionGrantCountOf(operator.user())).isZero();
        // 거절된 시도도 감사 사건이다. 이메일이 실린 사유는 원문이 남지 않는다.
        assertThat(decisionsOf(operator.user())).contains("DENIED:REASON_REQUIRED", "DENIED:REASON_INVALID",
                "DENIED:UNSUPPORTED_SCOPE", "DENIED:TARGET_INVALID", "DENIED:ACTOR_NOT_ACCEPTED");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE reason LIKE '%@%'")
                .query(Long.class).single()).isZero();
        assertThat(outcomesInLedger()).doesNotContain("REQUESTED");
    }

    @Test
    void 재인증이_없거나_오래되면_허가가_서지_않고_거절이_남는다() throws Exception {
        Signed bare = signedIn("export-no-reauth");
        operatorAccess().reinterpret(entry(bare.subject(), "command,audit"));
        assertRefused(prepareExport(bare, recentTarget(), "사유", key("no-reauth")), 403, "REAUTHENTICATION_REQUIRED");
        Signed stale = reauthenticated(bare);
        ageReauthentication(sessionIdOf(stale.cookie()), Duration.ofMinutes(6));
        assertRefused(prepareExport(stale, recentTarget(), "사유", key("stale-reauth")), 403,
                "REAUTHENTICATION_REQUIRED");
        assertThat(actionGrantCountOf(bare.user())).isZero();
        assertThat(decisionsOf(bare.user())).containsExactly("DENIED:REAUTHENTICATION_REQUIRED",
                "DENIED:REAUTHENTICATION_REQUIRED");
    }

    @Test
    void 문구가_다르면_허가를_소비하지_않고_거절이_남으며_고쳐_다시_확정할_수_있다() throws Exception {
        Signed operator = operator("export-phrase", "command,audit");
        String target = recentTarget();
        String key = key("export-phrase");
        var prepared = prepareExport(operator, target, "사유", key);
        String operationId = operationIdIn(prepared.body());
        String grant = operatorGrantOf(operator.user());

        var mismatch = confirmExport(operator, operationId, "감사 내보내기 AUDIT_LEDGER 다른기간", key);
        assertRefused(mismatch, 409, "CONFIRMATION_MISMATCH");
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        assertThat(sessionCookieOf(mismatch)).isEmpty();

        var confirmed = confirmExport(operator, operationId, phraseOf(target), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED", "COMPLETED");
    }

    @Test
    void 무장_없음_다른_operation_다른_키_만료는_하나의_거절이고_아무것도_바꾸지_않는다() throws Exception {
        Signed operator = operator("export-misuse", "command,audit");
        String target = recentTarget();
        String key = key("export-misuse");
        assertRefused(confirmExport(operator, "op_nothingarmed", phraseOf(target), key), 403,
                "OPERATOR_COMMAND_NOT_CONFIRMED");
        var prepared = prepareExport(operator, target, "사유", key);
        String operationId = operationIdIn(prepared.body());
        assertRefused(confirmExport(operator, "op_another", phraseOf(target), key), 403,
                "OPERATOR_COMMAND_NOT_CONFIRMED");
        assertRefused(confirmExport(operator, operationId, phraseOf(target), key("export-misuse-other")), 403,
                "OPERATOR_COMMAND_NOT_CONFIRMED");
        moveActionGrantExpiry(operatorGrantOf(operator.user()), Duration.ofSeconds(-1));
        assertRefused(confirmExport(operator, operationId, phraseOf(target), key), 403,
                "OPERATOR_COMMAND_NOT_CONFIRMED");
        assertThat(outcomesOf(operationId)).doesNotContain("COMPLETED");
    }

    @Test
    void 취소는_무장과_허가를_버리고_취소를_남기며_무장이_없어도_204다() throws Exception {
        Signed operator = operator("export-cancel", "command,audit");
        String target = recentTarget();
        String key = key("export-cancel");
        var prepared = prepareExport(operator, target, "사유", key);
        String operationId = operationIdIn(prepared.body());
        String grant = operatorGrantOf(operator.user());

        var cancelled = cancelExport(operator);
        assertThat(cancelled.statusCode()).isEqualTo(204);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "CANCELLED");
        assertThat(actionGrantExists(grant)).isFalse();
        assertThat(sessionAttribute(sessionIdOf(operator.cookie()), OperatorCommandFlow.FLOW_ATTRIBUTE)).isNull();
        assertRefused(confirmExport(operator, operationId, phraseOf(target), key), 403,
                "OPERATOR_COMMAND_NOT_CONFIRMED");

        assertThat(cancelExport(operator).statusCode()).isEqualTo(204);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "CANCELLED");
    }

    /**
     * AC-6: 준비 뒤 확정 트랜잭션 안에서 allowlist가 회수되면 — 소비 UPDATE 뒤, 원장 잠금을 기다리는 사이 — 재검사가
     * 거절하고 소비·완료가 함께 롤백되며 거절 행만 남는다. 상태 변경은 없다.
     */
    @Test
    void 확정_트랜잭션_안의_회수는_거절이고_DENIED만_남는다() throws Exception {
        Signed operator = operator("export-revoked", "command,audit");
        String target = recentTarget();
        String key = key("export-revoked");
        var prepared = prepareExport(operator, target, "사유", key);
        String operationId = operationIdIn(prepared.body());
        String grant = operatorGrantOf(operator.user());

        CompletableFuture<HttpResponse<String>> confirming;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (AutoCloseable held = holdAuditLock()) {
                confirming = CompletableFuture.supplyAsync(() -> call(() -> confirmExport(operator, operationId,
                        phraseOf(target), key)), executor);
                awaitAdvisoryWaiters(1);
                operatorAccess().reinterpret("");
            }
            var revoked = confirming.get();
            assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(404);
        }
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        assertThat(decisionsOf(operator.user())).last().isEqualTo("DENIED:ACCESS_REVOKED");
        // 소비가 롤백됐고 무장과 함께 허가도 무효화됐다.
        assertThat(actionGrantExists(grant)).isFalse();
    }

    /**
     * I/O 표 "회수 경합": 준비 뒤·확정 요청 전에 allowlist에서 빠지면 게이트가 같은 404로 끊되, 그 사용자의 무장을
     * 버리고 허가를 무효화하며 {@code DENIED}로 operation을 맺는다.
     */
    @Test
    void 준비_뒤_확정_전의_회수는_404이고_무장과_허가를_정리하며_DENIED로_맺는다() throws Exception {
        Signed operator = operator("export-revoked-before", "command,audit");
        String target = recentTarget();
        String key = key("export-revoked-before");
        String operationId = operationIdIn(prepareExport(operator, target, "사유", key).body());
        String grant = operatorGrantOf(operator.user());
        var unknown = get("/api/nope", operator.cookie());

        operatorAccess().reinterpret("");
        var revoked = confirmExport(operator, operationId, phraseOf(target), key);
        assertThat(revoked.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(revoked.body()).get("code")).isEqualTo(topLevelFieldsOf(unknown.body()).get("code"));
        assertThat(topLevelFieldsOf(revoked.body()).get("message"))
                .isEqualTo(topLevelFieldsOf(unknown.body()).get("message"));
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        assertThat(decisionsOf(operator.user())).last().isEqualTo("DENIED:ACCESS_REVOKED");
        assertThat(actionGrantExists(grant)).isFalse();
        assertThat(sessionAttribute(sessionIdOf(operator.cookie()), OperatorCommandFlow.FLOW_ATTRIBUTE)).isNull();

        // 무장이 사라졌으니 다시 끊겨도 행을 더 쓰지 않는다.
        assertThat(cancelExport(operator).statusCode()).isEqualTo(404);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
    }

    @Test
    void 일반_사용자의_확정_요청은_감사_행을_남기지_않는다() throws Exception {
        Signed user = reauthenticated(signedIn("export-plain-user"));
        assertThat(confirmExport(user, "op_whatever", phraseOf(recentTarget()), key("plain-user")).statusCode())
                .isEqualTo(404);
        assertThat(cancelExport(user).statusCode()).isEqualTo(404);
        assertThat(auditCount()).isZero();
    }

    /** AC-2·AC-6: 같은 무장·같은 키의 두 탭이 동시에 확정해도 한 번만 실행되고 둘 다 같은 묶음을 받는다. */
    @Test
    void 같은_키의_동시_확정은_한_번만_실행된다() throws Exception {
        Signed operator = operator("export-concurrent", "command,audit");
        String target = recentTarget();
        String key = key("export-concurrent");
        String operationId = operationIdIn(prepareExport(operator, target, "사유", key).body());

        HttpResponse<String> a;
        HttpResponse<String> b;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<HttpResponse<String>> first;
            CompletableFuture<HttpResponse<String>> second;
            try (AutoCloseable held = holdAuditLock()) {
                first = CompletableFuture.supplyAsync(() -> call(() -> confirmExport(operator, operationId,
                        phraseOf(target), key)), executor);
                awaitAdvisoryWaiters(1);
                second = CompletableFuture.supplyAsync(() -> call(() -> confirmExport(operator, operationId,
                        phraseOf(target), key)), executor);
                awaitRowLockWaiter();
            }
            a = first.get();
            b = second.get();
        }
        assertThat(List.of(a.statusCode(), b.statusCode())).as(a.body() + " / " + b.body()).containsExactly(200, 200);
        assertThat(topLevelFieldsOf(b.body())).isEqualTo(topLevelFieldsOf(a.body()));
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");
    }

    /** 감사 권한만 회수되면 게이트는 지나지만 확정이 404로 끊기고, 같은 규칙으로 무장을 맺는다. */
    @Test
    void 감사_권한만_회수된_확정도_DENIED로_맺고_무장과_허가를_정리한다() throws Exception {
        Signed operator = operator("export-audit-revoked", "command,audit");
        String target = recentTarget();
        String key = key("export-audit-revoked");
        String operationId = operationIdIn(prepareExport(operator, target, "사유", key).body());
        String grant = operatorGrantOf(operator.user());

        operatorAccess().reinterpret(entry(operator.subject(), "command"));
        assertThat(confirmExport(operator, operationId, phraseOf(target), key).statusCode()).isEqualTo(404);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        assertThat(decisionsOf(operator.user())).last().isEqualTo("DENIED:ACCESS_REVOKED");
        assertThat(actionGrantExists(grant)).isFalse();
        assertThat(sessionAttribute(sessionIdOf(operator.cookie()), OperatorCommandFlow.FLOW_ATTRIBUTE)).isNull();
    }

    /** 모르는 type의 취소는 경로가 없는 것과 같고 무장을 건드리지 않는다. */
    @Test
    void 모르는_type의_취소는_404이고_무장을_남긴다() throws Exception {
        Signed operator = operator("export-cancel-type", "command,audit");
        String target = recentTarget();
        String key = key("export-cancel-type");
        String operationId = operationIdIn(prepareExport(operator, target, "사유", key).body());
        String grant = operatorGrantOf(operator.user());

        var unknown = send("POST", OperatorGate.COMMANDS_PATH + "/unknown-type/cancel", operator.cookie(),
                headers(operator.csrf(), null));
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED");
        assertThat(actionGrantExists(grant)).isTrue();
        assertThat(confirmExport(operator, operationId, phraseOf(target), key).statusCode()).isEqualTo(200);
    }

    /** 권한 회수로 맺은 operation은 끝난 것이다 — 같은 키로 다시 준비하면 충돌이고 완료가 뒤에 붙지 않는다. */
    @Test
    void 회수로_맺은_operation의_키는_다시_준비할_수_없다() throws Exception {
        Signed operator = operator("export-revoked-key", "command,audit");
        String target = recentTarget();
        String key = key("export-revoked-key");
        String operationId = operationIdIn(prepareExport(operator, target, "사유", key).body());
        operatorAccess().reinterpret("");
        assertThat(confirmExport(operator, operationId, phraseOf(target), key).statusCode()).isEqualTo(404);

        operatorAccess().reinterpret(entry(operator.subject(), "command,audit"));
        assertRefused(prepareExport(operator, target, "사유", key), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        assertThat(actionGrantCountOf(operator.user())).isZero();
    }

    /**
     * 같은 새 키의 동시 준비 둘은 operation 하나를 만든다(키 판정과 요청 행이 원장 잠금 안의 한 트랜잭션이다). 두 요청은
     * 같은 운영자의 두 세션에서 온다 — 멱등은 수행자 단위이고, 한 세션의 동시 속성 쓰기는 세션 저장소의 경합이라 이
     * 시험이 보려는 것이 아니다.
     */
    @Test
    void 같은_새_키의_동시_준비는_operation_하나다() throws Exception {
        Signed operator = operator("export-concurrent-prepare", "command,audit");
        String otherCookie = sessionCookieOf(signIn(operator.subject())).orElseThrow();
        String otherCsrf = csrfTokenOf(otherCookie);
        Signed other = reauthenticated(new Signed(operator.user(), operator.subject(), otherCookie,
                otherCsrf == null ? "" : otherCsrf));
        String target = recentTarget();
        String key = key("export-concurrent-prepare");
        HttpResponse<String> a;
        HttpResponse<String> b;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<HttpResponse<String>> first;
            CompletableFuture<HttpResponse<String>> second;
            try (AutoCloseable held = holdAuditLock()) {
                first = CompletableFuture.supplyAsync(() -> call(() -> prepareExport(operator, target, "사유", key)),
                        executor);
                second = CompletableFuture.supplyAsync(() -> call(() -> prepareExport(other, target, "사유", key)),
                        executor);
                awaitAdvisoryWaiters(2);
            }
            a = first.get();
            b = second.get();
        }
        assertThat(List.of(a.statusCode(), b.statusCode())).as(a.body() + " / " + b.body()).containsExactly(200, 200);
        assertThat(operationIdIn(b.body())).isEqualTo(operationIdIn(a.body()));
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE outcome = 'REQUESTED'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    /** 실행이 예외로 끝나면 500이고 소비까지 롤백되며 실패 행이 남는다. */
    @Test
    void 실행_예외는_500이고_허가를_소비하지_않으며_FAILED가_남는다() throws Exception {
        Signed operator = operator("export-failure", "command,audit");
        String target = recentTarget();
        String key = key("export-failure");
        String operationId = operationIdIn(prepareExport(operator, target, "사유", key).body());
        String grant = operatorGrantOf(operator.user());
        jdbc().sql("CREATE FUNCTION test_block_completed_audit() RETURNS trigger LANGUAGE plpgsql AS "
                + "'BEGIN IF NEW.outcome = ''COMPLETED'' THEN RAISE EXCEPTION ''blocked''; END IF; RETURN NEW; END'")
                .update();
        try {
            jdbc().sql("CREATE TRIGGER test_block_completed_audit BEFORE INSERT ON operations_audit_event "
                    + "FOR EACH ROW EXECUTE FUNCTION test_block_completed_audit()").update();
            var failed = confirmExport(operator, operationId, phraseOf(target), key);
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(topLevelFieldsOf(failed.body())).containsEntry("code", "INTERNAL_ERROR");
        } finally {
            jdbc().sql("DROP TRIGGER IF EXISTS test_block_completed_audit ON operations_audit_event").update();
            jdbc().sql("DROP FUNCTION IF EXISTS test_block_completed_audit()").update();
        }
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "FAILED");
        assertThat(decisionsOf(operator.user())).last().isEqualTo("FAILED:EXECUTION_FAILED");
    }

    /** 같은 세션의 다른 키 새 준비는 앞선 operation을 대체로 맺고 앞선 허가를 무효화한다. */
    @Test
    void 새_준비는_앞선_operation을_대체로_맺는다() throws Exception {
        Signed operator = operator("export-superseded", "command,audit");
        String target = recentTarget();
        String first = operationIdIn(prepareExport(operator, target, "첫 사유", key("superseded-1")).body());
        String firstGrant = operatorGrantOf(operator.user());
        String second = operationIdIn(prepareExport(operator, target, "둘째 사유", key("superseded-2")).body());
        assertThat(second).isNotEqualTo(first);
        assertThat(outcomesOf(first)).containsExactly("REQUESTED", "CANCELLED");
        assertThat(jdbc().sql("SELECT details->>'code' FROM operations_audit_event WHERE operation_id = :o "
                + "AND outcome = 'CANCELLED'").param("o", first).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThat(actionGrantExists(firstGrant)).isFalse();
        assertThat(outcomesOf(second)).containsExactly("REQUESTED");
    }

    /** 회수 직후 첫 요청이 취소여도 operation은 거절로 맺고 허가는 사라진다. */
    @Test
    void 회수_직후의_취소도_DENIED로_맺는다() throws Exception {
        Signed operator = operator("export-revoked-cancel", "command,audit");
        String operationId = operationIdIn(
                prepareExport(operator, recentTarget(), "사유", key("revoked-cancel")).body());
        String grant = operatorGrantOf(operator.user());
        operatorAccess().reinterpret("");
        assertThat(cancelExport(operator).statusCode()).isEqualTo(404);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "DENIED");
        assertThat(actionGrantExists(grant)).isFalse();
    }

    private List<String> outcomesInLedger() {
        return jdbc().sql("SELECT outcome FROM operations_audit_event").query(String.class).list();
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(topLevelFieldsOf(response.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", code);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @FunctionalInterface
    private interface Call {
        HttpResponse<String> run() throws Exception;
    }

    private static HttpResponse<String> call(Call call) {
        try {
            return call.run();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
