package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.bokrilog.identity.DeletionOutcome;
import com.bokrilog.identity.internal.IdentityAccountRepository;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 이 스토리가 추가한 데이터의 삭제 경로.
 *
 * <p>Story 1.4가 identity에 들인 것은 인증 매핑과 세션이다. 삭제 경로가 그 둘을 함께 지우고 생명주기
 * guard 행은 남겨야 한다. 매핑만 지우고 세션을 남기면 로그인 수단이 사라진 계정의 세션이 여전히
 * 내부 {@code user_id}를 결정하고, guard까지 지우면 삭제된 계정으로 다시 세션을 만들 수 있다.
 *
 * <p>실제 로그인이 만든 세션을 지우는지 본다. {@code spring_session}의 {@code principal_name}이
 * 내부 {@code user_id} 문자열이라는 것은 인증 표면의 결정이고, 삭제 경로는 그 값을 술어로 쓴다.
 * 두 곳이 갈라지면 삭제가 조용히 0행이 되는데, 이 시험이 그 한 쌍을 붙잡는 유일한 장치다.
 *
 * <p>저장 실패도 실제로 주입한다. 주입 지점은 저장소 한 곳이고, 그래서 이 클래스만
 * {@link MockitoSpyBean}으로 컨텍스트가 갈라진다({@code AccountDeletionTest}와 같은 관용). 컨테이너는
 * 여전히 JVM에 하나다. 실패 경로를 시험하지 않으면 "부분 실패를 완료로 보고하지 않는다"는 문장이
 * 세션에 대해서는 확인되지 않은 채 남는다 — 세션 삭제만 커밋되고 매핑이 되돌아가면 로그인 수단은
 * 그대로인데 세션만 사라진 상태가 되고, 그 반대는 로그인 수단이 사라진 계정의 세션이 여전히 내부
 * {@code user_id}를 결정하는 상태다.
 */
class AuthDeletionPathTest extends AuthFlowTestBase {

    @MockitoSpyBean private IdentityAccountRepository repository;

    @Test
    void 삭제는_매핑과_세션을_함께_지우고_guard_행은_남긴다() throws Exception {
        String subject = "deleted-user-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(subject);
        assertThat(login.statusCode()).isEqualTo(302);
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);

        assertThat(accountDeletion().beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        assertThat(accountDeletion().erasePayload(userId)).isEqualTo(DeletionOutcome.COMPLETED);

        assertThat(socialLinkCount()).isZero();
        assertThat(sessionCount()).isZero();
        // 속성 행은 ON DELETE CASCADE로 함께 사라진다.
        assertThat(sessionAttributeCount()).isZero();
        // guard 행은 남는다. 이 한 행이 신규 세션 생성 차단의 전부다.
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void 삭제된_계정의_세션_쿠키는_더_이상_인증되지_않는다() throws Exception {
        String subject = "revoked-session-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(subject);
        String cookie = sessionCookieOf(login).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        accountDeletion().beginDeletion(userId);
        accountDeletion().erasePayload(userId);

        HttpResponse<String> sessionState = sessionState(cookie);
        assertThat(sessionState.statusCode()).isEqualTo(200);
        assertThat(sessionState.body()).contains("\"authenticated\":false");
    }

    @Test
    void 다른_계정의_매핑과_세션은_남는다() throws Exception {
        String deletedSubject = "erase-target-" + UUID.randomUUID();
        String keptSubject = "erase-bystander-" + UUID.randomUUID();
        signIn(deletedSubject);
        HttpResponse<String> keptLogin = signIn(keptSubject);
        String keptCookie = sessionCookieOf(keptLogin).orElseThrow();
        UUID deletedUser = userIdOf(OidcStubConfiguration.issuer(), deletedSubject).orElseThrow();

        accountDeletion().beginDeletion(deletedUser);
        accountDeletion().erasePayload(deletedUser);

        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(), keptSubject)).isPresent();
        assertThat(sessionState(keptCookie).body()).contains("\"authenticated\":true");
    }

    @Test
    void 전환을_거치지_않은_삭제는_완료로_보고하지_않고_아무것도_지우지_않는다() throws Exception {
        String subject = "not-transitioned-" + UUID.randomUUID();
        signIn(subject);
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        // `beginDeletion` 없이 바로 지우려는 호출이다. 삭제가 시작된 순간부터 새 세션·쓰기가
        // 막혀야 하는데, 전환 없이 지우면 그 창이 통째로 사라진다.
        assertThat(accountDeletion().erasePayload(userId)).isEqualTo(DeletionOutcome.NOT_APPLICABLE);

        // 부분 삭제도 일어나지 않았다. 매핑과 세션이 그대로다.
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void 삭제_재호출은_멱등하고_guard_행을_지우지_않는다() throws Exception {
        String subject = "idempotent-erase-" + UUID.randomUUID();
        signIn(subject);
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        accountDeletion().beginDeletion(userId);
        accountDeletion().erasePayload(userId);
        assertThat(accountDeletion().erasePayload(userId)).isEqualTo(DeletionOutcome.COMPLETED);

        assertThat(socialLinkCount()).isZero();
        assertThat(sessionCount()).isZero();
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void 저장이_실패하면_세션도_함께_롤백되고_완료로_보고하지_않는다() throws Exception {
        String subject = "rollback-session-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(subject);
        assertThat(login.statusCode()).isEqualTo(302);
        String cookie = sessionCookieOf(login).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(accountDeletion().beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        assertThat(sessionCount()).isEqualTo(1);

        // 첫 호출만 실패하고 두 번째부터는 실제 저장소로 간다. 실패와 재시도를 한 시험 안에서
        // 이어 보기 위한 배치다(`AccountDeletionTest`와 같은 모양).
        doThrow(new DataAccessResourceFailureException("저장 실패 주입"))
                .doCallRealMethod()
                .when(this.repository)
                .deleteAccountPayload(any());

        assertThatThrownBy(() -> accountDeletion().erasePayload(userId))
                .isInstanceOf(DataAccessResourceFailureException.class);

        /*
         * 트랜잭션이 통째로 롤백됐다. 세션 삭제는 매핑 삭제와 같은 트랜잭션에 있으므로 둘 다
         * 되돌아간다. 세션만 커밋되면 로그인 수단은 그대로인데 세션이 사라진 상태가 남는다.
         */
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(sessionAttributeCount()).isNotZero();
        assertThat(accountCount()).isEqualTo(1);
        // 되돌아온 세션은 여전히 그 계정의 주체를 결정한다. 삭제는 아직 일어나지 않았다.
        assertThat(sessionState(cookie).body()).contains("\"authenticated\":true");

        // 재시도가 끝낸다. 부분 실패를 완료로 보고하지 않았을 뿐 상태는 전진해 있었다.
        assertThat(accountDeletion().erasePayload(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        assertThat(socialLinkCount()).isZero();
        assertThat(sessionCount()).isZero();
        assertThat(accountCount()).isEqualTo(1);
    }

    private long sessionAttributeCount() {
        return jdbc()
                .sql("SELECT count(*) FROM spring_session_attributes")
                .query(Long.class)
                .single();
    }
}
