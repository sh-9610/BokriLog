package com.bokrilog;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountLifecycleApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;

/**
 * 로그인 수단을 현재 계정에서 해제한다.
 *
 * <p>{@code LinkService}의 대칭이되 왕복이 없다. 연결은 대상 제공자의 OIDC 왕복이 확인의 자리였고,
 * 해제에서는 <b>확인 대화상자가 그 자리를 대신한다</b> — 대화상자를 여는 요청이 허가를 발급하고
 * 무장하며({@link #start}) 최종 확인이 그것을 소비한다({@link #complete}).
 *
 * <p><b>마지막 수단 보호는 여기 없다.</b> 이 클래스가 하는 사전 확인은 화면이 이미 아는 사실을 서버
 * 쪽에서 한 번 더 보는 것일 뿐이고, 권위는 {@code identity}의 삭제 문장 하나다
 * ({@code AccountLifecycleApi#unlinkSocialIdentity}). 애플리케이션이 먼저 세고 나중에 지우는 형태를
 * 만들면 그 사이가 곧 "동시 요청으로 모든 수단이 사라지는" 창이다.
 *
 * <p><b>거부는 하나의 값이다.</b> 마지막 수단·허가 만료·세션 불일치·삭제 상태·미연결 제공자가
 * 호출자에게 구분되지 않는다 — {@link #start}는 빈 {@link Optional}, {@link #complete}는
 * {@link Outcome#DENIED} 하나로 수렴한다. 다만 마지막 수단만은 <b>화면이 미리 설명한다</b>: 사용자가
 * 연결 목록에서 이미 아는 사실이고, 숨기면 비활성화된 버튼에 이유가 없어 접근성이 깨진다. 서버 응답은
 * 여전히 구분하지 않으므로 API를 직접 부르는 쪽은 아무것도 새로 배우지 못한다.
 *
 * <p><b>최종 커밋은 한 트랜잭션이다.</b> 허가 소비와 {@code unlinkSocialIdentity}가 같은 경계에서
 * 커밋되므로 "허가만 소비되고 매핑이 남은 상태"도 "매핑만 사라지고 허가가 남은 상태"도 만들어지지
 * 않는다. 경계는 허가 포트가 소유하고({@code ActionGrantService}) 이 클래스는 그 안에서 돌 작업
 * 하나를 건넨다. 삭제가 거부되면 <b>예외로</b> 그 트랜잭션을 되돌린다 — 값으로 돌려주면 소비만
 * 커밋되고 매핑은 그대로인 반쪽 상태가 정확히 남는다({@code LinkService}와 같은 근거).
 *
 * <p><b>커밋 뒤 세션을 정리한다.</b> 현재 세션 외의 그 사용자 세션을 모두 폐기하고 현재 세션은 다시
 * 회전시켜 남긴다. 세션은 어떤 제공자로 인증됐는지 기록하지 않으므로(그 기록은 "세션이 담는 값은
 * 내부 {@code user_id} 하나"라는 불변식이 금지한다) "해제된 수단에서 유래한 세션만"을 고를 수 없고,
 * 남는 답은 fail-safe 쪽 하나다({@code SessionGuard#revokeSessionsOfExcept}).
 *
 * <p>등록 구성 여부를 보지 않는다. 연결 시작은 그것을 보아야 했다 — 뒤따르는 인가 시작이
 * fail-closed로 끝나면 참조할 수 없는 허가가 남기 때문이다({@code LinkService.start}). 해제에는 그
 * 뒤따르는 왕복이 없고, 오히려 <b>자격정보가 빠진 제공자일수록 떼어 낼 수 있어야 한다</b> — 구성이
 * 사라졌다는 이유로 이미 연결된 수단이 영구히 붙박이가 되면 그것이 계정을 가두는 또 다른 길이다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다({@code LinkService}와 같은 조건).
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class UnlinkService {

    /**
     * 민감 command의 이름.
     *
     * <p>{@code LinkService.COMMAND}와 <b>다른 값</b>이어야 한다. 허가는 이 문자열에 귀속하므로
     * (그리고 확인 해시가 command를 함께 해시하므로) 값이 같으면 연결용으로 발급된 허가가 해제
     * 요청에 쓰인다 — 본인 확인 한 번이 사용자가 의도하지 않은 파괴적 작업을 여는 경로다.
     */
    static final String COMMAND = "unlink-provider";

    /**
     * 확인 문구.
     *
     * <p>{@code SECURITY-ACTION-1.0}의 다섯 번째 귀속 축이다. 사용자가 옮겨 적는 값이 아니다 —
     * 대상명 재입력은 DESIGN.md가 <b>계정 삭제처럼 범위가 큰 작업</b>에만 요구하고(Epic 5), 해제의
     * 확인은 대화상자의 조작 하나다. 그래서 이 값은 서버가 든 상수이며 발급과 소비가 같은 자리에서
     * 같은 값을 쓴다. 상수여도 축은 살아 있다: 다른 command·다른 target과 함께 해시되므로
     * ({@code ActionGrant.canonicalConfirmationHash}) 해제 허가는 다른 작업에 쓰이지 않는다.
     */
    static final String CONFIRMATION = "해제";

    /**
     * 저장되는 실행 결과.
     *
     * <p>응답을 잃은 클라이언트의 재전송이 이 값을 그대로 다시 받는다. 제공자 이름·계정 식별자·
     * 시각을 담지 않는다 — 이 문자열은 허가 행에 남고, 그 행은 계정 삭제까지 산다.
     */
    static final String RESULT_UNLINKED = "unlinked";

    /**
     * 해제할 수 있으려면 있어야 하는 최소 수단 수.
     *
     * <p>사전 확인의 값이며 <b>권위가 아니다</b>. 권위는 {@code identity}의 삭제 문장 안에 있는 같은
     * 판정이고, 여기 있는 것은 "허가를 발급할 가치가 있는가"를 보는 게이트다 — 없으면 소비될 수 없는
     * 허가가 발급되고 5분을 산다. 두 값이 갈리면 결과는 같은 거부 하나이므로 위험하지 않다.
     */
    private static final int MINIMUM_SIGN_IN_METHODS = 2;

    /**
     * 삭제가 거부됐다.
     *
     * <p>소비 트랜잭션을 되돌리기 위한 사설 타입이다({@code LinkService.LinkRejected}와 같은 근거).
     * 스택도 메시지도 담지 않는다 — 이 예외는 이 파일 안에서 만들어져 이 파일 안에서 잡히고, 그
     * 사이에 로그를 지나지 않는다.
     */
    private static final class UnlinkRejected extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        UnlinkRejected() {
            super(null, null, false, false);
        }
    }

    /**
     * 해제 완료의 결과.
     *
     * <p>값이 둘뿐인 것이 요점이다({@code LinkService.Outcome}과 같은 계약). 거부의 사유를 늘리면
     * 호출자가 그것을 조합해 계정의 수단 구성을 복원할 수 있다.
     */
    enum Outcome {

        /** 허가 소비와 매핑 제거가 한 트랜잭션으로 커밋됐고 세션 정리도 끝났다. */
        UNLINKED,

        /** 거부. 마지막 수단·허가 만료·세션 불일치·삭제 상태·미연결이 구분되지 않는 하나다. */
        DENIED
    }

    private final AccountLifecycleApi accountLifecycle;

    private final ActionGrantService grants;

    /** 커밋 뒤 세션 정리의 소유자. 이 클래스는 판정을 하지 않고 대상만 정한다. */
    private final SessionGuard sessionGuard;

    UnlinkService(
            AccountLifecycleApi accountLifecycle, ActionGrantService grants, SessionGuard sessionGuard) {
        this.accountLifecycle = accountLifecycle;
        this.grants = grants;
        this.sessionGuard = sessionGuard;
    }

    /**
     * 확인 대화상자를 열 준비를 한다. <b>매핑·세션·목록을 건드리지 않는다.</b>
     *
     * <p>게이트가 둘이고 둘 다 사전 확인이다 — 대상이 <b>지금 연결돼 있고</b> 남은 수단이 둘 이상.
     * 재인증 신선도는 이 클래스가 보지 않는다: 허가 발급이 세션의 재인증 시각과 {@code now()}를
     * 데이터베이스에서 함께 읽고 그 결과가 곧 INSERT의 행 수다
     * ({@code ActionGrantRepository.issueIfReauthenticationFresh}). 의심 세션·삭제 상태·주체 대조도
     * 같은 호출이 함께 본다.
     *
     * <p>순서가 계약이다. 허가를 먼저 받고 무장한다 — 무장을 먼저 하면 허가가 서지 않은 흐름이
     * 세션에 남고, 그 흐름의 최종 확인은 소비할 것이 없어 거부로 끝난다.
     *
     * <p>귀속 세션 id를 제시하지 않는다. 준비와 확인 사이에 세션이 회전하지 않으므로 1.9 포트의
     * 기본 경로가 그대로 성립한다({@code UnlinkFlow}의 클래스 javadoc).
     *
     * @return 무장된 흐름, 게이트 중 하나라도 닫히면 빈 값. 어느 것인지는 구분되지 않는다
     */
    Optional<UnlinkFlow.Armed> start(HttpSession session, UUID userId, String provider) {
        Set<String> linked = this.accountLifecycle.findLinkedProviders(userId);
        if (!linked.contains(provider) || linked.size() < MINIMUM_SIGN_IN_METHODS) {
            /*
             * 연결되지 않은 제공자이거나 마지막 수단이다. 두 경우가 같은 빈 값으로 접힌다.
             *
             * 이 게이트가 막는 것은 "소비될 수 없는 허가"다. 권위는 삭제 문장 안의 같은 판정이고,
             * 그 사이에 상태가 바뀌어 이 게이트를 통과한 요청이 커밋에서 거부되는 것은 정상이다 —
             * 결과는 같은 거부 하나이며 저장소에는 아무것도 남지 않는다.
             */
            return Optional.empty();
        }

        Optional<String> reference =
                this.grants.issue(session, userId, COMMAND, provider, CONFIRMATION);
        if (reference.isEmpty()) {
            return Optional.empty();
        }

        /*
         * 앞선 흐름은 무장이 확정된 **뒤에** 정리한다.
         *
         * 앞에 두면 거절되는 준비 요청 하나가(마지막 수단을 눌러 본 경우가 그렇다) 진행 중이던
         * 흐름의 무장과 허가를 함께 없앤다 — 아무 조작도 성립하지 않았는데 열어 둔 것을 잃는다.
         *
         * 흐름 기록만 덮으면 그것이 가리키던 허가가 아무도 참조할 수 없는 채로 수명을 산다 —
         * `issue`의 무효화 술어는 `(user, command, target)`이고 target이 제공자 이름이라, Google로
         * 무장한 뒤 네이버로 다시 무장하면 Google 허가만 살아남는다(`LinkService.start`와 같은 결함).
         * 그래서 덮이는 무장의 허가를 **참조로** 함께 지운다.
         *
         * 재인증 무장도 버린다. 해제 준비가 성립한다는 것은 재인증이 이미 끝났다는 뜻이므로(그 시각이
         * 없으면 허가가 서지 않는다) 남아 있는 재인증 무장은 완료되지 않은 옛 의도다. 연결 무장을
         * 버리는 것은 이 메서드를 부르는 컨트롤러가 한다 — 두 서비스가 서로를 주입받으면 빈 순환이
         * 되고, 그 경계를 한 방향으로 고른 것이 그 자리다(`AuthSessionController`).
         */
        supersedePreviousArming(session);
        ReauthenticationFlow.discard(session);

        UnlinkFlow.Armed armed =
                new UnlinkFlow.Armed(
                        provider,
                        userId,
                        reference.get(),
                        // 멱등 키는 서버가 만든다. 클라이언트가 고르면 추측 가능한 값이
                        // `(user_id, operation_id)` 슬롯을 선점하는 경로가 열린다.
                        UUID.randomUUID().toString(),
                        Instant.now());
        UnlinkFlow.arm(session, armed);
        return Optional.of(armed);
    }

    /**
     * 이 세션에 남은 해제 무장을 버리고 <b>그 무장이 낳은 허가 하나만</b> 무효화한다.
     *
     * <p>{@code LinkService.abandon}과 같은 근거의 같은 모양이다. 호출자가 셋이고 셋 다 "이 세션의
     * 해제 의도를 지금 끝낸다"는 같은 뜻이다 — 새 해제 준비가 앞의 무장을 덮을 때({@link #start}),
     * 연결 시작이 흐름을 갈아탈 때, 재인증 시작이 갈아탈 때다(뒤의 둘은
     * {@code AuthSessionController}).
     *
     * <p>수명이 지난 무장도 대상이다. 그 무장의 허가는 아직 살아 있을 수 있고, 살아 있다면 같은
     * 이유로 지워야 한다.
     *
     * <p>참조로 좁힌다. {@code (command, target)}으로 지우면 오래된 탭의 취소 하나가 같은 작업을 위해
     * 다른 세션이 방금 받은 살아 있는 허가까지 지운다. 무장이 들고 있던 참조 하나로 좁히면 그 무장이
     * 낳은 허가만 사라지고, 이미 다른 발급이 대신했다면 아무것도 하지 않는다.
     */
    void abandon(HttpSession session) {
        UnlinkFlow.Armed armed = UnlinkFlow.peekIncludingExpired(session);
        UnlinkFlow.discard(session);
        if (armed != null) {
            this.grants.invalidateReference(armed.userId(), armed.grantReference());
        }
    }

    /**
     * 새 무장이 앞선 무장을 대신한다. 앞선 무장의 허가를 <b>참조로</b> 무효화한다.
     *
     * <p>대상이 같으면 방금 {@code issue}가 그 {@code (user, command, target)}의 허가를 이미 지웠으므로
     * 참조 하나를 지우는 이 호출은 0행으로 끝난다 — 방금 발급한 허가는 다른 참조라 걸리지 않는다. 대상이
     * 다르면 그 무장이 낳은 허가만 사라진다. 그래서 대상을 받지 않는다 — 옛 조건("대상이 다를 때만")은
     * {@code (user, command, target)} 단위가 방금 발급한 허가를 삼키던 시절의 것이다.
     */
    private void supersedePreviousArming(HttpSession session) {
        UnlinkFlow.Armed previous = UnlinkFlow.peekIncludingExpired(session);
        UnlinkFlow.discard(session);
        if (previous != null) {
            this.grants.invalidateReference(previous.userId(), previous.grantReference());
        }
    }

    /**
     * 최종 확인. <b>허가 소비와 매핑 제거가 한 트랜잭션</b>이고, 커밋 뒤 세션을 정리한다.
     *
     * <p>세 단계가 순서대로다.
     *
     * <ol>
     *   <li>무장을 읽는다. 없거나 대상 제공자가 다르거나 사용자가 다르면 거부다.
     *   <li>허가 소비와 {@code unlinkSocialIdentity}를 한 경계에서 커밋한다. 삭제가 거부되면 사설
     *       예외로 그 트랜잭션을 되돌려 소비도 함께 사라진다.
     *   <li>커밋 뒤에만, 그리고 <b>업무 변경이 실제로 돈 경우에만</b> 세션을 정리한다. 현재 세션 외
     *       전부 폐기하고 현재 세션을 회전시킨다.
     * </ol>
     *
     * <p><b>무장을 여기서 버리지 않는다.</b> 버리면 두 가지가 깨진다. 하나는 고아 허가다 —
     * {@code UnlinkRejected}가 아닌 예외(데이터베이스 오류, 포트의 배선 실수)가 나면 무장만 사라지고
     * 허가는 남아 아무도 참조할 수 없는 행이 수명을 산다. 다른 하나는 멱등이다 — 응답을 잃은
     * 클라이언트가 같은 {@code operation_id}를 다시 제시할 길이 없어져, 1.9가 만든 재전송 계약이
     * HTTP 표면에서 도달할 수 없는 코드로 남는다. 그래서 무장은 <b>다음 무장이 대신하거나</b>
     * ({@link #supersedePreviousArming}) <b>다른 흐름이 버리거나</b>({@link #abandon}) 수명이 다해
     * 사라진다. 일회성은 무장이 아니라 허가가 갖는 성질이며 그 권위는 조건부 UPDATE 한 문장이다.
     *
     * <p>세션 정리가 커밋 <b>뒤</b>인 것이 중요하다. 트랜잭션 안에서 하면 매핑 제거가 롤백되는 경로에
     * 세션만 사라진 상태가 남는다 — 사용자는 아무것도 해제되지 않은 채 다른 기기에서 쫓겨난다.
     * 재전송에서도 돌지 않는다: 업무 변경이 다시 실행되지 않았는데 세션을 또 폐기·회전시키면 같은
     * 이유로 "커밋 뒤에만"이 깨진다.
     *
     * <p>회전은 {@code SECURITY-ACTION-1.0}이 허가 소비 성공에 요구하는 것이고 1.9의 결과 타입 이름
     * ({@code ConsumedRequiringSessionRotation})이 이 자리로 넘긴 의무다. 폐기보다 <b>뒤</b>에 한다 —
     * 폐기 대상을 고를 때 저장소에 있는 현재 세션의 id는 아직 회전 전 값이기 때문이다(회전은 요청
     * 끝에 반영된다). 회전과 함께 재인증 시각이 지워진다({@code SessionRotation}) — 본인 확인 1회는
     * command 1건이다.
     *
     * @param request 지금 이 요청. 세션 회전이 여기서만 가능하다
     * @param session 지금 이 요청의 세션. 귀속의 세션 축이자 남길 세션이다
     * @param userId 세션이 결정한 사용자. 무장이 붙들어 둔 값과 같아야 한다
     * @param provider 최종 확인이 알려 준 제공자. 무장의 대상과 같아야 한다
     * @return 해제됐으면 {@link Outcome#UNLINKED}. 거부는 이유를 구분하지 않는 하나의 값이다
     */
    Outcome complete(
            HttpServletRequest request, HttpSession session, UUID userId, String provider) {
        UnlinkFlow.Armed armed = UnlinkFlow.peek(session);
        if (armed == null
                || !armed.targetProvider().equals(provider)
                || !armed.userId().equals(userId)) {
            /*
             * 무장이 없거나 이 확인의 것이 아니다. 무장을 지우지 않는다 — 대상이 다른 확인 하나가
             * 살아 있는 의도를 없앨 수 있으면 그것이 곧 방해 경로이고, 남은 무장은 어차피 5분 뒤
             * 스스로 만료된다.
             */
            return Outcome.DENIED;
        }

        ConsumptionRequest consumption =
                new ConsumptionRequest(
                        session,
                        armed.userId(),
                        armed.grantReference(),
                        COMMAND,
                        armed.targetProvider(),
                        CONFIRMATION,
                        armed.operationId());
        /*
         * 업무 변경이 이번에 실제로 돌았는가.
         *
         * 허가 포트는 처음 소비와 저장된 결과의 재전송을 **같은 타입**으로 답한다(둘 다 성공이고
         * 둘 다 같은 결과 문자열이다). 밖에서 그 둘을 가르는 재료는 하나뿐이다 — 건넨 작업이
         * 불렸는가. 재전송에서는 불리지 않으므로 이 상자가 거짓으로 남는다.
         */
        AtomicBoolean removed = new AtomicBoolean(false);
        if (consume(consumption, armed, removed) != Outcome.UNLINKED) {
            return Outcome.DENIED;
        }

        if (removed.get()) {
            settleSessions(request, session, armed.userId());
        }
        return Outcome.UNLINKED;
    }

    /** 허가 소비와 매핑 제거 한 트랜잭션. 저장소에 닿는 자리는 이 메서드 하나다. */
    private Outcome consume(
            ConsumptionRequest consumption, UnlinkFlow.Armed armed, AtomicBoolean removed) {
        try {
            Consumption outcome =
                    this.grants.consume(
                            consumption,
                            () -> {
                                if (this.accountLifecycle.unlinkSocialIdentity(
                                                armed.userId(), armed.targetProvider())
                                        != AccessDecision.ALLOWED) {
                                    /*
                                     * 마지막 수단, 연결되지 않은 제공자, 삭제가 시작된 계정이 모두
                                     * 여기로 온다. 셋 다 같은 하나로 접히며 이 트랜잭션은 통째로
                                     * 되돌아간다 — 허가도 소비되지 않은 채 남는다.
                                     */
                                    throw new UnlinkRejected();
                                }
                                removed.set(true);
                                return RESULT_UNLINKED;
                            });
            return outcome instanceof Consumption.ConsumedRequiringSessionRotation
                    ? Outcome.UNLINKED
                    : Outcome.DENIED;
        } catch (UnlinkRejected rejected) {
            // 소비 UPDATE까지 함께 롤백됐다. 저장소에 남은 변화는 0건이다.
            return Outcome.DENIED;
        }
    }

    /**
     * 커밋 뒤 세션 정리. <b>매핑이 실제로 사라진 요청에서만</b> 실행된다.
     *
     * <p>메서드로 뽑은 이유는 순서다. 폐기가 먼저이고 회전이 나중이며, 그 순서가 뒤집히면 폐기가
     * 현재 세션을 놓친다 — {@code changeSessionId}는 요청 끝에야 저장소에 반영되므로 회전 뒤에
     * {@code session.getId()}를 읽으면 저장소에 없는 새 id가 나오고, 저장소에 남아 있는 옛 id의 행이
     * "다른 세션"으로 분류돼 함께 지워진다. 그러면 방금 해제한 사용자가 그 자리에서 로그아웃된다.
     *
     * <p><b>여기서 실패해도 해제는 성공이다.</b> 매핑은 이미 커밋됐고 이 정리는 그 뒤의 곁다리이므로,
     * 예외를 올려보내면 클라이언트가 "해제하지 못했다"를 보면서 실제로는 해제된 상태가 된다 — 그
     * 불일치가 정리 실패보다 나쁘다({@code ActionGrantService.sweep}과 같은 판단).
     *
     * <p>두 번 재확인해도 다른 세션이 남을 수 있다. 조회와 삭제 사이에 그 사용자의 로그인이 커밋되면
     * 그 세션은 이 호출의 대상이 아니었다({@code SessionGuard.SessionStore.REVOKE_ATTEMPTS}). 그래서
     * 화면 카피는 "모두 종료됐다"가 아니라 사용자가 할 일을 말한다 — 지키지 못할 수 있는 약속을
     * 문장으로 만들지 않는다.
     */
    private void settleSessions(HttpServletRequest request, HttpSession session, UUID userId) {
        // 재인증 시각은 폐기보다 먼저, try 밖에서 지운다. 폐기가 예외로 끝나 회전에 닿지 못해도 해제는 이미
        // 커밋된 상태이므로 시각만 남으면 그 본인 확인이 다음 command를 연쇄 승인한다. 회전이 한 번 더 지우는
        // 것은 무해하다.
        ReauthenticationFlow.clearAuthentication(session);
        try {
            this.sessionGuard.revokeSessionsOfExcept(userId, session.getId());
            SessionRotation.rotateAfterSensitiveCommand(request);
        } catch (RuntimeException ignored) {
            // 다음 요청이 같은 세션 상태를 다시 본다. 해제 자체는 이미 커밋됐다.
        }
    }
}
