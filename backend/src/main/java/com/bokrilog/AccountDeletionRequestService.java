package com.bokrilog;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.AccountDeletionApi.DeletionRequest;
import com.bokrilog.identity.AccountDeletionApi.DeletionRequestOutcome;
import com.bokrilog.sync.IdempotencyKey;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 사용자의 계정 삭제 요청(Story 5.1, {@code SECURITY-ACTION-1.0}·{@code ACCOUNT-DELETION-1.0}).
 *
 * <p><b>세 단계를 순서대로 강제한다.</b> 최근 재인증(1.8) → {@code delete-account} 일회성 허가(1.9) → 고정 문구를 다시 입력한 확정.
 * Bybit 연결 해제({@link BybitRevocationCommandService})와 같은 모양이다 — 허가 서비스가 루트 package-private이라 흐름과 세션
 * 정리는 여기 있고, 요청 행과 삭제 진행 전환은 {@code identity}가 소유한다({@link AccountDeletionApi#requestDeletion}).
 *
 * <p><b>준비는 아무것도 바꾸지 않는다.</b> 계정 쓰기 접근을 잠금 없이 판정하고, target {@code <userId>|<정책 버전>}과 서버 상수 문구에
 * 귀속한 허가를 발급한 뒤에만 무장을 심는다.
 *
 * <p><b>확정은 허가 소비와 같은 트랜잭션 하나다.</b> 그 안에서 identity가 생명주기 행을 잠그고 요청 행 INSERT와
 * {@code ACTIVE → DELETION_IN_PROGRESS}를 함께 쓴다. 하나라도 실패하면 소비까지 전부 롤백된다. 커밋 뒤의 순서는 다른 세션 폐기
 * ({@link SessionGuard#revokeSessionsOfExcept}, 이 세션 id만 남긴다) → 무장 폐기 → 재인증 시각 제거이고 세션 ID는 회전하지 않는다
 * ({@link SessionRotation#completeSensitiveCommandWithoutRotation} — 응답 유실 뒤 같은 쿠키의 재전송이 같은 200을 받게 하는 이
 * command 한정 예외). 커밋 뒤 단계의 실패는 커밋된 요청을 오류로 바꾸지 않는다. 남은 요청 세션은 상태
 * 조회·같은 요청 재전송·로그아웃만 쓸 수 있다 — 그 밖의 경로는 이미 {@code DELETION_IN_PROGRESS}를 거부한다(SENSITIVE·
 * {@code lockWriteAccess}).
 *
 * <p><b>재전송 판정은 허가 경로보다 앞이다.</b> 삭제가 시작되면 {@code decide(SENSITIVE)}가 거부하므로 허가의 저장된 결과 재생에
 * 닿지 못한다. 그래서 요청 행의 사용자 유일성과 멱등 키 digest가 재생의 권위다 — 같은 키·같은 본문은 같은 요청을 200으로(그리고
 * 커밋 뒤 세션 정리를 멱등하게 다시 돈다), 같은 키·다른 본문은 409 {@link IdempotencyKey#CONFLICT_CODE}로, 다른 키는 409
 * {@link #IN_PROGRESS}로 답한다.
 *
 * <p><b>production에서는 열지 않는다.</b> 법정 분리 보존 정책(게이트 G-5)이 승인되기 전이다. {@code BOKRILOG_ENV=production}이면 준비·
 * 확정이 {@link #UNAVAILABLE}이고 아무 행도 만들지 않는다. servlet 웹 애플리케이션에서만 배선한다.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class AccountDeletionRequestService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AccountDeletionRequestService.class);

    /** 민감 command의 이름. 다른 command와 <b>다른 값</b>이어야 한다 — 허가는 이 문자열에 귀속한다. */
    static final String COMMAND = "delete-account";

    /** 계정 쓰기 접근이 없다. 공통 계약의 단일 거부로 나간다. */
    static final String NOT_PERMITTED = "NOT_PERMITTED";

    /** 재인증이 신선하지 않아 허가가 서지 않았다(준비). */
    static final String REAUTHENTICATION_REQUIRED = "REAUTHENTICATION_REQUIRED";

    /** 무장·허가 중 하나가 이 확정과 맞지 않는다(만료·재사용·흐름 없음·다른 세션). */
    static final String NOT_CONFIRMED = "ACCOUNT_DELETION_NOT_CONFIRMED";

    /** 입력한 문구가 서버 문구와 다르다. 허가를 소비하지 않고 무장을 남긴다. */
    static final String CONFIRMATION_MISMATCH = "CONFIRMATION_MISMATCH";

    /** 확정 본문의 정책 버전이 지금 정책과 다르다. 저장하지 않는다. */
    static final String POLICY_CHANGED = "ACCOUNT_DELETION_POLICY_CHANGED";

    /** 이미 삭제가 요청된 계정이다(다른 키의 확정·삭제 중 준비). */
    static final String IN_PROGRESS = "ACCOUNT_DELETION_IN_PROGRESS";

    /** production — G-5 승인 전이라 삭제 요청을 열지 않는다. */
    static final String UNAVAILABLE = "ACCOUNT_DELETION_UNAVAILABLE";

    /** 요청의 결과 상태. 생명주기 값 그대로다. */
    static final String STATE_IN_PROGRESS = "DELETION_IN_PROGRESS";

    /** 거절. 소비 트랜잭션을 되돌리기 위한 사설 타입이며 스택도 메시지도 담지 않는다. */
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
        record Armed(AccountDeletionFlow.Armed flow) implements Preparation {}

        record Refused(String code) implements Preparation {}
    }

    sealed interface Outcome {
        /**
         * 삭제가 요청돼 있다 — 이번 확정이 만들었거나({@code created}), 같은 키의 앞선 확정이 만든 것의 재생이다.
         */
        record Done(DeletionRequest request, boolean created) implements Outcome {}

        /** 요청하지 않았다. 계정도 허가도 그대로다. */
        record Refused(String code) implements Outcome {}
    }

    private final ActionGrantService grants;

    private final AccountAccessApi accounts;

    private final AccountDeletionApi deletions;

    private final SessionGuard sessionGuard;

    /** production인가. 기동 시각의 {@code bokrilog.telemetry.env}에서 정하고 시험만 {@link #reinterpret}으로 바꾼다. */
    private volatile boolean production;

    AccountDeletionRequestService(ActionGrantService grants, AccountAccessApi accounts, AccountDeletionApi deletions,
            SessionGuard sessionGuard, @Value("${bokrilog.telemetry.env:development}") String env) {
        this.grants = grants;
        this.accounts = accounts;
        this.deletions = deletions;
        this.sessionGuard = sessionGuard;
        reinterpret(env);
    }

    /**
     * 환경 값을 다시 해석한다. 운영 경로는 생성자 한 번뿐이고 시험이 production 차단을 같은 컨텍스트에서 보려고 부른다(광고
     * 공급자 활성의 {@code AdProviderState.reinterpret}과 같은 관용).
     */
    final void reinterpret(String env) {
        this.production = BokrilogRoleConfig.PRODUCTION.equals(env);
    }

    /** 삭제 요청을 열 수 있는 환경인가(production이 아니다). */
    boolean available() {
        return !this.production;
    }

    /** 허가의 target. 사용자와 정책 버전이 함께 들어가 다른 정책의 허가가 이 확정에 쓰이지 않는다. */
    static String targetOf(UUID userId, String policyVersion) {
        return userId + "|" + policyVersion;
    }

    /** 멱등 키의 digest. 키 원문은 저장하지 않는다. */
    static String keyDigestOf(String idempotencyKey) {
        return AccountDeletionPolicy.sha256("account-deletion-key\n" + idempotencyKey);
    }

    /** 그 계정의 삭제 요청. 상태 조회와 세션 응답이 쓴다. */
    Optional<DeletionRequest> requestOf(UUID userId) {
        return this.deletions.deletionRequestOf(userId);
    }

    /**
     * 확인 대화상자를 열 준비. production → 정책 버전 → 이미 요청 → 쓰기 접근 → 허가 발급 → 무장 순서다. 정책 버전은 사용자가 화면에서
     * 본 값이고 지금 정책과 다르면 {@link #POLICY_CHANGED}로 아무것도 발급·무장하지 않는다. 앞선 삭제 무장은 새 허가가 발급된 뒤에만
     * 덮이고 그 무장이 낳은 허가 하나만 무효화된다(거절되는 준비는 아무것도 버리지 않는다).
     */
    Preparation prepare(HttpSession session, UUID userId, String seenPolicyVersion) {
        if (this.production) {
            return new Preparation.Refused(UNAVAILABLE);
        }
        if (!AccountDeletionPolicy.VERSION.equals(seenPolicyVersion)) {
            return new Preparation.Refused(POLICY_CHANGED);
        }
        if (this.deletions.deletionRequestOf(userId).isPresent()) {
            return new Preparation.Refused(IN_PROGRESS);
        }
        if (this.accounts.decide(userId, userId, AccountAccessKind.WRITE) != AccessDecision.ALLOWED) {
            return new Preparation.Refused(NOT_PERMITTED);
        }
        String policyVersion = AccountDeletionPolicy.VERSION;
        String confirmation = AccountDeletionPolicy.CONFIRMATION;
        var reference = this.grants.issue(session, userId, COMMAND, targetOf(userId, policyVersion), confirmation);
        if (reference.isEmpty()) {
            return new Preparation.Refused(REAUTHENTICATION_REQUIRED);
        }
        AccountDeletionFlow.Armed previous = AccountDeletionFlow.peekIncludingExpired(session);
        if (previous != null) {
            this.grants.invalidateReference(previous.userId(), previous.grantReference());
        }
        // 남아 있는 재인증 무장은 옛 의도다. 방금 허가가 신선한 재인증 위에 섰다.
        ReauthenticationFlow.discard(session);
        AccountDeletionFlow.Armed armed = new AccountDeletionFlow.Armed(userId, policyVersion, reference.get(),
                confirmation, session.getId(), Instant.now());
        AccountDeletionFlow.arm(session, armed);
        return new Preparation.Armed(armed);
    }

    /**
     * 확정.
     *
     * @param policyVersion 사용자가 본 정책 버전
     * @param typed <b>사용자가 입력한</b> 확인 문구. 서버 문구와 NFC·strip 등가여야 하고, 허가 소비의 확인 hash도 이 값으로 만든다
     * @param idempotencyKey 이 확정의 멱등 키이자 허가의 operation id
     */
    Outcome confirm(HttpSession session, UUID userId, String policyVersion,
            String typed, String idempotencyKey) {
        if (this.production) {
            return new Outcome.Refused(UNAVAILABLE);
        }
        String keyDigest = keyDigestOf(idempotencyKey);
        // 재전송 판정이 먼저다 — 삭제가 시작된 계정은 아래 허가 경로(SENSITIVE)에 닿지 못한다.
        Outcome replayed = replayed(userId, keyDigest, policyVersion, typed);
        if (replayed != null) {
            if (replayed instanceof Outcome.Done) {
                /*
                 * 같은 요청의 재생이다. 앞선 확정이 커밋한 뒤 세션 정리 전에 끝났을 수 있으므로 같은 정리를 다시 돈다 — 세 단계 모두
                 * 멱등이다(남은 다른 세션이 없으면 지울 것이 없고, 무장·재인증 시각이 없으면 지울 것이 없다).
                 */
                settleSessions(session, userId, session.getId());
            }
            return replayed;
        }
        if (!AccountDeletionPolicy.VERSION.equals(policyVersion)) {
            return new Outcome.Refused(POLICY_CHANGED);
        }
        if (this.accounts.decide(userId, userId, AccountAccessKind.WRITE) != AccessDecision.ALLOWED) {
            return new Outcome.Refused(NOT_PERMITTED);
        }
        AccountDeletionFlow.Armed armed = AccountDeletionFlow.peek(session);
        if (armed == null
                || !armed.userId().equals(userId)
                || !armed.policyVersion().equals(policyVersion)
                || !armed.sessionId().equals(session.getId())) {
            return new Outcome.Refused(NOT_CONFIRMED);
        }
        if (!ActionGrant.normalize(armed.confirmation()).equals(ActionGrant.normalize(typed))) {
            // 허가를 소비하지 않고 무장도 남긴다 — 같은 대화상자에서 다시 입력한다(운영자 command 선례).
            return new Outcome.Refused(CONFIRMATION_MISMATCH);
        }

        String sessionIdBefore = session.getId();
        AtomicReference<@Nullable DeletionRequest> created = new AtomicReference<>();
        Consumption consumption;
        try {
            consumption = this.grants.consume(
                    new ConsumptionRequest(session, userId, armed.grantReference(), COMMAND,
                            targetOf(userId, policyVersion),
                            // 사용자가 입력한 값: 다섯 번째 귀속이 화면에서 실제로 입력된 문구다.
                            typed, idempotencyKey),
                    () -> {
                        var outcome = this.deletions.requestDeletion(userId, policyVersion,
                                AccountDeletionPolicy.noticeHash(), keyDigest);
                        return switch (outcome) {
                            case DeletionRequestOutcome.Created made -> {
                                created.set(made.request());
                                yield STATE_IN_PROGRESS + "|" + made.request().requestId() + "|"
                                        + made.request().requestedAt();
                            }
                            // 같은 키가 그 사이 커밋됐다 — 소비를 되돌리고 그 행을 재생한다.
                            case DeletionRequestOutcome.Replayed ignored -> throw new Refused(IN_PROGRESS);
                            case DeletionRequestOutcome.InProgress ignored -> throw new Refused(IN_PROGRESS);
                            case DeletionRequestOutcome.Denied ignored -> throw new Refused(NOT_PERMITTED);
                        };
                    });
        } catch (Refused refused) {
            // 소비까지 함께 롤백됐다. 같은 키의 요청이 그 사이 커밋됐으면 그 행이 답이다.
            Outcome committed = replayed(userId, keyDigest, policyVersion, typed);
            return committed != null ? committed : new Outcome.Refused(refused.code());
        } catch (DuplicateKeyException racing) {
            // 사용자당 유일 제약이 동시 요청을 거절했다. 이긴 쪽의 행이 답이다.
            Outcome committed = replayed(userId, keyDigest, policyVersion, typed);
            return committed != null ? committed : new Outcome.Refused(IN_PROGRESS);
        }
        DeletionRequest request = created.get();
        /*
         * `ConsumedRequiringSessionRotation`은 허가 서비스가 "이번 호출이 허가를 소비했다"를 알리는 공통 변형의 이름일 뿐이다. 이
         * command는 그 이름과 달리 세션 ID를 일부러 회전하지 않는다 — 재인증 시각만 지운다(settleSessions).
         */
        if (consumption instanceof Consumption.ConsumedRequiringSessionRotation && request != null) {
            settleSessions(session, userId, sessionIdBefore);
            LOG.info("account deletion requested: state={}", STATE_IN_PROGRESS);
            return new Outcome.Done(request, true);
        }
        // 허가가 거부했다(동시 확정의 진 쪽이거나 다른 세션). 같은 키의 행이 있으면 재생이다.
        Outcome settled = replayed(userId, keyDigest, policyVersion, typed);
        return settled != null ? settled : new Outcome.Refused(NOT_CONFIRMED);
    }

    /** 이 세션의 삭제 무장을 버리고 <b>그 무장이 낳은 허가 하나만</b> 무효화한다. 무장이 없어도 같은 결과다. */
    void abandon(HttpSession session) {
        AccountDeletionFlow.Armed armed = AccountDeletionFlow.peekIncludingExpired(session);
        AccountDeletionFlow.discard(session);
        if (armed != null) {
            this.grants.invalidateReference(armed.userId(), armed.grantReference());
        }
    }

    /**
     * 커밋 뒤 세션 정리. 요청을 실제로 만든 확정에서만 돈다. 순서는 다른 세션 폐기(이 세션 id만 남긴다) → 무장 폐기 → 재인증 시각
     * 제거이고 <b>세션 ID를 회전하지 않는다</b>({@link SessionRotation#completeSensitiveCommandWithoutRotation} — 응답 유실 뒤 같은
     * 쿠키의 재전송이 같은 200을 받아야 한다).
     *
     * <p>단계마다 따로 감싼다. 요청은 이미 커밋됐으므로 어느 단계의 실패도 그 사실을 오류 응답으로 바꾸지 않고 뒤 단계를 막지도 않는다
     * — 실패는 단계 code 한 줄로만 남는다(식별자·문구 없음). 남은 다른 세션·무장·재인증 시각이 있어도 계정은 이미
     * {@code DELETION_IN_PROGRESS}라 쓰기·민감 접근이 막혀 있다.
     */
    private void settleSessions(HttpSession session, UUID userId, String keptSessionId) {
        settleStep("REVOKE_OTHER_SESSIONS", () -> this.sessionGuard.revokeSessionsOfExcept(userId, keptSessionId));
        settleStep("DISCARD_ARMING", () -> AccountDeletionFlow.discard(session));
        settleStep("CLEAR_REAUTHENTICATION",
                () -> SessionRotation.completeSensitiveCommandWithoutRotation(session));
    }

    /**
     * 커밋 뒤 단계 하나. 실패를 삼키고 단계 code와 예외 클래스의 단순 이름 한 줄로 남긴다 — 메시지·스택·사용자·세션 식별자는 싣지
     * 않는다(메시지에는 SQL이나 값이 실릴 수 있다).
     */
    private static void settleStep(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("code", "SETTLE_" + step + "_FAILED")
                    .addKeyValue("exception", failure.getClass().getSimpleName())
                    .log("account deletion settle failed");
        }
    }

    /**
     * 이미 있는 요청 행으로 답할 수 있으면 그 답, 요청이 없으면 {@code null}. 다른 키는 {@link #IN_PROGRESS}다. 같은 키라도 본문이
     * 같아야 같은 요청이다 — 정책 버전이 저장된 요청의 것과 같고 입력 문구가 정규화 뒤 고정 문구와 같아야 하며, 아니면
     * {@link IdempotencyKey#CONFLICT_CODE}다(Bybit 해제의 재생과 같은 규칙. 스키마에 요청 hash 열이 없으므로 두 값을 직접 대조한다).
     */
    private @Nullable Outcome replayed(UUID userId, String keyDigest, String policyVersion, String typed) {
        DeletionRequest existing = this.deletions.deletionRequestOf(userId).orElse(null);
        if (existing == null) {
            return null;
        }
        if (!existing.idempotencyKeyDigest().equals(keyDigest)) {
            return new Outcome.Refused(IN_PROGRESS);
        }
        boolean sameBody = existing.policyVersion().equals(policyVersion)
                && ActionGrant.normalize(typed).equals(ActionGrant.normalize(AccountDeletionPolicy.CONFIRMATION));
        return sameBody ? new Outcome.Done(existing, false) : new Outcome.Refused(IdempotencyKey.CONFLICT_CODE);
    }
}
