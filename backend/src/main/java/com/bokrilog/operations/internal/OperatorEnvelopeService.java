package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandEnvironment;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.ServiceNoticeApi;
import com.bokrilog.operations.ServiceNoticeApi.NoticeStatus;
import com.bokrilog.operations.SupportInquiryApi;
import com.bokrilog.operations.internal.LiftRestrictionCommand.Lift;
import com.bokrilog.operations.internal.LiftRestrictionCommand.LiftCompletion;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영 command의 엔진 — envelope 검증과 원장 기록(Story 3.1), 그리고 command 전략의 등록표와 확정 템플릿(Epic 3 회고 AI-5).
 *
 * <p><b>command를 가르지 않는다.</b> 모든 command별 판단은 등록표({@link OperatorCommandRegistry})가 고른 전략
 * ({@link OperatorCommand}) 하나에 있다. 엔진이 갖는 것은 순서다 — 거절 행의 일반 규칙, 멱등 키 판정과 {@code REQUESTED}
 * 추가, 확정의 prologue(트랜잭션 가드 → 원장 추가 잠금 → 최종 권한 → {@code ACCESS_REVOKED})와 {@code COMPLETED} 행
 * ({@link #complete}), 재전송의 키 대조. 그리고 command가 공유하는 envelope 규칙(사유·메시지·해시·확인 문구 낱말)이다.
 *
 * <p>servlet 역할에서만 배선한다 — 감사 digest 키가 그 역할의 것이고({@link ActorDigest}), 운영 command는 API 프로세스에만
 * 있다.
 *
 * <p>원장 추가는 언제나 트랜잭션 안에서 잠금 → INSERT 순서다({@link OperatorAuditRepository#lockAppends}). 호출자가 연
 * 트랜잭션이 있으면 거기 참여한다 — 완료 행은 허가 소비와 같은 트랜잭션이어야 한다.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class OperatorEnvelopeService implements OperatorCommandApi {

    /** 수행자 종류. 운영 command는 운영자만 낸다(SYSTEM 행은 Story 3.3 자동 정지가 {@code OperationalSignalService}로 쓴다). */
    static final String ACTOR_OPERATOR = "OPERATOR";

    /** 이메일 주소처럼 보이는 조각. 사유에 이메일을 남기지 않는다(AC-4). */
    private static final Pattern EMAIL_LIKE = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    /** 제어 문자(개행 포함). 사유는 한 줄 문장이다 — 로그 위조와 화면 깨짐을 함께 막는다. */
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** 거절 행에 원문으로 남겨도 되는 scope·target의 모양. 그 밖은 {@link #UNREADABLE}이다. */
    private static final Pattern SAFE_SCOPE = Pattern.compile("[A-Z][A-Z_]{0,63}");

    private static final Pattern SAFE_TARGET = Pattern.compile("[0-9A-Za-z~:._-]{1,128}");

    /** 정규 UUID(대소문자 무관). {@link UUID#fromString}만으로는 줄인 묶음도 받는다. */
    static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** IPv4 주소와 {@code 호스트:포트} 모양(Story 3.5 리뷰). 내부 주소를 사용자 화면에 내보내지 않는다. */
    private static final Pattern NETWORK_LIKE = Pattern.compile(
            "\\p{Nd}{1,3}(?:\\.\\p{Nd}{1,3}){3}|[A-Za-z]:\\p{Nd}{2,5}(?!\\p{Nd})");

    /**
     * 화면 카피 금지 표현(Story 3.5 리뷰): 실시간·정확 보장·손실 공포·수익 축하·스트릭·배지. 프런트엔드
     * {@code content/copyRules.ts}의 {@code FORBIDDEN_COPY_WORDS}와 같은 목록이다 — 시험({@code ServiceNoticeTest})이 두 목록을
     * 대조한다. 운영자 한 줄도 사용자 화면에 그대로 나가므로 같은 규칙을 지난다.
     */
    static final List<String> FORBIDDEN_COPY_WORDS = List.of("실시간", "정확", "보장", "축하", "손실", "수익을 내",
            "스트릭", "배지");

    /**
     * URL·도메인·경로 모양(Story 3.5). 스킴({@code x://}), {@code www.}, {@code 이름.최상위} 도메인, 공백 뒤의 {@code /경로}다.
     * 공지 메시지가 외부 주소나 내부 경로를 사용자에게 내보내지 않게 한다.
     */
    private static final Pattern URL_LIKE = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://|www\\.|[a-z0-9-]+\\.[a-z]{2,}\\b|(^|[\\s(\\[])/[A-Za-z0-9._~-])");

    /** 거절 행에서 형식이 맞지 않아 원문을 남기지 않은 자리. */
    static final String UNREADABLE = "-";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OperatorAuditRepository repository;

    /** 제한 표(Story 3.2·3.4). 같은 모듈의 repository다 — 제한 행은 감사 행과 같은 트랜잭션에서만 생긴다. */
    private final OperationalRestrictionRepository restrictions;

    /** 모듈 밖의 사실(대상·연락처·계정 상태). 루트가 구현한다. */
    private final OperatorCommandEnvironment environment;

    private final ActorDigest digest;

    private final TransactionTemplate transactions;

    /** command 이름 → 전략. 엔진의 모든 command 분기가 이 조회 하나다. */
    private final OperatorCommandRegistry registry;

    OperatorEnvelopeService(OperatorAuditRepository repository, OperationalRestrictionRepository restrictions,
            ServiceNoticeRepository notices, SupportInquiryRepository inquiries, ActorDigest digest,
            SupportMetrics supportMetrics, OperationalRestrictionApi evaluator,
            OperatorCommandEnvironment environment, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.restrictions = restrictions;
        this.environment = environment;
        this.digest = digest;
        this.transactions = new TransactionTemplate(transactionManager);
        /*
         * 등록표. 새 command는 OperatorCommand 구현 하나와 여기 한 줄이다 — OperatorCommandRegistryTest가 이 표가 공개 command
         * 이름 전부를 덮는지 고정한다.
         */
        this.registry = new OperatorCommandRegistry(List.of(
                new AuditExportCommand(repository),
                new RestrictCommand(restrictions, digest, environment),
                new LiftRestrictionCommand(restrictions, evaluator, environment),
                new PublishNoticeCommand(notices, restrictions, environment),
                new UpdateInquiryCommand(inquiries, digest, supportMetrics, environment)));
    }

    /** 등록표(시험이 완전성을 본다). */
    OperatorCommandRegistry registry() {
        return this.registry;
    }

    @Override
    public boolean isSupported(String command) {
        return this.registry.find(command).isPresent();
    }

    @Override
    public Set<OperatorPermission> permissionsOf(String command) {
        return this.registry.of(command).permissions();
    }

    @Override
    public @Nullable Proposal parse(String command, JsonNode body, boolean actorClaimed) {
        try {
            // 공통 세 필드는 어느 command든 문자열이어야 한다(그 command가 쓰지 않는 필드도) — 아니면 본문 모양 오류다.
            Proposal common = new Proposal(command, CommandJson.stringOrNull(body, "scope"),
                    CommandJson.stringOrNull(body, "target"), CommandJson.stringOrNull(body, "reason"), actorClaimed);
            // 모르는 command는 공통 제안 그대로다 — 준비가 그것을 없는 경로(404)로 답한다.
            OperatorCommand<?, ?> strategy = this.registry.find(command).orElse(null);
            return strategy == null ? common : strategy.parse(command, body, common);
        } catch (IllegalArgumentException notText) {
            return null;
        }
    }

    @Override
    public Validation validate(Proposal proposal) {
        if (proposal.actorClaimed()) {
            return new Validation.Invalid(ACTOR_NOT_ACCEPTED);
        }
        return this.registry.of(proposal.command()).validate(proposal);
    }

    @Override
    public Inspection inspect(Accepted accepted) {
        return inspect(this.registry.of(accepted.command()), accepted);
    }

    private static <A extends Serializable, R> Inspection inspect(OperatorCommand<A, R> strategy, Accepted accepted) {
        return strategy.inspect(accepted, strategy.payloadType().cast(accepted.payload()));
    }

    // ------------------------------------------------------------------ envelope 규칙(command가 공유한다)

    /** 사유 규칙: 비어 있지 않고, 길이 안이고, 제어 문자와 이메일처럼 보이는 조각이 없다. */
    static boolean isAcceptableReason(String reason) {
        return !reason.isEmpty() && reason.length() <= MAX_REASON_CHARS && !CONTROL.matcher(reason).find()
                && !EMAIL_LIKE.matcher(reason).find();
    }

    /**
     * 공지 메시지 규칙(Story 3.5 AC-1): 사유 규칙(제어 문자·이메일 모양 거절)에 더해 200자 이내이고, 숫자 6자리 이상
     * 연속과 URL·도메인·경로 모양이 없다 — 금융값·식별자·내부 경로가 사용자 화면에 실리지 않게 한다.
     */
    static boolean isAcceptableMessage(String message) {
        return !message.isEmpty() && message.length() <= ServiceNoticeApi.MAX_MESSAGE_CHARS
                && !CONTROL.matcher(message).find() && !EMAIL_LIKE.matcher(message).find()
                // 금융값 모양(숫자열·끊어 쓴 숫자·금액)은 문의 규칙(Story 3.6)과 같은 한 벌이다.
                && !InquiryRules.financialLike(message) && !URL_LIKE.matcher(message).find()
                && !NETWORK_LIKE.matcher(message).find()
                && FORBIDDEN_COPY_WORDS.stream().noneMatch(message::contains);
    }

    /**
     * 버전 기본키 위반인가 — 같은 사건·같은 번호를 다른 발행이 먼저 썼다. 원인 사슬에서 제약 이름을 찾는다. 다른 무결성
     * 위반(사건 행·복구 유일 색인·CHECK)은 번호 충돌이 아니다.
     */
    static boolean versionConflict(org.springframework.dao.DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("\"" + ServiceNoticeRepository.VERSION_PRIMARY_KEY + "\"")) {
                return true;
            }
        }
        return false;
    }

    /** 공지 상태의 확인 문구 낱말. 사용자 화면의 상태 표지와 같은 말이다. */
    static String noticeStatusWord(NoticeStatus status) {
        return switch (status) {
            case INVESTIGATING -> "조사 중";
            case SCOPE_CHANGED -> "범위 변경";
            case RECOVERED -> "복구됨";
        };
    }

    /**
     * 문의 처리의 확인 문구: {@code 문의 <상태 낱말> <reference>}. reference를 담아 문구가 그 문의 하나를 가리킨다(item-7).
     */
    static String inquiryPhrase(SupportInquiryApi.Status to, String reference) {
        return "문의 " + inquiryStatusWord(to) + " " + reference;
    }

    /**
     * 문의 상태의 확인 문구 낱말. 사용자 화면의 상태 표지({@code content/support.ts}의 상태 문장)와 글자까지 같은 말이다 —
     * 시험({@code SupportInquiryTest})이 둘을 대조한다.
     */
    static String inquiryStatusWord(SupportInquiryApi.Status status) {
        return switch (status) {
            case RECEIVED -> "접수됨";
            case IN_PROGRESS -> "처리 중";
            case ANSWERED -> "답변 완료";
            case RESOLVED -> "해결됨";
        };
    }

    /** 제한 기능의 확인 문구 낱말. */
    static String featureWord(RestrictionFeature feature) {
        return switch (feature) {
            case NEW_CONNECTION -> "신규 연결";
            case SYNC -> "동기화";
            case CALCULATION_DISPLAY -> "계산 표시";
            case ADVERTISING -> "광고";
        };
    }

    /** 원장의 scope: {@code <기능>:<scope>}. */
    static String ledgerScope(RestrictionFeature feature, com.bokrilog.operations.RestrictionScope scope) {
        return feature.name() + ":" + scope.name();
    }

    /** 확정 응답의 머리 — 모든 command가 {@code operationId}·{@code outcome}·{@code command}로 시작한다. */
    static Map<String, Object> completedView(String operationId, String command) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("operationId", operationId);
        view.put("outcome", AuditOutcome.COMPLETED.name());
        view.put("command", command);
        return view;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", impossible);
        }
    }

    // ------------------------------------------------------------------ 멱등 키·요청·결정 행

    @Override
    public String newOperationId() {
        return OperationIds.next();
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
    public Claim claim(Envelope candidate, Serializable payload, String correlationId) {
        Map<String, Object> evidence = requestedDetails(this.registry.of(candidate.command()), payload);
        Claim claim = this.transactions.execute(status -> {
            this.repository.lockAppends();
            return switch (keyUse(candidate.actorUserId(), candidate.idempotencyKeyDigest(),
                    candidate.requestDigest())) {
                case KeyUse.Conflict conflict -> new Claim.Conflict();
                case KeyUse.Open open -> new Claim.Claimed(open.operationId(), open.requestedAt());
                case KeyUse.Fresh fresh ->
                        new Claim.Claimed(candidate.operationId(), recordRequested(candidate, evidence, correlationId));
            };
        });
        if (claim == null) {
            throw new IllegalStateException("operation을 정하지 못했다.");
        }
        return claim;
    }

    private static <A extends Serializable, R> Map<String, Object> requestedDetails(OperatorCommand<A, R> strategy,
            Serializable payload) {
        return strategy.requestedDetails(strategy.payloadType().cast(payload));
    }

    @Override
    public Instant recordRequested(Envelope envelope, String correlationId) {
        return recordRequested(envelope, Map.of(), correlationId);
    }

    private Instant recordRequested(Envelope envelope, Map<String, Object> evidence, String correlationId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("requestDigest", envelope.requestDigest());
        details.put("confirmationHash", envelope.confirmationHash());
        details.putAll(evidence);
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
        // 일반 규칙: 모양이 맞는 scope·target과 사유 규칙을 지난 사유만 원문이다. command의 전략이 그 command의 모양으로 좁힌다.
        OperatorCommand.RefusedLedger common = new OperatorCommand.RefusedLedger(
                SAFE_SCOPE.matcher(scope).matches() ? scope : UNREADABLE,
                SAFE_TARGET.matcher(target).matches() ? target : UNREADABLE, isAcceptableReason(reason));
        OperatorCommand.RefusedLedger shape = this.registry.of(proposal.command()).refusedLedger(proposal, common);
        var envelope = new Envelope(operationId, actorUserId, proposal.command(), shape.scope(), shape.target(),
                shape.keepReason() ? reason : UNREADABLE, "", POLICY_VERSION, null, idempotencyKeyDigest, "");
        recordDecision(envelope, AuditOutcome.DENIED, code, correlationId);
        return operationId;
    }

    // ------------------------------------------------------------------ 확정 템플릿

    @Override
    public Executed complete(Envelope envelope, Serializable payload, String correlationId,
            BooleanSupplier finalAuthority) {
        return execute(this.registry.of(envelope.command()), envelope, payload, correlationId, finalAuthority);
    }

    /**
     * 확정의 prologue와 완료 행 — 모든 command가 이 한 곳을 지난다. 전략은 잠금을 쥐거나 트랜잭션을 확인하지 않는다.
     */
    private <A extends Serializable, R> Executed execute(OperatorCommand<A, R> strategy, Envelope envelope,
            Serializable payload, String correlationId, BooleanSupplier finalAuthority) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 결과 행·감사 행·허가 소비가 한 트랜잭션이어야 한다. 밖에서 부르는 것은 배선 실수다.
            throw new IllegalStateException("운영 command 확정은 허가 소비의 트랜잭션 안에서만 쓴다.");
        }
        A typed = strategy.payloadType().cast(payload);
        this.repository.lockAppends();
        // 잠금 뒤의 최종 권한. 거짓이면 이 트랜잭션은 아무것도 쓰지 않았다.
        if (!finalAuthority.getAsBoolean()) {
            throw new CommandRefusal(ACCESS_REVOKED);
        }
        // 전략은 엔진을 이 인자로만 받는다 — 호출된 그 빈이다(시험의 spy 포함).
        OperatorCommand.Done<R> done = strategy.complete(this, envelope, typed);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.putAll(done.details());
        append(envelope, AuditOutcome.COMPLETED, true, correlationId, details);
        return new Executed(envelope.command(), strategy.grantRecord(done.result()), done.result());
    }

    @Override
    public @Nullable String refusalDetail(String command, Serializable payload) {
        return refusalDetail(this.registry.of(command), payload);
    }

    private static <A extends Serializable, R> @Nullable String refusalDetail(OperatorCommand<A, R> strategy,
            Serializable payload) {
        return strategy.refusalDetail(strategy.payloadType().cast(payload));
    }

    @Override
    public @Nullable String duplicateKeyRefusal(String command) {
        return this.registry.of(command).duplicateKeyRefusal();
    }

    @Override
    public Finished afterCommit(String requestedCommand, Envelope envelope, Executed executed,
            String correlationId) {
        // 커밋 뒤의 일이 부를 엔진은 이 빈 자신이다(호출자가 받은 그 빈 — 되돌림·smoke 기록이 그것을 지난다).
        return afterCommit(this.registry.of(executed.command()), requestedCommand, envelope, executed, correlationId);
    }

    private <A extends Serializable, R> Finished afterCommit(OperatorCommand<A, R> strategy, String requestedCommand,
            Envelope envelope, Executed executed, String correlationId) {
        return strategy.afterCommit(this, requestedCommand, envelope, strategy.resultType().cast(executed.result()),
                correlationId);
    }

    /**
     * 해제의 확정 본문(Story 3.4 AC-1·AC-3) — {@link LiftRestrictionCommand}가 부른다. 엔진 템플릿이 원장 잠금·최종 권한을
     * 마친 뒤다. <b>해제 문장({@code effective_until}을 쓰는 UPDATE)의 유일한 호출 자리다</b> — {@code OperatorRecoveryTest}가
     * 운영 소스를 훑어 이 메서드로 고정한다.
     *
     * <p>순서가 계약이다: 제한 행 잠금 → 열림·신호 계보 재검사 → 조건부 UPDATE 한 문장 → 남는 제한과 기대 단계. 신호
     * ({@code raise})도 원장 잠금을 먼저 쥐므로 잠금 뒤에는 새 신호가 그 제한에 연결될 수 없다. 갱신 행이 0이면 아무것도 쓰지
     * 않는다. 신호 행·원장 행은 고치지 않는다.
     *
     * @throws CommandRefusal {@code TARGET_INVALID}·{@code RESTRICTION_NOT_ACTIVE}·{@code NEW_SIGNAL_SINCE_PREPARE}
     */
    public LiftCompletion completeLift(Envelope envelope, Lift lift) {
        LiftTarget prepared = lift.current();
        if (prepared == null) {
            // 준비가 없는 제한을 무장하지 않는다. 여기 오는 것은 배선 실수다.
            throw new IllegalStateException("준비가 본 해제 대상이 없다.");
        }
        var locked = this.restrictions.lockForLift(lift.restrictionId()).orElse(null);
        if (locked == null) {
            throw new CommandRefusal(TARGET_INVALID);
        }
        if (!locked.open()) {
            throw new CommandRefusal(RESTRICTION_NOT_ACTIVE);
        }
        // 준비 뒤 같은 제한에 새 신호가 연결됐으면 운영자가 본 증거가 그 신호를 다루지 않았다. 다시 검증해야 한다.
        if (locked.linkedSignals() != prepared.linkedSignals()
                || !Objects.equals(locked.latestSignalAt(), prepared.latestSignalAt())) {
            throw new CommandRefusal(NEW_SIGNAL_SINCE_PREPARE);
        }
        Instant liftedAt = this.restrictions.lift(lift.restrictionId(), envelope.operationId()).orElse(null);
        if (liftedAt == null) {
            /*
             * 열림 판정과 UPDATE가 같은 술어(`OPEN`)이고 그 사이 행 잠금을 쥐고 있어 구조상 오지 않는 가지다. 그래도 영향 행
             * 수가 곧 판정이다 — 0행을 성공으로 접지 않는다.
             */
            throw new CommandRefusal(RESTRICTION_NOT_ACTIVE);
        }
        // 남는 제한은 그 행을 닫은 뒤 잠금 안에서 센다. 커밋 뒤 smoke가 이것이 여전히 막는지 본다.
        List<ActiveRestriction> remaining = this.environment.remainingAfterLift(lift.restrictionId(), prepared);
        LiftExpectation expected = this.environment.liftExpectation(prepared, remaining);
        Map<String, Object> details = new LinkedHashMap<>(LiftRestrictionCommand.evidenceOf(lift));
        details.put("feature", locked.feature().name());
        details.put("restrictionScope", locked.scope().name());
        details.put("liftedAt", liftedAt.toString());
        details.put("reopenedStages", expected.reopenedStages());
        details.put("stillBlockedStages", expected.stillBlockedStages());
        LiftResult result = new LiftResult(envelope.operationId(), lift.restrictionId(), locked.feature(),
                locked.scope(), liftedAt, expected.reopenedStages(), expected.stillBlockedStages());
        return new LiftCompletion(result, prepared, remaining, details);
    }

    @Override
    public Optional<UUID> revertLift(Envelope envelope, LiftResult result, String correlationId) {
        Optional<UUID> reinstated = this.transactions.execute(status -> {
            this.repository.lockAppends();
            UUID created = null;
            var lifted = this.restrictions.liftFacts(result.restrictionId()).orElse(null);
            // 같은 대상에 이미 끝이 정해지지 않은 제한이 있으면(그 사이 신호·운영자가 만든 것) 이미 막혀 있다.
            if (lifted != null && this.restrictions.activeRow(lifted.feature(), lifted.scope(), lifted.target())
                    .isEmpty()) {
                UUID candidate = UUID.randomUUID();
                if (this.restrictions.reinstate(candidate, result.restrictionId(), envelope.operationId())
                        .isPresent()) {
                    created = candidate;
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", SMOKE_FAILED);
            details.put("keyDigest", envelope.idempotencyKeyDigest());
            details.put("restrictionId", result.restrictionId().toString());
            if (created != null) {
                details.put("reinstatedRestrictionId", created.toString());
            }
            // 새 제한 행과 실패 행이 한 커밋이다. 잠금은 이미 쥐었다(append가 다시 쥐어도 같은 트랜잭션이다).
            append(envelope, AuditOutcome.FAILED, true, correlationId, details);
            return Optional.ofNullable(created);
        });
        return reinstated == null ? Optional.empty() : reinstated;
    }

    @Override
    public void recordSmokePassed(Envelope envelope, LiftResult result, String correlationId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", SMOKE_PASSED);
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("restrictionId", result.restrictionId().toString());
        append(envelope, AuditOutcome.APPROVED, true, correlationId, details);
    }

    @Override
    public LiftSmoke liftSmoke(String operationId) {
        String code = this.repository.firstCode(operationId, List.of(SMOKE_PASSED, SMOKE_FAILED)).orElse(null);
        if (SMOKE_PASSED.equals(code)) {
            return LiftSmoke.PASSED;
        }
        return SMOKE_FAILED.equals(code) ? LiftSmoke.REVERTED : LiftSmoke.PENDING;
    }

    // ------------------------------------------------------------------ 재전송

    @Override
    public Replay replay(UUID actorUserId, String operationId, String idempotencyKeyDigest, String requestedCommand) {
        var completed = this.repository.completed(actorUserId, operationId).orElse(null);
        if (completed == null) {
            return new Replay.None();
        }
        if (!idempotencyKeyDigest.equals(completed.keyDigest())) {
            return new Replay.Conflict();
        }
        JsonNode details = detailsOf(completed.details());
        // 그 operation의 command가 결과의 모양을 정한다(요청 경로의 command가 아니다).
        OperatorCommand<?, ?> strategy = this.registry.find(completed.command()).orElse(null);
        if (strategy != null) {
            try {
                return new Replay.Stored(strategy.replay(this, requestedCommand, operationId, completed.target(),
                        details));
            } catch (IllegalStateException partial) {
                // 그 command의 모양이 아니다 — 리팩터 전과 같은 차례로 접는다(아래).
            }
        }
        /*
         * 리팩터 전의 재생 차례(기준 b318c21): 모양을 알 수 없는 완료 행은 제한 생성의 사실(제한 ID·기능·scope·효력 시작)이
         * 있으면 제한 생성으로, 아니면 추출로 읽었다(없는 상한·행 수는 0, 잘림은 거짓). 같은 차례를 지킨다 — 추출 대상으로 읽을 수
         * 없는 target이면 행 묶음을 만들 때 실패한다(그때도 그랬다).
         */
        OperatorCommand<?, ?> restriction = this.registry.of(RESTRICT_SYNC);
        if (CommandJson.uuid(details, "restrictionId") != null && CommandJson.text(details, "feature") != null
                && CommandJson.text(details, "restrictionScope") != null
                && CommandJson.instant(details, "effectiveFrom") != null) {
            return new Replay.Stored(restriction.replay(this, requestedCommand, operationId, completed.target(),
                    details));
        }
        return new Replay.Stored(this.registry.of(AUDIT_EXPORT).replay(this, requestedCommand, operationId,
                completed.target(), details));
    }

    /** 완료 행의 {@code details}. 읽을 수 없거나 객체가 아니면 빈 객체다 — 재생 차례가 없는 사실로 접는다. */
    private static JsonNode detailsOf(String text) {
        try {
            JsonNode details = JSON.readTree(text);
            if (details != null && details.isObject()) {
                return details;
            }
        } catch (tools.jackson.core.JacksonException unreadable) {
            // 아래의 빈 객체다.
        }
        return JSON.createObjectNode();
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
}
