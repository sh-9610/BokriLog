package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.DeletionOutcome;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 제공자들의 독립.
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

    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;

    /** 이 시험이 도는 제공자 전부. 축을 늘리는 대신 같은 축이 이 목록을 돈다. */
    private static final List<String> PROVIDERS = List.of(GOOGLE, NAVER, KAKAO);

    @Test
    void 같은_subject_문자열이_여러_제공자에서_오면_별개_계정이다() throws Exception {
        /*
         * `subject`는 제공자의 namespace 안에서만 뜻이 있다. 여러 제공자가 같은 문자열을 쓰는 것은
         * 우연이며, 그 우연으로 계정이 합쳐지면 한 사용자가 다른 사용자의 데이터 경계 안으로
         * 들어간다. `(issuer, subject)`가 전역 유일 키인 이유가 이것이다.
         */
        String subject = "shared-subject-" + UUID.randomUUID();

        List<UUID> users = new ArrayList<>();
        for (String provider : PROVIDERS) {
            HttpResponse<String> response = signIn(provider, subject);

            assertThat(response.statusCode()).as("%s 콜백 상태", provider).isEqualTo(302);
            // 전부 신규 가입이다. 뒤의 것이 기존 계정을 찾았다면 목적지가 대시보드가 된다.
            assertThat(locationOf(response))
                    .as("%s 목적지", provider)
                    .endsWith(AuthFlowHandlers.ONBOARDING_PATH);
            users.add(userIdOf(OidcStubConfiguration.issuer(provider), subject).orElseThrow());
        }

        assertThat(accountCount()).isEqualTo(PROVIDERS.size());
        assertThat(socialLinkCount()).isEqualTo(PROVIDERS.size());
        // `user_id`가 서로 다르다. 하나라도 겹치면 병합이 일어난 것이다.
        assertThat(users).doesNotHaveDuplicates();
    }

    @Test
    void provider_컬럼에_인증에_실제로_쓰인_이름이_들어간다() throws Exception {
        /*
         * Story 1.6 전에는 이 컬럼에 리터럴 `"google"`이 무조건 들어갔다. 그러면 네이버로 로그인한
         * 계정도 Google로 기록되고, 그 컬럼을 술어로 쓰는 이후 스토리(로그인 수단 연결·해제)가
         * 거짓을 읽는다. 값을 정하는 것은 흐름이 알려 준 registration id 하나여야 한다.
         */
        for (String provider : PROVIDERS) {
            String subject = "provider-" + provider + "-" + UUID.randomUUID();

            signIn(provider, subject);

            assertThat(linkedProviderOf(OidcStubConfiguration.issuer(provider), subject))
                    .as("%s 매핑의 provider 컬럼", provider)
                    .contains(provider);
        }

        // 어느 행도 다른 제공자의 이름을 갖지 않는다. 이름 집합까지 세어 "하나만 옳다"를 막는다.
        assertThat(distinctProviders()).containsExactlyInAnyOrderElementsOf(PROVIDERS);
    }

    @Test
    void 여러_제공자의_매핑이_한_계정에_섞이지_않는다() throws Exception {
        /*
         * 이 스토리는 로그인 수단 연결(1.10)을 만들지 않는다. 그래서 계정마다 매핑이 정확히 하나이고,
         * 두 매핑이 한 `user_id`를 가리키는 상태는 병합이 일어났다는 뜻이다.
         */
        for (String provider : PROVIDERS) {
            signIn(provider, "no-merge-" + provider + "-" + UUID.randomUUID());
        }

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
    void 삭제가_시작된_계정은_어느_제공자의_재로그인으로도_세션을_얻지_못한다() throws Exception {
        for (String provider : PROVIDERS) {
            // 제공자마다 빈 저장소에서 시작한다. 행 수를 세는 단정이라 앞 iteration이 남긴 것이
            // 있으면 그 단정이 무엇을 세는지 알 수 없게 된다.
            resetStore();
            String subject = "deleting-" + provider + "-" + UUID.randomUUID();
            signIn(provider, subject);
            UUID userId =
                    userIdOf(OidcStubConfiguration.issuer(provider), subject).orElseThrow();
            assertThat(accountDeletion().beginDeletion(userId))
                    .isEqualTo(DeletionOutcome.COMPLETED);
            // 세션 개설 판정이 곧 거부다. 이 스토리가 새 판정을 만들지 않았다는 것이 이 한 줄이다.
            assertThat(accountLifecycle().decideSessionCreation(userId))
                    .isEqualTo(AccessDecision.DENIED);
            jdbc().sql("TRUNCATE TABLE spring_session, spring_session_attributes").update();

            HttpResponse<String> retry = signIn(provider, subject);

            assertThat(retry.statusCode()).as("%s 재로그인 상태", provider).isEqualTo(302);
            // 실패 표지는 닫힌 값 하나다. 삭제 진행은 타 사용자·미존재와 구분되지 않는다.
            assertThat(locationOf(retry))
                    .as("%s 재로그인 목적지", provider)
                    .endsWith(
                            AuthFlowHandlers.ENTRY_PATH
                                    + "?"
                                    + AuthFlowHandlers.FAILURE_MARKER_PARAMETER
                                    + "="
                                    + AuthFlowHandlers.FAILURE_MARKER_FAILED);
            assertThat(sessionCount()).as("%s 세션 행", provider).isZero();
            // 신규 계정도 만들어지지 않았다. 매핑이 남아 있으므로 재조회가 그 계정을 찾고 거부된다.
            assertThat(accountCount()).as("%s 계정 행", provider).isEqualTo(1);
            assertThat(socialLinkCount()).as("%s 매핑 행", provider).isEqualTo(1);
        }
    }

    @Test
    void erasePayload는_그_제공자의_매핑과_그_계정의_세션을_함께_지운다() throws Exception {
        /*
         * 방관자도 돈다. 방관자를 "대상이 아닌 첫 제공자"로 고정하면 그것이 늘 Google 아니면
         * 네이버가 되어 네이버↔카카오 조합이 한 번도 서지 않는다. 그 둘은 등록을 손으로 조립한
         * 제공자들이고, 삭제가 그 사이를 넘어가는지는 어느 층도 보지 않은 채로 남는다.
         */
        for (String provider : PROVIDERS) {
            for (String bystander : PROVIDERS) {
                if (bystander.equals(provider)) {
                    continue;
                }
                // 조합마다 빈 저장소에서 시작한다. 행 수를 세는 단정이라 앞 iteration이 남긴 것이
                // 있으면 그 단정이 무엇을 세는지 알 수 없게 된다.
                resetStore();
                String pair = provider + "↔" + bystander;
                String subject = "erase-" + provider + "-" + UUID.randomUUID();
                String bystanderSubject = "erase-bystander-" + bystander + "-" + UUID.randomUUID();
                HttpResponse<String> login = signIn(provider, subject);
                HttpResponse<String> bystanderLogin = signIn(bystander, bystanderSubject);
                String cookie = sessionCookieOf(login).orElseThrow();
                String bystanderCookie = sessionCookieOf(bystanderLogin).orElseThrow();
                UUID userId =
                        userIdOf(OidcStubConfiguration.issuer(provider), subject).orElseThrow();

                assertThat(accountDeletion().beginDeletion(userId))
                        .isEqualTo(DeletionOutcome.COMPLETED);
                assertThat(accountDeletion().erasePayload(userId))
                        .isEqualTo(DeletionOutcome.COMPLETED);

                // 그 계정의 매핑과 세션만 사라졌다. 남은 매핑은 방관자 하나다.
                assertThat(distinctProviders())
                        .as("%s 삭제 뒤 남은 이름", pair)
                        .containsExactly(bystander);
                assertThat(socialLinkCount()).as("%s 삭제 뒤 매핑 행", pair).isEqualTo(1);
                assertThat(sessionCountOf(userId)).as("%s 삭제 뒤 세션 행", pair).isZero();
                assertThat(sessionState(cookie).body()).contains("\"authenticated\":false");
                // 다른 사용자는 그대로다. 부분 삭제도, 과잉 삭제도 아니다.
                assertThat(sessionState(bystanderCookie).body()).contains("\"authenticated\":true");
                assertThat(userIdOf(OidcStubConfiguration.issuer(bystander), bystanderSubject))
                        .isPresent();
                // guard 행은 둘 다 남는다. 그 한 행이 신규 세션 생성 차단의 전부다.
                assertThat(accountCount()).as("%s 삭제 뒤 계정 행", pair).isEqualTo(2);
            }
        }
    }

    /** 저장된 {@code provider} 값의 집합. 어느 행도 다른 제공자의 이름을 갖지 않는지 본다. */
    private List<String> distinctProviders() {
        return jdbc()
                .sql("SELECT DISTINCT provider FROM identity_social_link ORDER BY provider")
                .query(String.class)
                .list();
    }
}
