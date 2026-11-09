package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.Accepted;
import com.bokrilog.operations.OperatorCommandApi.CommandRefusal;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandApi.Finished;
import com.bokrilog.operations.OperatorCommandApi.Inspection;
import com.bokrilog.operations.OperatorCommandApi.Proposal;
import com.bokrilog.operations.OperatorCommandApi.Refusal;
import com.bokrilog.operations.OperatorCommandApi.Validation;
import com.bokrilog.operations.OperatorCommandEnvironment;
import com.bokrilog.operations.OperatorCommandEnvironment.DeletionRunFacts;
import com.bokrilog.operations.OperatorPermission;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 재시도 필요로 멈춘 계정 삭제 실행의 재대기열(Epic 5 회고 AI-1) — {@value OperatorCommandApi#REQUEUE_DELETION}. target은 삭제 요청
 * ID({@code del_…})이고 scope는 받지 않는다(원장 scope는 {@value OperatorCommandApi#DELETION_SCOPE}). 원장의 target은 요청 ID의 keyed
 * digest다 — 요청 ID는 사용자 귀속 삭제 요청을 가리키므로 원장·{@code details}·로그에 원문을 남기지 않는다. 사용자 식별자는 이 command의
 * 어느 값에도 없다.
 *
 * <p>요청 세션을 잃은 사용자는 삭제 중 계정이라 다시 로그인할 수 없다 — 그 실행을 다시 대기열에 넣는 길이 이 command다. 실행 하나를
 * {@code RETRY_NEEDED → QUEUED}로 되돌릴 뿐 단계 상태는 바꾸지 않는다(실패·미실행 단계만 다시 도는 사용자 재시도와 같은 의미).
 *
 * <p>확정의 순서: (엔진: 잠금 → 최종 권한) → production 재검사 → 앞서 잠그지 않는 조건부 UPDATE 한 문장(identity, {@code SKIP LOCKED}) →
 * 0행이면 잠그지 않는 읽기로 거절 code({@code TARGET_INVALID}·{@code DELETION_NOT_RETRYABLE}) → (엔진: {@code COMPLETED} — 상태·단계
 * 수·시각뿐이다). 실행 행을 기다리지 않으므로 단계 트랜잭션(실행 행 → 원장 순서)과 교착하지 않는다.
 */
final class RequeueDeletionCommand implements OperatorCommand<RequeueDeletionCommand.Requeue,
        RequeueDeletionCommand.RequeueResult> {

    /** 삭제 요청 ID의 모양 — identity가 만드는 {@code del_} 접두사와 V30 CHECK의 64자 상한. */
    static final Pattern REQUEST_ID = Pattern.compile("del_[A-Za-z0-9_-]{1,60}");

    /** 재대기열 뒤의 실행 상태. 응답·원장이 싣는 유일한 값이다. */
    static final String QUEUED = "QUEUED";

    /**
     * 운영자 재대기열 키 digest의 입력 접두사. 사용자 재시도 키({@code account-deletion-retry-key})와 달라 두 경로의 digest가 섞이지
     * 않는다 — 운영자 재대기열 뒤 사용자가 예전 키로 재시도를 재전송하면 다른 digest라 재시도 필요가 아님(409)이다.
     */
    static final String RETRY_KEY_PREFIX = "account-deletion-operator-requeue\n";

    /**
     * 검증을 지난 재대기열. 세션 무장에 실린다. 요청 ID만 든다 — 실행의 사실은 준비·확정이 그때마다 identity에서 읽는다.
     */
    record Requeue(String requestId) implements Serializable {

        @Serial private static final long serialVersionUID = 1L;
    }

    /**
     * 재대기열의 결과. 원장의 {@code COMPLETED} 행이 같은 값을 재생한다. 요청 ID·사용자 식별자를 담지 않는다.
     *
     * @param requeuedAt 접수 시각(DB)
     * @param retrySteps 다시 돌 재시도 필요 단계 수
     */
    record RequeueResult(String operationId, Instant requeuedAt, int retrySteps) {}

    private final ActorDigest digest;

    private final OperatorCommandEnvironment environment;

    RequeueDeletionCommand(ActorDigest digest, OperatorCommandEnvironment environment) {
        this.digest = digest;
        this.environment = environment;
    }

    @Override
    public Set<String> names() {
        return Set.of(OperatorCommandApi.REQUEUE_DELETION);
    }

    @Override
    public Set<OperatorPermission> permissions() {
        return EnumSet.of(OperatorPermission.COMMAND);
    }

    @Override
    public Class<Requeue> payloadType() {
        return Requeue.class;
    }

    @Override
    public Class<RequeueResult> resultType() {
        return RequeueResult.class;
    }

    /** 재대기열은 scope를 받지 않는다 — 대상은 요청 ID 하나이고 범위는 그 실행이다. */
    @Override
    public Proposal parse(String command, JsonNode body, Proposal common) {
        return new Proposal(command, null, common.target(), common.reason(), common.actorClaimed());
    }

    /**
     * envelope 검증: 요청 ID의 모양과 사유. 실행의 상태와 환경 게이트는 준비 뒤 판정({@link #inspect})이 멱등 키·재인증 판정 <b>뒤</b>에
     * 본다(3.2와 같은 준비 순서).
     */
    @Override
    public Validation validate(Proposal proposal) {
        String requestId = proposal.target() == null ? "" : proposal.target().strip();
        if (!REQUEST_ID.matcher(requestId).matches()) {
            return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(OperatorCommandApi.REASON_REQUIRED);
        }
        if (!OperatorEnvelopeService.isAcceptableReason(reason)) {
            return new Validation.Invalid(OperatorCommandApi.REASON_INVALID);
        }
        String ledgerTarget = this.digest.ofDeletionRequest(requestId);
        String requestDigest = OperatorEnvelopeService.sha256(OperatorCommandApi.REQUEUE_DELETION + "\n"
                + OperatorCommandApi.DELETION_SCOPE + "\n" + ledgerTarget + "\n" + reason);
        return new Validation.Valid(new Accepted(OperatorCommandApi.REQUEUE_DELETION, OperatorCommandApi.DELETION_SCOPE,
                requestId, ledgerTarget, reason, OperatorEnvelopeService.deletionRequeuePhrase(requestId),
                OperatorCommandApi.POLICY_VERSION, permissions(), requestDigest, new Requeue(requestId)));
    }

    /**
     * 거절 행: scope는 이 command의 고정 scope, target은 요청 ID 모양일 때만 그 keyed digest로 남긴다 — 요청 ID 원문은 거절 행에도
     * 들어가지 않는다(일반 규칙은 모양이 맞는 target을 원문으로 남긴다).
     */
    @Override
    public RefusedLedger refusedLedger(Proposal proposal, RefusedLedger common) {
        String target = proposal.target() == null ? "" : proposal.target().strip();
        return new RefusedLedger(OperatorCommandApi.DELETION_SCOPE,
                REQUEST_ID.matcher(target).matches() ? this.digest.ofDeletionRequest(target)
                        : OperatorEnvelopeService.UNREADABLE,
                common.keepReason());
    }

    /**
     * 준비 뒤 판정 — production이면 {@code DELETION_REQUEUE_UNAVAILABLE}, 실행이 없으면 {@code TARGET_INVALID}, 재시도 필요가 아니면
     * {@code DELETION_NOT_RETRYABLE}이다. 미리보기는 요청 ID·요청 시각·멈춘 시각·실패 단계(단계·실패 code·시도 수)뿐이다.
     */
    @Override
    public Inspection inspect(Accepted accepted, Requeue requeue) {
        if (!this.environment.deletionRequeueAvailable()) {
            return new Inspection.Refused(new Refusal(OperatorCommandApi.DELETION_REQUEUE_UNAVAILABLE, null));
        }
        DeletionRunFacts run = this.environment.deletionRun(requeue.requestId()).orElse(null);
        // 요청 행이 없는 재시도 필요 실행은 Worker가 집지 못한다 — 확정의 0행 판정과 같이 없는 대상이다.
        if (run == null || (run.retryNeeded() && run.requestedAt() == null)) {
            return new Inspection.Refused(new Refusal(OperatorCommandApi.TARGET_INVALID, null));
        }
        if (!run.retryNeeded()) {
            return new Inspection.Refused(new Refusal(OperatorCommandApi.DELETION_NOT_RETRYABLE, null));
        }
        return new Inspection.Ready(preview(run));
    }

    /** 실행 하나의 화면 값 — 목록 항목과 준비 미리보기가 같은 모양이다. 사용자 식별자가 없다. */
    static Map<String, Object> preview(DeletionRunFacts run) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("requestId", run.requestId());
        // 값이 없으면 null 그대로 싣는다(값 없음을 생략하지 않는다).
        preview.put("requestedAt", run.requestedAt() == null ? null : run.requestedAt().toString());
        preview.put("stoppedAt", run.stoppedAt() == null ? null : run.stoppedAt().toString());
        preview.put("failedSteps", run.failedSteps().stream().map(step -> {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("step", step.step());
            failed.put("failureCode", step.failureCode());
            failed.put("attempts", step.attempts());
            return failed;
        }).toList());
        return preview;
    }

    @Override
    public Done<RequeueResult> complete(OperatorEnvelopeService engine, Envelope envelope, Requeue requeue) {
        // 준비 뒤 환경이 바뀌었을 수 있다(같은 컨텍스트의 재해석). 확정 순간에 다시 본다.
        if (!this.environment.deletionRequeueAvailable()) {
            throw new CommandRefusal(OperatorCommandApi.DELETION_REQUEUE_UNAVAILABLE);
        }
        String retryKeyDigest = OperatorEnvelopeService.sha256(RETRY_KEY_PREFIX + envelope.operationId());
        RequeueResult result = switch (this.environment.requeueDeletion(requeue.requestId(), retryKeyDigest)) {
            case OperatorCommandEnvironment.DeletionRequeue.Requeued requeued ->
                    new RequeueResult(envelope.operationId(), requeued.requeuedAt(), requeued.retrySteps());
            case OperatorCommandEnvironment.DeletionRequeue.NotRetryable ignored ->
                    throw new CommandRefusal(OperatorCommandApi.DELETION_NOT_RETRYABLE);
            case OperatorCommandEnvironment.DeletionRequeue.NotFound ignored ->
                    throw new CommandRefusal(OperatorCommandApi.TARGET_INVALID);
        };
        // 상태·단계 수·시각뿐이다. 요청 ID(원장 target의 digest가 이미 있다)·사용자 식별자는 넣지 않는다.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", QUEUED);
        details.put("retrySteps", result.retrySteps());
        details.put("requeuedAt", result.requeuedAt().toString());
        return new Done<>(result, details);
    }

    @Override
    public String grantRecord(RequeueResult result) {
        return result.operationId() + "|" + QUEUED + "|" + result.retrySteps() + "|" + result.requeuedAt();
    }

    @Override
    public Finished afterCommit(OperatorEnvelopeService engine, String requestedCommand, Envelope envelope,
            RequeueResult result, String correlationId) {
        // 상태·단계 수만 남긴다. 요청 ID·사유·수행자는 남기지 않는다.
        return new Finished(view(requestedCommand, result), "status=" + QUEUED + " retrySteps=" + result.retrySteps());
    }

    @Override
    public Map<String, Object> replay(OperatorEnvelopeService engine, String requestedCommand, String operationId,
            String ledgerTarget, JsonNode details) {
        String status = CommandJson.text(details, "status");
        Instant requeuedAt = CommandJson.instant(details, "requeuedAt");
        Long retrySteps = CommandJson.number(details, "retrySteps");
        if (!QUEUED.equals(status) || requeuedAt == null || retrySteps == null) {
            throw new IllegalStateException("삭제 재대기열의 완료 행을 읽을 수 없다.");
        }
        return view(requestedCommand, new RequeueResult(operationId, requeuedAt, Math.toIntExact(retrySteps)));
    }

    private static Map<String, Object> view(String command, RequeueResult result) {
        Map<String, Object> view = OperatorEnvelopeService.completedView(result.operationId(), command);
        view.put("status", QUEUED);
        view.put("requeuedAt", result.requeuedAt().toString());
        view.put("retrySteps", result.retrySteps());
        return view;
    }
}
