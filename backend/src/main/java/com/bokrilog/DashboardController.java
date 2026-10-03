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
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <p>{@code POST /api/dashboard/sync} (Story 2.11) is the one write of this surface: a sync request
 * {@code {trigger: ENTRY|VISIBILITY|MANUAL}} with an {@code Idempotency-Key}. 202 {@code ACCEPTED} with the new
 * attempt; 200 {@code JOINED} (the attempt in progress, or the same key replayed with the same trigger),
 * {@code COOLDOWN}, {@code NOT_DUE} or {@code LONG_GAP} with the latest attempt and — for the first two — the
 * seconds until it would be due; 403 when the account's write access refuses it; 409 {@code NO_ACTIVE_PLAN},
 * {@code SYNC_UNAVAILABLE} or {@code IDEMPOTENCY_CONFLICT}; 422 {@code IDEMPOTENCY_KEY_REQUIRED}; 400 for a
 * body that is not one of the three triggers. It never calls the provider; the worker does. The ready
 * dashboard carries the sync state beside its result.
 *
 * <p>{@code POST /api/dashboard/verification} (Story 2.12) is the second write: the user's precision verification or
 * long-gap continuation, {@code {kind: PRECISION|CATCH_UP, planId, pointerVersion, calculationId}} — the three values
 * of the result the screen shows are the user's choice token — with an {@code Idempotency-Key}. 202 {@code ACCEPTED};
 * 200 {@code JOINED} or {@code COOLDOWN} (with the seconds until it may be asked again); 400 for a body that is not
 * that shape; 403; 409 {@code NO_ACTIVE_PLAN}, {@code REFERENCE_STALE}, {@code JOB_IN_PROGRESS},
 * {@code VERIFICATION_UNAVAILABLE}, {@code LONG_GAP}, {@code NOT_LONG_GAP}, {@code VERIFICATION_NOT_USEFUL} or
 * {@code IDEMPOTENCY_CONFLICT}; 422 {@code IDEMPOTENCY_KEY_REQUIRED}; a code this surface does not know is a 500. It
 * never calls the provider. The ready dashboard carries the verification state and what the version changed, the
 * evidence carries the change part and every history row the marker of a late or changed value.
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

    /** The sync request (Story 2.11). */
    static final String SYNC_PATH=AuthSecurityConfig.DASHBOARD_PATH+"/sync";
    /** 409 codes of the sync admission → safe messages. A closed map; only the stable code is the machine value. */
    static final java.util.Map<String,String> SYNC_REFUSALS=java.util.Map.of(
            "NO_ACTIVE_PLAN","동기화할 활성 계획이 없습니다. 대시보드를 다시 불러와 주세요.",
            "SYNC_UNAVAILABLE","지금은 동기화할 수 없습니다. 대시보드에서 이유를 확인해 주세요.",
            "IDEMPOTENCY_CONFLICT","같은 요청 키가 다른 동기화 요청에 이미 쓰였습니다. 새로 요청해 주세요.");
    static final String MESSAGE_SYNC_KEY_REQUIRED=
            "요청 키가 없거나 형식이 맞지 않아 동기화를 받지 않았습니다. 화면을 새로 열어 다시 시도해 주세요.";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DashboardView(String state,String nextAction,@Nullable ResultView result,@Nullable SyncView sync,
            @Nullable VerificationView verification,@Nullable ChangeView change) {}
    /** One sync attempt of any purpose. Every key is sent; absent values are {@code null}. */
    record SyncJobView(UUID jobId,String purpose,@Nullable String reason,String trigger,String status,
            @Nullable String stage,@Nullable String failureCode,String admittedAt,@Nullable String finishedAt) {}
    /** One planned range of a verification. */
    record RangeView(String kind,String start,String end) {}
    /** A verification's reconciliation counts. {@code new} is the outcome's own name. */
    record CountsView(@com.fasterxml.jackson.annotation.JsonProperty("new") int added,int same,int missing,
            int superseded,int conflict) {}
    /**
     * The plan's latest verification: the attempt's keys, then its plan and counts, and whether it is the persistent
     * review that holds the current result.
     */
    record VerificationJobView(UUID jobId,String purpose,@Nullable String reason,String trigger,String status,
            @Nullable String stage,@Nullable String failureCode,String admittedAt,@Nullable String finishedAt,
            List<RangeView> targets,List<RangeView> reused,CountsView counts,boolean holdsResult) {}
    record PreviewView(String rangeStart,String unconfirmedFrom,int anomalyCount,@Nullable String reusedUntil) {}
    /** The ready dashboard's verification state. Every key is sent; absent values are {@code null}. */
    record VerificationView(String availability,@Nullable String reason,@Nullable Long retryAfterSeconds,
            @Nullable String suggestedReason,@Nullable PreviewView preview,@Nullable String catchUpFrom,
            @Nullable VerificationJobView latest) {}
    /** What the version changed. Every key is sent; absent values are {@code null}. */
    record ChangeView(String kind,@Nullable String purpose,@Nullable String reason,int lateChangeCount,
            int lateAddedCount,int valueChangedCount,int removedCount,@Nullable String firstAffectedAt,
            @Nullable String verifiedAt,List<RangeView> targets,List<RangeView> reused) {}
    /** The ready dashboard's sync state. Every key is sent; absent values are {@code null}. */
    record SyncView(String availability,@Nullable String reason,@Nullable Long retryAfterSeconds,
            @Nullable SyncJobView latest) {}
    /** A sync admission's answer. Every key is sent; absent values are {@code null}. */
    record SyncAdmissionView(String outcome,@Nullable SyncJobView job,@Nullable Long retryAfterSeconds) {}
    /**
     * Reads the sync request body here rather than binding it: a body the framework cannot read would otherwise
     * be answered by the common error path, without this surface's {@code no-store}.
     */
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder()
            .build();
    /** A trigger request is a few bytes; anything much longer is not one. */
    private static final int MAX_SYNC_BODY_CHARS=1024;

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
            case SyncJobApi.Dashboard.NoPlan none ->
                    ok(new DashboardView("NO_PLAN",none.nextAction(),null,null,null,null));
            case SyncJobApi.Dashboard.Preparing preparing ->
                    ok(new DashboardView("PREPARING",preparing.nextAction(),null,null,null,null));
            case SyncJobApi.Dashboard.Ready ready ->
                    ok(new DashboardView("READY",ready.nextAction(),view(ready.result()),syncView(ready.sync()),
                            verificationView(ready.verification()),changeView(ready.change())));
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

    // ------------------------------------------------------------------ sync (Story 2.11)

    static @Nullable SyncJobView syncJobView(SyncJobApi.@Nullable SyncJob job) {
        return job==null ? null : new SyncJobView(job.jobId(),job.purpose(),job.reason(),job.trigger(),job.status(),
                job.stage(),job.failureCode(),job.admittedAt().toString(),
                job.finishedAt()==null ? null : job.finishedAt().toString());
    }

    static List<RangeView> rangeViews(List<SyncJobApi.VerificationRange> ranges) {
        return ranges.stream().map(r -> new RangeView(r.kind(),r.start().toString(),r.end().toString())).toList();
    }

    static VerificationView verificationView(SyncJobApi.VerificationState v) {
        var preview=v.preview();
        var latest=v.latest();
        return new VerificationView(v.availability(),v.reason(),v.retryAfterSeconds(),v.suggestedReason(),
                preview==null ? null : new PreviewView(preview.rangeStart().toString(),
                        preview.unconfirmedFrom().toString(),preview.anomalyCount(),
                        preview.reusedUntil()==null ? null : preview.reusedUntil().toString()),
                v.catchUpFrom()==null ? null : v.catchUpFrom().toString(),
                latest==null ? null : new VerificationJobView(latest.job().jobId(),latest.job().purpose(),
                        latest.job().reason(),latest.job().trigger(),latest.job().status(),latest.job().stage(),
                        latest.job().failureCode(),latest.job().admittedAt().toString(),
                        latest.job().finishedAt()==null ? null : latest.job().finishedAt().toString(),
                        rangeViews(latest.targets()),rangeViews(latest.reused()),
                        new CountsView(latest.counts().added(),latest.counts().same(),latest.counts().missing(),
                                latest.counts().superseded(),latest.counts().conflict()),latest.holdsResult()));
    }

    static ChangeView changeView(SyncJobApi.VersionChange c) {
        return new ChangeView(c.kind(),c.purpose(),c.reason(),c.lateChangeCount(),c.lateAddedCount(),
                c.valueChangedCount(),c.removedCount(),c.firstAffectedAt()==null ? null : c.firstAffectedAt().toString(),
                c.verifiedAt()==null ? null : c.verifiedAt().toString(),rangeViews(c.targets()),rangeViews(c.reused()));
    }

    static SyncView syncView(SyncJobApi.SyncState sync) {
        return new SyncView(sync.availability(),sync.reason(),sync.retryAfterSeconds(),syncJobView(sync.latest()));
    }

    /**
     * One sync request, one operational line: the trigger, the result (an outcome or a refusal code), the status
     * answered and the latency in milliseconds. No user, key, job, amount or time.
     */
    @PostMapping(path=SYNC_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> sync(@RequestBody(required=false) @Nullable String body,
            @RequestHeader(name="Idempotency-Key",required=false) @Nullable String key,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        long started=System.nanoTime();
        SyncJobApi.SyncTrigger trigger=triggerOf(body);
        String triggerName=trigger==null ? "-" : trigger.name();
        if (trigger==null) {
            ResponseEntity<ApiErrorHandler.ApiError> bad=error(HttpStatus.BAD_REQUEST,ApiErrorHandler.CODE_BAD_REQUEST,
                    ApiErrorHandler.MESSAGE_BAD_REQUEST,request);
            logSync(triggerName,"BAD_REQUEST",bad.getStatusCode().value(),started);
            return bad;
        }
        SyncJobApi.SyncAdmission admission;
        try {
            admission=jobs.admitPlanSync(currentUserId(),trigger,key);
        } catch (RuntimeException failure) {
            // The class name at debug only: a message or cause can carry SQL or values.
            if (LOG.isDebugEnabled()) LOG.debug("sync admission failed: failure={}",failure.getClass().getName());
            ResponseEntity<ApiErrorHandler.ApiError> failed=error(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiErrorHandler.CODE_INTERNAL_ERROR,ApiErrorHandler.MESSAGE_INTERNAL_ERROR,request);
            logSync(triggerName,"ERROR",failed.getStatusCode().value(),started);
            return failed;
        }
        switch (admission) {
            case SyncJobApi.SyncAdmission.NotPermitted denied -> {
                response.sendError(403);
                logSync(triggerName,"DENIED",403,started);
                return null;
            }
            case SyncJobApi.SyncAdmission.Accepted accepted -> {
                logSync(triggerName,"ACCEPTED",202,started);
                return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                        .body(new SyncAdmissionView("ACCEPTED",syncJobView(accepted.job()),null));
            }
            case SyncJobApi.SyncAdmission.Joined joined -> {
                logSync(triggerName,"JOINED",200,started);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new SyncAdmissionView("JOINED",syncJobView(joined.job()),null));
            }
            case SyncJobApi.SyncAdmission.Deferred deferred -> {
                logSync(triggerName,deferred.outcome(),200,started);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new SyncAdmissionView(deferred.outcome(),syncJobView(deferred.latest()),
                                deferred.retryAfterSeconds()));
            }
            case SyncJobApi.SyncAdmission.Refused refused -> {
                String code=refused.code();
                if ("IDEMPOTENCY_KEY_REQUIRED".equals(code)) {
                    logSync(triggerName,code,422,started);
                    return ResponseEntity.status(422).contentType(MediaType.APPLICATION_JSON)
                            .cacheControl(CacheControl.noStore())
                            .body(new ApiErrorHandler.ApiError(code,MESSAGE_SYNC_KEY_REQUIRED,correlationOf(request)));
                }
                if ("BAD_REQUEST".equals(code)) {
                    logSync(triggerName,code,400,started);
                    return error(HttpStatus.BAD_REQUEST,ApiErrorHandler.CODE_BAD_REQUEST,
                            ApiErrorHandler.MESSAGE_BAD_REQUEST,request);
                }
                String message=SYNC_REFUSALS.get(code);
                if (message==null) {
                    // A refusal this surface does not know is a broken contract, not the caller's fault.
                    logSync(triggerName,"ERROR",500,started);
                    return error(HttpStatus.INTERNAL_SERVER_ERROR,ApiErrorHandler.CODE_INTERNAL_ERROR,
                            ApiErrorHandler.MESSAGE_INTERNAL_ERROR,request);
                }
                logSync(triggerName,code,409,started);
                return error(HttpStatus.CONFLICT,code,message,request);
            }
        }
    }

    /**
     * The trigger of the body, or {@code null} when there is no body, it is not a JSON object, or its
     * {@code trigger} is not exactly one of the three names. Other members are ignored.
     */
    static SyncJobApi.@Nullable SyncTrigger triggerOf(@Nullable String body) {
        if (body==null || body.length()>MAX_SYNC_BODY_CHARS) return null;
        tools.jackson.databind.JsonNode trigger;
        try {
            tools.jackson.databind.JsonNode node=JSON.readTree(body);
            if (node==null || !node.isObject()) return null;
            trigger=node.get("trigger");
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        if (trigger==null || !trigger.isString()) return null;
        return switch (trigger.asString()) {
            case "ENTRY" -> SyncJobApi.SyncTrigger.ENTRY;
            case "VISIBILITY" -> SyncJobApi.SyncTrigger.VISIBILITY;
            case "MANUAL" -> SyncJobApi.SyncTrigger.MANUAL;
            default -> null;
        };
    }

    private static void logSync(String trigger,String result,int status,long started) {
        LOG.info("plan sync admission: trigger={} result={} status={} latencyMs={}",trigger,result,status,
                (System.nanoTime()-started)/1_000_000);
    }

    // ------------------------------------------------------------------ verification (Story 2.12)

    /** The verification request. */
    static final String VERIFICATION_PATH=AuthSecurityConfig.DASHBOARD_PATH+"/verification";
    /** 409 codes of the verification admission → safe messages. A closed map; only the stable code is the machine value. */
    static final java.util.Map<String,String> VERIFICATION_REFUSALS=java.util.Map.of(
            "NO_ACTIVE_PLAN","검증할 활성 계획이 없습니다. 대시보드를 다시 불러와 주세요.",
            "REFERENCE_STALE","화면의 결과가 최신 공식 버전이 아닙니다. 대시보드를 다시 불러와 주세요.",
            "JOB_IN_PROGRESS","다른 작업이 진행 중입니다. 끝난 뒤 다시 요청해 주세요.",
            "VERIFICATION_UNAVAILABLE","지금은 검증할 수 없습니다. 대시보드에서 이유를 확인해 주세요.",
            "LONG_GAP","오래 동기화하지 않아 정밀 검증 대신 기존 계획 계속을 선택해야 합니다.",
            "NOT_LONG_GAP","장기 미동기화 상태가 아니어서 기존 계획 계속을 받지 않았습니다.",
            "VERIFICATION_NOT_USEFUL","같은 결과의 정밀 검증이나 기존 계획 계속이 이미 검토 필요로 끝났습니다. 다시 요청해도 바뀌지 않습니다.",
            "IDEMPOTENCY_CONFLICT","같은 요청 키가 다른 요청에 이미 쓰였습니다. 새로 요청해 주세요.");
    static final String MESSAGE_VERIFICATION_KEY_REQUIRED=
            "요청 키가 없거나 형식이 맞지 않아 검증 요청을 받지 않았습니다. 화면을 새로 열어 다시 시도해 주세요.";

    /** The body of a verification request, read here for the same {@code no-store} reason as the sync's. */
    record VerificationBody(SyncJobApi.VerificationKind kind,SyncJobApi.EvidenceReference reference) {}

    /**
     * One verification request, one operational line: the kind, the result (an outcome or a refusal code), the status
     * answered and the latency in milliseconds. No user, key, reference, job, amount or time.
     */
    @PostMapping(path=VERIFICATION_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> verification(@RequestBody(required=false) @Nullable String body,
            @RequestHeader(name="Idempotency-Key",required=false) @Nullable String key,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        long started=System.nanoTime();
        VerificationBody parsed=verificationBodyOf(body);
        String kind=parsed==null ? "-" : parsed.kind().name();
        if (parsed==null) {
            logVerification(kind,"BAD_REQUEST",400,started);
            return error(HttpStatus.BAD_REQUEST,ApiErrorHandler.CODE_BAD_REQUEST,ApiErrorHandler.MESSAGE_BAD_REQUEST,
                    request);
        }
        SyncJobApi.VerificationAdmission admission;
        try {
            admission=jobs.admitPlanVerification(currentUserId(),parsed.kind(),parsed.reference(),key);
        } catch (RuntimeException failure) {
            // The class name at debug only: a message or cause can carry SQL or values.
            if (LOG.isDebugEnabled()) LOG.debug("verification admission failed: failure={}",failure.getClass().getName());
            logVerification(kind,"ERROR",500,started);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,ApiErrorHandler.CODE_INTERNAL_ERROR,
                    ApiErrorHandler.MESSAGE_INTERNAL_ERROR,request);
        }
        switch (admission) {
            case SyncJobApi.VerificationAdmission.NotPermitted denied -> {
                response.sendError(403);
                logVerification(kind,"DENIED",403,started);
                return null;
            }
            case SyncJobApi.VerificationAdmission.Accepted accepted -> {
                logVerification(kind,"ACCEPTED",202,started);
                return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                        .body(new SyncAdmissionView("ACCEPTED",syncJobView(accepted.job()),null));
            }
            case SyncJobApi.VerificationAdmission.Joined joined -> {
                logVerification(kind,"JOINED",200,started);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new SyncAdmissionView("JOINED",syncJobView(joined.job()),null));
            }
            case SyncJobApi.VerificationAdmission.Cooldown cooldown -> {
                logVerification(kind,"COOLDOWN",200,started);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new SyncAdmissionView("COOLDOWN",syncJobView(cooldown.latest()),
                                cooldown.retryAfterSeconds()));
            }
            case SyncJobApi.VerificationAdmission.Refused refused -> {
                String code=refused.code();
                if ("IDEMPOTENCY_KEY_REQUIRED".equals(code)) {
                    logVerification(kind,code,422,started);
                    return ResponseEntity.status(422).contentType(MediaType.APPLICATION_JSON)
                            .cacheControl(CacheControl.noStore())
                            .body(new ApiErrorHandler.ApiError(code,MESSAGE_VERIFICATION_KEY_REQUIRED,
                                    correlationOf(request)));
                }
                if ("BAD_REQUEST".equals(code)) {
                    logVerification(kind,code,400,started);
                    return error(HttpStatus.BAD_REQUEST,ApiErrorHandler.CODE_BAD_REQUEST,
                            ApiErrorHandler.MESSAGE_BAD_REQUEST,request);
                }
                String message=VERIFICATION_REFUSALS.get(code);
                if (message==null) {
                    // A refusal this surface does not know is a broken contract, not the caller's fault.
                    logVerification(kind,"ERROR",500,started);
                    return error(HttpStatus.INTERNAL_SERVER_ERROR,ApiErrorHandler.CODE_INTERNAL_ERROR,
                            ApiErrorHandler.MESSAGE_INTERNAL_ERROR,request);
                }
                logVerification(kind,code,409,started);
                return error(HttpStatus.CONFLICT,code,message,request);
            }
        }
    }

    /**
     * The kind and the reference of the body, or {@code null} when there is no body, it is not a JSON object, the
     * {@code kind} is not exactly one of the two names, the two ids are not canonical UUID strings or the version is
     * not a positive JSON integer. Other members are ignored.
     */
    static @Nullable VerificationBody verificationBodyOf(@Nullable String body) {
        if (body==null || body.length()>MAX_SYNC_BODY_CHARS) return null;
        tools.jackson.databind.JsonNode node;
        try {
            node=JSON.readTree(body);
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        if (node==null || !node.isObject()) return null;
        tools.jackson.databind.JsonNode kind=node.get("kind");
        tools.jackson.databind.JsonNode plan=node.get("planId");
        tools.jackson.databind.JsonNode version=node.get("pointerVersion");
        tools.jackson.databind.JsonNode calculation=node.get("calculationId");
        if (kind==null || !kind.isString() || plan==null || !plan.isString() || calculation==null
                || !calculation.isString() || version==null || !version.isIntegralNumber() || !version.canConvertToLong())
            return null;
        SyncJobApi.VerificationKind parsedKind=switch (kind.asString()) {
            case "PRECISION" -> SyncJobApi.VerificationKind.PRECISION;
            case "CATCH_UP" -> SyncJobApi.VerificationKind.CATCH_UP;
            default -> null;
        };
        long pointerVersion=version.asLong();
        if (parsedKind==null || pointerVersion<1 || !UUID_TEXT.matcher(plan.asString()).matches()
                || !UUID_TEXT.matcher(calculation.asString()).matches())
            return null;
        return new VerificationBody(parsedKind,new SyncJobApi.EvidenceReference(UUID.fromString(plan.asString()),
                pointerVersion,UUID.fromString(calculation.asString())));
    }

    private static void logVerification(String kind,String result,int status,long started) {
        LOG.info("plan verification admission: kind={} result={} status={} latencyMs={}",kind,result,status,
                (System.nanoTime()-started)/1_000_000);
    }

    // ------------------------------------------------------------------ calculation evidence (Story 2.9)

    record EvidenceSnapshotsView(UUID portfolioSnapshotId,long portfolioSequence,UUID basisPortfolioSnapshotId,
            UUID basisSourceSnapshotId,UUID sourceSnapshotId) {}
    /** Four times under their own names. There is no common snapshot token, and none is invented here. */
    record EvidenceTimesView(String t0,String tc,String measuredAt,String cashflowVerifiedThrough) {}
    record EvidenceConnectionView(String provider,UUID connectionId,String policyVersion) {}
    /** The collected range {@code (t0, tc]} and where the version's own collection windows started. */
    record EvidenceSourceRangeView(String rangeStart,String rangeEnd,String windowStart,String collectionPolicy,
            String completenessHash) {}
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
    /** {@code appliedUntil} is {@code null} for the current version and is sent as such. */
    record OfficialVersionView(long pointerVersion,UUID calculationId,String publishedAt,@Nullable String appliedUntil,
            boolean current) {}
    record UnpublishedAttemptView(String status,String reasonCode,String finishedAt) {}
    /** Official versions and attempts that never published, in two separate lists. */
    record EvidenceVersionsView(List<OfficialVersionView> official,List<UnpublishedAttemptView> unpublished) {}
    record EvidencePreviousView(long pointerVersion,UUID calculationId,String basisAt) {}
    /** Both versions' value or reason of one metric. Every key is sent. */
    record MetricDeltaView(String metric,@Nullable String previousValue,@Nullable String previousUnavailableReason,
            @Nullable String value,@Nullable String unavailableReason) {}
    /** One changed event. Every key is sent; absent values are {@code null}. */
    record ChangedEventView(String sourceId,String occurredAt,String changeKind,boolean late,String transactionType,
            @Nullable String previousUsdValue,@Nullable Boolean previousEstimated,@Nullable String usdValue,
            @Nullable Boolean estimated,String evidenceType,String discoveredAt,String appliedAt) {}
    /** What the version changed (Story 2.12). Every key is sent; absent values are {@code null}. */
    record EvidenceChangeView(String kind,@Nullable String purpose,@Nullable String reason,int lateAddedCount,
            int valueChangedCount,int removedCount,List<RangeView> targets,List<RangeView> reused,
            @Nullable EvidencePreviousView previous,@Nullable String firstAffectedAt,List<MetricDeltaView> metrics,
            List<ChangedEventView> events) {}
    /**
     * The evidence in the screen's order: snapshots → times → input versions → calculation version →
     * metric impact → versions, with the change part (Story 2.12). The reference repeats the three values the
     * request named.
     */
    record EvidenceView(UUID planId,long pointerVersion,UUID calculationId,EvidenceSnapshotsView snapshots,
            EvidenceTimesView times,EvidenceInputsView inputs,EvidenceCalculationView calculation,
            List<MetricImpactView> metrics,EvidenceVersionsView versions,EvidenceChangeView change) {}

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
                                e.sourceRange().rangeEnd().toString(),e.sourceRange().windowStart().toString(),
                                e.sourceRange().collectionPolicy(),e.sourceRange().completenessHash()),
                        new EvidenceClassificationView(e.classification().classificationId(),
                                e.classification().policyVersion(),e.classification().inputScopeHash()),
                        new EvidenceConversionView(e.conversion().conversionId(),e.conversion().pricePolicy(),
                                e.conversion().resultFingerprint()),
                        new EvidenceCashflowsView(e.cashflows().appliedCount(),e.cashflows().estimatedCount()),
                        new EvidencePlanView(plan.planId(),new ConditionsView(plan.principal(),plan.startAt().toString(),
                                plan.endAt().toString(),plan.period(),plan.targetRate()))),
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
                                v.appliedUntil()==null ? null : v.appliedUntil().toString(),v.current())).toList(),
                        e.unpublishedAttempts().stream().map(a -> new UnpublishedAttemptView(a.status(),a.reasonCode(),
                                a.finishedAt().toString())).toList()),
                evidenceChangeView(e.change()));
    }

    static EvidenceChangeView evidenceChangeView(SyncJobApi.CalculationEvidence.EvidenceChange c) {
        var previous=c.previous();
        return new EvidenceChangeView(c.kind(),c.purpose(),c.reason(),c.lateAddedCount(),c.valueChangedCount(),
                c.removedCount(),rangeViews(c.targets()),rangeViews(c.reused()),
                previous==null ? null : new EvidencePreviousView(previous.pointerVersion(),previous.calculationId(),
                        previous.basisAt().toString()),
                c.firstAffectedAt()==null ? null : c.firstAffectedAt().toString(),
                c.metrics().stream().map(m -> new MetricDeltaView(m.metric(),m.previousValue(),
                        m.previousUnavailableReason(),m.value(),m.unavailableReason())).toList(),
                c.events().stream().map(v -> new ChangedEventView(v.sourceId(),v.occurredAt().toString(),v.changeKind(),
                        v.late(),v.transactionType(),v.previousUsdValue(),v.previousEstimated(),v.usdValue(),v.estimated(),
                        v.evidenceType(),v.discoveredAt().toString(),v.appliedAt().toString())).toList());
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
    /** The marker of a row this version added late or whose value it changed (Story 2.12). */
    record AdjustmentVersionMarkView(String change,boolean late,@Nullable String previousUsdValue,String discoveredAt,
            String appliedAt) {}
    /** One applied flow in the reading order: event → classification → price → discovery → references. */
    record AdjustmentRowView(int sequence,String occurredAt,String direction,String asset,String signedQuantity,
            String usdValue,AdjustmentClassificationView classification,AdjustmentPriceView price,String discoveredAt,
            AdjustmentRefsView refs,boolean changed,List<AdjustmentRevisionView> revisions,
            @Nullable AdjustmentVersionMarkView versionChange) {}
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
                                v.discoveredAt().toString())).toList(),
                        r.versionChange()==null ? null : new AdjustmentVersionMarkView(r.versionChange().change(),
                                r.versionChange().late(),r.versionChange().previousUsdValue(),
                                r.versionChange().discoveredAt().toString(),r.versionChange().appliedAt().toString())))
                        .toList(),
                h.nextCursor());
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
