package com.bokrilog;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperatorAuditApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.AuditOutcome;
import com.bokrilog.operations.OperatorCommandApi.CommandResult;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandApi.ExportResult;
import com.bokrilog.operations.OperatorCommandApi.InquiryCompletion;
import com.bokrilog.operations.OperatorCommandApi.InquiryResult;
import com.bokrilog.operations.OperatorCommandApi.LiftCompletion;
import com.bokrilog.operations.OperatorCommandApi.LiftResult;
import com.bokrilog.operations.OperatorCommandApi.LiftTarget;
import com.bokrilog.operations.OperatorCommandApi.NoticeCompletion;
import com.bokrilog.operations.OperatorCommandApi.NoticeResult;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperatorCommandApi.RestrictionCompletion;
import com.bokrilog.operations.OperatorCommandApi.RestrictionResult;
import com.bokrilog.operations.OperatorCommandApi.TargetCheck;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 보호된 운영 command의 흐름(Story 3.1 AC-2·AC-3·AC-6, {@code SECURITY-ACTION-1.0}).
 *
 * <p><b>두 번째 인증 경로·두 번째 허가 저장소가 없다.</b> 최근 재인증(1.8) → 일회성 허가(1.9) → 확인 대화상자의 확정을
 * 그대로 강제하고, 공통 부품({@link SessionArming}·{@link SessionRotation}·{@code IdempotencyKey})을 쓴다 — 계획 중도
 * 종료·Bybit 연결 해제와 같은 모양이다. envelope 검증·원장·실행은 {@code operations}가 소유하고, 허가 서비스가 루트
 * package-private이라 흐름은 여기 있다.
 *
 * <p><b>준비는 아무것도 바꾸지 않는다</b> — 원장에 {@code REQUESTED} 행 하나를 더하고 허가를 발급해 무장할 뿐이다.
 * 확인 문구는 서버가 정하고({@code 감사 내보내기 <scope> <target>}) 허가의 확인 hash는
 * {@code canonicalConfirmationHash(command, target, 문구)}다. 확정은 사용자가 다시 입력한 문구를 보내고, 불일치는 허가를
 * 소비하지 않는다 — Epic 1 item-7(확인 hash의 판별력)이 운영 command에서는 여기서 닫힌다.
 *
 * <p><b>확정은 허가 소비와 같은 트랜잭션 하나다.</b> 그 안에서 원장 추가 잠금 → 최종 권한 재검사(allowlist 재해석과
 * {@code decide(SENSITIVE)}) → 실행 → {@code COMPLETED} 행이 함께 커밋된다. 하나라도 실패하면 소비까지 전부 롤백되고, 거절·
 * 실패 행은 그 롤백 <b>뒤</b> 별도 짧은 트랜잭션으로 추가한다. 실제로 실행한 요청에서만 무장을 버리고 세션을 회전하며
 * 재인증 시각을 지운다. 상태가 불명확하면(500) 자동으로 성공 처리하거나 재실행하지 않는다 — 클라이언트는 준비가 준
 * operation ID로 조회한다.
 *
 * <p><b>멱등은 두 층이다.</b> 원장의 {@code COMPLETED} 행이 같은 operation·같은 키의 저장 결과를 재생하고(다른 키는
 * 충돌), 허가의 {@code (user_id, operation_id)} 유일 색인이 동시 확정 둘 가운데 하나만 실행시킨다.
 *
 * <p><b>해제(Story 3.4)는 커밋 뒤 smoke가 있다.</b> 해제 트랜잭션이 커밋되면 같은 대상 차원으로 evaluator를 다시 불러 그
 * 제한이 더는 보고되지 않는지와 남는 제한이 여전히 막는지를 본다. 어긋나면 해제를 되돌리지 않고 같은 범위의 새 제한과
 * {@code FAILED}({@code SMOKE_FAILED}) 행을 별도 트랜잭션으로 쓴다 — 해제 사실과 되돌림 사실이 둘 다 원장에 남는다.
 *
 * <p><b>공지 발행(Story 3.5)은 지원 연락처가 있어야 한다.</b> 배포 설정이 비면 준비와 확정이 모두
 * {@code SUPPORT_CONTACT_UNCONFIGURED}로 거절한다 — 허위·빈 지원 경로를 공지에 싣지 않는다. 버전 번호·덮는 제한·다음 안내
 * 시각은 준비가 한 번, 확정 트랜잭션이 원장 잠금 아래에서 한 번 더 본다.
 *
 * <p><b>문의 처리(Story 3.6)는 확정 순간에 상태를 다시 본다.</b> 준비가 본 전이를 확정 트랜잭션이 문의 행 잠금 아래에서
 * 다시 판정한다 — 그 사이 다른 처리가 상태를 옮겼거나 보존·계정 삭제로 문의가 사라졌으면 쓰지 않는다. 답변 원문은 원장·로그에
 * 들어가지 않는다.
 *
 * <p>로그에 수행자·대상·사유·허가·키를 남기지 않는다. servlet 웹 애플리케이션에서만 배선한다.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class OperatorCommandService {

    /** 확정이 없거나 이 확정과 맞지 않는 무장·허가(만료·재사용·다른 operation·다른 키·흐름 없음). */
    static final String NOT_CONFIRMED = "OPERATOR_COMMAND_NOT_CONFIRMED";

    /** 사용자가 다시 입력한 확인 문구가 서버의 문구와 다르다. 허가는 소비되지 않는다. */
    static final String CONFIRMATION_MISMATCH = "CONFIRMATION_MISMATCH";

    /** 재인증이 신선하지 않아 허가가 서지 않았다(준비). */
    static final String REAUTHENTICATION_REQUIRED = "REAUTHENTICATION_REQUIRED";

    /** 같은 멱등 키가 다른 입력·끝난 operation에 이미 쓰였다. */
    static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    /** 운영자가 아니거나 권한이 없다 — 미존재와 같은 404로 옮긴다. */
    static final String NOT_FOUND = "NOT_FOUND";

    /** 원장 code: 권한 회수로 거절했다. 이 code로 맺은 operation은 끝난 것이다(같은 키 재준비는 충돌). */
    static final String ACCESS_REVOKED = OperatorCommandApi.ACCESS_REVOKED;

    /** 원장 code: 실행이 예외로 끝나 전부 롤백됐다. */
    static final String EXECUTION_FAILED = "EXECUTION_FAILED";

    /** 원장 code: 사용자가 취소했다. */
    static final String CANCELLED = "CANCELLED";

    /** 원장 code: 같은 세션의 새 준비가 앞선 무장을 대신했다. */
    static final String SUPERSEDED = "SUPERSEDED";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OperatorCommandService.class);

    /**
     * 확정 트랜잭션을 되돌리기 위한 사설 타입. 스택도 메시지도 담지 않는다.
     *
     * <p>{@code code}는 원장 거절 code다 — 권한 회수({@value #ACCESS_REVOKED})는 404로, 제한의 대상·중복 거절(Story 3.2)은
     * 그 code 그대로 답한다.
     */
    private static final class Refused extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        private final String code;

        Refused(String code) {
            super(null, null, false, false);
            this.code = code;
        }

        String code() {
            return this.code;
        }
    }

    sealed interface Preparation {
        /**
         * @param impact 제한 생성 command의 영향 미리보기(Story 3.2). 그 밖은 {@code null}
         * @param liftImpact 해제 command의 영향 미리보기(Story 3.4). 그 밖은 {@code null}
         */
        record Armed(OperatorCommandFlow.Armed flow, RestrictionTargets.@Nullable Impact impact,
                RestrictionTargets.@Nullable LiftImpact liftImpact) implements Preparation {}

        /**
         * @param detail 공지 발행(Story 3.5)이면 요청한 공지 상태 — 같은 거절 code의 문장을 가른다. 그 밖은 {@code null}
         */
        record Refused(String code, @Nullable String detail) implements Preparation {

            Refused(String code) {
                this(code, null);
            }
        }
    }

    sealed interface Outcome {
        /**
         * 실행됐다 — 이번 요청이 했거나({@code ran}), 같은 키의 앞선 실행의 재생이다.
         *
         * @param rows 추출 결과의 allowlist 필드 묶음. 제한 생성·해제는 비어 있다
         * @param smoke 해제의 커밋 뒤 smoke(Story 3.4). 그 밖은 {@code null}
         */
        record Completed(CommandResult result, List<OperatorAuditApi.Entry> rows, boolean ran, @Nullable Smoke smoke)
                implements Outcome {}

        /**
         * 실행하지 않았다.
         *
         * @param detail 공지 발행(Story 3.5)이면 무장된 공지 상태 — 같은 거절 code의 문장을 가른다. 그 밖은 {@code null}
         */
        record Refused(String code, @Nullable String detail) implements Outcome {

            Refused(String code) {
                this(code, null);
            }
        }
    }

    /** smoke 결말. 통과했거나, 어긋나 같은 범위를 새 제한으로 되돌렸거나, 원장에 결말이 아직 없다. */
    static final String SMOKE_PASSED = OperatorCommandApi.LiftSmoke.PASSED.name();

    static final String SMOKE_REVERTED = OperatorCommandApi.LiftSmoke.REVERTED.name();

    static final String SMOKE_PENDING = OperatorCommandApi.LiftSmoke.PENDING.name();

    /**
     * 해제 커밋 뒤의 smoke(Story 3.4 AC-5).
     *
     * @param status {@value #SMOKE_PASSED}·{@value #SMOKE_REVERTED}·{@value #SMOKE_PENDING}(재전송이 원장에서 결말을 찾지
     *     못했다 — smoke가 도는 중이거나 결말 기록이 실패했다)
     * @param reopenedStages 다시 열린 단계. 되돌렸거나 결말을 모르면 비어 있다
     * @param stillBlockedStages 해제한 제한의 단계 가운데 여전히 막힌 것. 되돌렸으면 그 단계 전부, 결말을 모르면 비어 있다
     */
    record Smoke(String status, List<String> reopenedStages, List<String> stillBlockedStages) {}

    private final OperatorCommandApi commands;

    private final OperatorAccess access;

    private final ActionGrantService grants;

    /** 제한 대상의 확인과 영향 미리보기(Story 3.2). 다른 모듈의 사실은 이 어댑터가 각 모듈 공개 API로 본다. */
    private final RestrictionTargets targets;

    /** 해제 smoke의 판독(Story 3.4). 차단 판정의 유일한 출처라 smoke도 이것만 본다. */
    private final OperationalRestrictionApi restrictions;

    /** 공지 발행의 지원 연락처(Story 3.5). 비면 발행하지 않는다. */
    private final SupportContact supportContact;

    /** 문의 주인 계정의 상태(Story 3.6). 삭제가 시작된 계정의 문의에는 답변을 쓰지 않는다. */
    private final AccountAccessApi accounts;

    OperatorCommandService(OperatorCommandApi commands, OperatorAccess access, ActionGrantService grants,
            RestrictionTargets targets, OperationalRestrictionApi restrictions, SupportContact supportContact,
            AccountAccessApi accounts) {
        this.accounts = accounts;
        this.commands = commands;
        this.access = access;
        this.grants = grants;
        this.targets = targets;
        this.restrictions = restrictions;
        this.supportContact = supportContact;
    }

    /** 이 command type을 아는가. 모르는 type은 경로가 없는 것과 같다. */
    boolean supports(String command) {
        return this.commands.isSupported(command);
    }

    /** 허가가 귀속하는 command 이름. 다른 민감 command와 겹치지 않게 접두사를 붙인다. */
    static String grantCommandOf(String command) {
        return "operator-" + command;
    }

    /**
     * 확인 대화상자를 열 준비. 권한 → envelope 검증 → 멱등 키 → 허가 발급 → {@code REQUESTED} → 무장 순서다.
     *
     * @param idempotencyKey 규칙을 지난 멱등 키(컨트롤러가 먼저 본다)
     */
    Preparation prepare(HttpSession session, UUID userId, OperatorCommandApi.Proposal proposal,
            String idempotencyKey, String correlationId) {
        String command = proposal.command();
        if (!this.commands.isSupported(command)) {
            return new Preparation.Refused(NOT_FOUND);
        }
        Set<OperatorPermission> required = this.commands.permissionsOf(command);
        if (!this.access.permits(userId, required)) {
            return new Preparation.Refused(NOT_FOUND);
        }
        String keyDigest = this.commands.digestOf(idempotencyKey);
        var validation = this.commands.validate(proposal);
        if (validation instanceof OperatorCommandApi.Validation.Invalid invalid) {
            this.commands.recordRefusedProposal(userId, proposal, invalid.code(), keyDigest, correlationId);
            return new Preparation.Refused(invalid.code());
        }
        var accepted = ((OperatorCommandApi.Validation.Valid) validation).accepted();
        var restriction = accepted.restriction();
        var lift = accepted.lift();
        var notice = accepted.notice();
        var inquiry = accepted.inquiry();

        var keyUse = this.commands.keyUse(userId, keyDigest, accepted.requestDigest());
        if (keyUse instanceof OperatorCommandApi.KeyUse.Conflict) {
            this.commands.recordRefusedProposal(userId, proposal, IDEMPOTENCY_CONFLICT, keyDigest, correlationId);
            return new Preparation.Refused(IDEMPOTENCY_CONFLICT);
        }
        // 미리 본 판정이다. 확정적인 판정은 허가 발급 뒤 원장 잠금 안에서 다시 한다(`claim`).
        String candidateId;
        Instant requestedAt;
        if (keyUse instanceof OperatorCommandApi.KeyUse.Open open) {
            candidateId = open.operationId();
            requestedAt = open.requestedAt();
        } else {
            candidateId = this.commands.newOperationId();
            requestedAt = null;
        }

        String grantCommand = grantCommandOf(command);
        String phrase = accepted.confirmationPhrase();
        // 허가·원장의 target은 원장 target이다 — 사용자에게 귀속된 제한 target은 digest로만 남는다(Story 3.2 AC-6).
        String grantTarget = accepted.ledgerTarget();
        String confirmationHash = ActionGrant.canonicalConfirmationHash(ActionGrant.normalize(grantCommand),
                ActionGrant.normalize(grantTarget), phrase);
        Envelope envelope = new Envelope(candidateId, userId, command, accepted.scope(), grantTarget,
                accepted.reason(), confirmationHash, accepted.policyVersion(), requestedAt, keyDigest,
                accepted.requestDigest());

        var reference = this.grants.issue(session, userId, grantCommand, grantTarget, phrase);
        if (reference.isEmpty()) {
            this.commands.recordDecision(envelope, AuditOutcome.DENIED, REAUTHENTICATION_REQUIRED, correlationId);
            return new Preparation.Refused(REAUTHENTICATION_REQUIRED);
        }
        /*
         * 제한의 대상은 다른 모듈의 사실이다(Story 3.2). 멱등 키 판정과 재인증 확인 뒤에 본다 — 끝난 operation의 키는
         * 충돌이, 재인증이 없는 요청은 재인증 요구가 대상의 상태보다 먼저 답한다. 없거나 상태가 맞지 않거나 이미 같은
         * 제한이 있으면 방금의 허가를 무효화하고 아무것도 무장하지 않는다.
         */
        RestrictionTargets.Impact impact = null;
        if (restriction != null) {
            String refusal = null;
            if (!(this.targets.check(restriction) instanceof TargetCheck.Valid valid)) {
                refusal = OperatorCommandApi.TARGET_INVALID;
            } else if (this.targets.alreadyRestricted(restriction)) {
                refusal = OperatorCommandApi.ALREADY_RESTRICTED;
            } else {
                impact = this.targets.impact(restriction, valid);
            }
            if (refusal != null) {
                this.grants.invalidateReference(userId, reference.get());
                this.commands.recordRefusedProposal(userId, proposal, refusal, keyDigest, correlationId);
                return new Preparation.Refused(refusal);
            }
        }
        /*
         * 해제의 대상(Story 3.4)도 같은 자리에서 본다 — 없는 제한은 `TARGET_INVALID`, 이미 닫혔거나 시작 전인 제한은
         * `RESTRICTION_NOT_ACTIVE`다. 열린 제한이면 다시 열릴 단계·남는 제한·신호 계보를 미리 보인다.
         */
        RestrictionTargets.LiftImpact liftImpact = null;
        if (lift != null) {
            LiftTarget current = lift.current();
            String refusal = null;
            if (current == null) {
                refusal = OperatorCommandApi.TARGET_INVALID;
            } else if (!current.open()) {
                refusal = OperatorCommandApi.RESTRICTION_NOT_ACTIVE;
            } else {
                liftImpact = this.targets.liftImpact(lift.restrictionId(), current);
            }
            if (refusal != null) {
                this.grants.invalidateReference(userId, reference.get());
                this.commands.recordRefusedProposal(userId, proposal, refusal, keyDigest, correlationId);
                return new Preparation.Refused(refusal);
            }
        }
        /*
         * 공지 발행(Story 3.5)의 상태 거절도 같은 자리다 — 없는 사건·덮을 수 없는 제한은 `TARGET_INVALID`, 복구로 닫힌
         * 사건은 `INCIDENT_CLOSED`, 지금보다 미래·7일 이내가 아닌 다음 안내 시각은 `NEXT_UPDATE_INVALID`다.
         */
        if (notice != null && (this.supportContact.value() == null || notice.refusal() != null)) {
            /*
             * 연락처가 비었으면 어떤 공지도 발행하지 않는다 — 멱등 키·재인증 판정 뒤, 대상 판정 앞의 자리다(3.2·3.4와 같은
             * 키 → 재인증 → 대상 순서).
             */
            String refusal = this.supportContact.value() == null ? OperatorCommandApi.SUPPORT_CONTACT_UNCONFIGURED
                    : java.util.Objects.requireNonNull(notice.refusal());
            this.grants.invalidateReference(userId, reference.get());
            this.commands.recordRefusedProposal(userId, proposal, refusal, keyDigest, correlationId);
            return new Preparation.Refused(refusal, notice.status().name());
        }
        /*
         * 문의 처리(Story 3.6)의 상태 거절도 같은 자리다 — 없는 문의는 `TARGET_INVALID`, 지금 상태에서 옮길 수 없는 전이
         * (역행·같은 상태·답변 건너뛰기)는 `INQUIRY_TRANSITION_INVALID`다.
         */
        if (inquiry != null && inquiry.refusal() != null) {
            String refusal = java.util.Objects.requireNonNull(inquiry.refusal());
            this.grants.invalidateReference(userId, reference.get());
            this.commands.recordRefusedProposal(userId, proposal, refusal, keyDigest, correlationId);
            return new Preparation.Refused(refusal);
        }
        /*
         * 키 판정과 `REQUESTED` 추가를 원장 잠금 안의 한 트랜잭션으로 한다. 같은 새 키의 동시 준비 둘은 여기서 줄을
         * 서고, 뒤의 것은 앞의 것이 쓴 operation을 받는다 — operation이 둘 생기지 않는다.
         */
        OperatorCommandApi.Claim claim;
        try {
            claim = this.commands.claim(envelope, lift, correlationId);
        } catch (RuntimeException failure) {
            // 감사 없는 허가를 남기지 않는다.
            this.grants.invalidateReference(userId, reference.get());
            throw failure;
        }
        if (!(claim instanceof OperatorCommandApi.Claim.Claimed claimed)) {
            this.grants.invalidateReference(userId, reference.get());
            this.commands.recordRefusedProposal(userId, proposal, IDEMPOTENCY_CONFLICT, keyDigest, correlationId);
            return new Preparation.Refused(IDEMPOTENCY_CONFLICT);
        }
        String operationId = claimed.operationId();
        Instant recorded = claimed.requestedAt();

        OperatorCommandFlow.Armed previous = OperatorCommandFlow.peekIncludingExpired(session);
        if (previous != null && !previous.grantReference().equals(reference.get())) {
            this.grants.invalidateReference(previous.userId(), previous.grantReference());
            if (!previous.operationId().equals(operationId)) {
                recordQuietly(envelopeOf(previous), AuditOutcome.CANCELLED, SUPERSEDED, correlationId);
            }
        }
        // 남아 있는 재인증 무장은 옛 의도다. 방금 허가가 신선한 재인증 위에 섰다.
        ReauthenticationFlow.discard(session);
        OperatorCommandFlow.Armed armed = new OperatorCommandFlow.Armed(userId, operationId, command,
                accepted.scope(), accepted.target(), grantTarget, accepted.reason(), accepted.policyVersion(),
                confirmationHash, accepted.requestDigest(), recorded, keyDigest, reference.get(), phrase,
                session.getId(), Instant.now(), restriction, lift, notice, inquiry);
        OperatorCommandFlow.arm(session, armed);
        return new Preparation.Armed(armed, impact, liftImpact);
    }

    /**
     * 확정.
     *
     * @param confirmation <b>사용자가 다시 입력한</b> 확인 문구. 무장의 문구와 정규화해 같아야 하고, 허가 소비의 확인
     *     hash도 이 값으로 만든다
     * @param idempotencyKey 준비에 쓴 것과 같은 멱등 키. 허가의 operation id다
     */
    Outcome confirm(HttpServletRequest httpRequest, HttpSession session, UUID userId, String command,
            String operationId, String confirmation, String idempotencyKey, String correlationId) {
        if (!this.commands.isSupported(command)) {
            return new Outcome.Refused(NOT_FOUND);
        }
        Set<OperatorPermission> required = this.commands.permissionsOf(command);
        if (!this.access.permits(userId, required)) {
            // 게이트는 지났지만(command 권한은 남았다) 이 command의 권한 하나가 회수됐다. 남은 무장을 같은 규칙으로 맺는다.
            abandonRevoked(session, userId, correlationId);
            return new Outcome.Refused(NOT_FOUND);
        }
        String keyDigest = this.commands.digestOf(idempotencyKey);
        Outcome replayed = replayed(userId, operationId, keyDigest);
        if (replayed != null) {
            return replayed;
        }
        OperatorCommandFlow.Armed armed = OperatorCommandFlow.peek(session);
        if (armed == null
                || !armed.userId().equals(userId)
                || !armed.command().equals(command)
                || !armed.operationId().equals(operationId)
                || !armed.sessionId().equals(session.getId())
                || !armed.keyDigest().equals(keyDigest)) {
            return new Outcome.Refused(NOT_CONFIRMED);
        }
        Envelope envelope = envelopeOf(armed);
        if (!ActionGrant.normalize(armed.confirmation()).equals(ActionGrant.normalize(confirmation))) {
            // 허가를 소비하지 않는다. 무장도 그대로라 사용자가 문구를 고쳐 다시 확정할 수 있다.
            this.commands.recordDecision(envelope, AuditOutcome.DENIED, CONFIRMATION_MISMATCH, correlationId);
            return new Outcome.Refused(CONFIRMATION_MISMATCH);
        }

        String grantCommand = grantCommandOf(command);
        AtomicReference<@Nullable CommandResult> ran = new AtomicReference<>();
        var restriction = armed.restriction();
        var lift = armed.lift();
        var notice = armed.notice();
        var inquiry = armed.inquiry();
        // 해제 확정이 원장 잠금 아래에서 본 남는 제한. 커밋 뒤 smoke가 이것이 여전히 막는지 본다.
        AtomicReference<List<ActiveRestriction>> remaining = new AtomicReference<>(List.of());
        Consumption consumption;
        try {
            consumption = this.grants.consume(
                    new ConsumptionRequest(session, userId, armed.grantReference(), grantCommand, armed.ledgerTarget(),
                            confirmation, idempotencyKey),
                    () -> {
                        if (inquiry != null) {
                            /*
                             * 원장 잠금 → 최종 권한 → 문의 행 잠금 → 전이 재검사 → 조건부 UPDATE → 완료 행(Story 3.6 AC-5).
                             */
                            var completion = this.commands.completeInquiry(envelope, inquiry, correlationId,
                                    () -> this.access.permits(userId, required),
                                    owner -> this.accounts.decide(owner, owner, AccountAccessKind.SENSITIVE)
                                            == AccessDecision.ALLOWED);
                            if (completion instanceof InquiryCompletion.Refused refused) {
                                throw new Refused(refused.code());
                            }
                            InquiryResult result = ((InquiryCompletion.Completed) completion).result();
                            ran.set(result);
                            return result.operationId() + "|" + result.status() + "|" + result.transitionedAt();
                        }
                        if (notice != null) {
                            /*
                             * 원장 잠금 → 최종 권한 → 사건의 최신 버전 → 덮는 제한 → 다음 안내 시각 → (사건 행) → 버전 행 →
                             * 완료 행(Story 3.5 AC-3·AC-6). 연락처가 그사이 비었으면 아무것도 쓰지 않는다.
                             */
                            String contact = this.supportContact.value();
                            if (contact == null) {
                                throw new Refused(OperatorCommandApi.SUPPORT_CONTACT_UNCONFIGURED);
                            }
                            var completion = this.commands.completeNotice(envelope, notice, contact, correlationId,
                                    () -> this.access.permits(userId, required));
                            if (completion instanceof NoticeCompletion.Refused refused) {
                                throw new Refused(refused.code());
                            }
                            NoticeResult result = ((NoticeCompletion.Completed) completion).result();
                            ran.set(result);
                            return result.operationId() + "|" + result.incidentId() + "|" + result.version();
                        }
                        if (lift != null) {
                            /*
                             * 원장 잠금 → 최종 권한 → 제한 행 잠금 → 열림·신호 계보 재검사 → 조건부 UPDATE → 기대 단계 →
                             * 완료 행(Story 3.4 AC-1·AC-3). 남는 제한은 그 행을 닫은 뒤 잠금 안에서 센다.
                             */
                            LiftTarget current = java.util.Objects.requireNonNull(lift.current());
                            var completion = this.commands.completeLift(envelope, lift, correlationId,
                                    () -> this.access.permits(userId, required), () -> {
                                        List<ActiveRestriction> others =
                                                this.targets.remaining(lift.restrictionId(), current);
                                        remaining.set(others);
                                        return RestrictionTargets.expectation(current, others);
                                    });
                            if (completion instanceof LiftCompletion.Refused refused) {
                                throw new Refused(refused.code());
                            }
                            LiftResult result = ((LiftCompletion.Completed) completion).result();
                            ran.set(result);
                            return result.operationId() + "|" + result.restrictionId() + "|" + result.liftedAt();
                        }
                        if (restriction != null) {
                            // 원장 잠금 → 최종 권한 → 대상 재검사 → 중복 → 제한 행 → 완료 행(Story 3.2 AC-3).
                            var completion = this.commands.completeRestriction(envelope, restriction, correlationId,
                                    () -> this.access.permits(userId, required), () -> this.targets.check(restriction));
                            if (completion instanceof RestrictionCompletion.Refused refused) {
                                throw new Refused(refused.code());
                            }
                            RestrictionResult result = ((RestrictionCompletion.Completed) completion).result();
                            ran.set(result);
                            return result.operationId() + "|" + result.restrictionId();
                        }
                        ExportResult result = this.commands
                                .completeExport(envelope, correlationId, () -> this.access.permits(userId, required))
                                .orElseThrow(() -> new Refused(ACCESS_REVOKED));
                        ran.set(result);
                        return result.operationId() + "|" + result.upperSequence() + "|" + result.rowCount() + "|"
                                + result.truncated();
                    });
        } catch (Refused refused) {
            // 소비·실행·완료 행이 함께 롤백됐다. 거절 행은 밖에서 쓰고, 이 무장은 다시 쓸 수 없으니 버린다.
            recordQuietly(envelope, AuditOutcome.DENIED, refused.code(), correlationId);
            abandon(session, armed);
            return new Outcome.Refused(ACCESS_REVOKED.equals(refused.code()) ? NOT_FOUND : refused.code(),
                    notice == null ? null : notice.status().name());
        } catch (DuplicateKeyException duplicate) {
            if (restriction == null) {
                /*
                 * 공지 발행(Story 3.5)의 버전 기본키 위반은 `completeNotice`가 제약 이름으로 가려 번호 충돌로 맺는다. 여기
                 * 오는 것은 그 밖의 무결성 위반이라 실행 실패다 — 전부 롤백됐다.
                 */
                recordQuietly(envelope, AuditOutcome.FAILED, EXECUTION_FAILED, correlationId);
                throw duplicate;
            }
            /*
             * 활성 제한의 부분 유일 색인이 권위다(Story 3.2). 잠금 안의 중복 판정이 놓친 같은 제한(효력 시작 전인 행 등)을
             * 색인이 거절했다 — 소비·제한 행·완료 행이 함께 롤백됐으므로 중복 거절과 같은 결말로 맺는다.
             */
            recordQuietly(envelope, AuditOutcome.DENIED, OperatorCommandApi.ALREADY_RESTRICTED, correlationId);
            abandon(session, armed);
            return new Outcome.Refused(OperatorCommandApi.ALREADY_RESTRICTED);
        } catch (RuntimeException failure) {
            // 롤백됐다. 실패 행을 남기되 그것마저 실패하면(같은 DB 장애) 삼킨다 — 응답은 어차피 500이다.
            recordQuietly(envelope, AuditOutcome.FAILED, EXECUTION_FAILED, correlationId);
            throw failure;
        }
        CommandResult executed = ran.get();
        if (executed != null) {
            // 이 요청이 실행했다: 무장은 다 썼고 세션은 회전하며 재인증 시각은 지워진다(SECURITY-ACTION-1.0).
            OperatorCommandFlow.discard(session);
            SessionRotation.completeSensitiveCommand(httpRequest);
            if (executed instanceof InquiryResult handled) {
                // 상태만 남긴다. reference·답변·사유·수행자는 남기지 않는다.
                LOG.info("operator command completed: command={} status={}", command, handled.status());
                return new Outcome.Completed(handled, List.of(), true, null);
            }
            if (executed instanceof NoticeResult published) {
                // 상태·버전만 남긴다. 사건 ID·메시지·수행자는 남기지 않는다.
                LOG.info("operator command completed: command={} status={} version={}", command, published.status(),
                        published.version());
                return new Outcome.Completed(published, List.of(), true, null);
            }
            if (executed instanceof RestrictionResult restricted) {
                // 기능과 scope만 남긴다. target·사유·수행자는 남기지 않는다.
                LOG.info("operator command completed: command={} feature={} scope={}", command,
                        restricted.feature(), restricted.scope());
                return new Outcome.Completed(restricted, List.of(), true, null);
            }
            if (executed instanceof LiftResult lifted && lift != null) {
                Smoke smoke = smoke(envelope, lifted, java.util.Objects.requireNonNull(lift.current()),
                        remaining.get(), correlationId);
                // 기능·scope·smoke 결말만 남긴다. 제한 ID·target·사유·수행자는 남기지 않는다.
                LOG.info("operator command completed: command={} feature={} scope={} smoke={}", command,
                        lifted.feature(), lifted.scope(), smoke.status());
                return new Outcome.Completed(lifted, List.of(), true, smoke);
            }
            ExportResult result = (ExportResult) executed;
            LOG.info("operator command completed: command={} rows={} truncated={}", command, result.rowCount(),
                    result.truncated());
            List<OperatorAuditApi.Entry> rows;
            try {
                rows = this.commands.exportRows(result);
            } catch (RuntimeException unreadable) {
                /*
                 * 실행은 이미 커밋됐다. 행을 다시 읽지 못한 사실만 남기고 실패로 답한다 — 클라이언트는 응답을 불확실로
                 * 다루고 같은 키로 다시 확정하면 원장이 같은 묶음을 재생한다. 실패 행을 쓰지 않는다(실패가 아니다).
                 */
                LOG.warn("operator command completed but its rows were not read back: command={} failure={}", command,
                        unreadable.getClass().getName());
                throw unreadable;
            }
            return new Outcome.Completed(result, rows, true, null);
        }
        // 허가가 같은 키의 앞선 실행을 답했거나(동시 확정) 거부했다. 원장이 답한다.
        Outcome settled = replayed(userId, operationId, keyDigest);
        if (settled != null) {
            return settled;
        }
        if (consumption instanceof Consumption.Denied) {
            recordQuietly(envelope, AuditOutcome.DENIED, NOT_CONFIRMED, correlationId);
        }
        return new Outcome.Refused(NOT_CONFIRMED);
    }

    /**
     * 이 세션의 그 command 무장을 버리고 <b>그 무장이 낳은 허가 하나만</b> 무효화하며 {@code CANCELLED} 행을 남긴다. 무장이
     * 없거나 다른 사용자·다른 command의 것이면 아무것도 쓰지 않는다.
     *
     * @return 그 command를 아는가. 모르면 경로가 없는 것과 같다(404)
     */
    boolean cancel(HttpSession session, UUID userId, String command, String correlationId) {
        if (!this.commands.isSupported(command)) {
            // 모르는 type은 경로가 없는 것과 같다. 무장을 건드리지 않는다.
            return false;
        }
        OperatorCommandFlow.Armed armed = OperatorCommandFlow.peekIncludingExpired(session);
        if (armed == null || !armed.userId().equals(userId) || !armed.command().equals(command)) {
            return true;
        }
        abandon(session, armed);
        recordQuietly(envelopeOf(armed), AuditOutcome.CANCELLED, CANCELLED, correlationId);
        return true;
    }

    /**
     * 권한을 잃은 운영자의 무장을 정리한다 — 게이트({@link OperatorGate})가 확정·취소를 404로 끊기 직전에 부른다.
     *
     * <p>준비 뒤·확정 요청 전에 allowlist에서 빠지면 요청은 서비스에 닿지 못한다. 그때 무장과 허가가 남고 operation은
     * {@code REQUESTED}만 남아 결말이 없다. 그래서 그 사용자의 무장이 있으면 무장을 버리고 허가 참조를 무효화하며
     * {@code DENIED}({@value #ACCESS_REVOKED}) 행을 짧은 트랜잭션 하나로 더한다. 무장이 없거나 다른 사용자의 것이면
     * 아무것도 쓰지 않는다 — 처음부터 운영자가 아닌 사용자의 요청은 원장에 흔적을 남기지 않는다.
     */
    void abandonRevoked(HttpSession session, UUID userId, String correlationId) {
        OperatorCommandFlow.Armed armed = OperatorCommandFlow.peekIncludingExpired(session);
        if (armed == null || !armed.userId().equals(userId)) {
            return;
        }
        abandon(session, armed);
        recordQuietly(envelopeOf(armed), AuditOutcome.DENIED, ACCESS_REVOKED, correlationId);
    }

    private void abandon(HttpSession session, OperatorCommandFlow.Armed armed) {
        OperatorCommandFlow.discard(session);
        this.grants.invalidateReference(armed.userId(), armed.grantReference());
    }

    /**
     * 해제 커밋 뒤의 smoke(Story 3.4 AC-5). 해제한 제한이 막던 대상 차원으로 evaluator의 후보 전체
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
    private Smoke smoke(Envelope envelope, LiftResult lifted, LiftTarget target, List<ActiveRestriction> remaining,
            String correlationId) {
        boolean passed;
        try {
            RestrictionSubject subject = this.targets.subjectOf(target);
            Set<RestrictionFeature> features = EnumSet.of(lifted.feature());
            for (ActiveRestriction other : remaining) {
                features.add(other.feature());
            }
            Set<UUID> reported = new HashSet<>();
            for (ActiveRestriction found : this.restrictions.matching(features, subject, Set.of())) {
                reported.add(found.restrictionId());
            }
            passed = !reported.contains(lifted.restrictionId());
            for (ActiveRestriction other : remaining) {
                if (!passed) {
                    break;
                }
                if (!reported.contains(other.restrictionId())) {
                    // 빠졌다. 지금도 열려 있고 원 target이 있는데 막지 않는다면 실패다. 정상적으로 닫혔으면 실패가 아니다.
                    passed = this.restrictions.current(other.restrictionId())
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
            this.commands.recordSmokePassed(envelope, lifted, correlationId);
            return passed(lifted);
        }
        LOG.warn("operator lift smoke failed: feature={} scope={}", lifted.feature(), lifted.scope());
        this.commands.revertLift(envelope, lifted, correlationId);
        return reverted(lifted);
    }

    private static Smoke passed(LiftResult lifted) {
        return new Smoke(SMOKE_PASSED, lifted.reopenedStages(), lifted.stillBlockedStages());
    }

    /** 되돌린 해제의 smoke: 다시 열린 단계가 없고 해제한 제한의 단계가 모두 막혀 있다. */
    private static Smoke reverted(LiftResult lifted) {
        return new Smoke(SMOKE_REVERTED, List.of(), RestrictionTargets.stages(lifted.feature(), lifted.scope()));
    }

    private @Nullable Outcome replayed(UUID userId, String operationId, String keyDigest) {
        return switch (this.commands.replay(userId, operationId, keyDigest)) {
            case OperatorCommandApi.Replay.Stored stored when stored.result() instanceof LiftResult lifted ->
                    new Outcome.Completed(lifted, List.of(), false, switch (this.commands.liftSmoke(operationId)) {
                        case PASSED -> passed(lifted);
                        case REVERTED -> reverted(lifted);
                        case PENDING -> new Smoke(SMOKE_PENDING, List.of(), List.of());
                    });
            case OperatorCommandApi.Replay.Stored stored -> new Outcome.Completed(stored.result(),
                    stored.result() instanceof ExportResult export ? this.commands.exportRows(export) : List.of(),
                    false, null);
            case OperatorCommandApi.Replay.Conflict conflict -> new Outcome.Refused(IDEMPOTENCY_CONFLICT);
            case OperatorCommandApi.Replay.None none -> null;
        };
    }

    private static Envelope envelopeOf(OperatorCommandFlow.Armed armed) {
        return new Envelope(armed.operationId(), armed.userId(), armed.command(), armed.scope(), armed.ledgerTarget(),
                armed.reason(), armed.confirmationHash(), armed.policyVersion(), armed.requestedAt(),
                armed.keyDigest(), armed.requestDigest());
    }

    /** 결정 행을 쓰되 실패를 삼킨다. 롤백 뒤의 기록이고 응답은 이미 정해졌다. */
    private void recordQuietly(Envelope envelope, AuditOutcome outcome, String code, String correlationId) {
        try {
            this.commands.recordDecision(envelope, outcome, code, correlationId);
        } catch (DataAccessException unavailable) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("operator audit decision not recorded: outcome={} failure={}", outcome,
                        unavailable.getClass().getName());
            }
        }
    }
}
