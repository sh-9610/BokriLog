package com.bokrilog;

import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.IdempotencyKey;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin, authenticated first-snapshot entry point. */
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class FirstSnapshotController {
    private final SyncJobApi jobs;
    FirstSnapshotController(SyncJobApi jobs) { this.jobs=jobs; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JobState(UUID jobId,String status,@Nullable String failureReason) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SnapshotState(boolean connected,boolean restricted,@Nullable JobState job,
            @Nullable String decimal,@Nullable String currency,
            @Nullable String measuredAt,@Nullable String composedAt,@Nullable String publishedAt,
            @Nullable UUID snapshotId,
            // Story 2.16: present (true) only after a revocation; the screen then offers no connection.
            @Nullable Boolean revoked,
            // Story 2.17: 계획 없이 다른 Bybit 계정으로 다시 연결했을 때만(true) 싣는다 — 이전 계정의 값은 싣지 않고 새 첫 확인을 받는다.
            @Nullable Boolean accountChanged) {}
    record CollectionRequest(UUID intentId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CollectionJobState(UUID jobId,String status,@Nullable String failureCode,
            @Nullable String lastCheckedAt) {}
    record CollectionState(@Nullable CollectionJobState job) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CashflowClassificationState(UUID jobId,String status,@Nullable String failureCode,
            @Nullable String processedAt,int depositCount,int withdrawalCount,int excludedCount,
            int reviewCount,@Nullable String reasonCode) {}
    record PendingFlow(String direction,String quantity,String occurredAt,String status,String reasonCode,
            boolean retryable) {}
    record PendingAsset(String assetCode,int count,java.util.List<PendingFlow> flows) {}
    /** The four outcome counts are omitted when the attempt has no result bundle. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CashflowConversionState(UUID jobId,String status,@Nullable String reasonCode,boolean retryable,
            @Nullable String processedAt,int flowCount,@Nullable Integer convertedCount,
            @Nullable Integer estimatedCount,@Nullable Integer conversionRequiredCount,@Nullable Integer reviewCount,
            boolean calculable,String pricePolicyVersion,String calculationPolicyVersion,
            java.util.List<PendingAsset> pendingAssets) {}

    @PostMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<JobState> start(@RequestHeader(name=IdempotencyKey.HEADER,required=false)
            @Nullable String key,HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var job=jobs.admit(user,key==null ? UUID.randomUUID().toString() : key);
        if (job.isEmpty()) { response.sendError(403); return null; }
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(toState(job.get()));
    }
    @GetMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/jobs/{id}",
            produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<JobState> job(@PathVariable UUID id,HttpServletResponse response)
            throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var job=jobs.job(user,id);
        if (job.isEmpty()) { response.sendError(404); return null; }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toState(job.get()));
    }
    @GetMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<SnapshotState> state(HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var state=jobs.state(user);
        if (state.isEmpty()) { response.sendError(403); return null; }
        var value=state.get().officialValue();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SnapshotState(state.get().connected(),state.get().restricted(),
                        state.get().job()==null ? null : toState(state.get().job()),
                        value==null ? null : value.decimal(),value==null ? null : value.currency(),
                        value==null ? null : value.measuredAt().toString(),
                        value==null ? null : value.composedAt().toString(),
                        value==null ? null : value.publishedAt().toString(),
                        value==null ? null : value.snapshotId(),
                        state.get().revoked() ? Boolean.TRUE : null,
                        state.get().accountChanged() ? Boolean.TRUE : null));
    }
    @PostMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/inputs",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CollectionJobState> startInitialInputs(@RequestBody CollectionRequest body,
            @RequestHeader(name=IdempotencyKey.HEADER,required=false) @Nullable String key,
            HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if(user==null){response.sendError(403);return null;}
        var job=jobs.admitInitialInputs(user,body.intentId(),key==null?UUID.randomUUID().toString():key);
        if(job.isEmpty()){response.sendError(409);return null;}
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(toCollectionState(job.get()));
    }
    @GetMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/inputs",produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CollectionState> initialInputs(@RequestParam UUID intentId,
            HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if(user==null){response.sendError(403);return null;}
        var state=jobs.initialCollectionState(user,intentId);
        if(!state.permitted()){response.sendError(403);return null;}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CollectionState(state.job()==null?null:toCollectionState(state.job())));
    }
    @PostMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/cashflow-classification",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CashflowClassificationState> classifyCashflows(@RequestBody CollectionRequest body,
            @RequestHeader(name=IdempotencyKey.HEADER,required=false) @Nullable String key,
            HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if(user==null){response.sendError(403);return null;}
        var job=jobs.admitCashflowClassification(user,body.intentId(),key==null?UUID.randomUUID().toString():key);
        if(job.isEmpty()){response.sendError(409);return null;}
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(toClassificationState(job.get()));
    }
    @GetMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/cashflow-classification",
            produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CashflowClassificationState> cashflowClassification(@RequestParam UUID intentId,
            HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if(user==null){response.sendError(403);return null;}
        var state=jobs.cashflowClassificationState(user,intentId);
        if(!state.permitted()){response.sendError(403);return null;}
        if(state.job()==null) return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toClassificationState(state.job()));
    }
    /**
     * Story 2.6 event-time USD conversion. Same contract as classification: POST answers 202 or
     * 409, GET answers 200, 204 (no attempt yet) or 403, and every answer is {@code no-store}.
     * The body never carries a USD value — only status, counts, policy versions and the held flows.
     */
    @PostMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/cashflow-conversion",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CashflowConversionState> convertCashflows(@RequestBody CollectionRequest body,
            @RequestHeader(name=IdempotencyKey.HEADER,required=false) @Nullable String key,
            HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if(user==null){response.sendError(403);return null;}
        var job=jobs.admitCashflowConversion(user,body.intentId(),key==null?UUID.randomUUID().toString():key);
        if(job.isEmpty()){response.sendError(409);return null;}
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(toConversionState(job.get()));
    }
    @GetMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/cashflow-conversion",
            produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CashflowConversionState> cashflowConversion(@RequestParam UUID intentId,
            HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if(user==null){response.sendError(403);return null;}
        var state=jobs.cashflowConversionState(user,intentId);
        if(!state.permitted()){response.sendError(403);return null;}
        if(state.job()==null) return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toConversionState(state.job()));
    }
    /** Story 2.7 publication attempt. The plan id appears only after it published. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PublicationJobState(UUID jobId,String status,@Nullable String failureCode,boolean retryable,
            @Nullable UUID planId,String createdAt,@Nullable String finishedAt) {}

    /**
     * The intent's publication state. {@code startable} is judged by the admission's own readiness, so
     * the screen offers a start exactly when the admission would accept one; {@code reason} is the
     * admission's refusal code when it would not (and no attempt is in progress).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PublicationView(@Nullable PublicationJobState job,boolean startable,@Nullable String reason) {}

    /**
     * 409 reasons → safe messages. A closed map: every refusal code the publication admission can return
     * has a sentence here, and only the stable code travels as the machine value.
     */
    static final java.util.Map<String,String> PUBLICATION_REFUSALS=java.util.Map.of(
            IdempotencyKey.CONFLICT_CODE,"같은 요청 키가 다른 계획 조건에 이미 쓰였습니다. 새로 요청해 주세요.",
            "ACTIVE_PLAN_EXISTS",PlanConditionController.MESSAGE_ACTIVE_PLAN_EXISTS,
            "PLAN_CONDITIONS_REQUIRED","저장된 계획 조건을 쓸 수 없습니다. 계획 조건을 다시 확인해 주세요.",
            "PLAN_PERIOD_ENDED","계획 종료 시각이 지나 이 조건으로는 계획을 만들 수 없습니다. 새 조건을 정해 주세요.",
            "INPUTS_NOT_READY","계산에 쓸 입력이 준비되지 않았습니다. 수집·판정·환산을 먼저 완료해 주세요.",
            "CALCULATION_POLICY_UNVERIFIED","계산 정책의 고정 예제를 확인하지 못해 발행을 받지 않았습니다. 서비스 상태를 확인한 뒤 다시 시도해 주세요.",
            "CALCULATION_RANGE_EXCEEDED","같은 입력의 계산이 허용 범위를 벗어났습니다. 계획 조건을 다시 정해 주세요.",
            "CALCULATION_INPUT_INVALID","같은 입력의 계산 형식을 확인할 수 없었습니다. 입력을 다시 준비해 주세요.");


    /**
     * Story 2.7 first publication. POST answers 202 with the attempt, 403 when the account or
     * restriction state does not permit it, 422 without a valid {@code Idempotency-Key} (the command
     * creates an immutable plan, so no key is invented for the caller), or 409 with a stable code in the
     * common error shape. GET answers 200 with the latest attempt (if any) and whether a new one would
     * start, or 403 when the account itself is not readable. Every answer is {@code no-store}.
     */
    @PostMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/plan-publication",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> publishPlan(@RequestBody CollectionRequest body,
            @RequestHeader(name=IdempotencyKey.HEADER,required=false) @Nullable String key,
            jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if(user==null){response.sendError(403);return null;}
        var admission=jobs.admitPlanPublication(user,body.intentId(),key);
        if(admission instanceof SyncJobApi.PublicationAdmission.Accepted accepted)
            return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                    .body(toPublicationState(accepted.job()));
        String code=((SyncJobApi.PublicationAdmission.Refused)admission).code();
        String correlation=ControllerSupport.correlationIdOf(request);
        // 요청 자체의 잘못인 유일한 거절: 이 command는 호출자의 키를 요구한다. 규칙·code는 IdempotencyKey가 소유한다.
        if(IdempotencyKey.REQUIRED_CODE.equals(code))
            return ControllerSupport.error(HttpStatus.UNPROCESSABLE_CONTENT,
                    new ApiErrorHandler.ApiError(code,ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED,correlation));
        String message=PUBLICATION_REFUSALS.get(code);
        if(message==null){response.sendError(403);return null;}
        return ControllerSupport.error(HttpStatus.CONFLICT,new ApiErrorHandler.ApiError(code,message,correlation));
    }
    @GetMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/plan-publication",
            produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<PublicationView> planPublication(@RequestParam UUID intentId,
            HttpServletResponse response) throws IOException {
        UUID user=ControllerSupport.currentUserId();
        if(user==null){response.sendError(403);return null;}
        var state=jobs.planPublicationState(user,intentId);
        if(!state.permitted()){response.sendError(403);return null;}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PublicationView(
                state.job()==null?null:toPublicationState(state.job()),state.startable(),state.reason()));
    }
    private static PublicationJobState toPublicationState(SyncJobApi.PublicationJob job) {
        return new PublicationJobState(job.jobId(),job.status(),job.failureCode(),job.retryable(),job.planId(),
                job.createdAt().toString(),job.finishedAt()==null?null:job.finishedAt().toString());
    }
    private static CashflowConversionState toConversionState(SyncJobApi.ConversionJob job) {
        return new CashflowConversionState(job.jobId(),job.status(),job.reasonCode(),job.retryable(),
                job.processedAt()==null?null:job.processedAt().toString(),job.flowCount(),job.convertedCount(),
                job.estimatedCount(),job.conversionRequiredCount(),job.reviewCount(),job.calculable(),
                job.pricePolicyVersion(),job.calculationPolicyVersion(),
                job.pendingAssets().stream().map(asset->new PendingAsset(asset.assetCode(),asset.count(),
                        asset.flows().stream().map(flow->new PendingFlow(flow.direction(),flow.quantity(),
                                flow.occurredAt().toString(),flow.status(),flow.reasonCode(),flow.retryable())).toList())).toList());
    }
    private static JobState toState(SyncJobApi.Job job) {
        return new JobState(job.jobId(),job.status(),job.failureReason());
    }
    private static CollectionJobState toCollectionState(SyncJobApi.CollectionJob job) {
        return new CollectionJobState(job.jobId(),job.status(),job.failureCode(),
                job.lastCheckedAt()==null?null:job.lastCheckedAt().toString());
    }
    private static CashflowClassificationState toClassificationState(SyncJobApi.ClassificationJob job) {
        return new CashflowClassificationState(job.jobId(),job.status(),job.failureCode(),
                job.processedAt()==null?null:job.processedAt().toString(),job.depositCount(),job.withdrawalCount(),
                job.excludedCount(),job.reviewCount(),job.reasonCode());
    }
}
