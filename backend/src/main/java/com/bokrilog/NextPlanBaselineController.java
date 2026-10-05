package com.bokrilog;

import com.bokrilog.sync.SyncJobApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 후속 계획 기준 준비. 상태 읽기는 provider를 호출하지 않는다. */
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class NextPlanBaselineController {
    private static final String PATH="/api/plans/next/baseline";
    private final SyncJobApi jobs;
    NextPlanBaselineController(SyncJobApi jobs) { this.jobs=jobs; }
    private static java.util.@Nullable UUID user() {
        var a=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (a==null || !a.isAuthenticated() || a instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) return null;
        try { return java.util.UUID.fromString(a.getName()); } catch (IllegalArgumentException invalid) { return null; }
    }
    @GetMapping(PATH)
    @Nullable ResponseEntity<?> state(HttpServletResponse response) throws IOException {
        var state=jobs.nextBaselineState(user());
        if ("NOT_PERMITTED".equals(state.reason())) { response.sendError(403); return null; }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state);
    }
    @PostMapping(PATH)
    @Nullable ResponseEntity<?> prepare(@RequestHeader(name="Idempotency-Key",required=false) @Nullable String key,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        if (user()==null) { response.sendError(403); return null; }
        if (key==null || key.length()<16 || key.length()>128 || !key.matches("[A-Za-z0-9._:-]+")
                || key.contains("..") || key.startsWith("-"))
            return ResponseEntity.unprocessableContent().cacheControl(CacheControl.noStore()).body(new ApiErrorHandler.ApiError(
                    "IDEMPOTENCY_KEY_REQUIRED","요청 키를 확인할 수 없습니다. 화면을 다시 열어 주세요.",ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER))));
        var job=jobs.admitNextBaseline(user(),key);
        if (job.isEmpty() && "NOT_PERMITTED".equals(jobs.nextBaselineState(user()).reason())) {
            response.sendError(403); return null;
        }
        if (job.isEmpty()) return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(new ApiErrorHandler.ApiError(
                "NEXT_BASELINE_UNAVAILABLE","새 기준을 준비할 수 없습니다. 진행 중인 작업과 연결 상태를 확인해 주세요.",ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER))));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(job.get());
    }
}
