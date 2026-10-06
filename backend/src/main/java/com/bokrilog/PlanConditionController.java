package com.bokrilog;

import com.bokrilog.ApiErrorHandler.ApiError;
import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.plan.PlanConditionApi.ActivePlan;
import com.bokrilog.plan.PlanConditionApi.Confirmation;
import com.bokrilog.plan.PlanConditionApi.Intent;
import com.bokrilog.plan.PlanConditionApi.Preview;
import com.bokrilog.plan.PlanConditionApi.PreviewInput;
import com.bokrilog.plan.PlanConditionApi.Snapshot;
import com.bokrilog.sync.IdempotencyKey;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

/** Same-origin plan condition API. */
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class PlanConditionController {
    /**
     * 활성 계획이 있어 새 조건이나 새 계획을 받지 않을 때의 안내. 원인과 다음 행동(결과 보기)만 말한다.
     * 조건 미리보기·확인과 최초 발행({@code FirstSnapshotController})이 같은 {@code ACTIVE_PLAN_EXISTS}
     * 코드에 이 문장 하나를 쓴다.
     */
    static final String MESSAGE_ACTIVE_PLAN_EXISTS =
            "이미 활성 계획이 있어 새 계획 조건이나 계획을 만들지 않았습니다. 대시보드에서 현재 계획의 결과를 확인해 주세요.";

    /**
     * 계획이 중도 종료돼 새 조건을 받지 않을 때의 안내(Story 2.13). 후속 계획은 Story 2.15가 연다. 원인과
     * 다음 행동(종료 기록 보기)만 말한다.
     */
    static final String MESSAGE_PLAN_ENDED =
            "계획이 종료되어 새 계획 조건을 받지 않았습니다. 대시보드에서 종료된 계획의 기록을 확인해 주세요.";

    private final PlanConditionApi plans;
    PlanConditionController(PlanConditionApi plans) { this.plans=plans; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record IntentView(UUID intentId,String status,String decimal,String measuredAt,String endAt,
            String period,String targetRate,UUID snapshotId,String createdAt) {}
    /**
     * The active plan summary. Present only with the reason {@code ACTIVE_PLAN_EXISTS}.
     *
     * @param startAt the plan's start {@code t0} (the first official snapshot's measurement time)
     * @param decimal {@code P0} in canonical notation
     * @param targetRate the rate in canonical notation
     */
    record ActivePlanView(UUID planId,String status,String decimal,String startAt,String endAt,
            String period,String targetRate,UUID snapshotId,String createdAt) {}
    record StateView(boolean eligible,@Nullable String reason,@Nullable SnapshotView snapshot,
            @Nullable IntentView openIntent,@Nullable ActivePlanView activePlan) {}
    record SnapshotView(String decimal,String measuredAt,String publishedAt,UUID portfolioId,
            UUID snapshotId,UUID membershipId,UUID connectionId,UUID providerAccountId,
            UUID sourceSnapshotId,String inputStatus,String sourcePolicyVersion,
            String portfolioPolicyVersion) {}
    record PreviewView(String confirmationRef,String expiresAt,SnapshotView snapshot,String endAt,
            String period,String targetRate,String calendarPolicy,String calculationPolicy) {}
    record ConfirmRequest(String confirmationRef) {}
    record ConfirmView(boolean accepted,boolean conflict,@Nullable IntentView intent) {}

    @GetMapping(path=AuthSecurityConfig.PLAN_CONDITIONS_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<StateView> state(HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var state=plans.state(user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new StateView(
                state.eligible(),state.reason(),state.snapshot()==null?null:view(state.snapshot()),
                state.openIntent()==null?null:view(state.openIntent()),
                state.activePlan()==null?null:view(state.activePlan())));
    }

    @PostMapping(path=AuthSecurityConfig.PLAN_CONDITIONS_PATH+"/preview",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> preview(@RequestBody PreviewInput input,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var session=request.getSession(false);
        if (session==null) { response.sendError(403); return null; }
        var state=plans.state(user);
        if (PlanConditionApi.ACTIVE_PLAN_EXISTS.equals(state.reason())) return activePlanExists(request);
        if (PlanConditionApi.PLAN_ENDED.equals(state.reason())) return planEnded(request);
        if (!state.eligible()) { response.sendError(409); return null; }
        var preview=plans.preview(user,session.getId(),input);
        if (preview.isEmpty()) {
            var after=plans.state(user);
            if (PlanConditionApi.ACTIVE_PLAN_EXISTS.equals(after.reason())) return activePlanExists(request);
            if (PlanConditionApi.PLAN_ENDED.equals(after.reason())) return planEnded(request);
            response.sendError(after.eligible()?422:409);
            return null;
        }
        Preview p=preview.get();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PreviewView(
                p.confirmationRef(),p.expiresAt().toString(),view(p.snapshot()),p.endAt().toString(),
                p.period().name(),p.targetRate(),p.calendarPolicy(),p.calculationPolicy()));
    }

    @PostMapping(path=AuthSecurityConfig.PLAN_CONDITIONS_PATH+"/confirm",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> confirm(@RequestBody ConfirmRequest body,
            @RequestHeader(name=IdempotencyKey.HEADER,required=false) @Nullable String key,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var session=request.getSession(false);
        if (session==null) { response.sendError(403); return null; }
        Confirmation result=plans.confirm(user,session.getId(),body.confirmationRef(),key);
        if (PlanConditionApi.ACTIVE_PLAN_EXISTS.equals(result.reason())) return activePlanExists(request);
        if (PlanConditionApi.PLAN_ENDED.equals(result.reason())) return planEnded(request);
        HttpStatus status=result.accepted()?HttpStatus.CREATED:
                (result.conflict()?HttpStatus.CONFLICT:HttpStatus.valueOf(422));
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ConfirmView(
                result.accepted(),result.conflict(),result.intent()==null?null:view(result.intent())));
    }

    /**
     * 409 with the stable code {@code ACTIVE_PLAN_EXISTS} in the common error shape. No plan is
     * created; the client reloads the condition state, which carries the active plan summary.
     */
    private static ResponseEntity<ApiError> activePlanExists(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(PlanConditionApi.ACTIVE_PLAN_EXISTS,MESSAGE_ACTIVE_PLAN_EXISTS,
                        ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER))));
    }

    /** 409 with the stable code {@code PLAN_ENDED} in the common error shape (Story 2.13). Nothing is created. */
    private static ResponseEntity<ApiError> planEnded(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(PlanConditionApi.PLAN_ENDED,MESSAGE_PLAN_ENDED,
                        ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER))));
    }

    private static SnapshotView view(Snapshot s) {
        return new SnapshotView(s.decimal(),s.measuredAt().toString(),s.publishedAt().toString(),
                s.portfolioId(),s.snapshotId(),s.membershipId(),s.connectionId(),s.providerAccountId(),
                s.sourceSnapshotId(),s.inputStatus(),
                s.sourcePolicyVersion(),s.portfolioPolicyVersion());
    }
    private static IntentView view(Intent i) {
        return new IntentView(i.intentId(),i.status(),i.decimal(),i.measuredAt().toString(),
                i.endAt().toString(),i.period().name(),i.targetRate(),i.snapshotId(),i.createdAt().toString());
    }
    private static ActivePlanView view(ActivePlan p) {
        return new ActivePlanView(p.planId(),p.status(),canonical(p.decimal()),p.t0().toString(),p.endAt().toString(),
                p.period().name(),canonical(p.targetRate()),p.snapshotId(),p.createdAt().toString());
    }
    /** The lossless canonical notation of a stored decimal ({@code "1250.00"} → {@code "1250"}). */
    private static String canonical(String decimal) {
        String notation=PlanCalculator.plainDecimal(decimal);
        return notation==null?decimal:notation;
    }
    private static @Nullable UUID currentUserId() {
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
