package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevBybitStub.Scenario;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.2 AC-1: 허용 조합·대상·중복·쓰기 key의 개별 거절, 그리고 evaluator의 구체 우선.
 *
 * <p>조합(기능 × scope)은 서버가 검증하고 다른 조합은 {@code UNSUPPORTED_SCOPE}다. 대상이 없거나 상태가 맞지 않으면
 * {@code TARGET_INVALID}, 같은 기능·scope·target의 활성 제한이 있으면 {@code ALREADY_RESTRICTED}(409)다. 거절도 감사
 * 사건이지만 원장에 원식별자를 남기지 않는다.
 */
class RestrictionScopeTest extends OperatorTestBase {

    @Autowired
    private OperationalRestrictionApi restrictions;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "restrict-new-connection, PLAN",
        "restrict-new-connection, PROVIDER_CONNECTION",
        "restrict-advertising, PORTFOLIO",
        "restrict-sync, CALCULATION_POLICY_VERSION",
        "restrict-calculation-display, PROVIDER_CONNECTION",
        "restrict-sync, NOPE",
        "restrict-sync, plan"
    })
    void 허용되지_않은_조합은_UNSUPPORTED_SCOPE이고_원식별자를_남기지_않는다(String command, String scope)
            throws Exception {
        Signed operator = operator("scope-unsupported", "command");
        String target = UUID.randomUUID().toString();
        var refused = prepareRestriction(operator, command, scope, target, "사유", key("scope-unsupported"));
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "UNSUPPORTED_SCOPE");
        assertThat(restrictionCount()).isZero();
        assertThat(decisionsOf(operator.user())).containsExactly("DENIED:UNSUPPORTED_SCOPE");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE target LIKE :t")
                .param("t", "%" + target + "%").query(Long.class).single()).isZero();
    }

    @Test
    void 대상이_없거나_형식이나_상태가_맞지_않으면_TARGET_INVALID다() throws Exception {
        UUID owner = connect(Scenario.UTA2);
        publishedPlan(owner, "scope-target-" + UUID.randomUUID(), null);
        UUID plan = ready(owner).planId();
        UUID connection = jdbc().sql("SELECT connection_id FROM source_connection WHERE user_id = :u")
                .param("u", owner).query(UUID.class).single();
        Signed operator = operator("scope-target", "command");

        List<String[]> invalid = List.of(
                new String[] {OperatorCommandApi.RESTRICT_SYNC, "GLOBAL", "EVERYTHING"},
                new String[] {OperatorCommandApi.RESTRICT_SYNC, "PLAN", UUID.randomUUID().toString()},
                new String[] {OperatorCommandApi.RESTRICT_SYNC, "PLAN", "not-a-plan"},
                new String[] {OperatorCommandApi.RESTRICT_SYNC, "PORTFOLIO", UUID.randomUUID().toString()},
                new String[] {OperatorCommandApi.RESTRICT_SYNC, "PROVIDER_CONNECTION", UUID.randomUUID().toString()},
                new String[] {OperatorCommandApi.RESTRICT_CALCULATION_DISPLAY, "CALCULATION_POLICY_VERSION", "CALC-9.9"},
                new String[] {OperatorCommandApi.RESTRICT_CALCULATION_DISPLAY, "CALCULATION_POLICY_VERSION", "calc"});
        for (String[] request : invalid) {
            var refused = prepareRestriction(operator, request[0], request[1], request[2], "사유",
                    key("scope-target"));
            assertThat(refused.statusCode()).as(String.join(" ", request)).isEqualTo(422);
            assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "TARGET_INVALID");
        }

        // 끝난 계획: 동기화 제한은 받지 않고(끝난 계획은 동기화하지 않는다) 계산 표시 제한은 받는다.
        jdbc().sql("UPDATE plan_definition SET status = 'COMPLETED' WHERE plan_id = :p").param("p", plan).update();
        var ended = prepareRestriction(operator, OperatorCommandApi.RESTRICT_SYNC, "PLAN", plan.toString(), "사유",
                key("scope-ended"));
        assertThat(topLevelFieldsOf(ended.body())).containsEntry("code", "TARGET_INVALID");
        var display = prepareRestriction(operator, OperatorCommandApi.RESTRICT_CALCULATION_DISPLAY, "PLAN",
                plan.toString(), "사유", key("scope-ended-display"));
        assertThat(display.statusCode()).as(display.body()).isEqualTo(200);
        jdbc().sql("UPDATE plan_definition SET status = 'ACTIVE' WHERE plan_id = :p").param("p", plan).update();

        // 해제된 연결은 대상이 아니다.
        revokeConnectionOf(owner);
        var revoked = prepareRestriction(operator, OperatorCommandApi.RESTRICT_SYNC, "PROVIDER_CONNECTION",
                connection.toString(), "사유", key("scope-revoked"));
        assertThat(topLevelFieldsOf(revoked.body())).containsEntry("code", "TARGET_INVALID");
        assertThat(restrictionCount()).isZero();

        // 거절 행의 target: 사용자 귀속 자리의 UUID는 digest, 그 밖은 형식이 맞을 때만 원문이다.
        assertThat(jdbc().sql("SELECT DISTINCT target FROM operations_audit_event WHERE actor_user_id = :u "
                        + "AND scope IN ('SYNC:PLAN', 'SYNC:PORTFOLIO', 'SYNC:PROVIDER_CONNECTION', "
                        + "'CALCULATION_DISPLAY:PLAN')").param("u", operator.user()).query(String.class).list())
                .allSatisfy(target -> assertThat(target).matches("[0-9a-f]{64}|-"));
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE target IN (:raw)")
                .param("raw", List.of(plan.toString(), connection.toString())).query(Long.class).single()).isZero();
    }

    @Test
    void 같은_활성_제한이_있으면_ALREADY_RESTRICTED이고_범위가_다르면_받는다() throws Exception {
        Signed operator = operator("scope-duplicate", "command");
        UUID existing = restrictGlobally(RestrictionFeature.SYNC);
        var duplicate = prepareRestriction(operator, OperatorCommandApi.RESTRICT_SYNC, "GLOBAL", "ALL", "사유",
                key("scope-duplicate"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(duplicate.body())).containsEntry("code", "ALREADY_RESTRICTED");
        assertThat(decisionsOf(operator.user())).containsExactly("DENIED:ALREADY_RESTRICTED");

        // 다른 기능의 전역 제한은 겹치지 않는다. 미리보기의 남는 기능에서 이미 멈춘 동기화가 빠진다.
        var other = prepareRestriction(operator, OperatorCommandApi.RESTRICT_NEW_CONNECTION, "GLOBAL", "ALL", "사유",
                key("scope-other"));
        assertThat(other.statusCode()).as(other.body()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var impact = (java.util.Map<String, Object>) topLevelFieldsOf(other.body()).get("impact");
        assertThat(impact.get("remainingFeatures")).isEqualTo(List.of("CALCULATION_DISPLAY", "ADVERTISING"));
        assertThat(impact.get("blockedStages")).isEqualTo(List.of("CONNECTION_CREATE"));
        assertThat(phraseIn(other.body())).isEqualTo("신규 연결 제한 전역");

        // 끝난(효력이 지난) 제한은 중복이 아니다.
        jdbc().sql("UPDATE operations_restriction SET effective_until = statement_timestamp() WHERE restriction_id = :r")
                .param("r", existing).update();
        var after = prepareRestriction(operator, OperatorCommandApi.RESTRICT_SYNC, "GLOBAL", "ALL", "사유",
                key("scope-after"));
        assertThat(after.statusCode()).as(after.body()).isEqualTo(200);
    }

    /** 쓰기 권한 key는 그 연결 시도만 거절한다 — 제한을 만들지 않고 다른 사용자의 연결은 열려 있다. */
    @Test
    void 쓰기_권한_key는_그_시도만_거절하고_제한을_만들지_않는다() throws Exception {
        Signed first = reauthenticated(signedIn("scope-write-key"));
        var refused = connectBybitRoundTrip(first.cookie(), first.csrf(),
                BybitStubConfiguration.apiKey(Scenario.WRITE_PERMISSION, "962001"),
                BybitStubConfiguration.newSecret());
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(restrictionCount()).isZero();
        assertThat(this.restrictions.blocks(EnumSet.allOf(RestrictionFeature.class), RestrictionSubject.none()))
                .isFalse();

        Signed second = reauthenticated(signedIn("scope-write-key-other"));
        var connected = connectBybitRoundTrip(second.cookie(), second.csrf(),
                BybitStubConfiguration.apiKey(Scenario.UTA2, "963001"),
                BybitStubConfiguration.newSecret());
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
    }

    /**
     * 구체 우선은 보고의 순서다 — 차단은 합집합이고, effective 제한은 일치하는 행 가운데 가장 구체적인 것이다. 넘기지 않은
     * 차원의 구체 제한은 적용되지 않는다.
     */
    @Test
    void evaluator는_합집합으로_막고_가장_구체적인_행을_보고한다() {
        UUID plan = UUID.randomUUID();
        UUID portfolio = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        restrictGlobally(RestrictionFeature.ADVERTISING);
        restrict(RestrictionFeature.SYNC, RestrictionScope.PORTFOLIO, portfolio.toString());
        UUID planRow = restrict(RestrictionFeature.SYNC, RestrictionScope.PLAN, plan.toString());
        restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.CALCULATION_POLICY_VERSION, "CALC-1.0");

        var full = RestrictionSubject.none().withPlan(plan).withPortfolio(portfolio).withConnection(connection)
                .withCalculationPolicy("CALC-1.0");
        var effective = this.restrictions.evaluate(EnumSet.of(RestrictionFeature.SYNC), full).orElseThrow();
        assertThat(effective.scope()).isEqualTo(RestrictionScope.PLAN);
        assertThat(effective.restrictionId()).isEqualTo(planRow);
        // 계획 차원을 넘기지 않으면 portfolio 제한이 보고된다(차단은 그대로다).
        assertThat(this.restrictions.evaluate(EnumSet.of(RestrictionFeature.SYNC), full.withPlan(null))
                .orElseThrow().scope()).isEqualTo(RestrictionScope.PORTFOLIO);
        // 대상 차원이 없으면 구체 제한은 적용되지 않는다.
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC), RestrictionSubject.none()))
                .isFalse();
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withConnection(connection))).isFalse();
        // 기능은 독립이다: 계산 표시는 정책 버전만, 광고는 전역으로 막힌다.
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.CALCULATION_DISPLAY), full)).isTrue();
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.CALCULATION_DISPLAY),
                full.withCalculationPolicy(null))).isFalse();
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.NEW_CONNECTION), full)).isFalse();
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.ADVERTISING), RestrictionSubject.none()))
                .isTrue();
        // 둘 이상의 기능을 물으면 어느 하나라도 막히면 막힌다.
        assertThat(this.restrictions.evaluate(EnumSet.of(RestrictionFeature.NEW_CONNECTION,
                RestrictionFeature.CALCULATION_DISPLAY), full).orElseThrow().feature())
                .isEqualTo(RestrictionFeature.CALCULATION_DISPLAY);
        // 목록은 구체 우선이다.
        assertThat(this.restrictions.active()).extracting(OperationalRestrictionApi.ActiveRestriction::scope)
                .containsExactly(RestrictionScope.PLAN, RestrictionScope.PORTFOLIO,
                        RestrictionScope.CALCULATION_POLICY_VERSION, RestrictionScope.GLOBAL);
    }
}
