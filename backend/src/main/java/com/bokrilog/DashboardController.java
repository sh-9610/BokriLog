package com.bokrilog;

import com.bokrilog.sync.SyncJobApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The official dashboard (Story 2.8). It replaces Story 2.7's {@code GET /api/plans/active}: a 204 could
 * not tell "no plan" from "result being prepared", and two surfaces would judge deletion, caching and
 * versions in two places.
 *
 * <p>One response is one unit: {@code {state, nextAction, result?}}. {@code NO_PLAN} and {@code PREPARING}
 * carry no financial field. {@code READY} carries the bundle the active plan's pointer cites — the plan
 * terms, the pointer, the calculation version, its six metrics and the three trust axes, each with its
 * state, cause, last normal time, affected metrics and next action as codes. The client never assembles
 * cards from other requests and never judges time.
 *
 * <p>The path takes no identifier: the scope is the session's internal user id alone. Query parameters
 * are ignored (no parameter is bound), so a request carrying another user's plan, snapshot or
 * calculation id answers exactly what the bare request answers, and every sub-path is the same common
 * 404. 403 when the account's own access refuses the read before or after it (its deletion began or
 * finished); 503 {@code DASHBOARD_UNAVAILABLE} when the pointer's bundle does not agree with itself —
 * never a partial bundle. Answers are {@code no-store}.
 *
 * <p>Amounts and ratios are canonical decimal strings. Rounding for display is the client's final step.
 */
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class DashboardController {
    /** The stable machine code of an inconsistent bundle. The client treats it as a failed read. */
    static final String DASHBOARD_UNAVAILABLE="DASHBOARD_UNAVAILABLE";
    static final String MESSAGE_DASHBOARD_UNAVAILABLE=
            "공식 결과를 한 묶음으로 확인하지 못해 보내지 않았습니다. 잠시 후 다시 불러와 주세요.";

    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DashboardController.class);

    private final SyncJobApi jobs;
    DashboardController(SyncJobApi jobs) { this.jobs=jobs; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DashboardView(String state,String nextAction,@Nullable ResultView result) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MetricView(String metric,@Nullable String value,String nature,@Nullable String unavailableReason) {}
    record ConditionsView(String principal,String startAt,String endAt,String period,String targetRate) {}
    /** Freshness vouches for the measurement; both times it is judged from keep their own names. */
    record FreshnessView(String state,String cause,String lastNormalAt,String measuredAt,
            String cashflowVerifiedThrough,List<String> affectedMetrics,String nextAction) {}
    record ReliabilityView(String state,String cause,String lastNormalAt,List<String> affectedMetrics,
            String nextAction) {}
    /**
     * @param state {@code NOT_COMPUTABLE} when any metric is undefined, otherwise the character
     * @param character {@code OFFICIAL} or {@code ESTIMATED}, kept even when a metric is undefined
     */
    record NatureView(String state,String character,String cause,String lastNormalAt,List<String> affectedMetrics,
            List<String> unavailableMetrics,int estimatedFlowCount,String nextAction) {}
    record TrustView(FreshnessView freshness,ReliabilityView inputReliability,NatureView calculationNature) {}
    record PolicyView(String calculation,String calendar,String display,String price,String implementation) {}
    record ResultView(UUID planId,String planStatus,ConditionsView conditions,long pointerVersion,
            String publishedAt,UUID calculationId,UUID portfolioSnapshotId,UUID basisPortfolioSnapshotId,
            String basisAt,String measuredAt,String cashflowVerifiedThrough,PolicyView policies,String inputHash,
            String resultHash,int flowCount,int estimatedFlowCount,TrustView trust,List<MetricView> metrics) {}

    /**
     * One read, one operational line. The line is written here because only here is the answered status
     * known — the 403 of {@code sendError}, the 503 body, the 200 view, or the 500 of a read that threw.
     * It carries exactly four fields: the outcome, that status, the latency in milliseconds and whether
     * the bundle agreed with itself ({@code unknown} when no bundle was read). No user, correlation id,
     * amount, time or calculation input.
     */
    @GetMapping(path=AuthSecurityConfig.DASHBOARD_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> dashboard(HttpServletRequest request,HttpServletResponse response)
            throws IOException {
        long started=System.nanoTime();
        SyncJobApi.DashboardRead read;
        try {
            read=jobs.dashboard(currentUserId());
        } catch (RuntimeException failure) {
            // The class name at debug only: a message or cause can carry SQL or values.
            if (LOG.isDebugEnabled()) LOG.debug("dashboard read failed: failure={}",failure.getClass().getName());
            ResponseEntity<ApiErrorHandler.ApiError> failed=ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                    .body(new ApiErrorHandler.ApiError(ApiErrorHandler.CODE_INTERNAL_ERROR,
                            ApiErrorHandler.MESSAGE_INTERNAL_ERROR,correlationOf(request)));
            log("ERROR",failed.getStatusCode().value(),started,"unknown");
            return failed;
        }
        ResponseEntity<?> answer=switch (read.dashboard()) {
            case SyncJobApi.Dashboard.NotPermitted denied -> {
                response.sendError(403);
                yield null;
            }
            case SyncJobApi.Dashboard.Unavailable unavailable ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                            .cacheControl(CacheControl.noStore())
                            .body(new ApiErrorHandler.ApiError(DASHBOARD_UNAVAILABLE,MESSAGE_DASHBOARD_UNAVAILABLE,
                                    correlationOf(request)));
            case SyncJobApi.Dashboard.NoPlan none -> ok(new DashboardView("NO_PLAN",none.nextAction(),null));
            case SyncJobApi.Dashboard.Preparing preparing ->
                    ok(new DashboardView("PREPARING",preparing.nextAction(),null));
            case SyncJobApi.Dashboard.Ready ready ->
                    ok(new DashboardView("READY",ready.nextAction(),view(ready.result())));
        };
        String state=switch (read.dashboard()) {
            case SyncJobApi.Dashboard.NotPermitted denied -> "DENIED";
            case SyncJobApi.Dashboard.Unavailable unavailable -> "INCONSISTENT";
            case SyncJobApi.Dashboard.NoPlan none -> "NO_PLAN";
            case SyncJobApi.Dashboard.Preparing preparing -> "PREPARING";
            case SyncJobApi.Dashboard.Ready ready -> "READY";
        };
        String consistent=switch (read.consistency()) {
            case CONSISTENT -> "true";
            case INCONSISTENT -> "false";
            case NOT_READ -> "unknown";
        };
        log(state,answer==null?response.getStatus():answer.getStatusCode().value(),started,consistent);
        return answer;
    }

    private static void log(String state,int status,long started,String consistent) {
        LOG.info("dashboard read: state={} status={} latencyMs={} consistent={}",state,status,
                (System.nanoTime()-started)/1_000_000,consistent);
    }

    private static String correlationOf(HttpServletRequest request) {
        return ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER));
    }

    private static ResponseEntity<DashboardView> ok(DashboardView body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    static ResultView view(SyncJobApi.DashboardResult r) {
        var freshness=r.trust().freshness();
        var reliability=r.trust().inputReliability();
        var nature=r.trust().calculationNature();
        return new ResultView(r.planId(),r.planStatus(),
                new ConditionsView(r.principal(),r.startAt().toString(),r.endAt().toString(),r.period(),r.targetRate()),
                r.pointerVersion(),r.publishedAt().toString(),r.calculationId(),r.portfolioSnapshotId(),
                r.basisPortfolioSnapshotId(),r.basisAt().toString(),r.measuredAt().toString(),
                r.cashflowVerifiedThrough().toString(),
                new PolicyView(r.calculationPolicy(),r.calendarPolicy(),r.displayPolicy(),r.pricePolicy(),
                        r.implementationVersion()),
                r.inputHash(),r.resultHash(),r.flowCount(),r.estimatedFlowCount(),
                new TrustView(
                        new FreshnessView(freshness.state(),freshness.cause(),freshness.lastNormalAt().toString(),
                                r.measuredAt().toString(),r.cashflowVerifiedThrough().toString(),
                                freshness.affectedMetrics(),freshness.nextAction()),
                        new ReliabilityView(reliability.state(),reliability.cause(),
                                reliability.lastNormalAt().toString(),reliability.affectedMetrics(),
                                reliability.nextAction()),
                        new NatureView(nature.axis().state(),nature.character(),nature.axis().cause(),
                                nature.axis().lastNormalAt().toString(),nature.axis().affectedMetrics(),
                                nature.unavailableMetrics(),nature.estimatedFlowCount(),nature.axis().nextAction())),
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
