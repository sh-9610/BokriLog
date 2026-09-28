package com.bokrilog;

import com.bokrilog.sync.SyncJobApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The active plan's official result (Story 2.7).
 *
 * <p>One response from one pointer: the plan terms, the pointer, the calculation version it cites and
 * that version's six metrics with the three trust axes. The client renders it as one unit and never
 * assembles cards from other requests. 200 with the result, 204 without an active plan or pointer,
 * 403 when the account's own access refuses it (its deletion is in progress) — a restriction on a
 * write command does not hide an existing result; always {@code no-store}.
 *
 * <p>Amounts and ratios are canonical decimal strings. Rounding for display is the client's final step.
 * The freshness axis carries the two times it is judged from, each under its own name; there is no
 * separate "last normal" time that would only repeat one of them.
 */
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class PlanResultController {
    private final SyncJobApi jobs;
    PlanResultController(SyncJobApi jobs) { this.jobs=jobs; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MetricView(String metric,@Nullable String value,String nature,@Nullable String unavailableReason) {}
    record ConditionsView(String principal,String startAt,String endAt,String period,String targetRate) {}
    record FreshnessView(String state,String measuredAt,String cashflowVerifiedThrough) {}
    record ReliabilityView(String state) {}
    /**
     * @param state {@code NOT_COMPUTABLE} when any metric is undefined, otherwise the character
     * @param character {@code OFFICIAL} or {@code ESTIMATED}, kept even when a metric is undefined
     */
    record NatureView(String state,String character,List<String> unavailableMetrics,int estimatedFlowCount) {}
    record TrustView(FreshnessView freshness,ReliabilityView inputReliability,NatureView calculationNature) {}
    record PolicyView(String calculation,String calendar,String display,String price,String implementation) {}
    record ResultView(UUID planId,String planStatus,ConditionsView conditions,long pointerVersion,
            String publishedAt,UUID calculationId,UUID portfolioSnapshotId,UUID basisPortfolioSnapshotId,
            String basisAt,String measuredAt,String cashflowVerifiedThrough,PolicyView policies,String inputHash,
            String resultHash,int flowCount,int estimatedFlowCount,TrustView trust,List<MetricView> metrics) {}

    @GetMapping(path=AuthSecurityConfig.ACTIVE_PLAN_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<ResultView> active(HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if (user==null) { response.sendError(403); return null; }
        return switch (jobs.activePlanResult(user)) {
            case SyncJobApi.ActivePlanResult.NotPermitted denied -> {
                response.sendError(403);
                yield null;
            }
            case SyncJobApi.ActivePlanResult.None none ->
                    ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            case SyncJobApi.ActivePlanResult.Found found ->
                    ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(found.result()));
        };
    }

    static ResultView view(SyncJobApi.PlanResult r) {
        List<String> unavailable=r.metrics().stream().filter(m -> m.value()==null)
                .map(SyncJobApi.PlanResultMetric::metric).toList();
        String character=r.estimated()?"ESTIMATED":"OFFICIAL";
        return new ResultView(r.planId(),r.planStatus(),
                new ConditionsView(r.principal(),r.startAt().toString(),r.endAt().toString(),r.period(),r.targetRate()),
                r.pointerVersion(),r.publishedAt().toString(),r.calculationId(),r.portfolioSnapshotId(),
                r.basisPortfolioSnapshotId(),r.basisAt().toString(),r.measuredAt().toString(),
                r.cashflowVerifiedThrough().toString(),
                new PolicyView(r.calculationPolicy(),r.calendarPolicy(),r.displayPolicy(),r.pricePolicy(),
                        r.implementationVersion()),
                r.inputHash(),r.resultHash(),r.flowCount(),r.estimatedFlowCount(),
                new TrustView(new FreshnessView(r.freshness(),r.measuredAt().toString(),
                        r.cashflowVerifiedThrough().toString()),new ReliabilityView(r.inputReliability()),
                        new NatureView(unavailable.isEmpty()?character:"NOT_COMPUTABLE",character,unavailable,
                                r.estimatedFlowCount())),
                r.metrics().stream().map(m -> new MetricView(m.metric(),m.value(),m.nature(),m.unavailableReason()))
                        .toList());
    }

    private static @Nullable UUID currentUserId() {
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
