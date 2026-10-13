package com.bokrilog;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperatorCommandApi.LiftExpectation;
import com.bokrilog.operations.OperatorCommandApi.LiftTarget;
import com.bokrilog.operations.OperatorCommandApi.Restriction;
import com.bokrilog.operations.OperatorCommandApi.TargetCheck;
import com.bokrilog.operations.OperatorCommandEnvironment.LiftImpact;
import com.bokrilog.operations.OperatorCommandEnvironment.Remaining;
import com.bokrilog.operations.OperatorCommandEnvironment.RestrictionImpact;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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
 *
 * <p><b>해제(Story 3.4).</b> 해제 대상이 막던 대상 차원({@link #subjectOf})과 그 차원을 해제 뒤에도 막는 다른 활성 제한
 * ({@link #remaining})을 evaluator와 같은 술어({@link OperationalRestrictionApi#matching})로 센다. 미리보기·확정의 기대·
 * smoke가 모두 이 두 메서드를 지난다 — 어느 하나가 다른 규칙으로 세지 않는다.
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

    /** 영향 미리보기. 대상 확인을 지난 제한에만 부른다. */
    RestrictionImpact impact(Restriction restriction, TargetCheck.Valid valid) {
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
        return new RestrictionImpact(stages(feature, scope), affectedPlans, affectedConnections,
                remaining(feature, scope));
    }

    /**
     * 해제 대상이 막던 대상 차원(Story 3.4). 계획 제한은 그 계획의 portfolio·연결·계산 정책까지 담는다 — 계획의 검사
     * 지점이 그 차원을 함께 넘기므로 더 넓은 portfolio·정책 제한도 같은 단계를 막는다. 비식별된 target은 원식별자가 없어
     * 차원이 없다(전역만 적용된다 — evaluator도 비식별 행을 고르지 않는다).
     */
    RestrictionSubject subjectOf(LiftTarget target) {
        if (target.targetErased() || target.scope() == RestrictionScope.GLOBAL) {
            return RestrictionSubject.none();
        }
        return switch (target.scope()) {
            case PLAN -> {
                UUID plan = UUID.fromString(target.target());
                yield this.plans.planTarget(plan)
                        .map(found -> new RestrictionSubject(found.planId(), found.portfolioId(), found.connectionId(),
                                found.calculationPolicy()))
                        .orElseGet(() -> RestrictionSubject.none().withPlan(plan));
            }
            case PORTFOLIO -> RestrictionSubject.none().withPortfolio(UUID.fromString(target.target()));
            case PROVIDER_CONNECTION -> RestrictionSubject.none().withConnection(UUID.fromString(target.target()));
            case CALCULATION_POLICY_VERSION -> RestrictionSubject.none().withCalculationPolicy(target.target());
            case GLOBAL -> RestrictionSubject.none();
        };
    }

    /**
     * 해제 뒤에도 그 대상의 단계를 막는 다른 활성 제한(Story 3.4). 해제한 제한의 단계를 막을 수 있는 기능만 묻는다 — 신규
     * 연결 해제에 동기화 제한이 "남는 제한"으로 섞이지 않는다. 술어와 순서는 evaluator 그대로다(구체 우선).
     */
    List<ActiveRestriction> remaining(UUID liftedRestrictionId, LiftTarget target) {
        List<String> lifted = stages(target.feature(), target.scope());
        Set<RestrictionFeature> features = EnumSet.noneOf(RestrictionFeature.class);
        for (RestrictionFeature candidate : RestrictionFeature.values()) {
            if (stages(candidate, RestrictionScope.GLOBAL).stream().anyMatch(lifted::contains)) {
                features.add(candidate);
            }
        }
        return this.restrictions.matching(features, subjectOf(target), Set.of(liftedRestrictionId)).stream()
                .filter(other -> stages(other.feature(), other.scope()).stream().anyMatch(lifted::contains))
                .toList();
    }

    /** 해제 뒤 기대하는 단계: 해제한 제한의 단계 가운데 남는 제한이 막지 않는 것과 여전히 막는 것. */
    static LiftExpectation expectation(LiftTarget target, List<ActiveRestriction> remaining) {
        Set<String> blocked = new LinkedHashSet<>();
        for (ActiveRestriction other : remaining) {
            blocked.addAll(stages(other.feature(), other.scope()));
        }
        List<String> reopened = new ArrayList<>();
        List<String> still = new ArrayList<>();
        for (String stage : stages(target.feature(), target.scope())) {
            (blocked.contains(stage) ? still : reopened).add(stage);
        }
        return new LiftExpectation(List.copyOf(reopened), List.copyOf(still));
    }

    /** 해제의 영향 미리보기. 준비가 열린 제한에만 부른다. */
    LiftImpact liftImpact(UUID liftedRestrictionId, LiftTarget target) {
        List<ActiveRestriction> others = remaining(liftedRestrictionId, target);
        return new LiftImpact(expectation(target, others).reopenedStages(),
                others.stream().map(other -> new Remaining(other.feature().name(), other.scope().name(),
                        other.origin().name())).toList(),
                target.linkedSignals(), target.latestSignalAt());
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
