package com.bokrilog;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountLifecycleApi;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;

/**
 * 새 로그인 수단을 현재 계정에 연결한다.
 *
 * <p>연결은 <b>세 단계가 순서대로</b> 성립해야 한다 — 기존 수단으로 재인증(Story 1.8) → 연결
 * 작업용 허가 발급(Story 1.9) → 대상 제공자 OIDC 왕복. 앞 단계가 없으면 뒷 단계를 시작하지
 * 않는다. 이 클래스가 첫 두 단계의 판정({@link #start})과 마지막 커밋({@link #complete})을
 * 소유하며, 그래서 이 스토리가 1.8·1.9의 계약을 실제로 소비하는 첫 자리다.
 *
 * <p><b>거부는 하나의 값이다.</b> 이미 내 계정에 연결됨·다른 계정에 연결됨·허가 만료·세션 불일치·
 * 삭제 상태가 호출자에게 구분되지 않는다 — {@link #start}는 빈 {@link Optional}, {@link #complete}는
 * {@code false} 하나로 수렴한다. 사유를 값으로 돌려주면 그 차이가 곧 "그 식별자가 어느 계정의
 * 것인가"를 알려 주는 신호가 된다.
 *
 * <p><b>최종 커밋은 한 트랜잭션이다.</b> 허가 소비와 {@code linkSocialIdentity}가 같은 경계에서
 * 커밋되므로 "허가만 소비되고 매핑이 없는 상태"도 "매핑만 생기고 허가가 남는 상태"도 만들어지지
 * 않는다. 경계는 허가 포트가 소유하고({@code ActionGrantService}) 이 클래스는 그 안에서 돌 작업
 * 하나를 건넨다. 매핑이 거부되면 <b>예외로</b> 그 트랜잭션을 되돌린다 — 값으로 돌려주면 소비만
 * 커밋되고 매핑이 없는 상태가 정확히 남는다.
 *
 * <p>유일성은 스키마가 최종 방어다. {@code (issuer, subject)} 전역 유일과 {@code (user_id,
 * provider)} 계정당 하나를 {@code identity}가 조건부 INSERT 한 문장으로 함께 판정하므로
 * ({@code IdentityAccountRepository.insertSocialLinkIfActive}) 이 클래스는 그 답을 읽기만 한다.
 * 이메일·표시명으로 병합하지 않으며 그 값을 읽지도 않는다 — 공개 API 시그니처에 없다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다. 연결은 세션과 HTTP 왕복을 전제하고, 웹 서버 없이
 * 뜨는 일회성 migration 역할에는 그 문맥이 없다({@code AuthSessionService}와 같은 조건).
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class LinkService {

    /**
     * 민감 command의 이름.
     *
     * <p>허가 포트는 이 문자열을 해석하지 않는다. 뜻은 소비자가 소유하며, 발급과 소비가 같은 값을
     * 써야 한다는 것만이 계약이다({@code ActionGrant.Binding}).
     */
    static final String COMMAND = "link-provider";

    /**
     * 확인 문구.
     *
     * <p>{@code SECURITY-ACTION-1.0}의 다섯 번째 귀속 축이다. 연결에는 사용자가 옮겨 적는 확인
     * 문구가 없다 — 확인은 설정 화면의 연결 조작 하나이고, 비가역 결과를 설명하는 확인 대화상자는
     * <b>해제</b>(Story 1.11)가 자기 작업과 함께 만든다. 그래서 이 값은 서버가 든 상수이며 발급과
     * 소비가 같은 자리에서 같은 값을 쓴다. 상수여도 축은 살아 있다: 다른 command·다른 target과
     * 함께 해시되므로({@code ActionGrant.canonicalConfirmationHash}) 연결 허가는 해제 요청에
     * 쓰이지 않는다.
     */
    static final String CONFIRMATION = "연결";

    /**
     * 저장되는 실행 결과.
     *
     * <p>응답을 잃은 클라이언트의 재전송이 이 값을 그대로 다시 받는다. 제공자 이름·계정 식별자·
     * 시각을 담지 않는다 — 이 문자열은 허가 행에 남고, 그 행은 계정 삭제까지 산다.
     */
    static final String RESULT_LINKED = "linked";

    /**
     * 매핑이 거부됐다.
     *
     * <p>소비 트랜잭션을 되돌리기 위한 사설 타입이다. 값으로 돌려주면 소비 UPDATE가 커밋되어
     * "허가만 소비되고 매핑이 없는" 상태가 남는다. 스택도 메시지도 담지 않는다 — 이 예외는
     * 이 파일 안에서 만들어져 이 파일 안에서 잡히고, 그 사이에 로그를 지나지 않는다.
     */
    private static final class LinkRejected extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        LinkRejected() {
            super(null, null, false, false);
        }
    }

    /**
     * 연결 완료의 결과.
     *
     * <p>값이 둘뿐인 것이 요점이다({@code ActionGrantService.Consumption}과 같은 계약) — 거부의
     * 사유를 늘리면 호출자가 그것을 조합해 다른 계정의 존재와 소유자를 복원할 수 있다.
     *
     * <p>{@code boolean}이 아닌 이유는 호출자 쪽이다. 세션 재회전은 <b>이 값이
     * {@link #LINKED}일 때만</b> 일어나야 하고, 그 판정을 이름 있는 값으로 두면 회전이 실제로
     * 그 조건에 묶여 있는지를 HTTP 밖에서 볼 수 있다({@code AuthFlowHandlers.rotateIfLinked}).
     */
    enum Outcome {

        /** 허가 소비와 매핑 추가가 한 트랜잭션으로 커밋됐다. 세션 재회전이 뒤따라야 한다. */
        LINKED,

        /** 거부. 이미 연결됨·다른 계정·허가 만료·세션 불일치·삭제 상태가 구분되지 않는 하나다. */
        DENIED
    }

    private final AccountLifecycleApi accountLifecycle;

    private final ActionGrantService grants;

    /**
     * 제공자 등록.
     *
     * <p>연결 시작이 "그 제공자의 등록이 실제로 만들어졌는가"를 본다. 보지 않으면 아는 이름이지만
     * 구성되지 않은 제공자로 시작한 요청이 204를 받고, 허가와 무장이 남은 채 뒤따르는 인가 시작이
     * fail-closed로 끝난다.
     */
    private final AuthSecurityConfig.ProviderClientRegistrations registrations;

    LinkService(
            AccountLifecycleApi accountLifecycle,
            ActionGrantService grants,
            AuthSecurityConfig.ProviderClientRegistrations registrations) {
        this.accountLifecycle = accountLifecycle;
        this.grants = grants;
        this.registrations = registrations;
    }

    /**
     * 이 계정에 연결된 제공자 이름.
     *
     * <p>{@code identity} 공개 API를 그대로 돌려준다. 미존재·삭제 계정은 빈 집합이며 호출자는 그
     * 값에서 계정의 존재를 복원할 수 없다.
     */
    Set<String> linkedProviders(UUID userId) {
        return this.accountLifecycle.findLinkedProviders(userId);
    }

    /**
     * 연결 흐름을 시작한다.
     *
     * <p>게이트가 둘이다. 대상 제공자가 <b>이미 이 계정에 연결돼 있지 않아야</b> 하고, 재인증이
     * 신선해야 한다. 뒤의 판정은 이 클래스가 하지 않는다 — 허가 발급이 세션의 재인증 시각과
     * {@code now()}를 데이터베이스에서 함께 읽고 그 결과가 곧 INSERT의 행 수다
     * ({@code ActionGrantRepository.issueIfReauthenticationFresh}). 의심 세션·삭제 상태·주체
     * 대조도 같은 호출이 함께 본다.
     *
     * <p>순서가 계약이다. 허가를 먼저 받고 무장한다 — 무장을 먼저 하면 허가가 서지 않은 흐름이
     * 세션에 남고, 그 흐름의 콜백은 소비할 것이 없어 실패하면서도 OIDC 왕복은 이미 지난 상태가
     * 된다.
     *
     * <p>발급 시각의 세션 id를 무장에 함께 담는다. 콜백에서 세션이 반드시 한 번 회전하므로 그
     * 값이 없으면 소비가 구조적으로 성립하지 않는다({@code LinkFlow}의 클래스 javadoc).
     *
     * @return 무장된 흐름, 게이트 중 하나라도 닫히면 빈 값. 어느 것인지는 구분되지 않는다
     */
    Optional<LinkFlow.Armed> start(HttpSession session, UUID userId, String provider) {
        if (!this.registrations.isConfiguredProvider(provider)) {
            /*
             * 아는 이름이지만 등록이 만들어지지 않았다. 통과시키면 허가와 무장이 남은 채 뒤따르는
             * 인가 시작이 fail-closed로 끝나고, 사용자는 원본 오류 JSON에 닿으며 참조할 수 없게 된
             * 허가가 그대로 수명을 산다. Story 1.9가 재인증 시작에 "등록 구성 여부는 조회하지
             * 않는다"를 적은 것은 그 경로가 **남는 상태를 만들지 않기** 때문이고, 연결은 만든다.
             */
            return Optional.empty();
        }
        if (linkedProviders(userId).contains(provider)) {
            /*
             * 이미 연결된 제공자다. 화면은 그 제공자에 연결 조작을 주지 않으므로 정상 경로에서는
             * 여기 오지 않고, 그래도 막는 이유는 화면이 유일한 게이트가 되지 않게 하기 위해서다.
             * 거부는 다른 모든 거부와 같은 빈 값이다.
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
         * 앞에 두면 거절되는 시작 요청 하나가(재인증이 오래된 경우가 그렇다) 진행 중이던 흐름의
         * 무장과 허가를 함께 없앤다 — 사용자는 아무 조작도 성립하지 않았는데 열어 둔 것을 잃는다.
         *
         * 앞선 연결 무장은 허가를 함께 버려야 한다 — `issue`의 무효화 술어는 `(user, command,
         * target)`이고 target이 제공자 이름이라, 네이버로 무장한 뒤 카카오로 다시 무장하면 네이버
         * 허가만 살아남고 그것을 가리키던 흐름 기록은 사라진다. 아무도 참조할 수 없는 허가가
         * 수명을 사는 상태가 정확히 그것이다. 그 무장이 든 참조 하나만 지우므로 방금 발급한 허가는
         * 걸리지 않는다.
         *
         * 재인증 무장은 값만 버린다. 한 세션에 두 흐름이 함께 무장돼 있으면 콜백이 어느 쪽 것인지
         * 매번 등록 id로 가려야 하고, 그 판정이 한 번 어긋나면 재인증 콜백이 연결 분기에 삼켜진다.
         * 연결 시작이 성립한다는 것은 재인증이 이미 끝났다는 뜻이므로(그 시각이 없으면 허가가
         * 서지 않는다) 남아 있는 재인증 무장은 완료되지 않은 옛 의도다.
         */
        supersedePreviousArming(session);
        ReauthenticationFlow.discard(session);

        LinkFlow.Armed armed =
                new LinkFlow.Armed(
                        provider,
                        userId,
                        AuthFlowHandlers.SETTINGS_PATH,
                        reference.get(),
                        // 멱등 키는 서버가 만든다. 클라이언트가 고르면 추측 가능한 값이
                        // `(user_id, operation_id)` 슬롯을 선점하는 경로가 열린다.
                        UUID.randomUUID().toString(),
                        session.getId(),
                        Instant.now());
        LinkFlow.arm(session, armed);
        return Optional.of(armed);
    }

    /**
     * 이 세션에 남은 연결 무장을 버리고 <b>그 무장이 낳은 허가 하나만</b> 무효화한다.
     *
     * <p>흐름 기록만 버리면 그것이 가리키던 허가가 아무도 참조할 수 없는 채로 수명을 산다. 두
     * 호출자가 있고 둘 다 "이 세션의 연결 의도를 지금 끝낸다"는 같은 뜻이다 — 새 연결 시작이
     * 앞의 무장을 덮을 때({@link #start})와 재인증 시작이 흐름을 갈아탈 때
     * ({@code AuthSessionController})다.
     *
     * <p>수명이 지난 무장도 대상이다. 그 무장의 허가는 아직 살아 있을 수 있고(두 수명은 서로 다른
     * 상수다) 살아 있다면 같은 이유로 지워야 한다.
     *
     * <p>참조로 좁힌다. {@code (command, target)}으로 지우면 오래된 탭의 취소 하나가 같은 작업을 위해
     * 다른 세션이 방금 받은 살아 있는 허가까지 지운다({@code PlanEndCommandService.cancel}과 같은 단위).
     */
    void abandon(HttpSession session) {
        LinkFlow.Armed armed = LinkFlow.peekIncludingExpired(session);
        LinkFlow.discard(session);
        if (armed != null) {
            this.grants.invalidateReference(armed.userId(), armed.grantReference());
        }
    }

    /**
     * 새 무장이 앞선 무장을 대신한다. 앞선 무장의 허가를 <b>참조로</b> 무효화한다.
     *
     * <p>같은 제공자로 다시 무장하면 방금 {@code issue}가 그 {@code (user, command, target)}의 살아
     * 있는 허가를 이미 지웠으므로 참조 하나를 지우는 이 호출은 0행으로 끝나고, 방금 발급한 허가는
     * 다른 참조라 걸리지 않는다. 다른 제공자면 그 무장이 낳은 허가만 사라진다 — 그래서 대상을 받지
     * 않는다. 옛 조건("대상이 다를 때만")은 {@code (user, command, target)} 단위가 방금 발급한 허가를
     * 삼키던 시절의 것이다.
     */
    private void supersedePreviousArming(HttpSession session) {
        LinkFlow.Armed previous = LinkFlow.peekIncludingExpired(session);
        LinkFlow.discard(session);
        if (previous != null) {
            this.grants.invalidateReference(previous.userId(), previous.grantReference());
        }
    }

    /**
     * 연결을 확정한다. <b>허가 소비와 매핑 추가가 한 트랜잭션</b>이다.
     *
     * <p>경계는 허가 포트가 소유하고 이 메서드는 그 안에서 돌 작업 하나를 건넨다. 매핑이 거부되면
     * 사설 예외로 그 트랜잭션을 되돌려 소비도 함께 사라진다 — 그래야 "허가만 소비되고 매핑이
     * 없는 상태"가 남지 않는다. 사용자는 허가를 잃지 않지만 흐름은 이미 소비됐으므로 다시 시작할
     * 때 새 허가가 발급되고, 그 발급이 앞의 허가를 무효화한다.
     *
     * <p>같은 {@code operation_id}의 재호출은 저장된 같은 결과를 돌려주고 매핑을 다시 만들지
     * 않는다. 그 멱등은 1.9의 계약이 그대로 권위다.
     *
     * @param session 지금 이 요청의 세션. 회전 <b>뒤</b>의 세션이며 주체 대조와 세션 판정이 이
     *     값을 쓴다
     * @param armed 무장 시각에 붙들어 둔 값 전부. 귀속의 세션 축은 여기 담긴 발급 시각의 id다
     * @return 연결됐으면 {@link Outcome#LINKED}. 거부는 이유를 구분하지 않는 하나의 값이다
     */
    Outcome complete(HttpSession session, LinkFlow.Armed armed, String issuer, String subject) {
        ConsumptionRequest request =
                new ConsumptionRequest(
                        session,
                        armed.userId(),
                        armed.grantReference(),
                        COMMAND,
                        armed.targetProvider(),
                        CONFIRMATION,
                        armed.operationId(),
                        armed.boundSessionId());
        try {
            Consumption outcome =
                    this.grants.consume(
                            request,
                            () -> {
                                if (this.accountLifecycle.linkSocialIdentity(
                                                armed.userId(),
                                                armed.targetProvider(),
                                                issuer,
                                                subject)
                                        != AccessDecision.ALLOWED) {
                                    /*
                                     * 다른 계정에 연결된 식별자, 이미 그 제공자를 가진 계정, 삭제가
                                     * 시작된 계정, 삭제가 끝난 계정의 식별자(재가입 차단, Story 5.4)가
                                     * 모두 여기로 온다. 모두 같은 하나로 접히며 이 트랜잭션은 통째로
                                     * 되돌아간다.
                                     */
                                    throw new LinkRejected();
                                }
                                return RESULT_LINKED;
                            });
            /*
             * 성공의 타입 이름이 세션 재회전 의무를 들고 있다(`SECURITY-ACTION-1.0`). 회전은 HTTP
             * 문맥을 요구하므로 이 클래스가 아니라 엔드포인트가 배선한다(`AuthFlowHandlers`).
             */
            return outcome instanceof Consumption.ConsumedRequiringSessionRotation
                    ? Outcome.LINKED
                    : Outcome.DENIED;
        } catch (LinkRejected rejected) {
            // 소비 UPDATE까지 함께 롤백됐다. 저장소에 남은 변화는 0건이다.
            return Outcome.DENIED;
        }
    }
}
