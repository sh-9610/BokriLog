package com.bokrilog;

import com.bokrilog.ActionGrant.Binding;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 민감 작업의 5분·일회성 재인증 허가 포트({@code SECURITY-ACTION-1.0}).
 *
 * <p>표면이 둘뿐이다 — {@link #issue}와 {@link #consume}. <b>HTTP 표면은 없다.</b> 실행 가능한
 * 민감 command가 아직 없으므로 지금 엔드포인트를 두면 소비자 없는 표면이 되고, 시험용 상태 변경을
 * 외부 API로 여는 것은 {@code SessionGuard}가 이미 거절한 형태다. 이 포트를 부르는 것은 민감
 * command를 실제로 실행하는 스토리(1.10·1.11)의 엔드포인트다.
 *
 * <p>거부는 하나의 값이다. 만료·이미 사용됨·귀속 불일치·삭제 상태·의심 세션·무효해진 세션이
 * 호출자에게 구분되지 않는다 — {@link #issue}는 빈 {@link Optional}, {@link #consume}은
 * {@link Consumption.Denied} 하나로 수렴한다. 사유를 값으로 돌려주면 호출자가 그것을 조합해 남의
 * 허가와 계정 상태를 복원할 수 있다({@code AccessDecision}과 같은 계약).
 *
 * <p><b>거부와 오류를 가른다.</b> 위의 정책 거부는 값이고, 호출자가 계약을 어긴 경우
 * (트랜잭션을 먼저 연 채로 부르는 것, 상한을 넘는 결과를 돌려주는 것)는 예외다. 그런 배선 실수를
 * 거부로 접으면 원인을 볼 자리가 없어진다.
 *
 * <p><b>세션을 인자로 받는다.</b> 귀속의 세션 축은 기본적으로 <i>지금 이 요청의 세션</i>에서
 * 읽는다({@code HttpSession#getId}). 그래서 로그아웃으로 사라진 세션에는 건넬 {@link HttpSession}이
 * 없고, {@code userId}도 그 세션에 저장된 주체와 대조한다 — 두 값 모두 호출자가 건네므로 대조가
 * 없으면 "세션이 결정한 사용자"라는 계약이 문장으로만 남는다.
 *
 * <p>예외가 하나 있다. 소비는 <b>발급 시각의 세션 id</b>를 호출자가 제시할 수 있다
 * ({@link ConsumptionRequest#boundSessionId()}). OIDC 왕복을 지나는 민감 작업에서는 세션 고정
 * 방어가 성공 핸들러 직전에 ID를 회전시켜 두 값이 언제나 다르기 때문이며, 그 값의 출처는
 * <b>서버 측 흐름 기록</b> 하나다. 클라이언트가 세션 id를 보내는 경로를 만들면 그 값이 곧 귀속을
 * 우회하는 입력이 되므로, 요청 파라미터·헤더·본문에서 읽은 값을 이 인자로 넘기지 않는다.
 *
 * <p><b>트랜잭션 경계는 이 포트가 소유한다.</b> 호출자가 이미 트랜잭션을 연 채로 부르면 거절한다
 * ({@link IllegalStateException}). 참여하면 두 가지가 함께 깨진다 — 멱등 재전송의 유일 인덱스 충돌이
 * 호출자의 트랜잭션을 rollback-only로 만들어 그 커밋이 {@code UnexpectedRollbackException}으로 끝나고,
 * 업무 변경 실패의 롤백 범위가 이 포트가 약속한 범위보다 넓어진다. {@code REQUIRES_NEW}는 답이
 * 아니다 — 소비와 업무 변경이 같은 경계에서 커밋된다는 AC-3이 바로 그 분리를 금지한다.
 *
 * <p>세션 회전을 여기서 하지 않는다. {@code SECURITY-ACTION-1.0}은 성공 시 재회전을 요구하지만
 * 회전은 Spring Security 필터가 HTTP 요청 문맥에서만 수행하고({@code AuthSecurityConfig}) 이 포트에는
 * 그 문맥이 없다. 대신 성공 결과의 <b>타입 이름</b>이 그 의무를 들고 있고
 * ({@link Consumption.ConsumedRequiringSessionRotation}) 실제 배선은 첫 소비자가 자기 엔드포인트에서
 * 한다. 여기서 억지로 회전 경로를 만들면 그것이 곧 HTTP 표면이다.
 *
 * <p>{@code identity} 공개 API만 쓴다. 삭제 상태는 {@code AccountAccessApi.decide(user, user,
 * SENSITIVE)} 하나가 답하고, 이 클래스는 {@code identity.internal}이나 소유 테이블에 닿지 않는다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다. 일회성 migration 역할은 웹 서버 없이 뜨고 세션 개념
 * 자체가 없다({@code AuthSessionService}와 같은 조건).
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class ActionGrantService {

    /**
     * 소비 결과.
     *
     * <p>값이 둘뿐인 것이 요점이다. 거부의 사유를 늘리면 호출자가 그것을 조합해 허가의 존재와
     * 계정 상태를 복원할 수 있다.
     */
    sealed interface Consumption {

        /**
         * 소비와 업무 변경이 한 트랜잭션으로 커밋됐다.
         *
         * <p>이름이 계약이다. {@code SECURITY-ACTION-1.0}은 허가 소비 성공 시 세션 ID 재회전을
         * 요구하며, 이 포트에는 그것을 수행할 HTTP 문맥이 없다. 이 값을 받은 엔드포인트가 응답을
         * 내기 전에 회전을 배선해야 한다.
         *
         * @param result 저장된 실행 결과. 같은 요청의 재전송이 이 값을 그대로 다시 받는다. 뜻은
         *     소비자가 소유하고 이 포트는 문자열로만 다룬다
         */
        record ConsumedRequiringSessionRotation(String result) implements Consumption {}

        /** 거부. 만료·이미 사용됨·귀속 불일치·삭제 상태·의심 세션이 구분되지 않는 하나의 값이다. */
        record Denied() implements Consumption {}
    }

    /**
     * 소비 요청.
     *
     * <p>{@link HttpSession}을 성분으로 든다. 귀속의 세션 축을 클라이언트가 보낸 문자열이 아니라
     * 지금 이 요청의 세션에서 읽어야 하기 때문이다(클래스 javadoc 참조). 요청 하나의 수명 동안만
     * 사는 값이라 record가 값 타입이 아닌 성분을 드는 유일한 자리다.
     *
     * @param session 지금 이 요청의 세션
     * @param userId 인증 세션이 결정한 내부 식별자. 이 포트가 세션에 저장된 주체와 대조한다
     * @param grantReference 발급이 돌려준 불투명 참조
     * @param command 민감 작업의 이름. 발급 때와 같아야 한다
     * @param target 작업 대상. 발급 때와 같아야 한다
     * @param confirmation 확인 문구 <b>원문</b>. 해시는 이 포트가 만든다
     * @param operationId 이 실행의 멱등 키. <b>같은 요청</b>의 재전송은 저장된 결과를 그대로 받고
     *     업무 변경을 다시 실행하지 않는다. 다른 요청에 같은 값을 쓰면 재전송이 아니라 거부다
     * @param boundSessionId 발급 시각의 세션 id. {@code null}이면 지금 이 요청의 세션 id를 쓰며
     *     그것이 기본 경로다. <b>이 값은 클라이언트가 아니라 서버 측 흐름 기록에서만 온다</b> —
     *     요청 파라미터·헤더·본문에서 읽어 넘기면 그 순간 이 인자가 남의 허가를 겨누는 입력이
     *     된다. 지금 이 값을 건네는 호출자는 {@code LinkService} 하나이고 그 출처는 세션 속성에
     *     사는 {@code LinkFlow.Armed}다. 그 record를 읽을 수 있다는 사실 자체가 "회전 전 세션의
     *     연속"을 증명하므로(세션 속성은 {@code changeSessionId}를 건너 살아남고 다른 세션에는
     *     없다) 귀속이 무르지 않는다. 이 성분이 필요한 이유는 하나다: OIDC 콜백에서 세션 고정
     *     방어가 성공 핸들러 직전에 ID를 회전시키므로 발급 시각의 세션 id와 소비 시각의 live id가
     *     <b>언제나</b> 다르고, live id로만 판정하면 왕복을 지나는 민감 작업은 성립할 수 없다
     */
    record ConsumptionRequest(
            HttpSession session,
            UUID userId,
            String grantReference,
            String command,
            String target,
            String confirmation,
            String operationId,
            @Nullable String boundSessionId) {

        /** 귀속 세션이 지금 이 요청의 세션인 기본 경로. 왕복을 지나지 않는 소비가 쓴다. */
        ConsumptionRequest(
                HttpSession session,
                UUID userId,
                String grantReference,
                String command,
                String target,
                String confirmation,
                String operationId) {
            this(session, userId, grantReference, command, target, confirmation, operationId, null);
        }
    }

    /**
     * 우리 {@code (user_id, operation_id)} 유일 색인의 충돌.
     *
     * <p>호출자의 업무 변경이 던지는 {@link DuplicateKeyException}과 갈라야 해서 있는 타입이다.
     * 1.10의 첫 소비자가 부를 {@code linkSocialIdentity}는 유일 제약 위반이 정상 경로인 대표
     * 사례이고, 그 예외를 여기서 "이미 성공한 실행"으로 읽으면 실행되지 않은 작업이 성공으로
     * 보고된다. 그래서 충돌을 <b>일으킨 문장 자리에서</b> 감싸고, 그 밖의 무결성 오류는 손대지 않고
     * 지나가게 둔다.
     */
    private static final class OperationIdConflict extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        OperationIdConflict(DuplicateKeyException cause) {
            super(null, cause, false, false);
        }
    }

    private static final Consumption DENIED = new Consumption.Denied();

    private final ActionGrantRepository repository;

    private final AccountAccessApi accountAccess;

    private final SessionGuard sessionGuard;

    /**
     * 소비 UPDATE와 호출자 작업을 한 경계에 넣는 도구.
     *
     * <p>{@code @Transactional}이 아니라 {@link TransactionTemplate}인 이유는 감싸야 하는 것이 이
     * 클래스의 메서드가 아니라 <b>호출자가 건넨 작업</b>이기 때문이다. 경계를 애노테이션에 숨기면
     * "업무 변경이 소비와 같은 트랜잭션에서 커밋된다"가 코드에서 보이지 않는다
     * ({@code AuthSessionService}와 같은 관용).
     */
    private final TransactionTemplate transactions;

    ActionGrantService(
            ActionGrantRepository repository,
            AccountAccessApi accountAccess,
            SessionGuard sessionGuard,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.accountAccess = accountAccess;
        this.sessionGuard = sessionGuard;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 허가를 발급한다.
     *
     * <p>게이트가 다섯이다 — 쓸 수 있는 세션인가(무효화되지 않았고, 절대 수명 안이고, 의심 세션
     * 표시가 없다), 그 세션의 주체가 이 {@code userId}인가, 받아들일 수 있는 값인가, 삭제가
     * 시작되지 않았는가, 그리고 <b>재인증이 신선한가</b>. 마지막 판정은 자바가 하지 않는다. 세션이
     * 든 재인증 시각과 {@code now()}를 데이터베이스가 함께 읽고 그 결과가 곧 INSERT의 행 수다
     * ({@code ActionGrantRepository.issueIfReauthenticationFresh}).
     *
     * <p>같은 {@code (command, target)}의 살아 있는 허가를 먼저 무효화한다. 재인증 한 번이 같은
     * 작업의 허가를 여럿 만들면 "최근 본인 확인을 마친 작업을 한 번 실행한다"가 성립하지 않는다.
     * 무효화와 발급이 한 트랜잭션이라 발급이 서지 않으면 앞의 허가도 그대로 남는다.
     *
     * @return 불투명 참조. 게이트 중 하나라도 닫히면 빈 값이며 어느 것인지는 구분되지 않는다
     * @throws IllegalStateException 호출자가 이미 트랜잭션을 연 채로 불렀을 때
     */
    Optional<String> issue(
            @Nullable HttpSession session,
            @Nullable UUID userId,
            String command,
            String target,
            String confirmation) {
        requireOwnedTransactionBoundary();
        SessionFacts facts = SessionFacts.of(session);
        // 발급은 언제나 지금 이 요청의 세션에 귀속한다. 제시된 값을 받는 자리는 소비 하나뿐이다.
        Binding binding = bindingOf(facts, null, userId, command, target, confirmation);
        if (binding == null || facts == null || !isUsableSession(facts)) {
            return Optional.empty();
        }
        Instant reauthenticatedAt = facts.reauthenticatedAt();
        if (reauthenticatedAt == null) {
            return Optional.empty();
        }
        if (this.accountAccess.decide(userId, userId, AccountAccessKind.SENSITIVE)
                != AccessDecision.ALLOWED) {
            return Optional.empty();
        }

        sweep(binding.userId());

        String reference = ActionGrant.newReference();
        Integer issued = insert(reference, binding, reauthenticatedAt);
        return issued != null && issued == 1 ? Optional.of(reference) : Optional.empty();
    }

    /**
     * 그 작업의 살아 있는 허가를 무효화한다.
     *
     * <p>{@link #issue}가 발급 앞에서 부르는 것과 같은 문장이고, 이 표면은 <b>발급 없이</b> 그
     * 무효화만 필요한 경우를 위한 것이다 — 흐름 기록을 버리면서 그것이 가리키던 허가를 남기면
     * 아무도 참조할 수 없는 허가가 수명이 다할 때까지 산다({@code LinkService.abandon}).
     *
     * <p>소비된 행은 건드리지 않는다. 그 행은 멱등 재전송의 근거다.
     *
     * <p>트랜잭션을 열지 않는다. 문장 하나라 그 자체로 원자적이고, 발급과 함께 쓰는 경로는
     * {@link #issue}가 자기 경계 안에서 같은 저장소 메서드를 다시 부른다.
     */
    void invalidate(@Nullable UUID userId, String command, String target) {
        if (userId == null) {
            return;
        }
        this.repository.invalidateLiveGrants(
                userId, ActionGrant.normalize(command), ActionGrant.normalize(target));
    }

    private @Nullable Integer insert(String reference, Binding binding, Instant reauthenticatedAt) {
        try {
            return this.transactions.execute(
                    status -> {
                        this.repository.invalidateLiveGrants(
                                binding.userId(), binding.command(), binding.target());
                        int rows =
                                this.repository.issueIfReauthenticationFresh(
                                        reference,
                                        binding,
                                        reauthenticatedAt,
                                        ActionGrant.LIFETIME,
                                        ActionGrant.REAUTHENTICATION_WINDOW,
                                        ActionGrant.REAUTHENTICATION_FUTURE_SKEW);
                        if (rows != 1) {
                            // 발급이 서지 않았다. 앞선 허가를 지운 채로 끝내면 재인증이 오래된
                            // 요청 하나가 살아 있는 허가를 없애는 도구가 된다.
                            status.setRollbackOnly();
                        }
                        return rows;
                    });
        } catch (DataIntegrityViolationException vanished) {
            /*
             * 판정과 INSERT 사이에 이 계정의 payload가 지워졌다(`erasePayload`). 외래키 위반이
             * 그것을 알리지만 호출자에게는 다른 거부와 같은 하나여야 한다.
             */
            return null;
        }
    }

    /**
     * 허가를 소비하고 그 자리에서 업무 변경을 실행한다.
     *
     * <p>소비와 업무 변경이 <b>같은 트랜잭션</b>에서 커밋된다. 업무 변경이 예외로 끝나면 소비
     * UPDATE도 함께 롤백되어 "허가만 소비된 성공"이 남지 않고, 사용자는 같은 허가로 다시 시도할 수
     * 있다. 예외는 그대로 호출자에게 전파한다 — 값으로 접으면 실패가 거부와 구분되지 않는다.
     * 업무 변경이 던지는 {@link DuplicateKeyException}도 마찬가지로 전파된다.
     *
     * <p><b>같은 요청</b>의 재전송은 저장된 결과를 그대로 돌려주고 업무 변경을 다시 실행하지
     * 않는다. 같은 요청의 정의는 {@code operation_id}만이 아니라 허가 참조·사용자·command·target·
     * 확인 해시가 모두 같은 것이다({@code ActionGrantRepository.findStoredResult}). 멱등의 권위는
     * {@code (user_id, operation_id)}의 유일 인덱스이며, 애플리케이션이 "이미 있나?"를 먼저 묻는
     * 형태는 동시 재전송에서 둘 다 통과한다.
     *
     * @param operation 업무 변경. 저장할 결과를 돌려준다. {@code null}은 빈 문자열로 접힌다
     * @throws IllegalStateException 호출자가 이미 트랜잭션을 연 채로 불렀거나, 업무 변경이
     *     {@link ActionGrant#MAX_RESULT_BYTES}를 넘는 결과를 돌려줬을 때
     */
    Consumption consume(ConsumptionRequest request, Supplier<String> operation) {
        requireOwnedTransactionBoundary();
        SessionFacts facts = SessionFacts.of(request.session());
        Binding binding =
                bindingOf(
                        facts,
                        request.boundSessionId(),
                        request.userId(),
                        request.command(),
                        request.target(),
                        request.confirmation());
        if (binding == null
                || facts == null
                || !isUsableSession(facts)
                || !ActionGrant.isAcceptable(
                        request.grantReference(), ActionGrant.MAX_GRANT_REFERENCE_BYTES)
                || !ActionGrant.isAcceptable(
                        request.operationId(), ActionGrant.MAX_OPERATION_ID_BYTES)) {
            return DENIED;
        }

        try {
            Consumption outcome =
                    this.transactions.execute(status -> attempt(request, binding, operation));
            // TransactionTemplate은 콜백이 null을 돌려줄 때만 null이다. 아래 콜백은 언제나 값을
            // 돌려주므로 여기 오는 것은 계약이 깨진 상태이고, 안전한 답은 거부다.
            return outcome == null ? DENIED : outcome;
        } catch (OperationIdConflict alreadyExecuted) {
            /*
             * 이 사용자가 그 `operation_id`를 이미 썼다. 유일 인덱스가 두 번째 소비를 거절했고 그
             * 트랜잭션은 통째로 롤백됐으므로 업무 변경은 실행되지 않았다. 조회는 <b>새 트랜잭션</b>
             * 에서 한다 — 앞의 것은 abort된 상태라 어떤 문장도 받지 않는다.
             *
             * 요청 지문이 그때와 같을 때만 저장된 결과가 나온다. 같은 값을 다른 작업에 재사용한
             * 요청은 여기서 빈 값을 받아 거부로 수렴한다.
             */
            return replay(request.grantReference(), binding, request.operationId());
        }
    }

    private Consumption attempt(
            ConsumptionRequest request, Binding binding, Supplier<String> operation) {
        /*
         * 삭제 상태를 여기서 다시 본다. 발급 시점에 활성이던 계정이 그 사이에 전환될 수 있고,
         * 허가 소비는 그 뒤에 오는 민감 작업의 전제이므로 삭제가 시작된 계정에서 성립하면 안 된다.
         * 이 조회 결과와 무관하게 아래 소비 UPDATE가 다섯 귀속을 다시 판정한다.
         */
        if (this.accountAccess.decide(request.userId(), request.userId(), AccountAccessKind.SENSITIVE)
                != AccessDecision.ALLOWED) {
            return DENIED;
        }

        int consumed;
        try {
            consumed = this.repository.consume(request.grantReference(), binding, request.operationId());
        } catch (DuplicateKeyException conflict) {
            // 이 문장이 부딪힐 수 있는 유일 제약은 `(user_id, operation_id)` 하나다. 여기서 감싸야
            // 호출자의 업무 변경이 던지는 같은 타입의 예외와 갈린다.
            throw new OperationIdConflict(conflict);
        }

        if (consumed != 1) {
            /*
             * 0행이다. 다섯 중 하나가 어긋났거나, 이미 소비됐거나, 만료됐다. 그중 한 경우만
             * 거부가 아니다 — 같은 요청이 이미 성공한 경우. 응답을 잃은 클라이언트의 재전송이 그
             * 모양이며, 업무 변경을 다시 실행하지 않고 저장된 결과를 돌려준다.
             */
            return replay(request.grantReference(), binding, request.operationId());
        }

        String result = operation.get();
        String stored = result == null ? "" : result;
        if (!ActionGrant.isStorableResult(stored)) {
            /*
             * 상한을 넘는 결과다. 그대로 저장하면 스키마 CHECK가 호출자 트랜잭션을 abort시키고
             * 그 실패는 SQL 오류의 모양으로 나타난다. 소비자의 배선 실수이므로 거부가 아니라
             * 예외이며, 이 트랜잭션은 롤백되어 업무 변경도 소비도 남지 않는다. 값은 메시지에
             * 담지 않는다.
             */
            throw new IllegalStateException(
                    "업무 변경이 상한을 넘는 결과를 돌려줬다: " + stored.length() + "자");
        }
        if (this.repository.storeResult(request.grantReference(), stored) != 1) {
            /*
             * 방금 소비한 행이 사라졌다는 뜻이다. 조용히 성공으로 두면 재전송이 결과를 찾지 못한다.
             *
             * 이 가지는 <b>구조상 도달하지 않는다</b> — 바로 위 UPDATE가 같은 트랜잭션에서 그 행을
             * 잠갔으므로 다른 트랜잭션이 지울 수 없다. 그래서 어떤 시험도 여기를 지나게 만들 수
             * 없고(변이로 확인했다), 그럼에도 남겨 두는 이유는 이 클래스의 다른 문장과 같다 —
             * 영향 행 수가 곧 판정이고, 0행을 성공으로 접는 자리를 하나도 만들지 않는다
             * ({@code AuthSessionService}의 {@code null} 트랜잭션 결과 처리와 같은 성격이다).
             */
            throw new IllegalStateException("소비한 허가에 결과를 적지 못했다.");
        }
        return new Consumption.ConsumedRequiringSessionRotation(stored);
    }

    private Consumption replay(String reference, Binding binding, String operationId) {
        return this.repository
                .findStoredResult(reference, binding, operationId)
                .<Consumption>map(Consumption.ConsumedRequiringSessionRotation::new)
                .orElse(DENIED);
    }

    /**
     * 만료된 미소비 허가를 기회적으로 지운다.
     *
     * <p>실패해도 발급이 막히지 않는다. 이 정리는 발급 판정의 일부가 아니라 곁다리이고, 여기서
     * 예외가 올라가면 청소 실패가 곧 기능 실패가 된다.
     */
    private void sweep(UUID userId) {
        try {
            this.repository.deleteExpiredUnconsumedOf(userId);
        } catch (DataAccessException ignored) {
            // 다음 발급이 다시 시도한다.
        }
    }

    /**
     * 이 세션을 허가에 쓸 수 있는가.
     *
     * <p>판정을 {@code SessionGuard}가 한다. 절대 수명을 넘긴 세션과 재인증을 요구받은 의심 세션이
     * 같은 하나의 거부로 접히며, 두 상태를 여기서 다시 정의하지 않는다.
     */
    private boolean isUsableSession(SessionFacts facts) {
        return this.sessionGuard.evaluate(facts.creationTimeMillis(), facts.reauthMarker())
                == SessionGuard.Verdict.ACTIVE;
    }

    /**
     * 이 포트가 트랜잭션 경계를 소유한다는 계약을 강제한다.
     *
     * <p>거부가 아니라 예외다. 호출자가 이미 트랜잭션을 연 것은 사용자가 만든 상황이 아니라 배선
     * 실수이고, 값으로 접으면 그 실수가 "허가가 거부됐다"로 보인다.
     */
    private static void requireOwnedTransactionBoundary() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "허가 포트가 트랜잭션 경계를 소유한다. 호출자가 연 트랜잭션 안에서 부르지 않는다.");
        }
    }

    /**
     * 요청 하나가 세션에서 읽는 사실 전부.
     *
     * <p>세션을 읽는 자리를 하나로 모은다. 무효화된 세션의 접근자는
     * {@link IllegalStateException}을 던지고 세션이 아예 없으면 {@code null}인데, 그 둘이 코드 여기저기
     * 흩어져 있으면 "구분되지 않는 거부 하나"가 예외로 새는 자리가 그만큼 늘어난다.
     *
     * @param reauthenticatedAt 세션에 기록된 마지막 재인증 시각
     * @param principalName 세션에 저장된 인증 주체의 이름. 개설된 로그인에만 있다
     */
    private record SessionFacts(
            String id,
            long creationTimeMillis,
            @Nullable Object reauthMarker,
            @Nullable Instant reauthenticatedAt,
            @Nullable String principalName) {

        /**
         * 세션에서 사실을 읽는다.
         *
         * @return 읽을 수 없으면 {@code null}. 세션이 없는 경우와 이미 무효화된 경우가 같은 값이다
         */
        static @Nullable SessionFacts of(@Nullable HttpSession session) {
            if (session == null) {
                return null;
            }
            try {
                return new SessionFacts(
                        session.getId(),
                        session.getCreationTime(),
                        session.getAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE),
                        ReauthenticationFlow.lastAuthenticatedAt(session),
                        principalNameOf(session));
            } catch (IllegalStateException invalidated) {
                // 로그아웃·만료가 이 요청과 겹쳤다. 허가가 성립할 세션이 없다는 뜻이다.
                return null;
            }
        }

        /**
         * 이 세션이 들고 있는 개설된 로그인의 주체 이름.
         *
         * <p>세션 속성을 직접 읽는다({@code AuthFlowHandlers.carriesEstablishedPrincipal}과 같은
         * 관용). 진행 중인 OIDC 흐름의 중간 결과는 {@code OAuth2AuthenticationToken}이라 여기서
         * 걸러지고, 그 세션은 허가를 받을 수 없다.
         */
        private static @Nullable String principalNameOf(HttpSession session) {
            Object stored =
                    session.getAttribute(
                            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(stored instanceof SecurityContext context)) {
                return null;
            }
            Authentication authentication = context.getAuthentication();
            if (!(authentication instanceof PreAuthenticatedAuthenticationToken token)
                    || !token.isAuthenticated()) {
                return null;
            }
            return token.getName();
        }
    }

    /**
     * 다섯 귀속을 만든다. 받아들일 수 없는 값이 하나라도 있으면 {@code null}이다.
     *
     * <p>세션 id는 기본적으로 세션에서 읽고, {@code userId}는 <b>언제나</b> 그 세션에 저장된 주체와
     * 대조한다. 대조가 없으면 두 값 모두 호출자가 건네는 값이라 "세션이 유일한 {@code user_id}
     * 권위"라는 계약이 문장으로만 남는다.
     *
     * <p>{@code presentedSessionId}가 있으면 귀속의 세션 축만 그 값으로 바뀐다. 그 값의 출처는
     * 서버 측 흐름 기록 하나이며 근거는 {@link ConsumptionRequest#boundSessionId()}의 javadoc이
     * 소유한다. 주체 대조와 세션 판정({@link #isUsableSession})은 그대로 <b>지금 이 요청의
     * 세션</b>이 답하므로, 제시된 값이 여는 것은 "회전 전 id를 가리키는 것" 하나뿐이다.
     *
     * <p>{@code command}·{@code target}은 {@link ActionGrant#normalize}를 지난 값을 담는다. 컬럼에
     * 저장되는 값과 해시에 들어가는 값이 같아야 하기 때문이다 — 한쪽만 정규화하면 해시는 맞고 컬럼은
     * 어긋나 영영 소비할 수 없는 허가가 생긴다. 확인 문구는 해시로만 남으므로 여기서 원문이 밖으로
     * 나가지 않는다.
     */
    private static @Nullable Binding bindingOf(
            @Nullable SessionFacts facts,
            @Nullable String presentedSessionId,
            @Nullable UUID userId,
            @Nullable String command,
            @Nullable String target,
            @Nullable String confirmation) {
        if (facts == null || userId == null || command == null || target == null || confirmation == null) {
            return null;
        }
        if (!userId.toString().equals(facts.principalName())) {
            // 세션이 결정한 사용자가 아니다. 다른 거부와 같은 하나로 접힌다.
            return null;
        }
        String sessionId = presentedSessionId == null ? facts.id() : presentedSessionId;
        String normalizedCommand = ActionGrant.normalize(command);
        String normalizedTarget = ActionGrant.normalize(target);
        if (!ActionGrant.isAcceptable(sessionId, ActionGrant.MAX_SESSION_ID_BYTES)
                || !ActionGrant.isAcceptable(normalizedCommand, ActionGrant.MAX_COMMAND_BYTES)
                || !ActionGrant.isAcceptable(normalizedTarget, ActionGrant.MAX_TARGET_BYTES)
                || !ActionGrant.isAcceptable(
                        ActionGrant.normalize(confirmation), ActionGrant.MAX_CONFIRMATION_BYTES)) {
            return null;
        }
        return new Binding(
                userId,
                sessionId,
                normalizedCommand,
                normalizedTarget,
                ActionGrant.canonicalConfirmationHash(normalizedCommand, normalizedTarget, confirmation));
    }
}
