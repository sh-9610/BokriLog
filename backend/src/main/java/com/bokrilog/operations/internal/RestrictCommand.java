package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.Accepted;
import com.bokrilog.operations.OperatorCommandApi.CommandRefusal;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandApi.Finished;
import com.bokrilog.operations.OperatorCommandApi.Inspection;
import com.bokrilog.operations.OperatorCommandApi.Proposal;
import com.bokrilog.operations.OperatorCommandApi.Refusal;
import com.bokrilog.operations.OperatorCommandApi.Restriction;
import com.bokrilog.operations.OperatorCommandApi.TargetCheck;
import com.bokrilog.operations.OperatorCommandApi.Validation;
import com.bokrilog.operations.OperatorCommandEnvironment;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionScope;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 영향 범위 제한 생성(Story 3.2) — 제한 command 넷({@code restrict-<기능>})이 이 전략 하나다. 기능은 command 이름이 정하고
 * ({@link OperatorCommandApi#restrictionFeatureOf}) 나머지 규칙은 같다.
 *
 * <p>원장의 scope는 {@code <기능>:<scope>}이고, 사용자에게 귀속된 target은 원장·허가·요청 digest에 keyed digest로만
 * 들어간다(AC-6). 원문은 확인 문구와 제한 행에만 있다. 대상의 존재·상태는 다른 모듈의 사실이라 루트가 본다
 * ({@link OperatorCommandEnvironment#restrictionTarget}) — 준비가 한 번, 확정이 원장 잠금 뒤 한 번 더.
 *
 * <p>확정의 순서: (엔진: 잠금 → 최종 권한) → 대상 재검사 → 활성 중복 판정 → 제한 INSERT → (엔진: {@code COMPLETED}). 잠금 뒤라
 * 같은 제한의 동시 확정 둘이 줄을 서고, 뒤의 것은 앞의 것이 커밋한 행을 보고 {@code ALREADY_RESTRICTED}로 거절된다. 최후
 * 권위는 활성 제한의 부분 유일 색인이다({@link #duplicateKeyRefusal}).
 */
final class RestrictCommand implements OperatorCommand<Restriction, RestrictCommand.RestrictionResult> {

    /** 계산 정책 버전의 모양(예: {@code CALC-1.0}). 실제로 아는 버전인지는 루트가 계산 모듈의 상수로 본다. */
    private static final Pattern POLICY_VERSION_TEXT = Pattern.compile("[A-Z][A-Z0-9]{0,15}-[0-9]{1,3}\\.[0-9]{1,3}");

    /**
     * 제한 생성의 결과. target 원문을 담지 않는다 — 재생이 원장에서 오고 원장에는 원문이 없다.
     *
     * @param effectiveFrom 제한의 효력 시작(DB 시각)
     */
    record RestrictionResult(String operationId, UUID restrictionId, RestrictionFeature feature,
            RestrictionScope scope, Instant effectiveFrom) {}

    private final OperationalRestrictionRepository restrictions;

    private final ActorDigest digest;

    private final OperatorCommandEnvironment environment;

    RestrictCommand(OperationalRestrictionRepository restrictions, ActorDigest digest,
            OperatorCommandEnvironment environment) {
        this.restrictions = restrictions;
        this.digest = digest;
        this.environment = environment;
    }

    @Override
    public Set<String> names() {
        return Set.of(OperatorCommandApi.RESTRICT_NEW_CONNECTION, OperatorCommandApi.RESTRICT_SYNC,
                OperatorCommandApi.RESTRICT_CALCULATION_DISPLAY, OperatorCommandApi.RESTRICT_ADVERTISING);
    }

    @Override
    public Set<OperatorPermission> permissions() {
        // 일반 운영 명령이다. 감사 데이터를 꺼내지 않는다.
        return EnumSet.of(OperatorPermission.COMMAND);
    }

    @Override
    public Class<Restriction> payloadType() {
        return Restriction.class;
    }

    @Override
    public Class<RestrictionResult> resultType() {
        return RestrictionResult.class;
    }

    /**
     * envelope 검증(AC-1). 조합(기능 × scope)과 target의 <b>형식</b>만 본다 — 대상의 존재·상태·중복은 다른 모듈과 제한 표의
     * 사실이라 준비 뒤 판정({@link #inspect})이 본다.
     */
    @Override
    public Validation validate(Proposal proposal) {
        RestrictionFeature feature = featureOf(proposal.command());
        RestrictionScope scope = scopeOf(proposal.scope());
        if (scope == null || !feature.admits(scope)) {
            return new Validation.Invalid(OperatorCommandApi.UNSUPPORTED_SCOPE);
        }
        String target = restrictionTarget(scope, proposal.target());
        if (target == null) {
            return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(OperatorCommandApi.REASON_REQUIRED);
        }
        if (!OperatorEnvelopeService.isAcceptableReason(reason)) {
            return new Validation.Invalid(OperatorCommandApi.REASON_INVALID);
        }
        String ledgerScope = OperatorEnvelopeService.ledgerScope(feature, scope);
        String ledgerTarget = scope.userAttributed() ? this.digest.ofTarget(scope.name(), target) : target;
        String command = proposal.command();
        return new Validation.Valid(new Accepted(command, ledgerScope, target, ledgerTarget, reason,
                restrictionPhrase(feature, scope, target), OperatorCommandApi.POLICY_VERSION, permissions(),
                OperatorEnvelopeService.sha256(command + "\n" + ledgerScope + "\n" + ledgerTarget + "\n" + reason),
                new Restriction(feature, scope, target)));
    }

    /**
     * 거절 행: scope는 아는 이름일 때만 {@code <기능>:<scope>}이고, target은 그 scope의 모양일 때만 남긴다 — 사용자 귀속
     * 자리(계획·portfolio·연결)는 digest로, 전역·계산 정책 버전은 원문으로. 모르는 scope이거나 모양이 아니면 남기지 않는다 —
     * 거절된 요청을 타고 원식별자가 원장에 들어가는 길을 막는다.
     */
    @Override
    public RefusedLedger refusedLedger(Proposal proposal, RefusedLedger common) {
        RestrictionFeature feature = featureOf(proposal.command());
        RestrictionScope known = scopeOf(proposal.scope());
        String scope = known == null ? OperatorEnvelopeService.UNREADABLE
                : OperatorEnvelopeService.ledgerScope(feature, known);
        String normalized = known == null ? null : restrictionTarget(known, proposal.target());
        String target;
        if (normalized == null) {
            // 모르는 scope이거나 그 scope의 모양이 아니다. 무엇인지 알 수 없는 값은 남기지 않는다.
            target = OperatorEnvelopeService.UNREADABLE;
        } else {
            target = known.userAttributed() ? this.digest.ofTarget(known.name(), normalized) : normalized;
        }
        return new RefusedLedger(scope, target, common.keepReason());
    }

    /**
     * 준비 뒤 대상 판정(멱등 키·재인증 판정 뒤) — 없거나 상태가 맞지 않으면 {@code TARGET_INVALID}, 이미 같은 제한이 있으면
     * {@code ALREADY_RESTRICTED}. 둘 다 아니면 영향 미리보기를 준다(수만 — 식별자도 금융값도 없다).
     */
    @Override
    public Inspection inspect(Accepted accepted, Restriction restriction) {
        if (!(this.environment.restrictionTarget(restriction) instanceof TargetCheck.Valid valid)) {
            return refused(OperatorCommandApi.TARGET_INVALID);
        }
        if (this.restrictions.isActive(restriction.feature(), restriction.scope(), restriction.target())) {
            return refused(OperatorCommandApi.ALREADY_RESTRICTED);
        }
        OperatorCommandEnvironment.RestrictionImpact impact = this.environment.restrictionImpact(restriction, valid);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("blockedStages", impact.blockedStages());
        preview.put("affectedPlans", impact.affectedPlans());
        preview.put("affectedConnections", impact.affectedConnections());
        preview.put("remainingFeatures", impact.remainingFeatures());
        return new Inspection.Ready(preview);
    }

    @Override
    public Done<RestrictionResult> complete(OperatorEnvelopeService engine, Envelope envelope, Restriction restriction) {
        // 확정 순간의 대상. 준비 뒤 계획이 끝났거나 연결이 해제됐으면 제한을 만들지 않는다.
        if (!(this.environment.restrictionTarget(restriction) instanceof TargetCheck.Valid valid)) {
            throw new CommandRefusal(OperatorCommandApi.TARGET_INVALID);
        }
        if (this.restrictions.isActive(restriction.feature(), restriction.scope(), restriction.target())) {
            throw new CommandRefusal(OperatorCommandApi.ALREADY_RESTRICTED);
        }
        UUID restrictionId = UUID.randomUUID();
        Instant effectiveFrom = this.restrictions.insert(restrictionId, restriction.feature(), restriction.scope(),
                restriction.target(), restriction.scope().userAttributed() ? valid.ownerUserId() : null,
                envelope.operationId(), RestrictionOrigin.OPERATOR);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restrictionId", restrictionId.toString());
        details.put("feature", restriction.feature().name());
        details.put("restrictionScope", restriction.scope().name());
        details.put("effectiveFrom", effectiveFrom.toString());
        return new Done<>(new RestrictionResult(envelope.operationId(), restrictionId, restriction.feature(),
                restriction.scope(), effectiveFrom), details);
    }

    @Override
    public String grantRecord(RestrictionResult result) {
        return result.operationId() + "|" + result.restrictionId();
    }

    /**
     * 활성 제한의 부분 유일 색인이 권위다. 잠금 안의 중복 판정이 놓친 같은 제한(효력 시작 전인 행 등)을 색인이 거절했다 —
     * 소비·제한 행·완료 행이 함께 롤백됐으므로 중복 거절과 같은 결말로 맺는다.
     */
    @Override
    public String duplicateKeyRefusal() {
        return OperatorCommandApi.ALREADY_RESTRICTED;
    }

    @Override
    public Finished afterCommit(OperatorEnvelopeService engine, String requestedCommand, Envelope envelope,
            RestrictionResult result, String correlationId) {
        // 기능과 scope만 남긴다. target·사유·수행자는 남기지 않는다.
        return new Finished(view(requestedCommand, result),
                "feature=" + result.feature() + " scope=" + result.scope());
    }

    @Override
    public Map<String, Object> replay(OperatorEnvelopeService engine, String requestedCommand, String operationId,
            String ledgerTarget, JsonNode details) {
        UUID restrictionId = CommandJson.uuid(details, "restrictionId");
        String feature = CommandJson.text(details, "feature");
        String scope = CommandJson.text(details, "restrictionScope");
        Instant effectiveFrom = CommandJson.instant(details, "effectiveFrom");
        if (restrictionId == null || feature == null || scope == null || effectiveFrom == null) {
            throw new IllegalStateException("제한 생성의 완료 행을 읽을 수 없다.");
        }
        return view(requestedCommand, new RestrictionResult(operationId, restrictionId,
                RestrictionFeature.valueOf(feature), RestrictionScope.valueOf(scope), effectiveFrom));
    }

    private static Map<String, Object> view(String command, RestrictionResult result) {
        Map<String, Object> view = OperatorEnvelopeService.completedView(result.operationId(), command);
        view.put("feature", result.feature().name());
        view.put("scope", result.scope().name());
        view.put("restrictionId", result.restrictionId());
        view.put("effectiveFrom", result.effectiveFrom().toString());
        return view;
    }

    private static Inspection refused(String code) {
        return new Inspection.Refused(new Refusal(code, null));
    }

    private static RestrictionFeature featureOf(String command) {
        return Objects.requireNonNull(OperatorCommandApi.restrictionFeatureOf(command), "제한 command가 아니다.");
    }

    private static @Nullable RestrictionScope scopeOf(@Nullable String text) {
        String scope = text == null ? "" : text.strip();
        for (RestrictionScope known : RestrictionScope.values()) {
            if (known.name().equals(scope)) {
                return known;
            }
        }
        return null;
    }

    /**
     * target의 형식. 전역은 {@code ALL} 하나, 계획·portfolio·연결은 정규 UUID(소문자로 정규화), 계산 정책 버전은 버전 모양.
     * 맞지 않으면 {@code null}이다.
     */
    private static @Nullable String restrictionTarget(RestrictionScope scope, @Nullable String text) {
        String target = text == null ? "" : text.strip();
        return switch (scope) {
            case GLOBAL -> RestrictionScope.GLOBAL_TARGET.equals(target) ? target : null;
            case PLAN, PORTFOLIO, PROVIDER_CONNECTION ->
                    OperatorEnvelopeService.UUID_TEXT.matcher(target).matches() ? UUID.fromString(target).toString()
                            : null;
            case CALCULATION_POLICY_VERSION -> POLICY_VERSION_TEXT.matcher(target).matches() ? target : null;
        };
    }

    /**
     * 서버가 정하는 확인 문구. 전역 제한은 대상명 {@code 전역}을 담는다(AC-2) — 다시 입력해야 확정된다. 나머지는 범위 이름과
     * target 원문이다.
     */
    static String restrictionPhrase(RestrictionFeature feature, RestrictionScope scope, String target) {
        String what = OperatorEnvelopeService.featureWord(feature);
        return switch (scope) {
            case GLOBAL -> what + " 제한 전역";
            case PLAN -> what + " 제한 계획 " + target;
            case PORTFOLIO -> what + " 제한 포트폴리오 " + target;
            case PROVIDER_CONNECTION -> what + " 제한 연결 " + target;
            case CALCULATION_POLICY_VERSION -> what + " 제한 계산 정책 " + target;
        };
    }
}
