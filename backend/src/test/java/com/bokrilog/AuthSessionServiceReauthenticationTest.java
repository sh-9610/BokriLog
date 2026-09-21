package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.identity.AccountLifecycleApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * 재인증의 계정 쪽 판정.
 *
 * <p>두 호출을 <b>각각</b> 고정한다. 왕복 시험으로는 그럴 수 없다 —
 * {@link AccountLifecycleApi#findUserIdBySocialIdentity}가 SQL에서 이미 활성 계정만 돌려주므로,
 * 삭제가 시작된 계정의 재인증 실패는 조회 하나가 만들고 그 뒤의
 * {@link AccountAccessApi#decide}는 답을 바꾸지 않는다. 그 줄을 {@code return true}로 바꿔도
 * 백엔드 전 시험이 통과하는 상태였다.
 *
 * <p>여기서는 두 API를 직접 세운다. "조회는 사용자를 돌려주는데 {@code SENSITIVE} 판정이
 * 거부"라는 조합은 실제 저장소로 만들 수 없지만, 그 조합이야말로 두 번째 호출이 존재하는 이유다 —
 * 조회와 판정 사이에 삭제 전환이 커밋되는 경합이 그 모양이다.
 *
 * <p>Spring 컨텍스트를 새로 가르지 않는다. {@link AuthSessionService}는 생성자로 세 값을 받고
 * {@code confirmReauthentication}은 트랜잭션을 열지 않으므로, 아무것도 하지 않는 트랜잭션
 * 관리자로 충분하다. 컨텍스트를 하나 더 띄우면 {@code backend-quality}의 wall-clock이 그만큼
 * 늘어난다.
 */
class AuthSessionServiceReauthenticationTest {

    private static final String ISSUER = "https://issuer.example";

    private static final String SUBJECT = "subject-value";

    @Test
    void 연결된_사용자이고_민감_접근이_허용되면_참이다() {
        UUID user = UUID.randomUUID();
        RecordingAccess access = new RecordingAccess(AccessDecision.ALLOWED);

        boolean confirmed = service(user, access).confirmReauthentication(user, ISSUER, SUBJECT);

        assertThat(confirmed).isTrue();
        // 판정이 실제로 불렸고 세 인자가 그대로 갔다. 호출이 없으면 아래 거부 축이 뜻을 잃는다.
        assertThat(access.calls).hasSize(1);
        assertThat(access.calls.getFirst())
                .isEqualTo(new Call(user, user, AccountAccessKind.SENSITIVE));
    }

    @Test
    void 조회가_사용자를_돌려줘도_민감_접근이_거부되면_거짓이다() {
        /*
         * 이 조합이 두 번째 호출의 존재 이유다. 조회는 활성 계정만 돌려주지만 그 조회와 판정
         * 사이에 삭제 전환이 커밋될 수 있고, 재인증은 뒤따르는 민감 command의 전제이므로 삭제가
         * 시작된 계정에서 성립하면 안 된다.
         */
        UUID user = UUID.randomUUID();
        RecordingAccess access = new RecordingAccess(AccessDecision.DENIED);

        boolean confirmed = service(user, access).confirmReauthentication(user, ISSUER, SUBJECT);

        assertThat(confirmed).isFalse();
        assertThat(access.calls).hasSize(1);
    }

    @Test
    void 타_사용자의_식별자는_판정을_묻지도_않는다() {
        UUID me = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        RecordingAccess access = new RecordingAccess(AccessDecision.ALLOWED);

        boolean confirmed =
                service(someoneElse, access).confirmReauthentication(me, ISSUER, SUBJECT);

        assertThat(confirmed).isFalse();
        // 남의 계정에 대해 접근 판정을 묻지 않는다. 물으면 그 호출 자체가 소유권 경계를 넘는다.
        assertThat(access.calls).isEmpty();
    }

    @Test
    void 어느_계정에도_없는_식별자는_같은_거짓이다() {
        UUID me = UUID.randomUUID();
        RecordingAccess access = new RecordingAccess(AccessDecision.ALLOWED);

        boolean confirmed = service(null, access).confirmReauthentication(me, ISSUER, SUBJECT);

        // 타 사용자와 미연결이 호출자에게 같은 값이다. 이 값에서 계정의 존재를 복원할 수 없다.
        assertThat(confirmed).isFalse();
        assertThat(access.calls).isEmpty();
    }

    /** {@code (issuer, subject)}에 연결된 계정. {@code null}이면 미연결이다. */
    private static AuthSessionService service(@Nullable UUID linked, AccountAccessApi access) {
        return new AuthSessionService(new StubLifecycle(linked), access, new NoOpTransactions());
    }

    private record Call(@Nullable UUID caller, @Nullable UUID target, @Nullable AccountAccessKind kind) {}

    /** 판정 하나를 돌려주고 받은 인자를 기록한다. 호출 여부 자체가 단정 대상이다. */
    private static final class RecordingAccess implements AccountAccessApi {

        private final AccessDecision decision;

        private final List<Call> calls = new ArrayList<>();

        private RecordingAccess(AccessDecision decision) {
            this.decision = decision;
        }

        @Override
        public AccessDecision decide(
                @Nullable UUID callerUserId,
                @Nullable UUID targetUserId,
                @Nullable AccountAccessKind kind) {
            this.calls.add(new Call(callerUserId, targetUserId, kind));
            return this.decision;
        }
    }

    /**
     * 조회 하나만 답하는 생명주기 API.
     *
     * <p>나머지 셋은 재인증 경로가 부르지 않는다. 부르면 여기서 소리 내어 실패하며, 그것이
     * "재인증은 계정을 만들지도 매핑을 만들지도 않는다"를 이 층에서 고정하는 방식이다.
     */
    private record StubLifecycle(@Nullable UUID linked) implements AccountLifecycleApi {

        @Override
        public UUID createAccount() {
            throw new AssertionError("재인증은 계정을 만들지 않는다.");
        }

        @Override
        public AccessDecision linkSocialIdentity(
                @Nullable UUID userId,
                @Nullable String provider,
                @Nullable String issuer,
                @Nullable String subject) {
            throw new AssertionError("재인증은 인증 매핑을 만들지 않는다.");
        }

        @Override
        public AccessDecision decideSessionCreation(@Nullable UUID userId) {
            throw new AssertionError("재인증은 세션을 개설하지 않는다.");
        }

        @Override
        public Optional<UUID> findUserIdBySocialIdentity(
                @Nullable String issuer, @Nullable String subject) {
            return Optional.ofNullable(this.linked);
        }
    }

    /** 아무것도 하지 않는 트랜잭션 관리자. 이 경로는 트랜잭션을 열지 않는다. */
    private static final class NoOpTransactions implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
            throw new AssertionError("재인증 판정은 트랜잭션을 열지 않는다.");
        }

        @Override
        public void commit(TransactionStatus status) {
            throw new AssertionError("재인증 판정은 커밋하지 않는다.");
        }

        @Override
        public void rollback(TransactionStatus status) {
            throw new AssertionError("재인증 판정은 롤백하지 않는다.");
        }
    }
}
