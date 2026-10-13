package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.Accepted;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandApi.Finished;
import com.bokrilog.operations.OperatorCommandApi.Inspection;
import com.bokrilog.operations.OperatorCommandApi.LiftResult;
import com.bokrilog.operations.OperatorCommandApi.LiftTarget;
import com.bokrilog.operations.OperatorCommandApi.Proposal;
import com.bokrilog.operations.OperatorCommandApi.Refusal;
import com.bokrilog.operations.OperatorCommandApi.Validation;
import com.bokrilog.operations.OperatorCommandEnvironment;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 제한 해제(Story 3.4) — {@value OperatorCommandApi#LIFT_RESTRICTION}. target은 제한 ID 하나이고 범위는 그 제한의 것이다.
 *
 * <p>확정 본문(제한 행 잠금 → 열림·신호 계보 재검사 → 조건부 UPDATE 한 문장 → 기대 단계)은 엔진의
 * {@link OperatorEnvelopeService#completeLift}에 있다 — 해제 문장의 유일한 호출 자리를 {@code OperatorRecoveryTest}가 그
 * 파일로 고정한다. 이 전략은 그 앞뒤(검증·미리보기·커밋 뒤 smoke·재전송)를 갖는다.
 *
 * <p><b>커밋 뒤 smoke(AC-5).</b> 해제 트랜잭션이 커밋되면 같은 대상 차원으로 evaluator를 다시 불러 그 제한이 더는 보고되지
 * 않는지와 남는 제한이 여전히 막는지를 본다. 어긋나면 해제를 되돌리지 않고 같은 범위의 새 제한과 {@code FAILED}
 * ({@code SMOKE_FAILED}) 행을 별도 트랜잭션으로 쓴다 — 해제 사실과 되돌림 사실이 둘 다 원장에 남는다.
 */
final class LiftRestrictionCommand implements OperatorCommand<LiftRestrictionCommand.Lift,
        LiftRestrictionCommand.LiftCompletion> {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LiftRestrictionCommand.class);

    /** 배포 버전의 모양. */
    private static final Pattern DEPLOY_VERSION =
            Pattern.compile("[A-Za-z0-9._+-]{1," + OperatorCommandApi.MAX_DEPLOY_VERSION_CHARS + "}");

    /** smoke 결말. 통과했거나, 어긋나 같은 범위를 새 제한으로 되돌렸거나, 원장에 결말이 아직 없다. */
    static final String SMOKE_PASSED = OperatorCommandApi.LiftSmoke.PASSED.name();

    static final String SMOKE_REVERTED = OperatorCommandApi.LiftSmoke.REVERTED.name();

    static final String SMOKE_PENDING = OperatorCommandApi.LiftSmoke.PENDING.name();

    /**
     * 해제의 증거 입력 원문. 사유({@link Proposal#reason})가 원인이다.
     *
     * @param conditions 확인한 복구 조건 code. 본문에 없으면 {@code null}
     */
    record RecoveryInput(@Nullable List<String> conditions, @Nullable String verification,
            @Nullable String evidenceRef, @Nullable String deployVersion) {}

    /**
     * 검증을 지난 해제 요청. 세션 무장에 실린다.
     *
     * @param current 준비 시각의 제한. 그 ID의 제한이 없으면 {@code null}
     * @param conditions {@link OperatorCommandApi#RECOVERY_CONDITIONS} 순서의 확인한 조건
     */
    record Lift(UUID restrictionId, @Nullable LiftTarget current, List<String> conditions, String verification,
            String evidenceRef, String deployVersion) implements Serializable {

        @Serial private static final long serialVersionUID = 1L;

        Lift {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * 해제 확정 본문의 결말.
     *
     * @param target 준비가 본 해제 대상. smoke가 그 대상 차원으로 evaluator를 다시 읽는다
     * @param remaining 원장 잠금 아래에서 센 남는 제한. 커밋 뒤 smoke가 이것이 여전히 막는지 본다
     * @param details {@code COMPLETED} 행의 {@code details}
     */
    record LiftCompletion(LiftResult result, LiftTarget target, List<ActiveRestriction> remaining,
            Map<String, Object> details) {

        LiftCompletion {
            remaining = List.copyOf(remaining);
        }
    }

    /**
     * 해제 커밋 뒤의 smoke.
     *
     * @param status {@value #SMOKE_PASSED}·{@value #SMOKE_REVERTED}·{@value #SMOKE_PENDING}(재전송이 원장에서 결말을 찾지
     *     못했다 — smoke가 도는 중이거나 결말 기록이 실패했다)
     * @param reopenedStages 다시 열린 단계. 되돌렸거나 결말을 모르면 비어 있다
     * @param stillBlockedStages 해제한 제한의 단계 가운데 여전히 막힌 것. 되돌렸으면 그 단계 전부, 결말을 모르면 비어 있다
     */
    record Smoke(String status, List<String> reopenedStages, List<String> stillBlockedStages) {}

    private final OperationalRestrictionRepository restrictions;

    /** smoke의 판독. 차단 판정의 유일한 출처라 smoke도 이것만 본다. */
    private final OperationalRestrictionApi evaluator;

    private final OperatorCommandEnvironment environment;

    LiftRestrictionCommand(OperationalRestrictionRepository restrictions, OperationalRestrictionApi evaluator,
            OperatorCommandEnvironment environment) {
        this.restrictions = restrictions;
        this.evaluator = evaluator;
        this.environment = environment;
    }

    @Override
    public Set<String> names() {
        return Set.of(OperatorCommandApi.LIFT_RESTRICTION);
    }

    @Override
    public Set<OperatorPermission> permissions() {
        return EnumSet.of(OperatorPermission.COMMAND);
    }

    @Override
    public Class<Lift> payloadType() {
        return Lift.class;
    }

    @Override
    public Class<LiftCompletion> resultType() {
        return LiftCompletion.class;
    }

    /**
     * 해제는 scope를 받지 않는다 — 대상은 제한 ID 하나이고 범위는 그 제한의 것이다. 증거 필드가 문자열이 아니거나 조건이
     * 문자열 배열이 아니면 400이다. 비어 있는 것은 검증이 거절한다(422).
     */
    @Override
    public Proposal parse(String command, JsonNode body, Proposal common) {
        // 조건은 넷이다. 그보다 긴 배열은 어떻게 읽어도 겹치거나 모르는 code를 담는다 — 본문 모양 오류다.
        RecoveryInput recovery = new RecoveryInput(
                CommandJson.stringsOrNull(body, "conditions", OperatorCommandApi.RECOVERY_CONDITIONS.size()),
                CommandJson.stringOrNull(body, "verification"), CommandJson.stringOrNull(body, "evidenceRef"),
                CommandJson.stringOrNull(body, "deployVersion"));
        return new Proposal(command, null, common.target(), common.reason(), common.actorClaimed(), recovery);
    }

    /**
     * envelope 검증(AC-1·AC-2). target(제한 ID)의 형식, 복구 조건 네 확인, 원인(사유)·수행한 검증·증거 참조의 사유 규칙, 배포
     * 버전의 모양을 본다. 그다음 제한 표에서 그 제한의 지금 사실을 읽어 원장 scope와 확인 문구를 정한다 — 없거나 닫힌 제한의
     * 거절은 준비 뒤 판정({@link #inspect})이 멱등 키·재인증 판정 <b>뒤</b>에 한다(3.2와 같은 준비 순서).
     *
     * <p>원장의 target은 제한 ID다. 제한 ID는 사용자 귀속 원식별자가 아니다(난수 행 ID). 요청 digest는 제한 ID와 증거 입력
     * 전부로 만든다 — 같은 키로 다른 증거를 보내면 충돌이다.
     */
    @Override
    public Validation validate(Proposal proposal) {
        String text = proposal.target() == null ? "" : proposal.target().strip();
        if (!OperatorEnvelopeService.UUID_TEXT.matcher(text).matches()) {
            return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
        }
        UUID restrictionId = UUID.fromString(text);
        RecoveryInput recovery = CommandJson.inputOf(proposal, RecoveryInput.class);
        List<String> given = recovery == null || recovery.conditions() == null ? List.of() : recovery.conditions();
        Set<String> seen = new HashSet<>();
        for (String condition : given) {
            // 모르는 code와 같은 code의 반복은 형식 오류다 — 넷을 확인했다는 사실을 개수로 꾸밀 수 없다.
            if (!OperatorCommandApi.RECOVERY_CONDITIONS.contains(condition) || !seen.add(condition)) {
                return new Validation.Invalid(OperatorCommandApi.RECOVERY_EVIDENCE_INVALID);
            }
        }
        if (!seen.containsAll(OperatorCommandApi.RECOVERY_CONDITIONS)) {
            return new Validation.Invalid(OperatorCommandApi.RECOVERY_CONDITION_MISSING);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(OperatorCommandApi.REASON_REQUIRED);
        }
        if (!OperatorEnvelopeService.isAcceptableReason(reason)) {
            return new Validation.Invalid(OperatorCommandApi.REASON_INVALID);
        }
        String verification = recovery == null || recovery.verification() == null ? ""
                : recovery.verification().strip();
        String evidenceRef = recovery == null || recovery.evidenceRef() == null ? "" : recovery.evidenceRef().strip();
        String deployVersion = recovery == null || recovery.deployVersion() == null ? ""
                : recovery.deployVersion().strip();
        if (verification.isEmpty() || evidenceRef.isEmpty() || deployVersion.isEmpty()) {
            return new Validation.Invalid(OperatorCommandApi.RECOVERY_EVIDENCE_REQUIRED);
        }
        if (!OperatorEnvelopeService.isAcceptableReason(verification)
                || !OperatorEnvelopeService.isAcceptableReason(evidenceRef)
                || !DEPLOY_VERSION.matcher(deployVersion).matches()) {
            return new Validation.Invalid(OperatorCommandApi.RECOVERY_EVIDENCE_INVALID);
        }
        LiftTarget current = this.restrictions.liftFacts(restrictionId)
                .map(facts -> new LiftTarget(facts.feature(), facts.scope(), facts.target(), facts.targetErased(),
                        facts.origin(), facts.open(), facts.linkedSignals(), facts.latestSignalAt()))
                .orElse(null);
        Lift lift = new Lift(restrictionId, current, OperatorCommandApi.RECOVERY_CONDITIONS, verification,
                evidenceRef, deployVersion);
        String target = restrictionId.toString();
        String ledgerScope = current == null ? OperatorEnvelopeService.UNREADABLE
                : OperatorEnvelopeService.ledgerScope(current.feature(), current.scope());
        String phrase = current == null ? "복구 " + target
                : liftPhrase(current.feature(), current.scope(), current.target(), current.targetErased());
        String digest = OperatorEnvelopeService.sha256(OperatorCommandApi.LIFT_RESTRICTION + "\n" + target + "\n"
                + reason + "\n" + verification + "\n" + evidenceRef + "\n" + deployVersion + "\n"
                + String.join(",", OperatorCommandApi.RECOVERY_CONDITIONS));
        return new Validation.Valid(new Accepted(OperatorCommandApi.LIFT_RESTRICTION, ledgerScope, target, target,
                reason, phrase, OperatorCommandApi.POLICY_VERSION, permissions(), digest, lift));
    }

    /**
     * 준비 뒤 대상 판정 — 없는 제한은 {@code TARGET_INVALID}, 이미 닫혔거나 시작 전인 제한은 {@code RESTRICTION_NOT_ACTIVE}다.
     * 열린 제한이면 다시 열릴 단계·남는 제한·신호 계보를 미리 보인다(식별자 없음).
     */
    @Override
    public Inspection inspect(Accepted accepted, Lift lift) {
        LiftTarget current = lift.current();
        if (current == null) {
            return new Inspection.Refused(new Refusal(OperatorCommandApi.TARGET_INVALID, null));
        }
        if (!current.open()) {
            return new Inspection.Refused(new Refusal(OperatorCommandApi.RESTRICTION_NOT_ACTIVE, null));
        }
        OperatorCommandEnvironment.LiftImpact impact = this.environment.liftImpact(lift.restrictionId(), current);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("reopenedStages", impact.reopenedStages());
        preview.put("remainingRestrictions", impact.remainingRestrictions().stream().map(other -> {
            Map<String, Object> remaining = new LinkedHashMap<>();
            remaining.put("feature", other.feature());
            remaining.put("scope", other.scope());
            remaining.put("origin", other.origin());
            return remaining;
        }).toList());
        preview.put("linkedSignals", impact.linkedSignals());
        // 신호가 없으면 null 그대로 싣는다(값 없음을 생략하지 않는다).
        preview.put("latestSignalAt", impact.latestSignalAt() == null ? null : impact.latestSignalAt().toString());
        return new Inspection.Ready(preview);
    }

    /**
     * 해제의 증거(AC-2) — 요청·완료 행에 남는다. 원인은 원장의 사유 열이고 여기에는 나머지가 든다. 금융값·이메일·사용자
     * 원식별자가 없다 — 텍스트는 사유 규칙을 지났고, 제한 ID는 난수 행 ID다.
     */
    @Override
    public Map<String, Object> requestedDetails(Lift lift) {
        return evidenceOf(lift);
    }

    static Map<String, Object> evidenceOf(Lift lift) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("conditions", lift.conditions());
        details.put("verification", lift.verification());
        details.put("evidenceRef", lift.evidenceRef());
        details.put("deployVersion", lift.deployVersion());
        details.put("restrictionId", lift.restrictionId().toString());
        LiftTarget current = lift.current();
        details.put("linkedSignals", current == null ? 0 : current.linkedSignals());
        return details;
    }

    @Override
    public Done<LiftCompletion> complete(OperatorEnvelopeService engine, Envelope envelope, Lift lift) {
        LiftCompletion completion = engine.completeLift(envelope, lift);
        return new Done<>(completion, completion.details());
    }

    @Override
    public String grantRecord(LiftCompletion completion) {
        LiftResult result = completion.result();
        return result.operationId() + "|" + result.restrictionId() + "|" + result.liftedAt();
    }

    @Override
    public Finished afterCommit(OperatorEnvelopeService engine, String requestedCommand, Envelope envelope,
            LiftCompletion completion, String correlationId) {
        LiftResult lifted = completion.result();
        Smoke smoke = smoke(engine, envelope, completion, correlationId);
        // 기능·scope·smoke 결말만 남긴다. 제한 ID·target·사유·수행자는 남기지 않는다.
        return new Finished(view(requestedCommand, lifted, smoke),
                "feature=" + lifted.feature() + " scope=" + lifted.scope() + " smoke=" + smoke.status());
    }

    /**
     * 해제 커밋 뒤의 smoke(AC-5). 해제한 제한이 막던 대상 차원으로 evaluator의 후보 전체
     * ({@link OperationalRestrictionApi#matching})를 다시 읽는다:
     *
     * <ol>
     *   <li>해제한 제한이 후보에 없다. 가장 구체적인 한 행만 보면 같은 구체성의 다른 행이 먼저 보고돼 해제한 행이 남아
     *       있어도 통과한다 — 그래서 후보 전체에서 그 ID를 찾는다.
     *   <li>원장 잠금 아래에서 센 남는 제한이 모두 후보에 있다. 빠진 행은 그 행이 지금도 열려 있는지 다시 본다 — 그사이
     *       다른 운영자가 정상 해제했거나 계정 삭제로 비식별된 행은 실패로 세지 않는다.
     * </ol>
     *
     * <p>결말은 언제나 원장에 남는다: 통과는 {@code APPROVED}({@code SMOKE_PASSED}) 행 하나, 어긋나거나 판독이 실패하면 같은
     * 범위의 새 제한과 {@code FAILED}({@code SMOKE_FAILED}) 행을 별도 트랜잭션으로 쓴다(fail-closed). 그 쓰기가 실패하면
     * 예외가 올라가 응답은 500이고, 같은 키의 재전송은 결말 행이 없으므로 {@value #SMOKE_PENDING}이다 — 통과로 재생하지 않는다.
     */
    private Smoke smoke(OperatorEnvelopeService engine, Envelope envelope, LiftCompletion completion,
            String correlationId) {
        LiftResult lifted = completion.result();
        boolean passed;
        try {
            RestrictionSubject subject = this.environment.subjectOf(completion.target());
            Set<RestrictionFeature> features = EnumSet.of(lifted.feature());
            for (ActiveRestriction other : completion.remaining()) {
                features.add(other.feature());
            }
            Set<UUID> reported = new HashSet<>();
            for (ActiveRestriction found : this.evaluator.matching(features, subject, Set.of())) {
                reported.add(found.restrictionId());
            }
            passed = !reported.contains(lifted.restrictionId());
            for (ActiveRestriction other : completion.remaining()) {
                if (!passed) {
                    break;
                }
                if (!reported.contains(other.restrictionId())) {
                    // 빠졌다. 지금도 열려 있고 원 target이 있는데 막지 않는다면 실패다. 정상적으로 닫혔으면 실패가 아니다.
                    passed = this.evaluator.current(other.restrictionId())
                            .filter(still -> !still.targetErased()).isEmpty();
                }
            }
        } catch (RuntimeException unreadable) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("operator lift smoke could not read: failure={}", unreadable.getClass().getName());
            }
            passed = false;
        }
        if (passed) {
            engine.recordSmokePassed(envelope, lifted, correlationId);
            return passed(lifted);
        }
        LOG.warn("operator lift smoke failed: feature={} scope={}", lifted.feature(), lifted.scope());
        engine.revertLift(envelope, lifted, correlationId);
        return reverted(lifted);
    }

    @Override
    public Map<String, Object> replay(OperatorEnvelopeService engine, String requestedCommand, String operationId,
            String ledgerTarget, JsonNode details) {
        UUID restrictionId = CommandJson.uuid(details, "restrictionId");
        String feature = CommandJson.text(details, "feature");
        String scope = CommandJson.text(details, "restrictionScope");
        Instant liftedAt = CommandJson.instant(details, "liftedAt");
        if (restrictionId == null || feature == null || scope == null || liftedAt == null) {
            throw new IllegalStateException("해제의 완료 행을 읽을 수 없다.");
        }
        LiftResult lifted = new LiftResult(operationId, restrictionId, RestrictionFeature.valueOf(feature),
                RestrictionScope.valueOf(scope), liftedAt, CommandJson.strings(details, "reopenedStages"),
                CommandJson.strings(details, "stillBlockedStages"));
        Smoke smoke = switch (engine.liftSmoke(operationId)) {
            case PASSED -> passed(lifted);
            case REVERTED -> reverted(lifted);
            case PENDING -> new Smoke(SMOKE_PENDING, List.of(), List.of());
        };
        return view(requestedCommand, lifted, smoke);
    }

    private Smoke passed(LiftResult lifted) {
        return new Smoke(SMOKE_PASSED, lifted.reopenedStages(), lifted.stillBlockedStages());
    }

    /** 되돌린 해제의 smoke: 다시 열린 단계가 없고 해제한 제한의 단계가 모두 막혀 있다. */
    private Smoke reverted(LiftResult lifted) {
        return new Smoke(SMOKE_REVERTED, List.of(), this.environment.stages(lifted.feature(), lifted.scope()));
    }

    private static Map<String, Object> view(String command, LiftResult lifted, Smoke smoke) {
        Map<String, Object> view = OperatorEnvelopeService.completedView(lifted.operationId(), command);
        view.put("feature", lifted.feature().name());
        view.put("scope", lifted.scope().name());
        view.put("restrictionId", lifted.restrictionId());
        view.put("liftedAt", lifted.liftedAt().toString());
        Map<String, Object> smokeView = new LinkedHashMap<>();
        smokeView.put("status", smoke.status());
        smokeView.put("reopenedStages", smoke.reopenedStages());
        smokeView.put("stillBlockedStages", smoke.stillBlockedStages());
        view.put("smoke", smokeView);
        return view;
    }

    /**
     * 해제의 확인 문구: {@code 복구 <기능> <scope> <target>}. 전역은 {@code 복구 <기능> 전역}이다 — 대상명 {@code 전역}을 다시
     * 입력해야 확정된다. 비식별된 target은 digest 대신 {@code 삭제된 대상}이다.
     */
    static String liftPhrase(RestrictionFeature feature, RestrictionScope scope, String target, boolean erased) {
        String what = OperatorEnvelopeService.featureWord(feature);
        String targetLabel = erased ? "삭제된 대상" : target;
        return switch (scope) {
            case GLOBAL -> "복구 " + what + " 전역";
            case PLAN -> "복구 " + what + " 계획 " + targetLabel;
            case PORTFOLIO -> "복구 " + what + " 포트폴리오 " + targetLabel;
            case PROVIDER_CONNECTION -> "복구 " + what + " 연결 " + targetLabel;
            case CALCULATION_POLICY_VERSION -> "복구 " + what + " 계산 정책 " + targetLabel;
        };
    }
}
