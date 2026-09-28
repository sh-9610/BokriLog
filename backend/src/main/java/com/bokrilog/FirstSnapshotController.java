package com.bokrilog;

import com.bokrilog.sync.SyncJobApi;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
            @Nullable UUID snapshotId) {}
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
    @Nullable ResponseEntity<JobState> start(@RequestHeader(name="Idempotency-Key",required=false)
            @Nullable String key,HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
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
        UUID user=currentUserId();
        if (user==null) { response.sendError(403); return null; }
        var job=jobs.job(user,id);
        if (job.isEmpty()) { response.sendError(404); return null; }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toState(job.get()));
    }
    @GetMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<SnapshotState> state(HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
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
                        value==null ? null : value.snapshotId()));
    }
    @PostMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/inputs",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CollectionJobState> startInitialInputs(@RequestBody CollectionRequest body,
            @RequestHeader(name="Idempotency-Key",required=false) @Nullable String key,
            HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if(user==null){response.sendError(403);return null;}
        var job=jobs.admitInitialInputs(user,body.intentId(),key==null?UUID.randomUUID().toString():key);
        if(job.isEmpty()){response.sendError(409);return null;}
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(toCollectionState(job.get()));
    }
    @GetMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/inputs",produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CollectionState> initialInputs(@RequestParam UUID intentId,
            HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
        if(user==null){response.sendError(403);return null;}
        var state=jobs.initialCollectionState(user,intentId);
        if(!state.permitted()){response.sendError(403);return null;}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CollectionState(state.job()==null?null:toCollectionState(state.job())));
    }
    @PostMapping(path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/cashflow-classification",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<CashflowClassificationState> classifyCashflows(@RequestBody CollectionRequest body,
            @RequestHeader(name="Idempotency-Key",required=false) @Nullable String key,
            HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
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
        UUID user=currentUserId();
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
            @RequestHeader(name="Idempotency-Key",required=false) @Nullable String key,
            HttpServletResponse response) throws IOException {
        UUID user=currentUserId();
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
        UUID user=currentUserId();
        if(user==null){response.sendError(403);return null;}
        var state=jobs.cashflowConversionState(user,intentId);
        if(!state.permitted()){response.sendError(403);return null;}
        if(state.job()==null) return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toConversionState(state.job()));
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
    private static @Nullable UUID currentUserId() {
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
