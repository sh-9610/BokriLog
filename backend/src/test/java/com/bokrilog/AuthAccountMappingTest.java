package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevOidcStub.TokenPlan;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 계정과 인증 매핑.
 *
 * <p>실제 PostgreSQL로 돈다. 여기서 고정하려는 것은 SQL 문법이 아니라 {@code (issuer, subject)}
 * 유일 인덱스가 동시 삽입을 실제로 하나로 접는다는 엔진의 동작이다. 다른 엔진에서 통과하는 시험은
 * 이 계약의 증거가 되지 못한다.
 */
class AuthAccountMappingTest extends AuthFlowTestBase {

    @Test
    void 미연결_식별자는_신규_가입으로_처리하고_온보딩으로_보낸다() throws Exception {
        String subject = "new-user-" + UUID.randomUUID();

        HttpResponse<String> response = signIn(subject);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(), subject)).isPresent();
    }

    @Test
    void 재로그인은_같은_user_id를_쓰고_계정을_늘리지_않는다() throws Exception {
        String subject = "returning-user-" + UUID.randomUUID();

        signIn(subject);
        UUID first = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        HttpResponse<String> second = signIn(subject);

        assertThat(second.statusCode()).isEqualTo(302);
        // 기존 사용자는 온보딩이 아니라 기본 대시보드로 간다.
        assertThat(locationOf(second)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(), subject)).contains(first);
    }

    @Test
    void subject가_다르면_별개_계정이다() throws Exception {
        /*
         * 이 시험이 "이메일만 같은 다른 식별자" 행이다.
         *
         * 이메일을 흉내낼 자리가 없는 것이 요점이다. 스키마에 이메일 컬럼이 없고
         * `AccountLifecycleApi`의 시그니처에도 이메일이 없으므로, 같은 이메일을 가진 두 식별자가
         * 병합될 경로 자체가 존재하지 않는다. 남는 축은 `subject`뿐이고, 그것이 다르면 다른 계정이다.
         */
        String first = "same-email-a-" + UUID.randomUUID();
        String second = "same-email-b-" + UUID.randomUUID();

        signIn(first);
        signIn(second);

        assertThat(accountCount()).isEqualTo(2);
        assertThat(socialLinkCount()).isEqualTo(2);
        UUID firstUser = userIdOf(OidcStubConfiguration.issuer(), first).orElseThrow();
        UUID secondUser = userIdOf(OidcStubConfiguration.issuer(), second).orElseThrow();
        assertThat(firstUser).isNotEqualTo(secondUser);
    }

    @Test
    void 같은_식별자로_동시에_처음_로그인해도_계정과_매핑은_하나다() throws Exception {
        String subject = "concurrent-user-" + UUID.randomUUID();

        // 두 흐름을 각자 시작한다. 세션도 state도 nonce도 서로 다르다.
        Authorization first = startAuthorization();
        Authorization second = startAuthorization();
        String firstCode =
                issueCode(first, TokenPlan.valid(first.nonce()).subject(subject));
        String secondCode =
                issueCode(second, TokenPlan.valid(second.nonce()).subject(subject));

        // 두 콜백을 같은 순간에 밀어 넣는다. latch가 없으면 한쪽이 끝난 뒤 다른 쪽이 시작해
        // 경합이 아니라 순차 실행을 시험하게 된다.
        CountDownLatch start = new CountDownLatch(1);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> firstCall =
                    executor.submit(
                            () -> {
                                start.await(10, TimeUnit.SECONDS);
                                return callback(first.sessionCookie(), firstCode, first.state());
                            });
            Future<HttpResponse<String>> secondCall =
                    executor.submit(
                            () -> {
                                start.await(10, TimeUnit.SECONDS);
                                return callback(second.sessionCookie(), secondCode, second.state());
                            });
            start.countDown();
            responses.add(firstCall.get(30, TimeUnit.SECONDS));
            responses.add(secondCall.get(30, TimeUnit.SECONDS));
        }

        // 이것이 이 시험의 핵심 불변식이다. 유일 인덱스가 두 번째 삽입을 0행으로 접고, 진 쪽의
        // 트랜잭션이 롤백되어 아무도 로그인할 수 없는 빈 계정이 남지 않는다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);

        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(302);
            /*
             * 두 요청이 모두 목적지로 갔다. 실패 수렴은 허용하지 않는다.
             *
             * 사람이 승인한 결정은 "경합에 졌다는 이유로 정상 로그인을 거절하지 않는다"이고
             * (SPEC `## Spec Change Log` 2026-09-07의 frozen 변경), 진 쪽은 롤백한 뒤 재조회로
             * 같은 계정의 세션을 얻는다. 실패까지 허용하면 그 결정이 회귀해도 이 시험이 통과한다.
             *
             * 목적지가 둘로 갈리는 것은 계약이다 — 이긴 쪽은 자기가 계정을 만들었으니 온보딩이고
             * 진 쪽은 재조회로 기존 계정을 찾았으니 기본 대시보드다. 어느 요청이 어느 쪽인지는
             * 스케줄링이 정하므로 요청별로 고정하지 않는다.
             */
            assertThat(locationOf(response))
                    .satisfiesAnyOf(
                            value -> assertThat(value).endsWith(AuthFlowHandlers.ONBOARDING_PATH),
                            value -> assertThat(value).endsWith(AuthFlowHandlers.DASHBOARD_PATH));
        }

        // 개설된 세션은 둘이고 모두 그 하나의 계정을 가리킨다. 하나뿐이면 한쪽이 로그인하지
        // 못한 것이고, principal이 갈리면 경합이 두 계정을 만든 것이다.
        List<String> principals =
                jdbc()
                        .sql("SELECT principal_name FROM spring_session WHERE principal_name IS NOT NULL")
                        .query(String.class)
                        .list();
        assertThat(principals).hasSize(2);
        assertThat(principals).allMatch(principal -> principal.equals(userId.toString()));
        // 두 세션은 서로 다른 행이다. `session_id`가 유일 인덱스이므로 행 수가 곧 세션 수다.
        assertThat(sessionCount()).isEqualTo(2);
    }
}
