package com.bokrilog;

import com.bokrilog.sync.IdempotencyKey;
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
    @GetMapping(PATH)
    @Nullable ResponseEntity<?> state(HttpServletResponse response) throws IOException {
        var state=jobs.nextBaselineState(ControllerSupport.currentUserId());
        if ("NOT_PERMITTED".equals(state.reason())) { response.sendError(403); return null; }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state);
    }
    @PostMapping(PATH)
    @Nullable ResponseEntity<?> prepare(@RequestHeader(name=IdempotencyKey.HEADER,required=false) @Nullable String key,
            HttpServletRequest request,HttpServletResponse response) throws IOException {
        var user=ControllerSupport.currentUserId();
        if (user==null) { response.sendError(403); return null; }
        // 이 경로의 오류 응답은 Content-Type을 명시하지 않고 내용 협상에 맡긴다(기본 Accept면 JSON). ControllerSupport.error는
        // JSON을 강제하므로 JSON이 아닌 Accept에서 결과가 달라질 수 있다 — 그래서 따로 둔다(별도 결정).
        if (!IdempotencyKey.isValid(key))
            return ResponseEntity.unprocessableContent().cacheControl(CacheControl.noStore()).body(new ApiErrorHandler.ApiError(
                    IdempotencyKey.REQUIRED_CODE,ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED,ControllerSupport.correlationIdOf(request)));
        var job=jobs.admitNextBaseline(user,key);
        if (job.isEmpty() && "NOT_PERMITTED".equals(jobs.nextBaselineState(user).reason())) {
            response.sendError(403); return null;
        }
        if (job.isEmpty()) return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(new ApiErrorHandler.ApiError(
                "NEXT_BASELINE_UNAVAILABLE","새 기준을 준비할 수 없습니다. 진행 중인 작업과 연결 상태를 확인해 주세요.",ControllerSupport.correlationIdOf(request)));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(job.get());
    }
}
