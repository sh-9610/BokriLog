package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.session.DelegatingIndexResolver;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 로그인 직후 같은 세션의 동시 세션 확인.
 *
 * <p>로그인 직후 같은 쿠키로 {@code GET /api/auth/session}이 겹친다(빠른 탭 열기·이동, 중단된 앞 문서의 요청). 예전에는 CSRF
 * 토큰이 그 세션의 첫 세션 확인에서 만들어져 저장됐으므로 두 결함이 함께 있었다.
 *
 * <ul>
 *   <li>두 요청이 모두 "속성 없음"으로 읽고 같은 속성을 각자 처음 저장하면 Spring Session JDBC의 plain {@code INSERT}가
 *       {@code (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)} 기본 키 충돌로 한쪽을 500으로 만들었다 — 세션 속성 저장의 PostgreSQL
 *       upsert({@code SessionAttributeConversion.postgreSqlAttributeUpsert})가 닫는다.
 *   <li>upsert만으로는 요청마다 다른 토큰을 만들어 응답에 싣고 마지막 저장 하나만 남는다(last-write-wins). 다른 응답의 토큰을
 *       든 화면은 다음 상태 변경이 403이다 — 인증 성공 요청이 토큰을 미리 저장하는 것({@code AuthFlowHandlers})이 닫는다.
 *       그 뒤의 세션 확인은 저장된 같은 토큰을 읽기만 한다.
 * </ul>
 *
 * <p>응답의 {@code csrfToken}은 요청마다 다르게 가려진(XOR) 값이라 문자열로는 비교할 수 없다. 그래서 제품의 CSRF 판정이 쓰는
 * 해석기({@link XorCsrfTokenRequestAttributeHandler})로 각 값을 풀어 저장된 토큰과 비교한다.
 *
 * <p>재현은 결정적이었으므로 반복은 CI 시간에 맞춰 작다. 더 돌릴 때는 {@code backend/gradlew --project-dir backend test --rerun
 * --tests 'com.bokrilog.AuthSessionConcurrencyTest'}를 여러 번 실행한다.
 */
class AuthSessionConcurrencyTest extends AuthFlowTestBase {

    /** 한 세션에 동시에 보내는 세션 확인 수. */
    private static final int CONCURRENT_CHECKS = 8;

    /** 한 반복에서 새로 만드는 세션 수. */
    private static final int SESSIONS_PER_REPETITION = 3;

    /** 동시 출발·응답 대기의 상한. 넘으면 멈추지 않고 실패한다. */
    private static final long WAIT_SECONDS = 30;

    /** 제품 저장소({@code SessionBoundCsrfTokenRepository})가 위임하는 저장소와 같은 기본 구성이 쓰는 세션 속성 이름. */
    private static final String CSRF_ATTRIBUTE = csrfAttributeName();

    @Autowired
    private SessionRepository<? extends Session> repository;

    @RepeatedTest(3)
    void 로그인_직후_같은_세션의_동시_세션_확인은_모두_인증된_200이고_같은_저장_토큰을_싣는다() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CHECKS)) {
            for (int round = 0; round < SESSIONS_PER_REPETITION; round++) {
                String cookie = sessionCookieOf(signIn("concurrent-check-" + UUID.randomUUID())).orElseThrow();
                String sessionId = sessionIdOf(cookie);
                // 토큰은 세션 확인 전에, 인증 성공 요청에서 이미 저장돼 있다.
                assertThat(csrfAttributeRows(sessionId)).isEqualTo(1);

                List<HttpResponse<String>> responses = concurrentSessionChecks(pool, cookie);

                CsrfToken stored = storedCsrfToken(sessionId);
                for (HttpResponse<String> response : responses) {
                    assertThat(response.statusCode()).as("세션 %s의 동시 세션 확인", sessionId).isEqualTo(200);
                    assertThat(topLevelFieldsOf(response.body())).containsEntry("authenticated", true);
                    assertThat(unmasked(csrfTokenIn(response.body()), stored))
                            .as("응답 토큰이 저장된 토큰과 같다")
                            .isEqualTo(stored.getToken());
                }
                assertThat(sessionRows(sessionId)).isEqualTo(1);
                assertThat(csrfAttributeRows(sessionId)).isEqualTo(1);
                assertThat(storedCsrfToken(sessionId).getToken()).isEqualTo(stored.getToken());

                // 그 토큰으로 보호 요청이 통과한다(CSRF·`Origin`).
                assertThat(logout(cookie, csrfTokenIn(responses.getLast().body())).statusCode()).isEqualTo(204);
            }
        }
    }

    @Test
    void 등록된_세션_저장소는_upsert와_주체_색인을_함께_적용하고_같은_속성을_처음_두_번_저장해도_한_행으로_덮어쓴다()
            throws Exception {
        assertThat(this.repository).isInstanceOf(JdbcIndexedSessionRepository.class);
        // upsert customizer가 적용됐다 — 속성 생성 SQL이 충돌 시 덮어쓴다.
        assertThat((String) ReflectionTestUtils.getField(this.repository, "createSessionAttributeQuery"))
                .contains("ON CONFLICT")
                .contains("DO UPDATE");
        // 기존 주체 색인 customizer도 함께 적용됐다.
        Object indexResolver = ReflectionTestUtils.getField(this.repository, "indexResolver");
        assertThat(indexResolver).isInstanceOf(DelegatingIndexResolver.class);
        assertThat((List<?>) ReflectionTestUtils.getField(indexResolver, "delegates"))
                .singleElement()
                .isInstanceOf(SessionAttributeConversion.ReadablePrincipalNameIndexResolver.class);

        String sessionId = sessionIdOf(sessionCookieOf(signIn("attribute-upsert-" + UUID.randomUUID())).orElseThrow());
        String name = "bokrilog.test.concurrent-first-write";
        assertThatCode(() -> saveTwiceAsFirstWrite(this.repository, sessionId, name)).doesNotThrowAnyException();

        assertThat(attributeRows(sessionId, name)).isEqualTo(1);
        assertThat(sessionAttribute(sessionId, name)).isEqualTo("second");
    }

    /**
     * 같은 쿠키의 세션 확인을 동시에 출발시킨다. 출발 준비가 시한을 넘어도 출발 신호를 내고, 응답은 모두 모은 뒤 돌려준다 — 시험이
     * 멈추지 않고 실패하게.
     */
    private List<HttpResponse<String>> concurrentSessionChecks(ExecutorService pool, String cookie)
            throws InterruptedException, ExecutionException, TimeoutException {
        CountDownLatch ready = new CountDownLatch(CONCURRENT_CHECKS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> checks = new ArrayList<>();
        boolean allReady;
        try {
            for (int index = 0; index < CONCURRENT_CHECKS; index++) {
                checks.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("출발 신호가 오지 않았다");
                    }
                    return sessionState(cookie);
                }));
            }
            allReady = ready.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            start.countDown();
        }
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> check : checks) {
            responses.add(check.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        assertThat(allReady).as("동시 출발 준비").isTrue();
        return responses;
    }

    /**
     * 두 요청이 같은 세션을 각자 읽은 모양 — 둘 다 이 속성이 없다고 보고 각자 "추가"로 저장한다. 기본 plain {@code INSERT}면 두 번째
     * 저장이 기본 키 충돌로 실패한다.
     */
    private static <S extends Session> void saveTwiceAsFirstWrite(
            SessionRepository<S> repository, String sessionId, String name) {
        S first = repository.findById(sessionId);
        S second = repository.findById(sessionId);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        first.setAttribute(name, "first");
        second.setAttribute(name, "second");
        repository.save(first);
        repository.save(second);
    }

    /** 응답의 가려진 토큰을 제품의 CSRF 해석기로 푼 원래 토큰. 형식이 맞지 않으면 {@code null}이다. */
    private static @Nullable String unmasked(@Nullable String responseToken, CsrfToken stored) {
        if (responseToken == null) {
            return null;
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(stored.getHeaderName(), responseToken);
        return new XorCsrfTokenRequestAttributeHandler().resolveCsrfTokenValue(request, stored);
    }

    private CsrfToken storedCsrfToken(String sessionId) {
        Object stored = sessionAttribute(sessionId, CSRF_ATTRIBUTE);
        assertThat(stored).isInstanceOf(CsrfToken.class);
        return (CsrfToken) stored;
    }

    /** 이름을 다시 적지 않고 저장소가 실제로 쓰는 속성 이름을 관측한다. */
    private static String csrfAttributeName() {
        HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();
        MockHttpServletRequest probe = new MockHttpServletRequest();
        csrf.saveToken(csrf.generateToken(probe), probe, new MockHttpServletResponse());
        List<String> names = Collections.list(probe.getSession().getAttributeNames());
        assertThat(names).hasSize(1);
        return names.getFirst();
    }

    private long sessionRows(String sessionId) {
        return jdbc().sql("SELECT count(*) FROM spring_session WHERE session_id = :id")
                .param("id", sessionId)
                .query(Long.class)
                .single();
    }

    private long csrfAttributeRows(String sessionId) {
        return attributeRows(sessionId, CSRF_ATTRIBUTE);
    }

    private long attributeRows(String sessionId, String name) {
        return jdbc().sql("SELECT count(*) FROM spring_session_attributes a JOIN spring_session s "
                        + "ON s.primary_id = a.session_primary_id WHERE s.session_id = :id AND a.attribute_name = :name")
                .param("id", sessionId)
                .param("name", name)
                .query(Long.class)
                .single();
    }
}
