package com.bokrilog;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.identity.AccountLifecycleApi;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검증을 통과한 인증 결과를 계정과 세션으로 옮긴다.
 *
 * <p>여기서 하는 일은 {@code identity} 공개 API 호출 세 개를 한 트랜잭션으로 묶는 것뿐이다.
 * 소유권·삭제 상태 판정을 새로 만들지 않고, {@code identity.internal}과 소유 테이블에 닿지 않는다.
 * 세션을 개설해도 되는지는 {@link AccountLifecycleApi#decideSessionCreation(UUID)} 하나가 답한다.
 *
 * <p>거부는 하나의 값이다. 삭제가 시작된 계정, 삭제가 끝난 계정, 경합에서 두 번 진 흐름이 모두
 * 빈 {@link Optional}로 접힌다. 호출자는 이 값에서 계정의 존재 여부를 복원할 수 없다.
 *
 * <p>트랜잭션을 {@link TransactionTemplate}로 다룬다. {@code @Transactional} 메서드를 같은 빈에서
 * 다시 부르면 프록시를 지나지 않아 새 트랜잭션이 열리지 않는다. 아래 재시도는 정확히 그 "새
 * 트랜잭션"을 필요로 하므로, 경계를 애노테이션이 아니라 코드로 드러낸다.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class AuthSessionService {

    /**
     * 경합 재시도 횟수.
     *
     * <p>두 번이면 충분하다. 첫 시도가 지는 경우는 같은 {@code (issuer, subject)}로 다른 콜백이
     * 먼저 매핑을 넣었을 때뿐이고, 그 매핑은 이미 커밋되어 있으므로 두 번째 시도의 조회가 반드시
     * 그것을 본다. 더 돌리면 같은 결과를 기다리는 시간만 늘고, 무한히 돌리면 삭제 전환과의
     * 경합에서 끝나지 않는 흐름이 생긴다.
     */
    private static final int MAX_ATTEMPTS = 2;

    private final AccountLifecycleApi accountLifecycle;

    /**
     * 민감 접근 판정.
     *
     * <p>재인증이 쓴다. 조회({@link AccountLifecycleApi#findUserIdBySocialIdentity})만으로는
     * 부족하다 — 그 조회는 활성 계정만 돌려주지만 조회와 판정 사이에 삭제 전환이 커밋될 수 있고,
     * 재인증은 그 뒤에 오는 민감 command의 전제이므로 삭제가 시작된 계정에서 성립하면 안 된다.
     */
    private final AccountAccessApi accountAccess;

    private final TransactionTemplate transactions;

    AuthSessionService(
            AccountLifecycleApi accountLifecycle,
            AccountAccessApi accountAccess,
            PlatformTransactionManager transactionManager) {
        this.accountLifecycle = accountLifecycle;
        this.accountAccess = accountAccess;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 개설된 세션의 주체.
     *
     * @param userId 인증 세션이 결정한 내부 식별자. 클라이언트가 보낸 값이 아니다.
     * @param firstLogin 이 호출이 계정을 처음 만들었는가. 복귀 목적지가 여기서 갈린다.
     */
    record AuthSession(UUID userId, boolean firstLogin) {}

    /**
     * 검증된 인증 매핑으로 세션 주체를 정한다.
     *
     * <p>연결된 계정이 있으면 그 계정의 세션이고, 없으면 신규 가입이다. 이메일이 같은지는 묻지도
     * 않는다 — 이 시그니처에 이메일이 없다.
     *
     * @param provider 인증에 <b>실제로 쓰인</b> 제공자 등록 id. 흐름이 알려 준 값이며
     *     ({@code OAuth2AuthenticationToken#getAuthorizedClientRegistrationId()}) 이 클래스가 짐작하지
     *     않는다. 이 값이 그대로 {@code identity_social_link.provider}에 들어간다 — 리터럴로 두면
     *     어느 제공자로 로그인해도 같은 이름이 저장되고, 그 컬럼을 술어로 쓰는 이후 스토리
     *     (로그인 수단 연결·해제)가 거짓을 읽는다
     * @return 세션을 개설해도 되면 그 주체, 아니면 빈 값
     */
    Optional<AuthSession> establish(String provider, String issuer, String subject) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Attempt result =
                    this.transactions.execute(status -> attempt(status, provider, issuer, subject));
            if (result == null) {
                // TransactionTemplate은 콜백이 null을 돌려줄 때만 null이다. 아래 콜백은 언제나
                // 값을 돌려주므로 여기 오는 것은 계약이 깨진 상태이고, 안전한 답은 거부다.
                return Optional.empty();
            }
            if (!result.contended()) {
                return Optional.ofNullable(result.session());
            }
        }
        // 경합에서 계속 진 흐름은 상태를 남기지 않고 실패로 수렴한다. 계정도 매핑도 늘지 않는다.
        return Optional.empty();
    }

    /**
     * 검증된 인증 매핑이 <b>지금 이 세션의 사용자</b>의 것인지 확인한다.
     *
     * <p>재인증의 계정 쪽 판정 하나다. 계정을 만들지 않고, 매핑을 만들지 않고, 세션의 사용자를
     * 바꾸지 않는다 — 이 메서드가 하는 일은 조회 하나와 판정 하나를 읽는 것뿐이고 어떤 쓰기도
     * 없다. 그래서 {@link #establish}와 달리 트랜잭션 경계도 재시도도 없다.
     *
     * <p>인증 표면이 {@code identity} 공개 API를 부르는 자리는 목적마다 하나다 — 세션 개설과
     * 재인증은 이 클래스가, 민감 작업의 삭제 상태 판정은 {@code ActionGrantService}가, 로그인 수단
     * 연결은 {@code LinkService}가 소유한다. 그 밖의 어느 클래스도 그 API를 부르지 않는다. 두 호출을
     * 함께 두는 이유는 각각이 다른 것을 막기 때문이다 — 조회는 "다른 사용자의 식별자"와 "어느
     * 계정에도 없는 식별자"를 걸러 내고, {@link AccountAccessKind#SENSITIVE} 판정은 삭제가 시작된
     * 계정을 걸러 낸다. 두 거부는 호출자에게 같은 {@code false} 하나이며 그 값에서 대상 계정의
     * 존재 여부를 복원할 수 없다.
     *
     * @param currentUserId 무장 시각에 붙들어 둔 이 세션의 사용자
     * @param issuer 검증을 통과한 제공자 namespace
     * @param subject 검증을 통과한 외부 식별자
     * @return 그 식별자가 이 사용자의 것이고 민감 접근이 허용될 때만 참
     */
    boolean confirmReauthentication(UUID currentUserId, String issuer, String subject) {
        Optional<UUID> linked = this.accountLifecycle.findUserIdBySocialIdentity(issuer, subject);
        if (linked.isEmpty() || !linked.get().equals(currentUserId)) {
            // 타 사용자의 식별자와 어느 계정에도 없는 식별자가 같은 답으로 접힌다.
            return false;
        }
        return this.accountAccess.decide(currentUserId, currentUserId, AccountAccessKind.SENSITIVE)
                == AccessDecision.ALLOWED;
    }

    /**
     * 한 트랜잭션 안의 시도.
     *
     * @param contended 같은 인증 매핑을 다른 흐름이 먼저 가져갔다. 이 트랜잭션은 롤백되어
     *     방금 만든 계정이 남지 않는다.
     */
    private record Attempt(@Nullable AuthSession session, boolean contended) {}

    private Attempt attempt(
            TransactionStatus status, String provider, String issuer, String subject) {
        Optional<UUID> existing = this.accountLifecycle.findUserIdBySocialIdentity(issuer, subject);
        if (existing.isPresent()) {
            UUID userId = existing.get();
            // 조회가 활성 계정만 돌려주지만 그 사이에 삭제 전환이 커밋될 수 있다. 세션 개설
            // 판정은 언제나 다시 통과해야 한다.
            return this.accountLifecycle.decideSessionCreation(userId) == AccessDecision.ALLOWED
                    ? new Attempt(new AuthSession(userId, false), false)
                    : new Attempt(null, false);
        }

        // 미연결 식별자는 신규 가입으로만 처리한다. 이메일 일치로 기존 계정에 붙이는 경로가 없다.
        UUID userId = this.accountLifecycle.createAccount();
        if (this.accountLifecycle.linkSocialIdentity(userId, provider, issuer, subject)
                != AccessDecision.ALLOWED) {
            /*
             * 거부다. 이 흐름이 방금 만든 계정은 아무 매핑도 갖지 못했으므로 남겨 두면 아무도
             * 로그인할 수 없는 빈 계정이 된다. 롤백해 계정 수를 1개로 유지한다.
             *
             * 거부의 이유는 둘 중 하나다 — 같은 `(issuer, subject)`를 다른 콜백이 먼저 넣었거나,
             * 방금 만든 계정의 삭제 전환이 그 사이에 커밋됐다. 어느 쪽인지 묻지 않고 다시
             * 조회한다. 앞의 경우는 두 번째 시도가 그 계정의 세션을 얻고, 뒤의 경우는 판정이
             * 다시 거부해 실패로 수렴한다.
             */
            status.setRollbackOnly();
            return new Attempt(null, true);
        }

        if (this.accountLifecycle.decideSessionCreation(userId) != AccessDecision.ALLOWED) {
            // 매핑까지 만들었는데 세션이 거부됐다. 삭제 전환이 이 트랜잭션과 경합해 이긴 경우다.
            // 부분 상태를 남기지 않는다.
            status.setRollbackOnly();
            return new Attempt(null, false);
        }
        return new Attempt(new AuthSession(userId, true), false);
    }
}
