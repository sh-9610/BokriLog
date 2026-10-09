package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.2 AC-2·AC-3·AC-5·AC-6: 제한 생성 command의 준비·확정·재전송, 확정 순간 경합, 원장의 digest, 활성 목록, 삭제 port.
 *
 * <p>3.1의 envelope·재인증·허가·세션 회전을 그대로 지난다. 제한 행과 {@code COMPLETED}는 한 트랜잭션이고, 거절은 롤백 뒤 따로
 * 쓴 {@code DENIED} 하나다. 사용자에게 귀속된 target(계획·portfolio·연결)은 원장에 64자 keyed digest로만 남는다.
 */
class OperatorRestrictionTest extends OperatorTestBase {

    private static final String RESTRICT_SYNC = OperatorCommandApi.RESTRICT_SYNC;

    private static final String RESTRICT_ADVERTISING = OperatorCommandApi.RESTRICT_ADVERTISING;

    private static final String RESTRICT_DISPLAY = OperatorCommandApi.RESTRICT_CALCULATION_DISPLAY;

    @Autowired
    private OperationalRestrictionApi restrictions;

    @Autowired
    private OperationsDeletionApi deletion;

    /** 계획 하나를 가진 사용자. 운영자와 다른 사람이다. */
    private record Owned(UUID user, UUID planId, UUID portfolioId, UUID connectionId) {}

    private Owned owned(String prefix) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, prefix + "-" + UUID.randomUUID(), null);
        var result = ready(user);
        UUID portfolio = jdbc().sql("SELECT portfolio_id FROM plan_definition WHERE plan_id = :p")
                .param("p", result.planId()).query(UUID.class).single();
        UUID connection = jdbc().sql("SELECT connection_id FROM source_connection WHERE user_id = :u AND status = 'ACTIVE'")
                .param("u", user).query(UUID.class).single();
        return new Owned(user, result.planId(), portfolio, connection);
    }

    @Test
    void 계획_범위_제한은_미리보기를_싣고_확정이_제한_행과_완료를_한_번에_쓴다() throws Exception {
        Owned owner = owned("restrict-plan");
        Signed operator = operator("restrict-plan-operator", "command");
        String key = key("restrict-plan");
        String target = owner.planId().toString();

        var prepared = prepareRestriction(operator, RESTRICT_SYNC, "PLAN", target, "계획 입력 이상", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        Map<String, Object> fields = topLevelFieldsOf(prepared.body());
        assertThat(fields).containsEntry("command", RESTRICT_SYNC).containsEntry("scope", "SYNC:PLAN")
                .containsEntry("target", target).containsEntry("confirmationPhrase", "동기화 제한 계획 " + target);
        @SuppressWarnings("unchecked")
        Map<String, Object> impact = (Map<String, Object>) fields.get("impact");
        assertThat(impact).containsEntry("affectedPlans", 1).containsEntry("affectedConnections", 1);
        assertThat(impact.get("blockedStages"))
                .isEqualTo(List.of("INCREMENTAL_SYNC", "INCREMENTAL_PUBLICATION", "PLAN_END"));
        assertThat(impact.get("remainingFeatures"))
                .isEqualTo(List.of("NEW_CONNECTION", "SYNC", "CALCULATION_DISPLAY", "ADVERTISING"));
        // 미리보기에는 수만 있다 — 식별자가 없다.
        assertThat(impact.toString()).doesNotContain(owner.user().toString()).doesNotContain(target);
        String operationId = operationIdIn(prepared.body());
        assertThat(restrictionCount()).isZero();

        // 원장: scope는 `<기능>:<scope>`, target은 64자 digest다. 원식별자가 어디에도 없다.
        var requested = ledgerRow(operationId, "REQUESTED");
        assertThat(requested.get("scope")).isEqualTo("SYNC:PLAN");
        assertThat((String) requested.get("target")).matches("[0-9a-f]{64}");
        assertLedgerHasNo(owner.planId());

        var confirmed = confirmRestriction(operator, RESTRICT_SYNC, operationId, "동기화 제한 계획 " + target, key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        Map<String, Object> done = topLevelFieldsOf(confirmed.body());
        assertThat(done).containsEntry("operationId", operationId).containsEntry("outcome", "COMPLETED")
                .containsEntry("feature", "SYNC").containsEntry("scope", "PLAN").doesNotContainKey("target");
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");
        assertThat(sessionCookieOf(confirmed)).as("세션이 회전한다").isPresent();
        assertThat(jdbc().sql("SELECT feature || '|' || scope || '|' || target || '|' || target_user_id || '|' "
                        + "|| operation_id FROM operations_restriction").query(String.class).single())
                .isEqualTo("SYNC|PLAN|" + target + "|" + owner.user() + "|" + operationId);
        assertThat(ledgerRow(operationId, "COMPLETED").get("target")).isEqualTo(requested.get("target"));
        assertLedgerHasNo(owner.planId());

        // 효력: 그 계획의 동기화만 막힌다.
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withPlan(owner.planId()))).isTrue();
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withPlan(UUID.randomUUID()))).isFalse();

        // 같은 키의 재전송: 저장된 결과, 회전 없음, 제한은 한 행.
        Signed rotated = after(operator, confirmed);
        var replayed = confirmRestriction(rotated, RESTRICT_SYNC, operationId, "동기화 제한 계획 " + target, key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body())).containsEntry("restrictionId", done.get("restrictionId"))
                .containsEntry("effectiveFrom", done.get("effectiveFrom"));
        assertThat(sessionCookieOf(replayed)).as("재전송은 회전하지 않는다").isEmpty();
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");
    }

    @Test
    void 전역_광고_제한은_대상명_전역을_다시_입력해야_하고_제한_행과_감사만_남긴다() throws Exception {
        Signed operator = operator("restrict-ads", "command");
        String key = key("restrict-ads");
        var prepared = prepareRestriction(operator, RESTRICT_ADVERTISING, "GLOBAL", "ALL", "광고 SDK 오류", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(phraseIn(prepared.body())).isEqualTo("광고 제한 전역");
        String operationId = operationIdIn(prepared.body());

        var mismatch = confirmRestriction(operator, RESTRICT_ADVERTISING, operationId, "광고 제한", key);
        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(mismatch.body())).containsEntry("code", "CONFIRMATION_MISMATCH");
        assertThat(restrictionCount()).isZero();

        var confirmed = confirmRestriction(operator, RESTRICT_ADVERTISING, operationId, "광고 제한 전역", key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(jdbc().sql("SELECT feature || '|' || scope || '|' || target || '|' || (target_user_id IS NULL) "
                + "FROM operations_restriction").query(String.class).single()).isEqualTo("ADVERTISING|GLOBAL|ALL|true");
        assertThat(ledgerRow(operationId, "COMPLETED")).containsEntry("scope", "ADVERTISING:GLOBAL")
                .containsEntry("target", "ALL");
        // 다른 기능은 막히지 않는다.
        for (RestrictionFeature other : EnumSet.complementOf(EnumSet.of(RestrictionFeature.ADVERTISING))) {
            assertThat(this.restrictions.blocks(EnumSet.of(other), RestrictionSubject.none())).as(other.name())
                    .isFalse();
        }
    }

    @Test
    void 확정_순간_대상이_사라졌거나_같은_제한이_생겼으면_거절하고_제한을_만들지_않는다() throws Exception {
        // 대상이 사라졌다: 준비 뒤 계획이 끝났다(동기화 제한은 활성 계획만 받는다).
        Owned ended = owned("restrict-race-ended");
        Signed operator = operator("restrict-race", "command");
        String key = key("restrict-race-ended");
        var prepared = prepareRestriction(operator, RESTRICT_SYNC, "PLAN", ended.planId().toString(), "사유", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        jdbc().sql("UPDATE plan_definition SET status = 'COMPLETED' WHERE plan_id = :p").param("p", ended.planId())
                .update();
        String operationId = operationIdIn(prepared.body());
        var refused = confirmRestriction(operator, RESTRICT_SYNC, operationId, phraseIn(prepared.body()), key);
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "TARGET_INVALID");
        assertThat(restrictionCount()).isZero();
        assertThat(decisionsOf(operator.user())).containsExactly("REQUESTED", "DENIED:TARGET_INVALID");
        jdbc().sql("UPDATE plan_definition SET status = 'ACTIVE' WHERE plan_id = :p").param("p", ended.planId())
                .update();

        // 같은 제한이 그 사이 생겼다: 원장 잠금 뒤의 중복 판정이 거절한다.
        Signed again = reauthenticated(operator);
        Owned other = owned("restrict-race-duplicate");
        String duplicateKey = key("restrict-race-duplicate");
        var second = prepareRestriction(again, RESTRICT_SYNC, "PORTFOLIO", other.portfolioId().toString(), "사유",
                duplicateKey);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        UUID raced = restrict(RestrictionFeature.SYNC, RestrictionScope.PORTFOLIO, other.portfolioId().toString());
        var duplicate = confirmRestriction(again, RESTRICT_SYNC, operationIdIn(second.body()), phraseIn(second.body()),
                duplicateKey);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(duplicate.body())).containsEntry("code", "ALREADY_RESTRICTED");
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(outcomesOf(operationIdIn(second.body()))).containsExactly("REQUESTED", "DENIED");
        lift(raced);
    }

    @Test
    void 활성_목록은_command_권한만_구체_우선으로_읽는다() throws Exception {
        Owned owner = owned("restrict-list");
        restrictGlobally(RestrictionFeature.NEW_CONNECTION);
        restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.CALCULATION_POLICY_VERSION, "CALC-1.0");
        Signed operator = operator("restrict-list-operator", "command");
        Signed rotated = restrictVia(operator, RESTRICT_DISPLAY, "PLAN", owner.planId().toString(),
                key("restrict-list")).signed();

        var listed = restrictionsGet(rotated);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var items = itemsIn(listed.body(), "items");
        assertThat(items).extracting(item -> item.get("scope"))
                .containsExactly("PLAN", "CALCULATION_POLICY_VERSION", "GLOBAL");
        assertThat(items.get(0)).containsEntry("feature", "CALCULATION_DISPLAY")
                .containsEntry("target", owner.planId().toString()).containsEntry("targetErased", false)
                .containsEntry("reason", "장애 범위 제한");
        assertThat((String) items.get(0).get("actorDigest")).matches("[0-9a-f]{64}");
        assertThat(items.get(2)).containsEntry("target", "ALL").doesNotContainKey("reason");

        Signed auditOnly = signedIn("restrict-list-audit");
        operatorAccess().reinterpret(entry(auditOnly.subject(), "audit"));
        assertThat(restrictionsGet(auditOnly).statusCode()).isEqualTo(404);
        operatorAccess().reinterpret("");
        Signed nobody = signedIn("restrict-list-nobody");
        var notFound = restrictionsGet(nobody);
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(notFound.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", topLevelFieldsOf(get("/api/operator/nope", nobody.cookie()).body()).get("code"));
    }

    @Test
    void 삭제_port는_그_사용자_귀속_target만_digest로_바꾸고_멱등이다() throws Exception {
        Owned erased = owned("restrict-erase");
        Owned kept = owned("restrict-erase-kept");
        Signed operator = operator("restrict-erase-operator", "command");
        var made = restrictVia(operator, RESTRICT_SYNC, "PLAN", erased.planId().toString(), key("restrict-erase"));
        String ledgerTarget = (String) ledgerRow(operationIdIn(made.prepared().body()), "COMPLETED").get("target");
        UUID portfolioRow = restrict(RestrictionFeature.SYNC, RestrictionScope.PORTFOLIO,
                erased.portfolioId().toString());
        jdbc().sql("UPDATE operations_restriction SET target_user_id = :u WHERE restriction_id = :r")
                .param("u", erased.user()).param("r", portfolioRow).update();
        UUID keptRow = restrict(RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION,
                kept.connectionId().toString());
        jdbc().sql("UPDATE operations_restriction SET target_user_id = :u WHERE restriction_id = :r")
                .param("u", kept.user()).param("r", keptRow).update();
        UUID global = restrictGlobally(RestrictionFeature.ADVERTISING);
        UUID policy = restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.CALCULATION_POLICY_VERSION,
                "CALC-1.0");
        List<String> untouched = rowsOf(List.of(keptRow, global, policy));

        assertThat(this.deletion.eraseRestrictionTargets(erased.user())).isEqualTo(2);
        var erasedRows = jdbc().sql("SELECT scope || '|' || target || '|' || (target_user_id IS NULL) || '|' "
                        + "|| (target_erased_at IS NOT NULL) FROM operations_restriction WHERE target_erased_at IS NOT NULL "
                        + "ORDER BY scope").query(String.class).list();
        assertThat(erasedRows).hasSize(2).allSatisfy(row -> assertThat(row).matches("[A-Z_]+\\|[0-9a-f]{64}\\|true\\|true"));
        // 원장과 같은 digest다 — 키를 가진 쪽만 둘을 묶는다.
        assertThat(erasedRows).anySatisfy(row -> assertThat(row).isEqualTo("PLAN|" + ledgerTarget + "|true|true"));
        assertThat(rowsOf(List.of(keptRow, global, policy))).isEqualTo(untouched);
        // 제한은 풀리지 않는다 — 효력 구간은 그대로다.
        assertThat(restrictionCount()).isEqualTo(5);
        // 목록은 비식별된 target을 싣지 않는다.
        var items = itemsIn(restrictionsGet(made.signed()).body(), "items");
        assertThat(items).filteredOn(item -> Boolean.TRUE.equals(item.get("targetErased"))).hasSize(2)
                .allSatisfy(item -> assertThat(item).doesNotContainKey("target"));
        assertThat(restrictionsGet(made.signed()).body()).doesNotContain(erased.planId().toString())
                .doesNotContain(erased.portfolioId().toString());

        // 멱등이다.
        assertThat(this.deletion.eraseRestrictionTargets(erased.user())).isZero();
        assertThat(this.deletion.eraseRestrictionTargets(null)).isZero();
    }

    // ------------------------------------------------------------------ 리뷰 patch: 경합·순서·필터·영향 수

    /** 같은 제한을 두 operation이 동시에 확정한다: 원장 잠금이 줄을 세우고 한쪽만 제한 행을 쓴다. */
    @Test
    void 같은_제한의_동시_확정은_한쪽만_제한을_만들고_다른_쪽은_DENIED다() throws Exception {
        Owned owner = owned("restrict-concurrent");
        String target = owner.planId().toString();
        Signed first = signedIn("restrict-concurrent-a");
        Signed second = signedIn("restrict-concurrent-b");
        operatorAccess().reinterpret(entry(first.subject(), "command") + ";" + entry(second.subject(), "command"));
        Signed a = reauthenticated(first);
        Signed b = reauthenticated(second);
        String keyA = key("restrict-concurrent-a");
        String keyB = key("restrict-concurrent-b");
        var preparedA = prepareRestriction(a, RESTRICT_SYNC, "PLAN", target, "사유", keyA);
        var preparedB = prepareRestriction(b, RESTRICT_SYNC, "PLAN", target, "사유", keyB);
        assertThat(preparedA.statusCode()).as(preparedA.body()).isEqualTo(200);
        assertThat(preparedB.statusCode()).as(preparedB.body()).isEqualTo(200);

        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>> confirmA;
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>> confirmB;
        AutoCloseable lock = holdAuditLock();
        try {
            confirmA = java.util.concurrent.CompletableFuture.supplyAsync(() -> confirmQuietly(a, preparedA, keyA));
            confirmB = java.util.concurrent.CompletableFuture.supplyAsync(() -> confirmQuietly(b, preparedB, keyB));
            awaitAdvisoryWaiters(2);
        } finally {
            lock.close();
        }
        var answers = List.of(confirmA.get(), confirmB.get());
        assertThat(answers).extracting(java.net.http.HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        var loser = answers.stream().filter(answer -> answer.statusCode() == 409).findFirst().orElseThrow();
        assertThat(topLevelFieldsOf(loser.body())).containsEntry("code", "ALREADY_RESTRICTED");
        assertThat(restrictionCount()).isEqualTo(1);
        String loserOperation = loser == answers.get(0) ? operationIdIn(preparedA.body())
                : operationIdIn(preparedB.body());
        assertThat(jdbc().sql("SELECT outcome || COALESCE(':' || (details->>'code'), '') FROM operations_audit_event "
                        + "WHERE operation_id = :o ORDER BY sequence").param("o", loserOperation)
                .query(String.class).list()).containsExactly("REQUESTED", "DENIED:ALREADY_RESTRICTED");
    }

    private java.net.http.HttpResponse<String> confirmQuietly(Signed signed,
            java.net.http.HttpResponse<String> prepared, String key) {
        try {
            return confirmRestriction(signed, RESTRICT_SYNC, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                    key);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * 잠금 안의 중복 판정이 놓치는 같은 제한(효력 시작 전인 행)은 부분 유일 색인이 거절한다 — 그 위반도 중복 거절과 같은
     * 결말이다: 409 {@code ALREADY_RESTRICTED}, 롤백 뒤 따로 쓴 {@code DENIED}, 새 제한 행 없음.
     */
    @Test
    void 유일_색인_위반은_ALREADY_RESTRICTED_거절로_맺는다() throws Exception {
        Owned owner = owned("restrict-index");
        String target = owner.planId().toString();
        Signed operator = operator("restrict-index-operator", "command");
        String key = key("restrict-index");
        var prepared = prepareRestriction(operator, RESTRICT_SYNC, "PLAN", target, "사유", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        jdbc().sql("INSERT INTO operations_restriction (restriction_id, feature, scope, target, operation_id, "
                        + "effective_from) VALUES (:id, 'SYNC', 'PLAN', :target, :op, statement_timestamp() + interval '1 hour')")
                .param("id", UUID.randomUUID()).param("target", target).param("op", "op_future").update();

        var confirmed = confirmRestriction(operator, RESTRICT_SYNC, operationIdIn(prepared.body()),
                phraseIn(prepared.body()), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(confirmed.body())).containsEntry("code", "ALREADY_RESTRICTED");
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(decisionsOf(operator.user())).containsExactly("REQUESTED", "DENIED:ALREADY_RESTRICTED");
    }

    /** 끝난 operation의 키로 다시 준비하면 대상·중복보다 멱등 키 충돌이 먼저 답한다 — 재인증이 없어도 같다. */
    @Test
    void 완료된_키의_재준비는_IDEMPOTENCY_CONFLICT가_먼저다() throws Exception {
        Owned owner = owned("restrict-replay-key");
        Signed operator = operator("restrict-replay-key-operator", "command");
        String key = key("restrict-replay-key");
        var made = restrictVia(operator, RESTRICT_SYNC, "PLAN", owner.planId().toString(), key);
        // 확정이 재인증 시각을 지웠다. 같은 키·같은 입력의 재준비.
        var again = prepareRestriction(made.signed(), RESTRICT_SYNC, "PLAN", owner.planId().toString(), "장애 범위 제한",
                key);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(again.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");
    }

    /** 재인증이 없으면 대상이 없거나 이미 제한된 요청도 재인증 요구가 먼저 답한다. 대상 거절은 발급한 허가를 지운다. */
    @Test
    void 재인증_확인이_대상_검사보다_먼저다() throws Exception {
        Signed plain = signedIn("restrict-order");
        operatorAccess().reinterpret(entry(plain.subject(), "command"));
        var missing = prepareRestriction(plain, RESTRICT_SYNC, "PLAN", UUID.randomUUID().toString(), "사유",
                key("restrict-order-missing"));
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(missing.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");
        restrictGlobally(RestrictionFeature.SYNC);
        var duplicate = prepareRestriction(plain, RESTRICT_SYNC, "GLOBAL", "ALL", "사유", key("restrict-order-dup"));
        assertThat(topLevelFieldsOf(duplicate.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");

        Signed reauthenticated = reauthenticated(plain);
        var invalid = prepareRestriction(reauthenticated, RESTRICT_SYNC, "PLAN", UUID.randomUUID().toString(), "사유",
                key("restrict-order-invalid"));
        assertThat(topLevelFieldsOf(invalid.body())).containsEntry("code", "TARGET_INVALID");
        assertThat(jdbc().sql("SELECT count(*) FROM action_grant WHERE user_id = :u AND consumed_at IS NULL")
                .param("u", plain.user()).query(Long.class).single()).as("대상 거절은 방금 발급한 허가를 지운다").isZero();
    }

    /** 감사 조회의 scope 필터는 제한 command의 {@code <기능>:<scope>}를 받는다. */
    @Test
    void 감사_조회는_콜론_scope로_거른다() throws Exception {
        Owned owner = owned("restrict-filter");
        Signed operator = operator("restrict-filter-operator", "command,audit");
        var made = restrictVia(operator, RESTRICT_SYNC, "PLAN", owner.planId().toString(), key("restrict-filter"));
        Signed last = restrictVia(reauthenticated(made.signed()), RESTRICT_ADVERTISING, "GLOBAL", "ALL",
                key("restrict-filter-ads")).signed();
        var filtered = auditGet(last, "scope=SYNC:PLAN");
        assertThat(filtered.statusCode()).as(filtered.body()).isEqualTo(200);
        var items = itemsIn(filtered.body(), "items");
        assertThat(items).isNotEmpty().allSatisfy(item -> assertThat(item).containsEntry("scope", "SYNC:PLAN"));
        assertThat(auditGet(last, "scope=SYNC:").statusCode()).isEqualTo(400);
    }

    /** 영향 수는 실제 데이터의 수다: portfolio·연결·계산 정책 범위마다. */
    @Test
    void 영향_미리보기의_수는_실제_계획과_연결을_센다() throws Exception {
        Owned a = owned("restrict-impact-a");
        owned("restrict-impact-b");
        Signed operator = operator("restrict-impact-operator", "command");
        var portfolio = prepareRestriction(operator, RESTRICT_SYNC, "PORTFOLIO", a.portfolioId().toString(), "사유",
                key("restrict-impact-portfolio"));
        assertThat(impactOf(portfolio)).containsEntry("affectedPlans", 1).containsEntry("affectedConnections", 1);
        var connection = prepareRestriction(operator, RESTRICT_SYNC, "PROVIDER_CONNECTION", a.connectionId().toString(),
                "사유", key("restrict-impact-connection"));
        assertThat(impactOf(connection)).containsEntry("affectedPlans", 1).containsEntry("affectedConnections", 1);
        var policy = prepareRestriction(operator, RESTRICT_DISPLAY, "CALCULATION_POLICY_VERSION", "CALC-1.0", "사유",
                key("restrict-impact-policy"));
        assertThat(impactOf(policy)).containsEntry("affectedPlans", 2).containsEntry("affectedConnections", 0);
        var global = prepareRestriction(operator, RESTRICT_SYNC, "GLOBAL", "ALL", "사유", key("restrict-impact-global"));
        assertThat(impactOf(global)).containsEntry("affectedPlans", 2).containsEntry("affectedConnections", 2);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> impactOf(java.net.http.HttpResponse<String> prepared) {
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        return (Map<String, Object>) topLevelFieldsOf(prepared.body()).get("impact");
    }

    private List<String> rowsOf(List<UUID> ids) {
        return ids.stream().map(id -> jdbc().sql("SELECT feature || '|' || scope || '|' || target || '|' "
                        + "|| COALESCE(CAST(target_user_id AS text), '-') || '|' || (target_erased_at IS NULL) "
                        + "FROM operations_restriction WHERE restriction_id = :r")
                .param("r", id).query(String.class).single()).toList();
    }

    private Map<String, Object> ledgerRow(String operationId, String outcome) {
        return jdbc().sql("SELECT scope, target FROM operations_audit_event WHERE operation_id = :o AND outcome = :c")
                .param("o", operationId).param("c", outcome)
                .query((rs, row) -> Map.<String, Object>of("scope", rs.getString("scope"), "target",
                        rs.getString("target")))
                .single();
    }

    /** 원장 어느 열에도 그 원식별자가 없다. */
    private void assertLedgerHasNo(UUID raw) {
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE CAST(row_to_json(operations_audit_event) "
                        + "AS text) LIKE :raw").param("raw", "%" + raw + "%").query(Long.class).single())
                .isZero();
    }
}
