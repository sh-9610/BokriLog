package com.bokrilog;

import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.plan.PlanConditionApi.Confirmation;
import com.bokrilog.plan.PlanConditionApi.Intent;
import com.bokrilog.plan.PlanConditionApi.Preview;
import com.bokrilog.plan.PlanConditionApi.PreviewInput;
import com.bokrilog.plan.PlanConditionApi.Snapshot;
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
    private final PlanConditionApi plans;
    PlanConditionController(PlanConditionApi plans) { this.plans=plans; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record IntentView(UUID intentId,String status,String decimal,String measuredAt,String endAt,
            String period,String targetRate,UUID snapshotId,String createdAt) {}
    record StateView(boolean eligible,@Nullable String reason,@Nullable SnapshotView snapshot,
            @Nullable IntentView openIntent) {}
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
                state.openIntent()==null?null:view(state.openIntent())));
    }

    @PostMapping(path=AuthSecurityConfig.PLAN_CONDITIONS_PATH+"/preview",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<PreviewView> preview(@RequestBody PreviewInput input,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var session=request.getSession(false);
        if (session==null) { response.sendError(403); return null; }
        if (!plans.state(user).eligible()) { response.sendError(409); return null; }
        var preview=plans.preview(user,session.getId(),input);
        if (preview.isEmpty()) {
            response.sendError(plans.state(user).eligible()?422:409);
            return null;
        }
        Preview p=preview.get();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PreviewView(
                p.confirmationRef(),p.expiresAt().toString(),view(p.snapshot()),p.endAt().toString(),
                p.period().name(),p.targetRate(),p.calendarPolicy(),p.calculationPolicy()));
    }

    @PostMapping(path=AuthSecurityConfig.PLAN_CONDITIONS_PATH+"/confirm",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<ConfirmView> confirm(@RequestBody ConfirmRequest body,
            @RequestHeader(name="Idempotency-Key",required=false) @Nullable String key,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var session=request.getSession(false);
        if (session==null) { response.sendError(403); return null; }
        Confirmation result=plans.confirm(user,session.getId(),body.confirmationRef(),key);
        HttpStatus status=result.accepted()?HttpStatus.CREATED:
                (result.conflict()?HttpStatus.CONFLICT:HttpStatus.valueOf(422));
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ConfirmView(
                result.accepted(),result.conflict(),result.intent()==null?null:view(result.intent())));
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
    private static @Nullable UUID currentUserId() {
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
