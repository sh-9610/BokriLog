package com.bokrilog;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperatorCommandApi.Restriction;
import com.bokrilog.operations.OperatorCommandApi.TargetCheck;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;

/**
 * 운영 제한의 대상 확인과 영향 미리보기(Story 3.2 AC-1·AC-2).
 *
 * <p><b>operations는 다른 모듈을 모른다.</b> 계획·portfolio·연결이 있는지, 상태가 맞는지, 누구에게 귀속되는지는 각 모듈의
 * 사실이라 루트의 이 어댑터가 각 모듈 공개 API(id로 찾는 메서드)로 본다. 준비가 한 번, 확정 트랜잭션 안에서 원장 잠금 뒤
 * 한 번 더 부른다 — 그 사이 계획이 끝나거나 연결이 해제됐으면 제한을 만들지 않는다.
 *
 * <p>허용 대상:
 *
 * <ul>
 *   <li>{@code GLOBAL}: 언제나({@code ALL}).
 *   <li>{@code PLAN}: 있는 계획. 동기화 제한은 활성 계획만 — 끝난 계획은 동기화하지 않으므로 그 제한은 아무것도 막지 않는다.
 *       계산 표시 제한은 상태와 무관하다(끝난 계획의 결과도 보인다).
 *   <li>{@code PORTFOLIO}: 있는 portfolio.
 *   <li>{@code PROVIDER_CONNECTION}: 활성 연결.
 *   <li>{@code CALCULATION_POLICY_VERSION}: 이 빌드가 아는 계산 정책 버전({@link PlanCalculator#CALCULATION_POLICY}).
 * </ul>
 *
 * <p>미리보기는 수만 담는다 — 식별자도 금융값도 없다(AC-2·AC-6).
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
class RestrictionTargets {

    /** 막히는 단계의 닫힌 code. 화면이 카피로 잇는다. */
    static final String CONNECTION_CREATE = "CONNECTION_CREATE";

    static final String FIRST_SNAPSHOT = "FIRST_SNAPSHOT";

    static final String INITIAL_COLLECTION = "INITIAL_COLLECTION";

    static final String PLAN_CONDITIONS = "PLAN_CONDITIONS";

    static final String FIRST_PUBLICATION = "FIRST_PUBLICATION";

    static final String NEXT_PLAN_BASELINE = "NEXT_PLAN_BASELINE";

    static final String INCREMENTAL_SYNC = "INCREMENTAL_SYNC";

    static final String INCREMENTAL_PUBLICATION = "INCREMENTAL_PUBLICATION";

    static final String PLAN_END = "PLAN_END";

    static final String DASHBOARD_RESULT = "DASHBOARD_RESULT";

    static final String EVIDENCE = "EVIDENCE";

    static final String ADJUSTMENTS = "ADJUSTMENTS";

    static final String ADVERTISING = "ADVERTISING";

    /**
     * 영향 미리보기.
     *
     * @param blockedStages 막히는 단계 code
     * @param affectedPlans 영향을 받는 계획 수
     * @param affectedConnections 영향을 받는 연결 수
     * @param remainingFeatures 이 제한 뒤에도 전역으로 막히지 않은 기능
     */
    record Impact(List<String> blockedStages, long affectedPlans, long affectedConnections,
            List<String> remainingFeatures) {}

    private final PlanConditionApi plans;

    private final PortfolioMembershipApi portfolios;

    private final SourceConnectionApi sources;

    private final OperationalRestrictionApi restrictions;

    RestrictionTargets(PlanConditionApi plans, PortfolioMembershipApi portfolios, SourceConnectionApi sources,
            OperationalRestrictionApi restrictions) {
        this.plans = plans;
        this.portfolios = portfolios;
        this.sources = sources;
        this.restrictions = restrictions;
    }

    /** 그 제한의 대상이 지금 있고 상태가 맞는가, 그리고 누구에게 귀속되는가. */
    TargetCheck check(Restriction restriction) {
        String target = restriction.target();
        return switch (restriction.scope()) {
            case GLOBAL -> new TargetCheck.Valid(null);
            case CALCULATION_POLICY_VERSION -> PlanCalculator.CALCULATION_POLICY.equals(target)
                    ? new TargetCheck.Valid(null) : new TargetCheck.Invalid();
            case PLAN -> this.plans.planTarget(UUID.fromString(target))
                    .filter(plan -> restriction.feature() != RestrictionFeature.SYNC || "ACTIVE".equals(plan.status()))
                    .<TargetCheck>map(plan -> new TargetCheck.Valid(plan.userId()))
                    .orElseGet(TargetCheck.Invalid::new);
            case PORTFOLIO -> this.portfolios.portfolioTarget(UUID.fromString(target))
                    .<TargetCheck>map(portfolio -> new TargetCheck.Valid(portfolio.userId()))
                    .orElseGet(TargetCheck.Invalid::new);
            case PROVIDER_CONNECTION -> this.sources.connectionTarget(UUID.fromString(target))
                    .filter(connection -> !connection.revoked())
                    .<TargetCheck>map(connection -> new TargetCheck.Valid(connection.userId()))
                    .orElseGet(TargetCheck.Invalid::new);
        };
    }

    /** 같은 기능·scope·target의 효력 중인 제한이 이미 있는가. */
    boolean alreadyRestricted(Restriction restriction) {
        return this.restrictions.isActive(restriction.feature(), restriction.scope(), restriction.target());
    }

    /** 영향 미리보기. 대상 확인을 지난 제한에만 부른다. */
    Impact impact(Restriction restriction, TargetCheck.Valid valid) {
        RestrictionFeature feature = restriction.feature();
        RestrictionScope scope = restriction.scope();
        long affectedPlans;
        long affectedConnections;
        switch (feature) {
            case SYNC -> {
                switch (scope) {
                    case PLAN -> {
                        affectedPlans = 1;
                        affectedConnections = 1;
                    }
                    case PORTFOLIO -> {
                        UUID portfolio = UUID.fromString(restriction.target());
                        affectedPlans = this.plans.countPlans(true, null, portfolio, null);
                        affectedConnections = this.portfolios.portfolioTarget(portfolio)
                                .filter(PortfolioMembershipApi.PortfolioTarget::hasActiveSource).isPresent() ? 1 : 0;
                    }
                    case PROVIDER_CONNECTION -> {
                        // 계획은 소유자의 활성 연결 위에서만 동기화된다 — 그 연결의 소유자의 활성 계획이 영향 범위다.
                        affectedPlans = valid.ownerUserId() == null ? 0
                                : this.plans.countPlans(true, valid.ownerUserId(), null, null);
                        affectedConnections = 1;
                    }
                    default -> {
                        affectedPlans = this.plans.countPlans(true, null, null, null);
                        affectedConnections = this.sources.countActiveConnections();
                    }
                }
            }
            case CALCULATION_DISPLAY -> {
                affectedConnections = 0;
                affectedPlans = switch (scope) {
                    case PLAN -> 1;
                    case PORTFOLIO -> this.plans.countPlans(false, null, UUID.fromString(restriction.target()), null);
                    case CALCULATION_POLICY_VERSION -> this.plans.countPlans(false, null, null, restriction.target());
                    default -> this.plans.countPlans(false, null, null, null);
                };
            }
            default -> {
                // 신규 연결·광고는 이미 있는 계획과 연결을 막지 않는다.
                affectedPlans = 0;
                affectedConnections = 0;
            }
        }
        return new Impact(stages(feature, scope), affectedPlans, affectedConnections, remaining(feature, scope));
    }

    /**
     * 막히는 단계 — 각 검사 지점이 실제로 보는 기능과 대상 차원 그대로다.
     *
     * <ul>
     *   <li>동기화: 첫 스냅샷·초기 수집·후속 계획 기준값(사용자의 지금 portfolio·연결), 계획 조건·첫 발행(그 intent의
     *       portfolio·연결), 증분 동기화와 그 발행·계획 종료(계획·portfolio·연결). 계획 하나의 제한은 계획이 있는 뒤의
     *       세 단계만 막는다.
     *   <li>계산 표시: 계획 조건·첫 발행(portfolio·계산 정책), 증분 발행·계획 종료·대시보드 결과·근거·조정 이력(계획·
     *       portfolio·계산 정책). 계획 하나의 제한은 계획이 있는 뒤의 다섯 단계만 막는다.
     * </ul>
     */
    static List<String> stages(RestrictionFeature feature, RestrictionScope scope) {
        boolean planOnly = scope == RestrictionScope.PLAN;
        return switch (feature) {
            case NEW_CONNECTION -> List.of(CONNECTION_CREATE);
            case SYNC -> planOnly ? List.of(INCREMENTAL_SYNC, INCREMENTAL_PUBLICATION, PLAN_END)
                    : List.of(FIRST_SNAPSHOT, INITIAL_COLLECTION, PLAN_CONDITIONS, FIRST_PUBLICATION,
                            NEXT_PLAN_BASELINE, INCREMENTAL_SYNC, INCREMENTAL_PUBLICATION, PLAN_END);
            case CALCULATION_DISPLAY -> planOnly
                    ? List.of(INCREMENTAL_PUBLICATION, PLAN_END, DASHBOARD_RESULT, EVIDENCE, ADJUSTMENTS)
                    : List.of(PLAN_CONDITIONS, FIRST_PUBLICATION, INCREMENTAL_PUBLICATION, PLAN_END, DASHBOARD_RESULT,
                            EVIDENCE, ADJUSTMENTS);
            case ADVERTISING -> List.of(ADVERTISING);
        };
    }

    /** 이 제한 뒤에도 전역으로 막히지 않은 기능. 범위를 좁힌 제한은 그 기능을 다른 대상에 남긴다. */
    private List<String> remaining(RestrictionFeature feature, RestrictionScope scope) {
        Set<RestrictionFeature> globallyStopped = EnumSet.noneOf(RestrictionFeature.class);
        for (var active : this.restrictions.active()) {
            if (active.scope() == RestrictionScope.GLOBAL) {
                globallyStopped.add(active.feature());
            }
        }
        if (scope == RestrictionScope.GLOBAL) {
            globallyStopped.add(feature);
        }
        List<String> remaining = new ArrayList<>();
        for (RestrictionFeature candidate : RestrictionFeature.values()) {
            if (!globallyStopped.contains(candidate)) {
                remaining.add(candidate.name());
            }
        }
        return List.copyOf(remaining);
    }
}
