package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import com.bokrilog.operations.SignalType;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Story 3.4: 검증 증거를 확인한 뒤 제한을 수동 복구한다 — SPEC I/O 매트릭스 전 행.
 *
 * <p>해제는 3.1 envelope의 운영 command {@code lift-restriction} 하나다. 준비는 복구 조건 넷·원인·검증·증거 참조·배포
 * 버전을 받고 다시 열릴 단계·남는 제한·신호 계보를 미리 보인다. 확정은 원장 잠금 → 최종 권한 → 제한 행 잠금 → 열림·
 * 신호 계보 재검사 → 조건부 UPDATE 한 문장 → {@code COMPLETED}를 한 커밋으로 쓰고, 커밋 뒤 evaluator smoke가 어긋나면
 * 같은 범위의 새 제한과 {@code FAILED SMOKE_FAILED}를 따로 쓴다.
 *
 * <p>smoke 실패는 시험 훅으로 만든다 — evaluator를 {@link MockitoSpyBean}으로 감싸 해제된 제한을 계속 보고하거나 남는
 * 제한을 빠뜨리게 하고, 되돌림 쓰기 실패는 command API를 감싸 만든다. 그 때문에 이 클래스만 컨텍스트가 갈라진다({@code AuthDeletionPathTest}와 같은 관용). 훅이 없는 동안 spy는 실제 구현에
 * 위임한다.
 */
class OperatorRecoveryTest extends OperatorTestBase {

    private static final String LIFT = OperatorCommandApi.LIFT_RESTRICTION;

    private static final List<String> CONDITIONS = OperatorCommandApi.RECOVERY_CONDITIONS;

    private static final List<String> PLAN_SYNC_STAGES =
            List.of("INCREMENTAL_SYNC", "INCREMENTAL_PUBLICATION", "PLAN_END");

    @MockitoSpyBean
    private OperationalRestrictionApi restrictions;

    /** 되돌림 쓰기 실패를 만드는 훅. 훅이 없는 동안 실제 구현에 위임한다. */
    @MockitoSpyBean
    private OperatorCommandApi commands;

    @Autowired
    private com.bokrilog.operations.OperationsDeletionApi deletion;

    @Autowired
    private OperationalSignalApi signals;

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

    // ------------------------------------------------------------------ 준비 → 확정 → 재전송

    @Test
    void 준비는_미리보기와_증거를_남기고_확정은_제한_하나를_닫으며_재전송은_저장_결과다() throws Exception {
        Owned owner = owned("lift-plan");
        Signed operator = operator("lift-plan-operator", "command,audit");
        var made = restrictVia(operator, OperatorCommandApi.RESTRICT_SYNC, "PLAN", owner.planId().toString(),
                key("lift-plan-restrict"));
        UUID restrictionId = restrictionIdIn(made.confirmed());
        Signed signed = reauthenticated(made.signed());
        String key = key("lift-plan");

        var prepared = prepareLift(signed, restrictionId.toString(), CONDITIONS, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        Map<String, Object> fields = topLevelFieldsOf(prepared.body());
        assertThat(fields).containsEntry("command", LIFT).containsEntry("scope", "SYNC:PLAN")
                .containsEntry("target", restrictionId.toString()).containsEntry("reason", "원인 계산 오류 수정")
                .containsEntry("policyVersion", "OPERATOR-COMMAND-1.2")
                .containsEntry("confirmationPhrase", "복구 동기화 계획 " + owner.planId());
        Map<String, Object> impact = mapOf(fields.get("impact"));
        assertThat(impact).containsEntry("reopenedStages", PLAN_SYNC_STAGES)
                .containsEntry("remainingRestrictions", List.of()).containsEntry("linkedSignals", 0)
                .containsEntry("latestSignalAt", null);
        // 미리보기에 식별자가 없다.
        assertThat(impact.toString()).doesNotContain(owner.planId().toString()).doesNotContain(restrictionId.toString());
        String operationId = operationIdIn(prepared.body());
        assertThat(openRestrictions()).containsExactly(restrictionId);

        // 원장의 REQUESTED: 사유는 원인, details에 증거. 원식별자·이메일이 없다.
        Map<String, Object> requested = ledgerDetails(operationId, "REQUESTED");
        assertThat(requested).containsEntry("conditions", CONDITIONS)
                .containsEntry("verification", "영향 범위 재현 시험 통과").containsEntry("evidenceRef", "INC-42 회귀 기록")
                .containsEntry("deployVersion", "1.4.2+build.7").containsEntry("restrictionId", restrictionId.toString())
                .containsEntry("linkedSignals", 0);
        assertThat(ledgerScopeOf(operationId)).isEqualTo("SYNC:PLAN");

        var confirmed = confirmLift(signed, operationId, "복구 동기화 계획 " + owner.planId(), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        Map<String, Object> done = topLevelFieldsOf(confirmed.body());
        assertThat(done).containsEntry("operationId", operationId).containsEntry("outcome", "COMPLETED")
                .containsEntry("command", LIFT).containsEntry("feature", "SYNC").containsEntry("scope", "PLAN")
                .containsEntry("restrictionId", restrictionId.toString()).doesNotContainKey("target");
        assertThat(mapOf(done.get("smoke"))).containsEntry("status", "PASSED")
                .containsEntry("reopenedStages", PLAN_SYNC_STAGES).containsEntry("stillBlockedStages", List.of());
        assertThat(sessionCookieOf(confirmed)).as("세션이 회전한다").isPresent();
        // smoke 통과도 원장에 남는다: 같은 operation의 APPROVED SMOKE_PASSED 한 행.
        assertThat(decisionsOfOperation(operationId)).containsExactly("REQUESTED", "COMPLETED", "APPROVED:SMOKE_PASSED");
        // 행 하나가 닫혔고 해제 operation이 남았다. 행은 지워지지 않는다.
        assertThat(jdbc().sql("SELECT (effective_until IS NOT NULL) || '|' || lifted_by_operation_id "
                        + "FROM operations_restriction WHERE restriction_id = :r").param("r", restrictionId)
                .query(String.class).single()).isEqualTo("true|" + operationId);
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withPlan(owner.planId()))).isFalse();
        Map<String, Object> completed = ledgerDetails(operationId, "COMPLETED");
        assertThat(completed).containsKeys("conditions", "verification", "evidenceRef", "deployVersion",
                "restrictionId", "linkedSignals").containsEntry("reopenedStages", PLAN_SYNC_STAGES);
        assertLedgerHasNo(owner.planId().toString());
        assertLedgerHasNo(owner.user().toString());
        assertLedgerHasNo("@");

        // 같은 키의 재전송: 저장된 결과, 회전 없음, 원장 행 추가 없음.
        Signed rotated = after(signed, confirmed);
        var replayed = confirmLift(rotated, operationId, "복구 동기화 계획 " + owner.planId(), key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body())).containsEntry("restrictionId", restrictionId.toString())
                .containsEntry("liftedAt", done.get("liftedAt")).containsEntry("smoke", done.get("smoke"));
        assertThat(sessionCookieOf(replayed)).as("재전송은 회전하지 않는다").isEmpty();
        assertThat(decisionsOfOperation(operationId)).containsExactly("REQUESTED", "COMPLETED", "APPROVED:SMOKE_PASSED");

        // 닫힌 제한의 새 해제는 409다(새 키).
        Signed again = reauthenticated(rotated);
        var closed = prepareLift(again, restrictionId.toString(), CONDITIONS, key("lift-plan-closed"));
        assertThat(closed.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(closed.body())).containsEntry("code", "RESTRICTION_NOT_ACTIVE");

        // 원장 조회의 scope 필터가 해제를 그 제한의 `<기능>:<scope>`로 고른다.
        var audit = auditGet(again, "command=" + LIFT + "&outcome=COMPLETED");
        assertThat(itemsIn(audit.body(), "items")).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("scope", "SYNC:PLAN")
                        .containsEntry("target", restrictionId.toString()).containsEntry("reason", "원인 계산 오류 수정"));
    }

    // ------------------------------------------------------------------ 입력 검증·준비 순서·권한

    @Test
    void 증거_입력이_빠지거나_모양이_틀리면_준비를_거절한다() throws Exception {
        UUID restrictionId = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed plain = signedIn("lift-input");
        operatorAccess().reinterpret(entry(plain.subject(), "command"));
        // 재인증이 없으면 대상 확인보다 재인증 요구가 먼저다.
        var unauthenticated = prepareLift(plain, UUID.randomUUID().toString(), CONDITIONS, key("lift-input-reauth"));
        assertThat(unauthenticated.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(unauthenticated.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");

        Signed operator = reauthenticated(plain);
        String id = restrictionId.toString();
        assertRefused(prepareLift(operator, id, CONDITIONS.subList(0, 3), key("lift-input-missing")), 422,
                "RECOVERY_CONDITION_MISSING");
        assertRefused(prepareLift(operator, id, List.of(), key("lift-input-empty")), 422,
                "RECOVERY_CONDITION_MISSING");
        assertRefused(prepare(operator, liftBody(id, "원인", "검증", "증거", "1.0.0", null), key("lift-input-none")),
                422, "RECOVERY_CONDITION_MISSING");
        List<String> unknown = new ArrayList<>(CONDITIONS.subList(0, 3));
        unknown.add("TRUST_ME");
        assertRefused(prepareLift(operator, id, unknown, key("lift-input-unknown")), 422,
                "RECOVERY_EVIDENCE_INVALID");
        List<String> repeated = List.of(CONDITIONS.get(0), CONDITIONS.get(0), CONDITIONS.get(1), CONDITIONS.get(2));
        assertRefused(prepareLift(operator, id, repeated, key("lift-input-repeated")), 422,
                "RECOVERY_EVIDENCE_INVALID");
        List<String> tooMany = new ArrayList<>(CONDITIONS);
        tooMany.add(CONDITIONS.get(0));
        assertThat(prepareLift(operator, id, tooMany, key("lift-input-too-many")).statusCode())
                .as("조건 수보다 긴 배열은 본문 모양 오류다").isEqualTo(400);
        assertRefused(prepare(operator, liftBody(id, " ", "검증", "증거", "1.0.0", CONDITIONS),
                key("lift-input-reason")), 422, "REASON_REQUIRED");
        assertRefused(prepare(operator, liftBody(id, "연락 ops@example.com", "검증", "증거", "1.0.0", CONDITIONS),
                key("lift-input-reason-email")), 422, "REASON_INVALID");
        assertRefused(prepare(operator, liftBody(id, "원인", "", "증거", "1.0.0", CONDITIONS),
                key("lift-input-verification")), 422, "RECOVERY_EVIDENCE_REQUIRED");
        assertRefused(prepare(operator, liftBody(id, "원인", "검증", " ", "1.0.0", CONDITIONS),
                key("lift-input-evidence-blank")), 422, "RECOVERY_EVIDENCE_REQUIRED");
        assertRefused(prepare(operator, liftBody(id, "원인", "검증", "증거", null, CONDITIONS),
                key("lift-input-version-missing")), 422, "RECOVERY_EVIDENCE_REQUIRED");
        assertRefused(prepare(operator, liftBody(id, "원인", "검증", "담당 ops@example.com", "1.0.0", CONDITIONS),
                key("lift-input-evidence")), 422, "RECOVERY_EVIDENCE_INVALID");
        assertRefused(prepare(operator, liftBody(id, "원인", "검증", "증거", "버전 1", CONDITIONS),
                key("lift-input-version")), 422, "RECOVERY_EVIDENCE_INVALID");
        assertRefused(prepare(operator, liftBody(id, "원인", "검증", "증거", "v".repeat(65), CONDITIONS),
                key("lift-input-version-long")), 422, "RECOVERY_EVIDENCE_INVALID");
        assertRefused(prepareLift(operator, "not-a-restriction", CONDITIONS, key("lift-input-shape")), 422,
                "TARGET_INVALID");
        assertRefused(prepareLift(operator, UUID.randomUUID().toString(), CONDITIONS, key("lift-input-unknown-id")),
                422, "TARGET_INVALID");
        // 조건이 배열이 아니면 본문 모양 오류다.
        var malformed = prepare(operator, "{\"target\":\"" + id + "\",\"reason\":\"원인\",\"verification\":\"검증\","
                + "\"evidenceRef\":\"증거\",\"deployVersion\":\"1.0.0\",\"conditions\":\"CAUSE_REMOVED\"}",
                key("lift-input-malformed"));
        assertThat(malformed.statusCode()).isEqualTo(400);

        // 거절은 아무것도 바꾸지 않았고, 원장에는 이메일이 남지 않았다.
        assertThat(openRestrictions()).containsExactly(restrictionId);
        assertLedgerHasNo("@");
        assertThat(jdbc().sql("SELECT count(*) FROM action_grant WHERE user_id = :u AND consumed_at IS NULL")
                .param("u", operator.user()).query(Long.class).single())
                .as("대상 거절은 방금 발급한 허가를 지운다").isZero();
    }

    @Test
    void 감사_권한만_가진_운영자와_비운영자는_404다() throws Exception {
        UUID restrictionId = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed auditOnly = operator("lift-audit-only", "audit");
        var refused = prepareLift(auditOnly, restrictionId.toString(), CONDITIONS, key("lift-audit-only"));
        assertThat(refused.statusCode()).isEqualTo(404);
        operatorAccess().reinterpret("");
        Signed nobody = signedIn("lift-nobody");
        var notFound = prepareLift(nobody, restrictionId.toString(), CONDITIONS, key("lift-nobody"));
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(notFound.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", topLevelFieldsOf(get("/api/operator/nope", nobody.cookie()).body()).get("code"));
        assertThat(openRestrictions()).containsExactly(restrictionId);
        assertThat(auditCount()).isZero();
    }

    // ------------------------------------------------------------------ 확정 순간 경합

    @Test
    void 준비_뒤_다른_operation이_먼저_해제하면_DENIED이고_아무것도_바꾸지_않는다() throws Exception {
        UUID restrictionId = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed first = signedIn("lift-race-a");
        Signed second = signedIn("lift-race-b");
        operatorAccess().reinterpret(entry(first.subject(), "command") + ";" + entry(second.subject(), "command"));
        Signed a = reauthenticated(first);
        Signed b = reauthenticated(second);
        String keyA = key("lift-race-a");
        String keyB = key("lift-race-b");
        var preparedA = prepareLift(a, restrictionId.toString(), CONDITIONS, keyA);
        var preparedB = prepareLift(b, restrictionId.toString(), CONDITIONS, keyB);
        assertThat(preparedA.statusCode()).as(preparedA.body()).isEqualTo(200);
        assertThat(preparedB.statusCode()).as(preparedB.body()).isEqualTo(200);
        assertThat(phraseIn(preparedA.body())).isEqualTo("복구 광고 전역");

        // 문구 불일치는 허가를 소비하지 않는다(대상명 `전역`을 다시 입력해야 한다).
        var mismatch = confirmLift(a, operationIdIn(preparedA.body()), "복구 광고", keyA);
        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(mismatch.body())).containsEntry("code", "CONFIRMATION_MISMATCH");

        var won = confirmLift(a, operationIdIn(preparedA.body()), "복구 광고 전역", keyA);
        assertThat(won.statusCode()).as(won.body()).isEqualTo(200);
        String closedBy = jdbc().sql("SELECT lifted_by_operation_id || '|' || effective_until FROM operations_restriction "
                + "WHERE restriction_id = :r").param("r", restrictionId).query(String.class).single();

        var lost = confirmLift(b, operationIdIn(preparedB.body()), "복구 광고 전역", keyB);
        assertThat(lost.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(lost.body())).containsEntry("code", "RESTRICTION_NOT_ACTIVE");
        assertThat(sessionCookieOf(lost)).as("거절은 회전하지 않는다").isEmpty();
        assertThat(decisionsOf(b.user())).containsExactly("REQUESTED", "DENIED:RESTRICTION_NOT_ACTIVE");
        assertThat(jdbc().sql("SELECT lifted_by_operation_id || '|' || effective_until FROM operations_restriction "
                + "WHERE restriction_id = :r").param("r", restrictionId).query(String.class).single())
                .as("앞선 해제가 그대로다").isEqualTo(closedBy);
        assertThat(restrictionCount()).isEqualTo(1);
    }

    @Test
    void 준비_뒤_같은_제한에_새_신호가_연결되면_NEW_SIGNAL_SINCE_PREPARE이고_제한은_유지된다() throws Exception {
        UUID user = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        var raised = this.signals.raise(unknownEnum(user, connection));
        UUID restrictionId = raised.decisions().getFirst().restrictionId();
        Signed operator = operator("lift-signal", "command");
        String key = key("lift-signal");

        var prepared = prepareLift(operator, restrictionId.toString(), CONDITIONS, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(phraseIn(prepared.body())).isEqualTo("복구 동기화 연결 " + connection);
        Map<String, Object> impact = mapOf(topLevelFieldsOf(prepared.body()).get("impact"));
        // 수동 검증 필요 행: 신호 계보(수·최신 감지 시각)를 미리 보인다.
        assertThat(impact).containsEntry("linkedSignals", 1).containsKey("latestSignalAt");
        assertThat(Instant.parse((String) impact.get("latestSignalAt"))).isNotNull();

        var linked = this.signals.raise(unknownEnum(user, connection));
        assertThat(linked.decisions().getFirst().outcome()).isEqualTo(OperationalSignalApi.Outcome.LINKED);
        var refused = confirmLift(operator, operationIdIn(prepared.body()), phraseIn(prepared.body()), key);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "NEW_SIGNAL_SINCE_PREPARE");
        assertThat(openRestrictions()).containsExactly(restrictionId);
        assertThat(outcomesOf(operationIdIn(prepared.body()))).containsExactly("REQUESTED", "DENIED");
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withConnection(connection))).isTrue();
        // 신호 행은 고쳐지지 않는다.
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal WHERE restriction_id = :r")
                .param("r", restrictionId).query(Integer.class).single()).isEqualTo(2);

        // 다시 준비하면 새 계보로 해제된다.
        Signed again = reauthenticated(operator);
        String retryKey = key("lift-signal-retry");
        var retried = prepareLift(again, restrictionId.toString(), CONDITIONS, retryKey);
        assertThat(mapOf(topLevelFieldsOf(retried.body()).get("impact"))).containsEntry("linkedSignals", 2);
        var lifted = confirmLift(again, operationIdIn(retried.body()), phraseIn(retried.body()), retryKey);
        assertThat(lifted.statusCode()).as(lifted.body()).isEqualTo(200);
        assertThat(openRestrictions()).isEmpty();
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal WHERE restriction_id = :r")
                .param("r", restrictionId).query(Integer.class).single()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 정확한 범위·독립 재개

    @Test
    void 계획_동기화_해제는_그_행만_빼고_전역_동기화와_표시_제한은_그대로_막는다() throws Exception {
        Owned owner = owned("lift-scope");
        Signed operator = operator("lift-scope-operator", "command");
        var made = restrictVia(operator, OperatorCommandApi.RESTRICT_SYNC, "PLAN", owner.planId().toString(),
                key("lift-scope-restrict"));
        UUID planSync = restrictionIdIn(made.confirmed());
        UUID globalSync = restrictGlobally(RestrictionFeature.SYNC);
        UUID planDisplay = restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.PLAN,
                owner.planId().toString());
        UUID otherFeature = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed signed = reauthenticated(made.signed());
        String key = key("lift-scope");

        var prepared = prepareLift(signed, planSync.toString(), CONDITIONS, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        Map<String, Object> impact = mapOf(topLevelFieldsOf(prepared.body()).get("impact"));
        // 남는 제한은 둘이다 — 같은 계획의 표시 제한(구체 우선)과 전역 동기화. 독립 기능(광고)은 섞이지 않는다.
        assertThat(impact).containsEntry("reopenedStages", List.of()).containsEntry("remainingRestrictions",
                List.of(Map.of("feature", "CALCULATION_DISPLAY", "scope", "PLAN", "origin", "OPERATOR"),
                        Map.of("feature", "SYNC", "scope", "GLOBAL", "origin", "OPERATOR")));

        var confirmed = confirmLift(signed, operationIdIn(prepared.body()), phraseIn(prepared.body()), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "PASSED")
                .containsEntry("reopenedStages", List.of()).containsEntry("stillBlockedStages", PLAN_SYNC_STAGES);
        // evaluator는 그 행 하나만 뺀다.
        RestrictionSubject plan = RestrictionSubject.none().withPlan(owner.planId());
        assertThat(this.restrictions.evaluate(EnumSet.of(RestrictionFeature.SYNC), plan))
                .hasValueSatisfying(found -> assertThat(found.restrictionId()).isEqualTo(globalSync));
        assertThat(this.restrictions.evaluate(EnumSet.of(RestrictionFeature.CALCULATION_DISPLAY), plan))
                .hasValueSatisfying(found -> assertThat(found.restrictionId()).isEqualTo(planDisplay));
        assertThat(openRestrictions()).containsExactlyInAnyOrder(globalSync, planDisplay, otherFeature);
    }

    @Test
    void 신규_연결_전역_해제는_연결_생성만_재개하고_다른_기능_제한은_바뀌지_않는다() throws Exception {
        Signed operator = operator("lift-independent", "command");
        var made = restrictVia(operator, OperatorCommandApi.RESTRICT_NEW_CONNECTION, "GLOBAL", "ALL",
                key("lift-independent-restrict"));
        UUID newConnection = restrictionIdIn(made.confirmed());
        UUID display = restrictGlobally(RestrictionFeature.CALCULATION_DISPLAY);
        UUID sync = restrictGlobally(RestrictionFeature.SYNC);
        List<String> before = rowsOf(List.of(display, sync));
        Signed signed = reauthenticated(made.signed());
        String key = key("lift-independent");

        var prepared = prepareLift(signed, newConnection.toString(), CONDITIONS, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(phraseIn(prepared.body())).isEqualTo("복구 신규 연결 전역");
        assertThat(mapOf(topLevelFieldsOf(prepared.body()).get("impact")))
                .containsEntry("reopenedStages", List.of("CONNECTION_CREATE"))
                .containsEntry("remainingRestrictions", List.of());
        var confirmed = confirmLift(signed, operationIdIn(prepared.body()), "복구 신규 연결 전역", key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "PASSED")
                .containsEntry("reopenedStages", List.of("CONNECTION_CREATE"));

        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.NEW_CONNECTION), RestrictionSubject.none()))
                .isFalse();
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.CALCULATION_DISPLAY),
                RestrictionSubject.none())).isTrue();
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC), RestrictionSubject.none())).isTrue();
        assertThat(rowsOf(List.of(display, sync))).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 자동 재개 없음

    @Test
    void 시간이_지나고_신호가_다시_오지_않아도_제한은_스스로_풀리지_않는다() throws Exception {
        UUID connection = UUID.randomUUID();
        UUID restrictionId = this.signals.raise(unknownEnum(UUID.randomUUID(), connection)).decisions().getFirst()
                .restrictionId();
        // 행 시각을 90일 앞으로 옮긴다(시간 경과). 신호는 다시 오지 않는다.
        jdbc().sql("UPDATE operations_signal SET detected_at = detected_at - interval '90 days', "
                + "effective_at = effective_at - interval '90 days', recorded_at = recorded_at - interval '90 days'")
                .update();
        jdbc().sql("UPDATE operations_restriction SET effective_from = effective_from - interval '90 days', "
                + "created_at = created_at - interval '90 days'").update();

        assertThat(openRestrictions()).containsExactly(restrictionId);
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withConnection(connection))).isTrue();
        assertThat(this.restrictions.active()).singleElement()
                .satisfies(found -> assertThat(found.requiresVerification()).isTrue());
    }

    /**
     * 운영 소스에서 {@code effective_until}을 쓰는 SQL은 해제 UPDATE 한 문장이고, 그 문장은 해제 command의 확정에서만 불린다.
     * 제한 행을 지우는 문장이 없고, 시작·주기 경로(스케줄러·러너·이벤트 리스너·수명 주기)는 제한 표에 닿지 않는다 — 시간
     * 경과·신호·재시작으로 닫히는 경로가 소스에 없다(매트릭스 "자동 재개 없음"의 근거). 비교는 문자열 연결과 공백·줄바꿈을
     * 정규화한 소스로 한다. 원장 repository의 UPDATE가 {@code eraseActor} 하나라는 단정은 {@code OperatorAuditTest}가 한다.
     */
    @Test
    void 운영_소스에서_effective_until을_쓰는_SQL은_해제_UPDATE_하나뿐이다() throws Exception {
        Path main = Path.of("src/main/java");
        Map<String, String> sources = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.put(file.getFileName().toString(), normalized(Files.readString(file)));
            }
        }
        Pattern write = Pattern.compile("effective_until\\s*=");
        List<String> writes = new ArrayList<>();
        List<String> callers = new ArrayList<>();
        List<String> updates = new ArrayList<>();
        for (var source : sources.entrySet()) {
            count(write, source.getValue(), source.getKey(), writes);
            count(Pattern.compile("restrictions\\.lift\\("), source.getValue(), source.getKey(), callers);
            Matcher update = Pattern.compile("UPDATE operations_restriction\\b[^\"]*", Pattern.CASE_INSENSITIVE)
                    .matcher(source.getValue());
            while (update.find()) {
                updates.add(update.group());
            }
            assertThat(source.getValue()).as(source.getKey() + ": 제한 행을 지우는 문장")
                    .doesNotContainIgnoringCase("DELETE FROM operations_restriction");
        }
        assertThat(writes).containsExactly("OperationalRestrictionRepository.java");
        assertThat(updates).as("제한 표의 UPDATE는 비식별과 해제 둘이고 효력 끝을 쓰는 것은 해제 하나다").hasSize(2)
                .filteredOn(sql -> sql.contains("effective_until")).singleElement()
                .isEqualTo("UPDATE operations_restriction r SET effective_until = statement_timestamp(), "
                        + "lifted_by_operation_id = :op WHERE r.restriction_id = :id AND ");
        String repository = sources.get("OperationalRestrictionRepository.java");
        assertThat(repository).contains("lifted_by_operation_id = :op WHERE r.restriction_id = :id AND \" + OPEN")
                .contains("private static final String OPEN = \"r.effective_until IS NULL AND statement_timestamp() "
                        + ">= r.effective_from\";");
        assertThat(callers).as("해제 문장의 호출자는 해제 확정 하나다").containsExactly("OperatorEnvelopeService.java");
        Matcher inside = Pattern.compile("public LiftCompletion completeLift\\([^{]*\\{.*?restrictions\\.lift\\(",
                Pattern.DOTALL).matcher(sources.get("OperatorEnvelopeService.java"));
        assertThat(inside.find()).as("호출은 completeLift 안이다").isTrue();
        // 시작·주기 경로는 제한 표·해제에 닿지 않는다.
        Pattern lifecycle = Pattern.compile("@Scheduled|ApplicationRunner|CommandLineRunner|@EventListener|"
                + "ApplicationListener|SmartLifecycle|@PostConstruct|@TransactionalEventListener");
        for (var source : sources.entrySet()) {
            if (lifecycle.matcher(source.getValue()).find()) {
                assertThat(source.getValue()).as(source.getKey()).doesNotContain("operations_restriction")
                        .doesNotContain("OperationalRestrictionRepository").doesNotContain("completeLift")
                        .doesNotContain("revertLift");
            }
        }
        // 마이그레이션에도 닫는 문장이 없다.
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                assertThat(write.matcher(Files.readString(file)).find()).as(file.toString()).isFalse();
            }
        }
    }

    // ------------------------------------------------------------------ 판정 경계·대상 차원·비식별

    @Test
    void 효력_시작_전인_제한은_준비에서_RESTRICTION_NOT_ACTIVE다() throws Exception {
        UUID future = UUID.randomUUID();
        jdbc().sql("INSERT INTO operations_restriction (restriction_id, feature, scope, target, effective_from) "
                + "VALUES (:id, 'ADVERTISING', 'GLOBAL', 'ALL', statement_timestamp() + interval '1 hour')")
                .param("id", future).update();
        Signed operator = operator("lift-future", "command");
        assertRefused(prepareLift(operator, future.toString(), CONDITIONS, key("lift-future")), 409,
                "RESTRICTION_NOT_ACTIVE");
        assertThat(jdbc().sql("SELECT effective_until IS NULL FROM operations_restriction WHERE restriction_id = :r")
                .param("r", future).query(Boolean.class).single()).isTrue();
    }

    @Test
    void 계획_해제는_그_계획의_portfolio_제한을_남는_제한으로_세고_단계를_다시_열지_않는다() throws Exception {
        Owned owner = owned("lift-portfolio");
        Signed operator = operator("lift-portfolio-operator", "command");
        var made = restrictVia(operator, OperatorCommandApi.RESTRICT_SYNC, "PLAN", owner.planId().toString(),
                key("lift-portfolio-restrict"));
        UUID planSync = restrictionIdIn(made.confirmed());
        UUID portfolioSync = restrict(RestrictionFeature.SYNC, RestrictionScope.PORTFOLIO,
                owner.portfolioId().toString());
        Signed signed = reauthenticated(made.signed());
        String key = key("lift-portfolio");
        var prepared = prepareLift(signed, planSync.toString(), CONDITIONS, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(prepared.body()).get("impact"))).containsEntry("reopenedStages", List.of())
                .containsEntry("remainingRestrictions",
                        List.of(Map.of("feature", "SYNC", "scope", "PORTFOLIO", "origin", "OPERATOR")));
        var confirmed = confirmLift(signed, operationIdIn(prepared.body()), phraseIn(prepared.body()), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "PASSED")
                .containsEntry("reopenedStages", List.of()).containsEntry("stillBlockedStages", PLAN_SYNC_STAGES);
        assertThat(openRestrictions()).containsExactly(portfolioSync);
    }

    @Test
    void 계정_삭제로_비식별된_제한도_원식별자와_digest_없이_복구된다() throws Exception {
        Owned owner = owned("lift-erased");
        Signed operator = operator("lift-erased-operator", "command");
        var made = restrictVia(operator, OperatorCommandApi.RESTRICT_SYNC, "PLAN", owner.planId().toString(),
                key("lift-erased-restrict"));
        UUID plan = restrictionIdIn(made.confirmed());
        UUID connection = restrict(RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION,
                owner.connectionId().toString());
        jdbc().sql("UPDATE operations_restriction SET target_user_id = :u WHERE restriction_id = :r")
                .param("u", owner.user()).param("r", connection).update();
        assertThat(this.deletion.eraseRestrictionTargets(owner.user())).isEqualTo(2);
        List<String> digests = jdbc().sql("SELECT target FROM operations_restriction WHERE target_erased_at IS NOT NULL")
                .query(String.class).list();
        assertThat(digests).hasSize(2);

        Signed signed = reauthenticated(made.signed());
        for (var lifted : List.of(Map.entry(plan, "복구 동기화 계획 삭제된 대상"),
                Map.entry(connection, "복구 동기화 연결 삭제된 대상"))) {
            String key = key("lift-erased");
            var prepared = prepareLift(signed, lifted.getKey().toString(), CONDITIONS, key);
            assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
            assertThat(phraseIn(prepared.body())).isEqualTo(lifted.getValue());
            String operationId = operationIdIn(prepared.body());
            var confirmed = confirmLift(signed, operationId, lifted.getValue(), key);
            assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
            assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "PASSED");
            String rows = jdbc().sql("SELECT string_agg(CAST(row_to_json(e) AS text), '') FROM operations_audit_event e "
                    + "WHERE operation_id = :o").param("o", operationId).query(String.class).single();
            for (String hidden : List.of(prepared.body(), confirmed.body(), rows)) {
                assertThat(hidden).doesNotContain(owner.planId().toString())
                        .doesNotContain(owner.connectionId().toString()).doesNotContain(owner.user().toString())
                        .doesNotContain(digests.get(0)).doesNotContain(digests.get(1));
            }
            signed = reauthenticated(after(signed, confirmed));
        }
        assertThat(openRestrictions()).isEmpty();
    }

    // ------------------------------------------------------------------ 권한 회수·멱등 키

    @Test
    void 확정_순간_권한이_회수되면_ACCESS_REVOKED로_맺고_제한은_열려_있다() throws Exception {
        UUID restrictionId = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("lift-revoked", "command");
        String key = key("lift-revoked");
        var prepared = prepareLift(operator, restrictionId.toString(), CONDITIONS, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        HttpResponse<String> revoked;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.CompletableFuture<HttpResponse<String>> confirming;
            AutoCloseable held = holdAuditLock();
            try {
                confirming = java.util.concurrent.CompletableFuture.supplyAsync(() -> quietly(() ->
                        confirmLift(operator, operationIdIn(prepared.body()), "복구 광고 전역", key)), executor);
                awaitAdvisoryWaiters(1);
                operatorAccess().reinterpret("");
            } finally {
                held.close();
            }
            revoked = confirming.get();
        }
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(404);
        assertThat(openRestrictions()).containsExactly(restrictionId);
        assertThat(decisionsOf(operator.user())).containsExactly("REQUESTED", "DENIED:ACCESS_REVOKED");
    }

    @Test
    void 같은_키에_다른_증거를_보내면_IDEMPOTENCY_CONFLICT다() throws Exception {
        UUID restrictionId = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("lift-key", "command");
        String key = key("lift-key");
        assertThat(prepareLift(operator, restrictionId.toString(), CONDITIONS, key).statusCode()).isEqualTo(200);
        var other = prepare(operator, liftBody(restrictionId.toString(), "원인 계산 오류 수정", "다른 검증", "INC-42 회귀 기록",
                "1.4.2+build.7", CONDITIONS), key);
        assertRefused(other, 409, "IDEMPOTENCY_CONFLICT");
        assertThat(openRestrictions()).containsExactly(restrictionId);
    }

    @Test
    void 같은_키의_동시_확정은_한_번만_닫고_둘_다_같은_결과다() throws Exception {
        UUID restrictionId = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("lift-concurrent", "command");
        String key = key("lift-concurrent");
        var prepared = prepareLift(operator, restrictionId.toString(), CONDITIONS, key);
        String operationId = operationIdIn(prepared.body());
        HttpResponse<String> a;
        HttpResponse<String> b;
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.CompletableFuture<HttpResponse<String>> first;
            java.util.concurrent.CompletableFuture<HttpResponse<String>> second;
            AutoCloseable held = holdAuditLock();
            try {
                first = java.util.concurrent.CompletableFuture.supplyAsync(() -> quietly(() ->
                        confirmLift(operator, operationId, "복구 광고 전역", key)), executor);
                awaitAdvisoryWaiters(1);
                second = java.util.concurrent.CompletableFuture.supplyAsync(() -> quietly(() ->
                        confirmLift(operator, operationId, "복구 광고 전역", key)), executor);
                awaitRowLockWaiter();
            } finally {
                held.close();
            }
            a = first.get();
            b = second.get();
        }
        assertThat(List.of(a.statusCode(), b.statusCode())).as(a.body() + " / " + b.body()).containsExactly(200, 200);
        Map<String, Object> left = topLevelFieldsOf(a.body());
        Map<String, Object> right = topLevelFieldsOf(b.body());
        assertThat(right.get("restrictionId")).isEqualTo(left.get("restrictionId"));
        assertThat(right.get("liftedAt")).isEqualTo(left.get("liftedAt"));
        // 재생 쪽은 smoke 결말이 원장에 아직 없으면 PENDING이다 — 통과로 지어내지 않는다.
        assertThat(List.of(mapOf(left.get("smoke")).get("status"), mapOf(right.get("smoke")).get("status")))
                .contains("PASSED").allSatisfy(status -> assertThat(status).isIn("PASSED", "PENDING"));
        assertThat(decisionsOfOperation(operationId)).containsExactly("REQUESTED", "COMPLETED", "APPROVED:SMOKE_PASSED");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction WHERE lifted_by_operation_id = :o")
                .param("o", operationId).query(Integer.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ smoke

    /** smoke 훅의 판독 하나. 해제 smoke만 제외 집합 없이 후보 전체를 읽는다(미리보기·기대는 해제 행을 뺀다). */
    private void hookSmoke(java.util.function.Function<List<ActiveRestriction>, List<ActiveRestriction>> answer) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ActiveRestriction> real = (List<ActiveRestriction>) invocation.callRealMethod();
            Set<?> excluding = invocation.getArgument(2);
            return excluding.isEmpty() ? answer.apply(real) : real;
        }).when(this.restrictions).matching(any(), any(), any());
    }

    /**
     * 해제된 제한이 계속 보고되면 같은 범위의 새 제한(원 제한을 가리키고 출처를 잇는다)과 FAILED를 남긴다. 신호가 만든
     * 제한이면 되돌림 행도 자동 정지이고, 목록의 신호 수·수동 검증 표시·다음 해제의 계보는 원 제한의 신호까지 센다.
     */
    @Test
    void smoke가_해제된_제한을_계속_보면_계보를_이은_새_제한과_FAILED를_남기고_REVERTED다() throws Exception {
        UUID user = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        UUID restrictionId = this.signals.raise(unknownEnum(user, connection)).decisions().getFirst().restrictionId();
        Signed operator = operator("lift-smoke", "command");
        String key = key("lift-smoke");
        var prepared = prepareLift(operator, restrictionId.toString(), CONDITIONS, key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        String operationId = operationIdIn(prepared.body());

        // 시험 훅: evaluator가 해제된 제한을 계속 후보로 보고한다.
        AtomicReference<@Nullable UUID> stale = new AtomicReference<>(restrictionId);
        hookSmoke(real -> {
            UUID reported = stale.get();
            if (reported == null) {
                return real;
            }
            List<ActiveRestriction> with = new ArrayList<>(real);
            with.add(new ActiveRestriction(reported, RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION,
                    connection.toString(), false, Instant.now(), null, null, RestrictionOrigin.SIGNAL, 1, null));
            return with;
        });

        var confirmed = confirmLift(operator, operationId, phraseIn(prepared.body()), key);
        stale.set(null);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "REVERTED")
                .containsEntry("reopenedStages", List.of());
        // 해제는 되돌리지 않았다 — 해제된 행 + 같은 범위의 새 행(원 제한을 가리키고 출처를 잇는다, 같은 operation).
        assertThat(jdbc().sql("SELECT (effective_until IS NOT NULL) || '|' || lifted_by_operation_id "
                        + "FROM operations_restriction WHERE restriction_id = :r").param("r", restrictionId)
                .query(String.class).single()).isEqualTo("true|" + operationId);
        assertThat(jdbc().sql("SELECT feature || '|' || scope || '|' || target || '|' || target_user_id || '|' "
                        + "|| operation_id || '|' || origin || '|' || reinstated_from FROM operations_restriction "
                        + "WHERE effective_until IS NULL").query(String.class).single())
                .isEqualTo("SYNC|PROVIDER_CONNECTION|" + connection + "|" + user + "|" + operationId + "|SIGNAL|"
                        + restrictionId);
        assertThat(restrictionCount()).isEqualTo(2);
        assertThat(decisionsOfOperation(operationId)).containsExactly("REQUESTED", "COMPLETED", "FAILED:SMOKE_FAILED");
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withConnection(connection))).isTrue();
        // 신호 행은 고쳐지지 않는다.
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal WHERE restriction_id = :r")
                .param("r", restrictionId).query(Integer.class).single()).isEqualTo(1);

        // 목록: 되돌림 행은 자동 정지·수동 검증 필요이고 신호 수는 원 제한의 계보까지 센다. 사유는 해제 원인이 아니다.
        Signed rotated = after(operator, confirmed);
        var items = itemsIn(restrictionsGet(rotated).body(), "items");
        assertThat(items).singleElement().satisfies(item -> assertThat(item).containsEntry("origin", "SIGNAL")
                .containsEntry("linkedSignals", 1).containsEntry("requiresVerification", true)
                .containsEntry("reinstated", true).containsEntry("reason", "SMOKE_REVERTED"));

        // 재전송도 되돌림을 말한다(원장의 smoke 행에서 읽는다).
        var replayed = confirmLift(rotated, operationId, phraseIn(prepared.body()), key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(replayed.body()).get("smoke"))).containsEntry("status", "REVERTED");

        // 다음 해제의 신호 계보도 사슬을 본다: 되돌림 행에 새 신호가 연결되면 계보는 둘이다.
        UUID reinstated = UUID.fromString((String) items.getFirst().get("restrictionId"));
        Signed again = reauthenticated(rotated);
        var next = prepareLift(again, reinstated.toString(), CONDITIONS, key("lift-smoke-next"));
        assertThat(mapOf(topLevelFieldsOf(next.body()).get("impact"))).containsEntry("linkedSignals", 1);
        this.signals.raise(unknownEnum(user, connection));
        var linked = prepareLift(again, reinstated.toString(), CONDITIONS, key("lift-smoke-linked"));
        assertThat(mapOf(topLevelFieldsOf(linked.body()).get("impact"))).containsEntry("linkedSignals", 2);
    }

    /** 남는 제한이 열려 있는데 smoke 시점에 막지 못하면 실패다. */
    @Test
    void 남는_제한이_열려_있는데_막지_못하면_REVERTED다() throws Exception {
        UUID lifted = restrictGlobally(RestrictionFeature.SYNC);
        UUID remaining = restrictGlobally(RestrictionFeature.CALCULATION_DISPLAY);
        Signed operator = operator("lift-remaining-open", "command");
        String key = key("lift-remaining-open");
        var prepared = prepareLift(operator, lifted.toString(), CONDITIONS, key);
        assertThat(mapOf(topLevelFieldsOf(prepared.body()).get("impact")).get("remainingRestrictions"))
                .isEqualTo(List.of(Map.of("feature", "CALCULATION_DISPLAY", "scope", "GLOBAL", "origin", "OPERATOR")));
        hookSmoke(real -> real.stream().filter(found -> !found.restrictionId().equals(remaining)).toList());
        var confirmed = confirmLift(operator, operationIdIn(prepared.body()), "복구 동기화 전역", key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "REVERTED");
        assertThat(restrictionCount()).isEqualTo(3);
    }

    /** 남는 제한이 그사이 정상적으로 닫혔으면(다른 운영자의 해제) 실패로 세지 않는다. */
    @Test
    void 남는_제한이_그사이_정상_해제됐으면_PASSED다() throws Exception {
        UUID lifted = restrictGlobally(RestrictionFeature.SYNC);
        UUID remaining = restrictGlobally(RestrictionFeature.CALCULATION_DISPLAY);
        Signed operator = operator("lift-remaining-closed", "command");
        String key = key("lift-remaining-closed");
        var prepared = prepareLift(operator, lifted.toString(), CONDITIONS, key);
        hookSmoke(real -> {
            // smoke가 읽기 직전에 다른 해제가 커밋됐다.
            jdbc().sql("UPDATE operations_restriction SET effective_until = statement_timestamp() "
                    + "WHERE restriction_id = :r AND effective_until IS NULL").param("r", remaining).update();
            return real.stream().filter(found -> !found.restrictionId().equals(remaining)).toList();
        });
        var confirmed = confirmLift(operator, operationIdIn(prepared.body()), "복구 동기화 전역", key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "PASSED");
        assertThat(openRestrictions()).isEmpty();
        assertThat(decisionsOfOperation(operationIdIn(prepared.body())))
                .containsExactly("REQUESTED", "COMPLETED", "APPROVED:SMOKE_PASSED");
    }

    /** 되돌릴 대상에 이미 활성 제한이 생겼으면 새 행 없이 FAILED만 쓴다 — 이미 막혀 있다. */
    @Test
    void 되돌릴_대상이_이미_막혀_있으면_새_행_없이_FAILED만_남긴다() throws Exception {
        UUID lifted = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("lift-already", "command");
        String key = key("lift-already");
        var prepared = prepareLift(operator, lifted.toString(), CONDITIONS, key);
        AtomicReference<@Nullable UUID> raced = new AtomicReference<>();
        hookSmoke(real -> {
            if (raced.get() == null) {
                raced.set(restrictGlobally(RestrictionFeature.ADVERTISING));
            }
            List<ActiveRestriction> with = new ArrayList<>(real);
            with.add(new ActiveRestriction(lifted, RestrictionFeature.ADVERTISING, RestrictionScope.GLOBAL, "ALL", false,
                    Instant.now(), null, null, RestrictionOrigin.OPERATOR, 0, null));
            return with;
        });
        var confirmed = confirmLift(operator, operationIdIn(prepared.body()), "복구 광고 전역", key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(confirmed.body()).get("smoke"))).containsEntry("status", "REVERTED");
        assertThat(restrictionCount()).as("해제된 행 + 그사이 생긴 행").isEqualTo(2);
        assertThat(openRestrictions()).containsExactly(raced.get());
        assertThat(decisionsOfOperation(operationIdIn(prepared.body())))
                .containsExactly("REQUESTED", "COMPLETED", "FAILED:SMOKE_FAILED");
    }

    /** 되돌림 쓰기가 실패하면 500이고, 같은 키의 재전송은 PASSED가 아니라 PENDING이다. */
    @Test
    void 되돌림_쓰기가_실패하면_500이고_재전송은_PENDING이다() throws Exception {
        UUID lifted = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("lift-revert-fails", "command");
        String key = key("lift-revert-fails");
        var prepared = prepareLift(operator, lifted.toString(), CONDITIONS, key);
        String operationId = operationIdIn(prepared.body());
        hookSmoke(real -> {
            List<ActiveRestriction> with = new ArrayList<>(real);
            with.add(new ActiveRestriction(lifted, RestrictionFeature.ADVERTISING, RestrictionScope.GLOBAL, "ALL", false,
                    Instant.now(), null, null, RestrictionOrigin.OPERATOR, 0, null));
            return with;
        });
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("시험 장애"))
                .when(this.commands).revertLift(any(), any(), any());

        var failed = confirmLift(operator, operationId, "복구 광고 전역", key);
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(500);
        assertThat(decisionsOfOperation(operationId)).containsExactly("REQUESTED", "COMPLETED");
        var replayed = confirmLift(after(operator, failed), operationId, "복구 광고 전역", key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(mapOf(topLevelFieldsOf(replayed.body()).get("smoke"))).containsEntry("status", "PENDING")
                .containsEntry("reopenedStages", List.of()).containsEntry("stillBlockedStages", List.of());
    }

    // ------------------------------------------------------------------ 도구

    private static String liftBody(String target, @Nullable String reason, @Nullable String verification,
            @Nullable String evidenceRef, @Nullable String deployVersion, @Nullable List<String> conditions) {
        List<String> fields = new ArrayList<>();
        fields.add("\"target\":\"" + target + "\"");
        if (reason != null) {
            fields.add("\"reason\":\"" + reason + "\"");
        }
        if (verification != null) {
            fields.add("\"verification\":\"" + verification + "\"");
        }
        if (evidenceRef != null) {
            fields.add("\"evidenceRef\":\"" + evidenceRef + "\"");
        }
        if (deployVersion != null) {
            fields.add("\"deployVersion\":\"" + deployVersion + "\"");
        }
        if (conditions != null) {
            fields.add("\"conditions\":[" + String.join(",", conditions.stream().map(c -> "\"" + c + "\"").toList())
                    + "]");
        }
        return "{" + String.join(",", fields) + "}";
    }

    private HttpResponse<String> prepare(Signed signed, String body, String key) throws Exception {
        return sendJson("POST", commandPath(LIFT), body, signed.cookie(), headers(signed.csrf(), key));
    }

    private HttpResponse<String> prepareLift(Signed signed, String target, List<String> conditions, String key)
            throws Exception {
        return prepare(signed, liftBody(target, "원인 계산 오류 수정", "영향 범위 재현 시험 통과", "INC-42 회귀 기록",
                "1.4.2+build.7", conditions), key);
    }

    private HttpResponse<String> confirmLift(Signed signed, String operationId, String confirmation, String key)
            throws Exception {
        return confirmRestriction(signed, LIFT, operationId, confirmation, key);
    }

    /** 그 operation의 결과·code, 순서대로({@code APPROVED:SMOKE_PASSED}). */
    private List<String> decisionsOfOperation(String operationId) {
        return jdbc().sql("SELECT outcome || COALESCE(':' || (details->>'code'), '') FROM operations_audit_event "
                        + "WHERE operation_id = :o ORDER BY sequence").param("o", operationId).query(String.class).list();
    }

    /** 문자열 연결과 공백·줄바꿈을 정규화한 소스. 줄을 나눈 SQL을 한 문장으로 비교한다. */
    private static String normalized(String source) {
        return source.replaceAll("\"\\s*\\+\\s*\"", "").replaceAll("\\s+", " ");
    }

    private static void count(Pattern pattern, String source, String name, List<String> into) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            into.add(name);
        }
    }

    private interface Call {
        HttpResponse<String> run() throws Exception;
    }

    private static HttpResponse<String> quietly(Call call) {
        try {
            return call.run();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(topLevelFieldsOf(response.body())).containsEntry("code", code);
    }

    private static UUID restrictionIdIn(HttpResponse<String> confirmed) {
        return UUID.fromString((String) topLevelFieldsOf(confirmed.body()).get("restrictionId"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(@Nullable Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private List<UUID> openRestrictions() {
        return jdbc().sql("SELECT restriction_id FROM operations_restriction WHERE effective_until IS NULL "
                + "ORDER BY effective_from, restriction_id").query(UUID.class).list();
    }

    private Map<String, Object> ledgerDetails(String operationId, String outcome) {
        String json = jdbc().sql("SELECT CAST(details AS text) FROM operations_audit_event WHERE operation_id = :o "
                + "AND outcome = :c").param("o", operationId).param("c", outcome).query(String.class).single();
        return topLevelFieldsOf(json);
    }

    private String ledgerScopeOf(String operationId) {
        return jdbc().sql("SELECT DISTINCT scope FROM operations_audit_event WHERE operation_id = :o")
                .param("o", operationId).query(String.class).single();
    }

    private List<String> rowsOf(List<UUID> ids) {
        return ids.stream().map(id -> jdbc().sql("SELECT feature || '|' || scope || '|' || target || '|' "
                        + "|| COALESCE(CAST(effective_until AS text), '-') || '|' || effective_from "
                        + "FROM operations_restriction WHERE restriction_id = :r")
                .param("r", id).query(String.class).single()).toList();
    }

    /** 원장 어느 열에도 그 글자가 없다. */
    private void assertLedgerHasNo(String raw) {
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE CAST(row_to_json(operations_audit_event) "
                        + "AS text) LIKE :raw").param("raw", "%" + raw + "%").query(Long.class).single())
                .as(raw).isZero();
    }

    private static Signal unknownEnum(UUID user, UUID connection) {
        return new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM, RestrictionSubject.none().withConnection(connection),
                user, Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()),
                null);
    }
}
