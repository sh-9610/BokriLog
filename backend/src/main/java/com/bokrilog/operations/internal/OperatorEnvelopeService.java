package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorAuditApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영 command의 envelope 검증과 원장 기록(Story 3.1).
 *
 * <p>servlet 역할에서만 배선한다 — 감사 digest 키가 그 역할의 것이고({@link ActorDigest}), 운영 command는 API 프로세스에만
 * 있다.
 *
 * <p>원장 추가는 언제나 트랜잭션 안에서 잠금 → INSERT 순서다({@link OperatorAuditRepository#lockAppends}). 호출자가 연
 * 트랜잭션이 있으면 거기 참여한다 — 추출의 완료 행은 허가 소비와 같은 트랜잭션이어야 한다.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class OperatorEnvelopeService implements OperatorCommandApi {

    /** 수행자 종류. 3.1은 운영자만 command를 낸다(SYSTEM은 3.3 자동 정지의 몫). */
    static final String ACTOR_OPERATOR = "OPERATOR";

    /** 이메일 주소처럼 보이는 조각. 사유에 이메일을 남기지 않는다(AC-4). */
    private static final Pattern EMAIL_LIKE = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    /** 제어 문자(개행 포함). 사유는 한 줄 문장이다 — 로그 위조와 화면 깨짐을 함께 막는다. */
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** 거절 행에 원문으로 남겨도 되는 scope·target의 모양. 그 밖은 {@link #UNREADABLE}이다. */
    private static final Pattern SAFE_SCOPE = Pattern.compile("[A-Z][A-Z_]{0,63}");

    private static final Pattern SAFE_TARGET = Pattern.compile("[0-9A-Za-z~:._-]{1,128}");

    /** 정규 UUID(대소문자 무관). {@link UUID#fromString}만으로는 줄인 묶음도 받는다. */
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 계산 정책 버전의 모양(예: {@code CALC-1.0}). 실제로 아는 버전인지는 루트가 계산 모듈의 상수로 본다. */
    private static final Pattern POLICY_VERSION_TEXT = Pattern.compile("[A-Z][A-Z0-9]{0,15}-[0-9]{1,3}\\.[0-9]{1,3}");

    /** 거절 행에서 형식이 맞지 않아 원문을 남기지 않은 자리. */
    private static final String UNREADABLE = "-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder OPERATION_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OperatorAuditRepository repository;

    /** 제한 표(Story 3.2). 같은 모듈의 repository다 — 제한 행은 감사 행과 같은 트랜잭션에서만 생긴다. */
    private final OperationalRestrictionRepository restrictions;

    private final ActorDigest digest;

    private final TransactionTemplate transactions;

    OperatorEnvelopeService(OperatorAuditRepository repository, OperationalRestrictionRepository restrictions,
            ActorDigest digest, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.restrictions = restrictions;
        this.digest = digest;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean isSupported(String command) {
        return AUDIT_EXPORT.equals(command) || OperatorCommandApi.restrictionFeatureOf(command) != null;
    }

    @Override
    public Set<OperatorPermission> permissionsOf(String command) {
        if (AUDIT_EXPORT.equals(command)) {
            // command이면서 감사 데이터를 꺼낸다 — 두 권한을 함께 요구한다(AC-5).
            return EnumSet.of(OperatorPermission.COMMAND, OperatorPermission.AUDIT);
        }
        if (OperatorCommandApi.restrictionFeatureOf(command) != null) {
            // 제한은 일반 운영 명령이다. 감사 데이터를 꺼내지 않는다.
            return EnumSet.of(OperatorPermission.COMMAND);
        }
        throw new IllegalArgumentException("모르는 운영 command다.");
    }

    @Override
    public Validation validate(Proposal proposal) {
        if (proposal.actorClaimed()) {
            return new Validation.Invalid(ACTOR_NOT_ACCEPTED);
        }
        if (!isSupported(proposal.command())) {
            throw new IllegalArgumentException("모르는 운영 command다.");
        }
        RestrictionFeature feature = OperatorCommandApi.restrictionFeatureOf(proposal.command());
        if (feature != null) {
            return validateRestriction(proposal, feature);
        }
        String scope = proposal.scope() == null ? "" : proposal.scope().strip();
        if (!SCOPE_AUDIT_LEDGER.equals(scope)) {
            return new Validation.Invalid(UNSUPPORTED_SCOPE);
        }
        AuditPeriod period = AuditPeriod.parseTarget(proposal.target());
        if (period == null || period.days() > MAX_EXPORT_DAYS) {
            return new Validation.Invalid(TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(REASON_REQUIRED);
        }
        if (!isAcceptableReason(reason)) {
            return new Validation.Invalid(REASON_INVALID);
        }
        String target = period.target();
        String phrase = "감사 내보내기 " + scope + " " + target;
        return new Validation.Valid(new Accepted(AUDIT_EXPORT, scope, target, target, reason, phrase, POLICY_VERSION,
                permissionsOf(AUDIT_EXPORT), sha256(AUDIT_EXPORT + "\n" + scope + "\n" + target + "\n" + reason),
                null));
    }

    /**
     * 제한 생성 command의 envelope 검증(Story 3.2 AC-1). 조합(기능 × scope)과 target의 <b>형식</b>만 본다 — 대상의
     * 존재·상태·중복은 다른 모듈과 제한 표의 사실이라 루트가 본다.
     *
     * <p>원장의 scope는 {@code <기능>:<scope>}이고, 사용자에게 귀속된 target은 원장·허가·요청 digest에 keyed digest로만
     * 들어간다(AC-6). 원문은 확인 문구와 제한 행에만 있다.
     */
    private Validation validateRestriction(Proposal proposal, RestrictionFeature feature) {
        RestrictionScope scope = scopeOf(proposal.scope());
        if (scope == null || !feature.admits(scope)) {
            return new Validation.Invalid(UNSUPPORTED_SCOPE);
        }
        String target = restrictionTarget(scope, proposal.target());
        if (target == null) {
            return new Validation.Invalid(TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(REASON_REQUIRED);
        }
        if (!isAcceptableReason(reason)) {
            return new Validation.Invalid(REASON_INVALID);
        }
        String ledgerScope = ledgerScope(feature, scope);
        String ledgerTarget = scope.userAttributed() ? this.digest.ofTarget(scope.name(), target) : target;
        String command = proposal.command();
        return new Validation.Valid(new Accepted(command, ledgerScope, target, ledgerTarget, reason,
                restrictionPhrase(feature, scope, target), POLICY_VERSION, permissionsOf(command),
                sha256(command + "\n" + ledgerScope + "\n" + ledgerTarget + "\n" + reason),
                new Restriction(feature, scope, target)));
    }

    /** 원장의 scope: {@code <기능>:<scope>}. */
    static String ledgerScope(RestrictionFeature feature, RestrictionScope scope) {
        return feature.name() + ":" + scope.name();
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
                    UUID_TEXT.matcher(target).matches() ? UUID.fromString(target).toString() : null;
            case CALCULATION_POLICY_VERSION -> POLICY_VERSION_TEXT.matcher(target).matches() ? target : null;
        };
    }

    /**
     * 서버가 정하는 확인 문구. 전역 제한은 대상명 {@code 전역}을 담는다(AC-2) — 다시 입력해야 확정된다. 나머지는 범위 이름과
     * target 원문이다.
     */
    static String restrictionPhrase(RestrictionFeature feature, RestrictionScope scope, String target) {
        String what = switch (feature) {
            case NEW_CONNECTION -> "신규 연결";
            case SYNC -> "동기화";
            case CALCULATION_DISPLAY -> "계산 표시";
            case ADVERTISING -> "광고";
        };
        return switch (scope) {
            case GLOBAL -> what + " 제한 전역";
            case PLAN -> what + " 제한 계획 " + target;
            case PORTFOLIO -> what + " 제한 포트폴리오 " + target;
            case PROVIDER_CONNECTION -> what + " 제한 연결 " + target;
            case CALCULATION_POLICY_VERSION -> what + " 제한 계산 정책 " + target;
        };
    }

    @Override
    public String newOperationId() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return "op_" + OPERATION_ID_ENCODER.encodeToString(bytes);
    }

    @Override
    public String digestOf(String idempotencyKey) {
        return sha256("idempotency-key\n" + idempotencyKey);
    }

    @Override
    public KeyUse keyUse(UUID actorUserId, String idempotencyKeyDigest, String requestDigest) {
        var requested = this.repository.requestedByKey(actorUserId, idempotencyKeyDigest).orElse(null);
        if (requested == null) {
            return new KeyUse.Fresh();
        }
        if (requested.settled() || !requestDigest.equals(requested.requestDigest())) {
            return new KeyUse.Conflict();
        }
        return new KeyUse.Open(requested.operationId(), requested.requestedAt());
    }

    @Override
    public Claim claim(Envelope candidate, String correlationId) {
        Claim claim = this.transactions.execute(status -> {
            this.repository.lockAppends();
            return switch (keyUse(candidate.actorUserId(), candidate.idempotencyKeyDigest(),
                    candidate.requestDigest())) {
                case KeyUse.Conflict conflict -> new Claim.Conflict();
                case KeyUse.Open open -> new Claim.Claimed(open.operationId(), open.requestedAt());
                case KeyUse.Fresh fresh ->
                        new Claim.Claimed(candidate.operationId(), recordRequested(candidate, correlationId));
            };
        });
        if (claim == null) {
            throw new IllegalStateException("operation을 정하지 못했다.");
        }
        return claim;
    }

    @Override
    public Instant recordRequested(Envelope envelope, String correlationId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("requestDigest", envelope.requestDigest());
        details.put("confirmationHash", envelope.confirmationHash());
        return append(envelope, AuditOutcome.REQUESTED, false, correlationId, details);
    }

    @Override
    public void recordDecision(Envelope envelope, AuditOutcome outcome, String code, String correlationId) {
        if (outcome != AuditOutcome.DENIED && outcome != AuditOutcome.FAILED && outcome != AuditOutcome.CANCELLED) {
            throw new IllegalArgumentException("결정 행은 거절·실패·취소뿐이다.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        append(envelope, outcome, true, correlationId, details);
    }

    @Override
    public String recordRefusedProposal(UUID actorUserId, Proposal proposal, String code, String idempotencyKeyDigest,
            String correlationId) {
        String operationId = newOperationId();
        String scope = proposal.scope() == null ? "" : proposal.scope().strip();
        String target = proposal.target() == null ? "" : proposal.target().strip();
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        String ledgerScope = SAFE_SCOPE.matcher(scope).matches() ? scope : UNREADABLE;
        String ledgerTarget = SAFE_TARGET.matcher(target).matches() ? target : UNREADABLE;
        RestrictionFeature feature = OperatorCommandApi.restrictionFeatureOf(proposal.command());
        if (feature != null) {
            /*
             * 제한 command의 거절 행: scope는 아는 이름일 때만 `<기능>:<scope>`이고, target은 그 scope의 모양일 때만 남긴다 —
             * 사용자 귀속 자리(계획·portfolio·연결)는 digest로, 전역·계산 정책 버전은 원문으로. 모르는 scope이거나 모양이
             * 아니면 남기지 않는다 — 거절된 요청을 타고 원식별자가 원장에 들어가는 길을 막는다.
             */
            RestrictionScope known = scopeOf(scope);
            ledgerScope = known == null ? UNREADABLE : ledgerScope(feature, known);
            String normalized = known == null ? null : restrictionTarget(known, target);
            if (normalized == null) {
                // 모르는 scope이거나 그 scope의 모양이 아니다. 무엇인지 알 수 없는 값은 남기지 않는다.
                ledgerTarget = UNREADABLE;
            } else {
                ledgerTarget = known.userAttributed() ? this.digest.ofTarget(known.name(), normalized) : normalized;
            }
        }
        var envelope = new Envelope(operationId, actorUserId, proposal.command(), ledgerScope, ledgerTarget,
                isAcceptableReason(reason) ? reason : UNREADABLE, "", POLICY_VERSION, null, idempotencyKeyDigest, "");
        recordDecision(envelope, AuditOutcome.DENIED, code, correlationId);
        return operationId;
    }

    /** 사유 규칙: 비어 있지 않고, 길이 안이고, 제어 문자와 이메일처럼 보이는 조각이 없다. */
    private static boolean isAcceptableReason(String reason) {
        return !reason.isEmpty() && reason.length() <= MAX_REASON_CHARS && !CONTROL.matcher(reason).find()
                && !EMAIL_LIKE.matcher(reason).find();
    }

    @Override
    public Optional<ExportResult> completeExport(Envelope envelope, String correlationId,
            BooleanSupplier finalAuthority) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 감사 행과 허가 소비가 한 트랜잭션이어야 한다. 밖에서 부르는 것은 배선 실수다.
            throw new IllegalStateException("추출 완료는 허가 소비의 트랜잭션 안에서만 쓴다.");
        }
        AuditPeriod period = AuditPeriod.parseTarget(envelope.target());
        if (period == null) {
            throw new IllegalStateException("검증을 지난 추출 대상이 아니다.");
        }
        this.repository.lockAppends();
        // 잠금 뒤의 최종 권한. 거짓이면 이 트랜잭션은 아무것도 쓰지 않았다.
        if (!finalAuthority.getAsBoolean()) {
            return Optional.empty();
        }
        long upper = this.repository.maxSequence();
        int counted = this.repository.countWithin(period.start(), period.end(), upper, MAX_EXPORT_ROWS + 1);
        boolean truncated = counted > MAX_EXPORT_ROWS;
        int rowCount = Math.min(counted, MAX_EXPORT_ROWS);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("upperSequence", upper);
        details.put("rowCount", rowCount);
        details.put("truncated", truncated);
        append(envelope, AuditOutcome.COMPLETED, true, correlationId, details);
        return Optional.of(new ExportResult(envelope.operationId(), envelope.target(), upper, rowCount, truncated));
    }

    @Override
    public RestrictionCompletion completeRestriction(Envelope envelope, Restriction restriction, String correlationId,
            BooleanSupplier finalAuthority, Supplier<TargetCheck> targetCheck) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 제한 행·감사 행·허가 소비가 한 트랜잭션이어야 한다. 밖에서 부르는 것은 배선 실수다.
            throw new IllegalStateException("제한 생성은 허가 소비의 트랜잭션 안에서만 쓴다.");
        }
        this.repository.lockAppends();
        // 잠금 뒤의 최종 권한. 거짓이면 이 트랜잭션은 아무것도 쓰지 않았다.
        if (!finalAuthority.getAsBoolean()) {
            return new RestrictionCompletion.Refused(ACCESS_REVOKED);
        }
        // 확정 순간의 대상. 준비 뒤 계획이 끝났거나 연결이 해제됐으면 제한을 만들지 않는다.
        if (!(targetCheck.get() instanceof TargetCheck.Valid valid)) {
            return new RestrictionCompletion.Refused(TARGET_INVALID);
        }
        if (this.restrictions.isActive(restriction.feature(), restriction.scope(), restriction.target())) {
            return new RestrictionCompletion.Refused(ALREADY_RESTRICTED);
        }
        UUID restrictionId = UUID.randomUUID();
        Instant effectiveFrom = this.restrictions.insert(restrictionId, restriction.feature(), restriction.scope(),
                restriction.target(), restriction.scope().userAttributed() ? valid.ownerUserId() : null,
                envelope.operationId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("restrictionId", restrictionId.toString());
        details.put("feature", restriction.feature().name());
        details.put("restrictionScope", restriction.scope().name());
        details.put("effectiveFrom", effectiveFrom.toString());
        append(envelope, AuditOutcome.COMPLETED, true, correlationId, details);
        return new RestrictionCompletion.Completed(new RestrictionResult(envelope.operationId(), restrictionId,
                restriction.feature(), restriction.scope(), effectiveFrom));
    }

    @Override
    public Replay replay(UUID actorUserId, String operationId, String idempotencyKeyDigest) {
        var completed = this.repository.completed(actorUserId, operationId).orElse(null);
        if (completed == null) {
            return new Replay.None();
        }
        if (!idempotencyKeyDigest.equals(completed.keyDigest())) {
            return new Replay.Conflict();
        }
        if (completed.restrictionId() != null && completed.feature() != null && completed.restrictionScope() != null
                && completed.effectiveFrom() != null) {
            return new Replay.Stored(new RestrictionResult(operationId, completed.restrictionId(),
                    RestrictionFeature.valueOf(completed.feature()),
                    RestrictionScope.valueOf(completed.restrictionScope()), completed.effectiveFrom()));
        }
        return new Replay.Stored(new ExportResult(operationId, completed.target(), completed.upperSequence(),
                completed.rowCount(), completed.truncated()));
    }

    @Override
    public List<OperatorAuditApi.Entry> exportRows(ExportResult result) {
        AuditPeriod period = AuditPeriod.parseTarget(result.target());
        if (period == null) {
            throw new IllegalStateException("저장된 추출 대상을 읽을 수 없다.");
        }
        return this.repository.within(period.start(), period.end(), result.upperSequence(), MAX_EXPORT_ROWS);
    }

    /** 잠금 → INSERT를 한 트랜잭션에서. 열린 트랜잭션이 있으면 거기 참여한다. */
    private Instant append(Envelope envelope, AuditOutcome outcome, boolean decided, String correlationId,
            Map<String, Object> details) {
        var row = new OperatorAuditRepository.NewRow(envelope.operationId(), envelope.actorUserId(),
                this.digest.of(envelope.actorUserId()), ACTOR_OPERATOR, envelope.command(), envelope.scope(),
                envelope.target(), envelope.reason(), outcome.name(), envelope.policyVersion(),
                envelope.requestedAt(), decided, correlationId, JSON.writeValueAsString(details));
        Instant requestedAt = this.transactions.execute(status -> {
            this.repository.lockAppends();
            return this.repository.append(row);
        });
        if (requestedAt == null) {
            throw new IllegalStateException("감사 행을 쓰지 못했다.");
        }
        return requestedAt;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", impossible);
        }
    }
}
