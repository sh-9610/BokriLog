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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * calculation id answers exactly what the bare request answers, and every sub-path but one is the same
 * common 404. 403 when the account's own access refuses the read before or after it (its deletion began
 * or finished); 503 {@code DASHBOARD_UNAVAILABLE} when the pointer's bundle does not agree with itself —
 * never a partial bundle. Answers are {@code no-store}.
 *
 * <p>Two sub-paths have handlers, and they are the only dashboard surfaces that receive identifiers — they
 * only judge them. {@code GET /api/dashboard/evidence} (Story 2.9) is the calculation evidence of the result
 * the screen shows: the three values of the shown pointer ({@code planId}, {@code pointerVersion},
 * {@code calculationId}) answer 200 when they are the user's current official pointer, and anything else
 * (another user's, an earlier version, an unknown, malformed, repeated or missing value, no active plan) is
 * the same common 404 as a missing route, so nothing can be explored with them. 503
 * {@code EVIDENCE_UNAVAILABLE} when the current pointer's lineage does not agree with itself.
 * {@code GET /api/dashboard/adjustments} (Story 2.10) is the same pointer's applied cash-flow history, one
 * page at a time: the same three values, plus an optional {@code direction} ({@code ALL}, {@code IN},
 * {@code OUT}) and an optional opaque {@code cursor}. A cursor made for another user, reference or filter,
 * or one that is malformed, is the same 404; source event, classification and conversion ids are never
 * taken. 503 {@code ADJUSTMENT_HISTORY_UNAVAILABLE} when any applied row of the version disagrees with its
 * lineage — never a partial list.
 *
 * <p>A third sub-path, {@code /api/dashboard/sync} (Story 2.11), admits and reports the active plan's
 * incremental sync: {@code POST} with {@code {trigger}} (the one state-changing request of this surface, so
 * the dashboard predicate's CSRF and {@code Origin} checks reach it) and {@code GET} for the attempt and the
 * button's availability. It takes no identifier either, and it carries no financial value.
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

    /** The calculation evidence of the shown result (Story 2.9). The dashboard path's predicate covers it. */
    static final String EVIDENCE_PATH=AuthSecurityConfig.DASHBOARD_PATH+"/evidence";
    /** The stable machine code of a lineage that does not agree with itself. The client treats it as retryable. */
    static final String EVIDENCE_UNAVAILABLE="EVIDENCE_UNAVAILABLE";
    static final String MESSAGE_EVIDENCE_UNAVAILABLE=
            "계산 근거를 한 묶음으로 확인하지 못해 보내지 않았습니다. 잠시 후 다시 시도해 주세요.";

    /** The adjustment history of the shown result (Story 2.10). The dashboard path's predicate covers it too. */
    static final String ADJUSTMENTS_PATH=AuthSecurityConfig.DASHBOARD_PATH+"/adjustments";
    /** The stable machine code of an applied history that does not agree with its lineage. Retryable for the client. */
    static final String ADJUSTMENT_HISTORY_UNAVAILABLE="ADJUSTMENT_HISTORY_UNAVAILABLE";
    static final String MESSAGE_ADJUSTMENT_HISTORY_UNAVAILABLE=
            "조정 이력을 한 묶음으로 확인하지 못해 보내지 않았습니다. 잠시 후 다시 시도해 주세요.";

    /** A canonical UUID (either case). {@link UUID#fromString} alone also accepts shortened groups. */
    private static final java.util.regex.Pattern UUID_TEXT=java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** A positive version without a sign, leading zero or exponent, short enough for a {@code long}. */
    private static final java.util.regex.Pattern VERSION_TEXT=java.util.regex.Pattern.compile("[1-9][0-9]{0,17}");

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

    // ------------------------------------------------------------------ calculation evidence (Story 2.9)

    record EvidenceSnapshotsView(UUID portfolioSnapshotId,long portfolioSequence,UUID basisPortfolioSnapshotId,
            UUID basisSourceSnapshotId,UUID sourceSnapshotId) {}
    /** Four times under their own names. There is no common snapshot token, and none is invented here. */
    record EvidenceTimesView(String t0,String tc,String measuredAt,String cashflowVerifiedThrough) {}
    record EvidenceConnectionView(String provider,UUID connectionId,String policyVersion) {}
    record EvidenceSourceRangeView(String rangeStart,String rangeEnd,String collectionPolicy,String completenessHash) {}
    record EvidenceClassificationView(UUID classificationId,String policyVersion,String inputScopeHash) {}
    record EvidenceConversionView(UUID conversionId,String pricePolicy,String resultFingerprint) {}
    record EvidenceCashflowsView(int appliedCount,int estimatedCount) {}
    record EvidencePlanView(UUID planId,ConditionsView conditions) {}
    record EvidenceInputsView(EvidenceConnectionView connection,EvidenceSourceRangeView sourceRange,
            EvidenceClassificationView classification,EvidenceConversionView conversion,EvidenceCashflowsView cashflows,
            EvidencePlanView plan) {}
    record EvidenceCalculationView(UUID calculationId,String calculationPolicy,String calendarPolicy,
            String displayPolicy,String precisionPolicy,String timeZone,String implementationVersion,String inputHash,
            String resultHash,String createdAt) {}
    record EstimatedCauseView(String evidenceType,int flowCount) {}
    /** No value: the evidence explains a metric's character; the value itself is the dashboard's. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MetricImpactView(String metric,String nature,@Nullable String unavailableReason,
            @Nullable EstimatedCauseView estimatedCause) {}
    /** {@code supersededAt} is {@code null} for the current version and is sent as such. */
    record OfficialVersionView(long pointerVersion,UUID calculationId,String publishedAt,@Nullable String supersededAt,
            boolean current) {}
    record UnpublishedAttemptView(String status,String reasonCode,String finishedAt) {}
    /**
     * Official versions (the current one and at most ten earlier, newest first, with the total) and attempts that
     * never published, in two separate lists.
     */
    record EvidenceVersionsView(List<OfficialVersionView> official,int officialCount,
            List<UnpublishedAttemptView> unpublished) {}
    /** What an incremental version carried from the previous official version (Story 2.11). */
    record EvidenceInheritedView(UUID previousCalculationId,long previousPointerVersion,int flowCount,String through) {}
    /**
     * How the version's cash flows were verified: {@code INITIAL}, {@code NORMAL} or {@code EXTENDED}, the
     * collection start, the last extended verification, and what was carried ({@code null} for the first).
     */
    record EvidenceCollectionView(String mode,String rangeStart,String lastExtendedVerificationAt,
            @Nullable EvidenceInheritedView inherited) {}
    /**
     * The evidence in the screen's order: snapshots → times → input versions → calculation version →
     * metric impact → versions. The reference repeats the three values the request named.
     */
    record EvidenceView(UUID planId,long pointerVersion,UUID calculationId,EvidenceSnapshotsView snapshots,
            EvidenceTimesView times,EvidenceInputsView inputs,EvidenceCollectionView collection,
            EvidenceCalculationView calculation,List<MetricImpactView> metrics,EvidenceVersionsView versions) {}

    /**
     * One evidence read, one operational line — like the dashboard's: the outcome, the status answered, the
     * latency in milliseconds and whether the lineage agreed with itself ({@code unknown} when none was
     * read). No user, reference, correlation id, amount, time or hash.
     */
    @GetMapping(path=EVIDENCE_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> evidence(HttpServletRequest request) {
        long started=System.nanoTime();
        SyncJobApi.EvidenceRead read;
        try {
            read=jobs.calculationEvidence(currentUserId(),referenceOf(request));
        } catch (RuntimeException failure) {
            // The class name at debug only: a message or cause can carry SQL or values.
            if (LOG.isDebugEnabled()) LOG.debug("calculation evidence read failed: failure={}",failure.getClass().getName());
            ResponseEntity<ApiErrorHandler.ApiError> failed=error(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiErrorHandler.CODE_INTERNAL_ERROR,ApiErrorHandler.MESSAGE_INTERNAL_ERROR,request);
            logEvidence("ERROR",failed.getStatusCode().value(),started,"unknown");
            return failed;
        }
        ResponseEntity<?> answer=switch (read.outcome()) {
            case SyncJobApi.EvidenceOutcome.NotPermitted denied ->
                    error(HttpStatus.FORBIDDEN,ApiErrorHandler.CODE_FORBIDDEN,ApiErrorHandler.MESSAGE_FORBIDDEN,request);
            // The very body of a missing route: whether anything exists behind the reference stays unsaid.
            case SyncJobApi.EvidenceOutcome.NotFound notFound ->
                    error(HttpStatus.NOT_FOUND,ApiErrorHandler.CODE_NOT_FOUND,ApiErrorHandler.MESSAGE_NOT_FOUND,request);
            case SyncJobApi.EvidenceOutcome.Unavailable unavailable ->
                    error(HttpStatus.SERVICE_UNAVAILABLE,EVIDENCE_UNAVAILABLE,MESSAGE_EVIDENCE_UNAVAILABLE,request);
            case SyncJobApi.EvidenceOutcome.Found found ->
                    ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidenceView(found.evidence()));
        };
        String state=switch (read.outcome()) {
            case SyncJobApi.EvidenceOutcome.NotPermitted denied -> "DENIED";
            case SyncJobApi.EvidenceOutcome.NotFound notFound -> "NOT_FOUND";
            case SyncJobApi.EvidenceOutcome.Unavailable unavailable -> "INCONSISTENT";
            case SyncJobApi.EvidenceOutcome.Found found -> "FOUND";
        };
        String consistent=switch (read.consistency()) {
            case CONSISTENT -> "true";
            case INCONSISTENT -> "false";
            case NOT_READ -> "unknown";
        };
        logEvidence(state,answer.getStatusCode().value(),started,consistent);
        return answer;
    }

    private static void logEvidence(String state,int status,long started,String consistent) {
        LOG.info("calculation evidence read: state={} status={} latencyMs={} consistent={}",state,status,
                (System.nanoTime()-started)/1_000_000,consistent);
    }

    /** The common error body, {@code no-store} like every answer of this surface. */
    private static ResponseEntity<ApiErrorHandler.ApiError> error(HttpStatus status,String code,String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(new ApiErrorHandler.ApiError(code,message,correlationOf(request)));
    }

    /**
     * The three values of the shown pointer, or {@code null} when any is missing, repeated or malformed, or
     * the query itself cannot be parsed. A {@code null} is answered like a stale reference — the same 404 —
     * after the account's own access. Other parameters are ignored.
     */
    static SyncJobApi.@Nullable EvidenceReference referenceOf(HttpServletRequest request) {
        String plan;
        String version;
        String calculation;
        try {
            plan=single(request,"planId");
            version=single(request,"pointerVersion");
            calculation=single(request,"calculationId");
        } catch (RuntimeException unparsable) {
            /*
             * The container parses the query lazily, on the first parameter read, and refuses a malformed
             * one — an invalid percent escape, or more parameters than it accepts — by throwing (Tomcat 11's
             * InvalidParameterException, an IllegalStateException). That is a malformed reference like any
             * other: the same 404 after the account's own access, never a 500.
             */
            return null;
        }
        if (plan==null || version==null || calculation==null) return null;
        if (!UUID_TEXT.matcher(plan).matches() || !UUID_TEXT.matcher(calculation).matches()
                || !VERSION_TEXT.matcher(version).matches())
            return null;
        return new SyncJobApi.EvidenceReference(UUID.fromString(plan),Long.parseLong(version),UUID.fromString(calculation));
    }

    private static @Nullable String single(HttpServletRequest request,String name) {
        String[] values=request.getParameterValues(name);
        return values!=null && values.length==1 ? values[0] : null;
    }

    static EvidenceView evidenceView(SyncJobApi.CalculationEvidence e) {
        var snapshots=e.snapshots();
        var times=e.times();
        var plan=e.plan();
        var calculation=e.calculation();
        return new EvidenceView(e.planId(),e.pointerVersion(),e.calculationId(),
                new EvidenceSnapshotsView(snapshots.portfolioSnapshotId(),snapshots.portfolioSequence(),
                        snapshots.basisPortfolioSnapshotId(),snapshots.basisSourceSnapshotId(),
                        snapshots.sourceSnapshotId()),
                new EvidenceTimesView(times.t0().toString(),times.tc().toString(),times.measuredAt().toString(),
                        times.cashflowVerifiedThrough().toString()),
                new EvidenceInputsView(
                        new EvidenceConnectionView(e.connection().provider(),e.connection().connectionId(),
                                e.connection().policyVersion()),
                        new EvidenceSourceRangeView(e.sourceRange().rangeStart().toString(),
                                e.sourceRange().rangeEnd().toString(),e.sourceRange().collectionPolicy(),
                                e.sourceRange().completenessHash()),
                        new EvidenceClassificationView(e.classification().classificationId(),
                                e.classification().policyVersion(),e.classification().inputScopeHash()),
                        new EvidenceConversionView(e.conversion().conversionId(),e.conversion().pricePolicy(),
                                e.conversion().resultFingerprint()),
                        new EvidenceCashflowsView(e.cashflows().appliedCount(),e.cashflows().estimatedCount()),
                        new EvidencePlanView(plan.planId(),new ConditionsView(plan.principal(),plan.startAt().toString(),
                                plan.endAt().toString(),plan.period(),plan.targetRate()))),
                new EvidenceCollectionView(e.collection().mode(),e.collection().rangeStart().toString(),
                        e.collection().lastExtendedVerificationAt().toString(),
                        e.collection().inherited()==null ? null : new EvidenceInheritedView(
                                e.collection().inherited().previousCalculationId(),
                                e.collection().inherited().previousPointerVersion(),e.collection().inherited().flowCount(),
                                e.collection().inherited().through().toString())),
                new EvidenceCalculationView(calculation.calculationId(),calculation.calculationPolicy(),
                        calculation.calendarPolicy(),calculation.displayPolicy(),calculation.precisionPolicy(),
                        calculation.timeZone(),calculation.implementationVersion(),calculation.inputHash(),
                        calculation.resultHash(),calculation.createdAt().toString()),
                e.metrics().stream().map(m -> new MetricImpactView(m.metric(),m.nature(),m.unavailableReason(),
                        m.estimatedCause()==null ? null
                                : new EstimatedCauseView(m.estimatedCause().evidenceType(),m.estimatedCause().flowCount())))
                        .toList(),
                new EvidenceVersionsView(
                        e.officialVersions().stream().map(v -> new OfficialVersionView(v.pointerVersion(),
                                v.calculationId(),v.publishedAt().toString(),
                                v.supersededAt()==null ? null : v.supersededAt().toString(),v.current())).toList(),
                        e.officialVersionCount(),
                        e.unpublishedAttempts().stream().map(a -> new UnpublishedAttemptView(a.status(),a.reasonCode(),
                                a.finishedAt().toString())).toList()));
    }

    // ------------------------------------------------------------------ adjustment history (Story 2.10)

    /** The verified cash-flow range of the version, {@code (t0, cashflowVerifiedThrough]}. */
    record AdjustmentCoverageView(String from,String through) {}
    /** Counted over the whole filtered range; the net is the exact sum of the stored values, never rounded. */
    record AdjustmentSummaryView(int count,String netUsdValue,int excludedCount) {}
    record AdjustmentClassificationView(String transactionType,String ruleId,String policyVersion) {}
    record AdjustmentLegView(String symbol,String candleStartAt,String candleEndAt,String closePrice,
            String selectionRule,int lagMinutes) {}
    record AdjustmentPriceView(String policyVersion,String evidenceType,boolean estimated,String unitPrice,
            String routeId,List<AdjustmentLegView> legs) {}
    record AdjustmentRefsView(UUID sourceEventId,String sourceId,UUID classificationId,UUID conversionId,
            UUID calculationId) {}
    /** An earlier conversion version as it was stored. {@code reasonCode} and {@code usdValue} may be null. */
    record AdjustmentRevisionView(UUID conversionId,String processedAt,String status,@Nullable String reasonCode,
            @Nullable String usdValue,String changeReason,String discoveredAt) {}
    /** One applied flow in the reading order: event → classification → price → discovery → references. */
    record AdjustmentRowView(int sequence,String occurredAt,String direction,String asset,String signedQuantity,
            String usdValue,AdjustmentClassificationView classification,AdjustmentPriceView price,String discoveredAt,
            AdjustmentRefsView refs,boolean changed,List<AdjustmentRevisionView> revisions) {}
    /** One page. {@code nextCursor} is {@code null} on the last page and is sent as such. */
    record AdjustmentHistoryView(UUID planId,long pointerVersion,UUID calculationId,String direction,
            String appliedAt,AdjustmentCoverageView coverage,AdjustmentSummaryView summary,List<AdjustmentRowView> rows,
            @Nullable String nextCursor) {}

    /**
     * One adjustment history read, one operational line — like the evidence's: the outcome, the status
     * answered, the latency in milliseconds and whether the version's applied history agreed with its lineage
     * ({@code unknown} when none was read). No user, reference, cursor, correlation id, amount, time or event.
     */
    @GetMapping(path=ADJUSTMENTS_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> adjustments(HttpServletRequest request) {
        long started=System.nanoTime();
        SyncJobApi.AdjustmentRead read;
        try {
            read=jobs.adjustmentHistory(currentUserId(),adjustmentQueryOf(request));
        } catch (RuntimeException failure) {
            // The class name at debug only: a message or cause can carry SQL or values.
            if (LOG.isDebugEnabled()) LOG.debug("adjustment history read failed: failure={}",failure.getClass().getName());
            ResponseEntity<ApiErrorHandler.ApiError> failed=error(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiErrorHandler.CODE_INTERNAL_ERROR,ApiErrorHandler.MESSAGE_INTERNAL_ERROR,request);
            logAdjustments("ERROR",failed.getStatusCode().value(),started,"unknown");
            return failed;
        }
        ResponseEntity<?> answer=switch (read.outcome()) {
            case SyncJobApi.AdjustmentOutcome.NotPermitted denied ->
                    error(HttpStatus.FORBIDDEN,ApiErrorHandler.CODE_FORBIDDEN,ApiErrorHandler.MESSAGE_FORBIDDEN,request);
            // The very body of a missing route: whether anything exists behind the reference or cursor stays unsaid.
            case SyncJobApi.AdjustmentOutcome.NotFound notFound ->
                    error(HttpStatus.NOT_FOUND,ApiErrorHandler.CODE_NOT_FOUND,ApiErrorHandler.MESSAGE_NOT_FOUND,request);
            case SyncJobApi.AdjustmentOutcome.Unavailable unavailable ->
                    error(HttpStatus.SERVICE_UNAVAILABLE,ADJUSTMENT_HISTORY_UNAVAILABLE,
                            MESSAGE_ADJUSTMENT_HISTORY_UNAVAILABLE,request);
            case SyncJobApi.AdjustmentOutcome.Found found ->
                    ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(adjustmentsView(found.history()));
        };
        String state=switch (read.outcome()) {
            case SyncJobApi.AdjustmentOutcome.NotPermitted denied -> "DENIED";
            case SyncJobApi.AdjustmentOutcome.NotFound notFound -> "NOT_FOUND";
            case SyncJobApi.AdjustmentOutcome.Unavailable unavailable -> "INCONSISTENT";
            case SyncJobApi.AdjustmentOutcome.Found found -> "FOUND";
        };
        String consistent=switch (read.consistency()) {
            case CONSISTENT -> "true";
            case INCONSISTENT -> "false";
            case NOT_READ -> "unknown";
        };
        logAdjustments(state,answer.getStatusCode().value(),started,consistent);
        return answer;
    }

    private static void logAdjustments(String state,int status,long started,String consistent) {
        LOG.info("adjustment history read: state={} status={} latencyMs={} consistent={}",state,status,
                (System.nanoTime()-started)/1_000_000,consistent);
    }

    /**
     * The page the screen asks for, or {@code null} when it names nothing: any of the three reference values
     * missing, repeated or malformed; a {@code direction} that is repeated or not exactly {@code ALL},
     * {@code IN} or {@code OUT} (absent means {@code ALL}); a repeated {@code cursor}; or a query the container
     * cannot parse. A {@code null} is answered like a stale reference — the same 404 — after the account's own
     * access. The cursor itself is passed as sent: only sync can judge it. Other parameters are ignored.
     */
    static SyncJobApi.@Nullable AdjustmentQuery adjustmentQueryOf(HttpServletRequest request) {
        SyncJobApi.EvidenceReference reference=referenceOf(request);
        if (reference==null) return null;
        String[] directions;
        String[] cursors;
        try {
            directions=request.getParameterValues("direction");
            cursors=request.getParameterValues("cursor");
        } catch (RuntimeException unparsable) {
            return null;
        }
        SyncJobApi.AdjustmentDirection direction;
        if (directions==null) {
            direction=SyncJobApi.AdjustmentDirection.ALL;
        } else if (directions.length!=1) {
            return null;
        } else {
            direction=switch (directions[0]) {
                case "ALL" -> SyncJobApi.AdjustmentDirection.ALL;
                case "IN" -> SyncJobApi.AdjustmentDirection.IN;
                case "OUT" -> SyncJobApi.AdjustmentDirection.OUT;
                default -> null;
            };
            if (direction==null) return null;
        }
        if (cursors!=null && cursors.length!=1) return null;
        return new SyncJobApi.AdjustmentQuery(reference.planId(),reference.pointerVersion(),reference.calculationId(),
                direction,cursors==null ? null : cursors[0]);
    }

    static AdjustmentHistoryView adjustmentsView(SyncJobApi.AdjustmentHistory h) {
        return new AdjustmentHistoryView(h.planId(),h.pointerVersion(),h.calculationId(),h.direction().name(),
                h.appliedAt().toString(),
                new AdjustmentCoverageView(h.coverage().from().toString(),h.coverage().through().toString()),
                new AdjustmentSummaryView(h.summary().count(),h.summary().netUsdValue(),h.summary().excludedCount()),
                h.rows().stream().map(r -> new AdjustmentRowView(r.sequence(),r.occurredAt().toString(),r.direction(),
                        r.asset(),r.signedQuantity(),r.usdValue(),
                        new AdjustmentClassificationView(r.classification().transactionType(),r.classification().ruleId(),
                                r.classification().policyVersion()),
                        new AdjustmentPriceView(r.price().policyVersion(),r.price().evidenceType(),r.price().estimated(),
                                r.price().unitPrice(),r.price().routeId(),
                                r.price().legs().stream().map(l -> new AdjustmentLegView(l.symbol(),
                                        l.candleStartAt().toString(),l.candleEndAt().toString(),l.closePrice(),
                                        l.selectionRule(),l.lagMinutes())).toList()),
                        r.discoveredAt().toString(),
                        new AdjustmentRefsView(r.references().sourceEventId(),r.references().sourceId(),
                                r.references().classificationId(),r.references().conversionId(),
                                r.references().calculationId()),
                        r.changed(),
                        r.revisions().stream().map(v -> new AdjustmentRevisionView(v.conversionId(),
                                v.processedAt().toString(),v.status(),v.reasonCode(),v.usdValue(),v.changeReason(),
                                v.discoveredAt().toString())).toList()))
                        .toList(),
                h.nextCursor());
    }

    // ------------------------------------------------------------------ incremental sync (Story 2.11)

    /** The incremental sync of the active plan. The dashboard path's predicate covers it: auth, CSRF, Origin. */
    static final String SYNC_PATH=AuthSecurityConfig.DASHBOARD_PATH+"/sync";

    /** The one refusal that is the request's own fault: the body's trigger is missing or not one of the three. */
    static final String SYNC_TRIGGER_REQUIRED="SYNC_TRIGGER_REQUIRED";
    static final String MESSAGE_SYNC_TRIGGER_REQUIRED="동기화 요청의 형식이 맞지 않아 받지 않았습니다. 화면을 새로 열어 다시 시도해 주세요.";

    /** The request body. Only {@code trigger} is read; anything else in it is ignored. */
    record SyncRequest(@Nullable String trigger) {}

    /** One attempt: codes and times, and the attempt's own id — no financial value, no other id. */
    record SyncJobView(UUID jobId,String trigger,String status,String stage,@Nullable String mode,
            @Nullable String failureCode,boolean retryable,String createdAt,@Nullable String finishedAt,
            @Nullable Long pointerVersion) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ManualSyncView(boolean available,@Nullable String reason,@Nullable Integer retryAfterSeconds) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SyncAnswerView(String outcome,@Nullable String reason,@Nullable SyncJobView job,ManualSyncView manual) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SyncStateView(@Nullable SyncJobView job,ManualSyncView manual) {}

    /**
     * Admits an incremental sync (Story 2.11). The body is {@code {"trigger": "DASHBOARD_ENTRY" |
     * "TAB_REACTIVATION" | "MANUAL"}}. 202 with the new attempt ({@code ADMITTED}); 200 with the outcome that
     * admitted nothing — {@code IN_PROGRESS}, {@code RECENTLY_SYNCED}, {@code COOLDOWN}, {@code LONG_GAP} or
     * {@code NOT_AVAILABLE} with its reason — and the existing reference; 400 {@code SYNC_TRIGGER_REQUIRED} when
     * the trigger is missing or unknown; 403 when the account's own access refuses it. Never calls the provider.
     * Every answer is {@code no-store}. The service writes the admission's operational line.
     */
    @PostMapping(path=SYNC_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> sync(@RequestBody(required=false) @Nullable SyncRequest body,HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if (user==null) { response.sendError(403); return null; }
        SyncJobApi.SyncTrigger trigger=triggerOf(body==null ? null : body.trigger());
        if (trigger==null)
            return error(HttpStatus.BAD_REQUEST,SYNC_TRIGGER_REQUIRED,MESSAGE_SYNC_TRIGGER_REQUIRED,request);
        var admission=jobs.admitPlanSync(user,trigger);
        if (!(admission instanceof SyncJobApi.SyncAdmission.Answered answered))
            return error(HttpStatus.FORBIDDEN,ApiErrorHandler.CODE_FORBIDDEN,ApiErrorHandler.MESSAGE_FORBIDDEN,request);
        var answer=answered.answer();
        HttpStatus status="ADMITTED".equals(answer.outcome()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new SyncAnswerView(answer.outcome(),answer.reason(),jobView(answer.job()),manualView(answer.manual())));
    }

    /**
     * The sync state for the screen: the active plan's attempt in progress or its latest one, and whether the
     * button would start one now. 200, or 403 when the account's own access refuses it. {@code no-store}.
     */
    @GetMapping(path=SYNC_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> syncState(HttpServletRequest request) {
        var state=jobs.planSyncState(currentUserId()).orElse(null);
        if (state==null)
            return error(HttpStatus.FORBIDDEN,ApiErrorHandler.CODE_FORBIDDEN,ApiErrorHandler.MESSAGE_FORBIDDEN,request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SyncStateView(jobView(state.job()),manualView(state.manual())));
    }

    /** Exactly one of the three trigger names, or {@code null}. */
    static SyncJobApi.@Nullable SyncTrigger triggerOf(@Nullable String value) {
        if (value==null) return null;
        for (SyncJobApi.SyncTrigger trigger : SyncJobApi.SyncTrigger.values())
            if (trigger.name().equals(value)) return trigger;
        return null;
    }

    private static @Nullable SyncJobView jobView(SyncJobApi.@Nullable SyncJob job) {
        if (job==null) return null;
        return new SyncJobView(job.jobId(),job.trigger(),job.status(),job.stage(),job.mode(),job.failureCode(),
                job.retryable(),job.createdAt().toString(),job.finishedAt()==null ? null : job.finishedAt().toString(),
                job.pointerVersion());
    }

    private static ManualSyncView manualView(SyncJobApi.ManualSync manual) {
        return new ManualSyncView(manual.available(),manual.reason(),manual.retryAfterSeconds());
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
