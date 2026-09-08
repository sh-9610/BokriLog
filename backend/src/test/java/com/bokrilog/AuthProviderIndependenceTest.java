package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.DeletionOutcome;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 두 제공자의 독립.
 *
 * <p>실제 PostgreSQL로 돈다. 여기서 고정하려는 것은 {@code (issuer, subject)} 전역 유일 인덱스가
 * 같은 {@code subject} 문자열을 두 issuer에서 <b>별개</b>로 다룬다는 엔진의 동작과,
 * {@code identity_social_link.provider}에 인증에 실제로 쓰인 이름이 들어간다는 저장 사실이다.
 *
 * <p>이메일로 단정하지 않는다. 이 시스템은 이메일을 <b>받지 않는다</b> — 스키마에 컬럼이 없고
 * {@code AccountLifecycleApi}의 시그니처에도 없고 동의 범위가 {@code openid} 하나이며 스텁의 claim
 * 집합에도 email이 없다. 그래서 "이메일이 같은 두 계정이 병합되지 않는다"는 흉내낼 자리조차 없고,
 * 실제 계약은 <b>"{@code (issuer, subject)}가 다르면 별개이고 병합 경로가 존재하지 않는다"</b>이며
 * 그것을 보증하는 것은 시험이 아니라 구조다. 이 파일은 그 구조를 고정하는 역할만 한다
 * ({@code AuthAccountMappingTest}가 같은 이유로 subject 축을 쓴 것과 같은 판단).
 */
class AuthProviderIndependenceTest extends AuthFlowTestBase {

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;

    @Test
    void 같은_subject_문자열이_두_제공자에서_오면_별개_계정이다() throws Exception {
        /*
         * `subject`는 제공자의 namespace 안에서만 뜻이 있다. 두 제공자가 같은 문자열을 쓰는 것은
         * 우연이며, 그 우연으로 계정이 합쳐지면 한 사용자가 다른 사용자의 데이터 경계 안으로
         * 들어간다. `(issuer, subject)`가 전역 유일 키인 이유가 이것이다.
         */
        String subject = "shared-subject-" + UUID.randomUUID();

        HttpResponse<String> google = signIn(GOOGLE, subject);
        HttpResponse<String> naver = signIn(NAVER, subject);

        assertThat(google.statusCode()).isEqualTo(302);
        assertThat(naver.statusCode()).isEqualTo(302);
        // 둘 다 신규 가입이다. 두 번째가 기존 계정을 찾았다면 목적지가 대시보드가 된다.
        assertThat(locationOf(google)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(locationOf(naver)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);

        assertThat(accountCount()).isEqualTo(2);
        assertThat(socialLinkCount()).isEqualTo(2);

        UUID googleUser = userIdOf(OidcStubConfiguration.issuer(GOOGLE), subject).orElseThrow();
        UUID naverUser = userIdOf(OidcStubConfiguration.issuer(NAVER), subject).orElseThrow();
        assertThat(googleUser).isNotEqualTo(naverUser);
    }

    @Test
    void provider_컬럼에_인증에_실제로_쓰인_이름이_들어간다() throws Exception {
        /*
         * Story 1.6 전에는 이 컬럼에 리터럴 `"google"`이 무조건 들어갔다. 그러면 네이버로 로그인한
         * 계정도 Google로 기록되고, 그 컬럼을 술어로 쓰는 이후 스토리(로그인 수단 연결·해제)가
         * 거짓을 읽는다. 값을 정하는 것은 흐름이 알려 준 registration id 하나여야 한다.
         */
        String googleSubject = "provider-google-" + UUID.randomUUID();
        String naverSubject = "provider-naver-" + UUID.randomUUID();

        signIn(GOOGLE, googleSubject);
        signIn(NAVER, naverSubject);

        assertThat(linkedProviderOf(OidcStubConfiguration.issuer(GOOGLE), googleSubject))
                .contains(GOOGLE);
        assertThat(linkedProviderOf(OidcStubConfiguration.issuer(NAVER), naverSubject))
                .contains(NAVER);

        // 어느 행도 다른 제공자의 이름을 갖지 않는다. 행 수까지 세어 "둘 중 하나만 옳다"를 막는다.
        assertThat(distinctProviders()).containsExactlyInAnyOrder(GOOGLE, NAVER);
    }

    @Test
    void 두_제공자의_매핑이_한_계정에_섞이지_않는다() throws Exception {
        /*
         * 이 스토리는 로그인 수단 연결(1.10)을 만들지 않는다. 그래서 계정마다 매핑이 정확히 하나이고,
         * 두 매핑이 한 `user_id`를 가리키는 상태는 병합이 일어났다는 뜻이다.
         */
        signIn(GOOGLE, "no-merge-google-" + UUID.randomUUID());
        signIn(NAVER, "no-merge-naver-" + UUID.randomUUID());

        assertThat(
                        jdbc()
                                .sql(
                                        "SELECT count(*) FROM (SELECT user_id FROM identity_social_link "
                                                + "GROUP BY user_id HAVING count(*) > 1) multi")
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void 삭제가_시작된_계정은_네이버_재로그인으로도_세션을_얻지_못한다() throws Exception {
        String subject = "naver-deleting-" + UUID.randomUUID();
        signIn(NAVER, subject);
        UUID userId = userIdOf(OidcStubConfiguration.issuer(NAVER), subject).orElseThrow();
        assertThat(accountDeletion().beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        // 세션 개설 판정이 곧 거부다. 이 스토리가 새 판정을 만들지 않았다는 것이 이 한 줄이다.
        assertThat(accountLifecycle().decideSessionCreation(userId))
                .isEqualTo(AccessDecision.DENIED);
        jdbc().sql("TRUNCATE TABLE spring_session, spring_session_attributes").update();

        HttpResponse<String> retry = signIn(NAVER, subject);

        assertThat(retry.statusCode()).isEqualTo(302);
        // 실패 표지는 닫힌 값 하나다. 삭제 진행은 타 사용자·미존재와 구분되지 않는다.
        assertThat(locationOf(retry))
                .endsWith(
                        AuthFlowHandlers.ENTRY_PATH
                                + "?"
                                + AuthFlowHandlers.FAILURE_MARKER_PARAMETER
                                + "="
                                + AuthFlowHandlers.FAILURE_MARKER_FAILED);
        assertThat(sessionCount()).isZero();
        // 신규 계정도 만들어지지 않았다. 매핑이 남아 있으므로 재조회가 그 계정을 찾고 거부된다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void erasePayload는_네이버_매핑과_그_계정의_세션을_함께_지운다() throws Exception {
        String naverSubject = "erase-naver-" + UUID.randomUUID();
        String googleSubject = "erase-bystander-" + UUID.randomUUID();
        HttpResponse<String> naverLogin = signIn(NAVER, naverSubject);
        HttpResponse<String> googleLogin = signIn(GOOGLE, googleSubject);
        String naverCookie = sessionCookieOf(naverLogin).orElseThrow();
        String googleCookie = sessionCookieOf(googleLogin).orElseThrow();
        UUID naverUser = userIdOf(OidcStubConfiguration.issuer(NAVER), naverSubject).orElseThrow();

        assertThat(accountDeletion().beginDeletion(naverUser)).isEqualTo(DeletionOutcome.COMPLETED);
        assertThat(accountDeletion().erasePayload(naverUser)).isEqualTo(DeletionOutcome.COMPLETED);

        // 그 계정의 매핑과 세션만 사라졌다. 남은 매핑은 Google 하나다.
        assertThat(distinctProviders()).containsExactly(GOOGLE);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCountOf(naverUser)).isZero();
        assertThat(sessionState(naverCookie).body()).contains("\"authenticated\":false");
        // 다른 사용자는 그대로다. 부분 삭제도, 과잉 삭제도 아니다.
        assertThat(sessionState(googleCookie).body()).contains("\"authenticated\":true");
        assertThat(userIdOf(OidcStubConfiguration.issuer(GOOGLE), googleSubject)).isPresent();
        // guard 행은 둘 다 남는다. 그 한 행이 신규 세션 생성 차단의 전부다.
        assertThat(accountCount()).isEqualTo(2);
    }

    /** 저장된 {@code provider} 값의 집합. 어느 행도 다른 제공자의 이름을 갖지 않는지 본다. */
    private List<String> distinctProviders() {
        return jdbc()
                .sql("SELECT DISTINCT provider FROM identity_social_link ORDER BY provider")
                .query(String.class)
                .list();
    }
}
