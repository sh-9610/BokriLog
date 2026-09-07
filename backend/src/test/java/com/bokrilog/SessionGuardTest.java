package com.bokrilog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 세션 상태 전환 기계장치.
 *
 * <p>두 가지를 고정한다.
 *
 * <ul>
 *   <li><b>저장소의 차이.</b> 재인증 요구는 세션 행을 남기고 폐기는 지운다. 사용자 단위 조작은 그
 *       {@code principal_name}의 행만 건드린다. 이 차이가 시험으로 고정되면 1.8이 상태 기계를
 *       다시 만들지 않는다.
 *   <li><b>표면의 부재.</b> 상태 설정 기능에 도달하는 HTTP 경로가 없다. 구조로 한 번(ArchUnit),
 *       실제로 뜬 서버에 요청을 던져 한 번 더 확인한다. 구조만 보면 다른 클래스가 이 bean을 감싸는
 *       컨트롤러를 만들어도 통과하고, 요청만 보면 목록을 늘리는 것으로 부재를 증명할 수 없다.
 * </ul>
 *
 * <p>{@code AuthFlowTestBase}를 상속해 같은 Spring 컨텍스트와 같은 PostgreSQL 컨테이너를 쓴다.
 * 여기서 확인하는 것이 실제 세션 행의 변화이므로 저장소 없이 성립하는 시험이 아니다.
 */
class SessionGuardTest extends AuthFlowTestBase {

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.bokrilog");

    @Test
    void 세션_guard에는_HTTP_표면이_없다() {
        /*
         * 애노테이션도 ArchUnit의 의존으로 잡힌다. `@RestController`·`@RequestMapping`·`@Endpoint`를
         * 붙이는 순간 이 규칙이 깨지고, 서블릿·MVC 타입을 참조하는 경로도 함께 막힌다.
         *
         * `identity`에 적용한 규칙과 같은 목록이다(`ModuleBoundaryTest`). 이 클래스는 루트 패키지에
         * 있어 그 규칙의 대상이 아니지만, "일반 사용자 HTTP API·Actuator·개발 전용 엔드포인트
         * 어디에도 그 경로가 없다"는 계약은 위치와 무관하게 검사로 존재해야 한다.
         */
        ArchRule rule =
                noClasses()
                        .that()
                        .haveNameMatching(SessionGuard.class.getName() + "(\\$.*)?")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "org.springframework.web..",
                                "org.springframework.http..",
                                "org.springframework.boot.webmvc..",
                                "org.springframework.boot.actuate..",
                                "org.springframework.boot.web..",
                                "jakarta.servlet..")
                        .because(
                                "의심 세션 상태 전환은 Java bean 표면만 갖는다. 일반 사용자 HTTP API·"
                                        + "Actuator·개발 전용 엔드포인트로 노출하지 않는다.");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void 세션_guard는_identity의_세션_결합을_넓히지_않는다() {
        // `identity`의 `deleteSessionsOf`는 삭제 경로 전용으로 그대로 남는다. 세션 상태 전환이
        // 그 모듈을 지나면 `deferred-work.md`에 이미 올라간 결합이 커진다.
        ArchRule rule =
                noClasses()
                        .that()
                        .haveNameMatching(SessionGuard.class.getName() + "(\\$.*)?")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage("com.bokrilog.identity..")
                        .because("세션 폐기 포트는 루트 패키지가 소유한다. identity 쪽은 건드리지 않는다.");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void 상태_설정_기능에_도달하는_HTTP_경로가_없다() throws Exception {
        String subject = "guard-surface-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        /*
         * "이 기능이 HTTP로 열린다면 아마 이 이름이었을 것"들이다. 목록을 늘리는 것으로 부재를
         * 증명할 수는 없으므로 위 구조 검사가 실제 증거이고, 여기서 보는 것은 어떤 경로도 200을
         * 돌려주지 않으며 세션 상태가 그대로라는 사실이다.
         */
        String[][] probes = {
            {"POST", "/api/auth/sessions/revoke"},
            {"POST", "/api/auth/sessions/reauth"},
            {"POST", "/api/auth/session/revoke"},
            {"DELETE", "/api/auth/sessions"},
            {"POST", "/api/sessions/revoke"},
            {"POST", "/api/session-guard"},
            {"POST", "/api/dev/session-guard"},
            {"GET", "/actuator/sessions"},
            {"DELETE", "/actuator/sessions/" + userId},
            {"POST", "/api/users/me/sessions/revoke"},
        };

        for (String[] probe : probes) {
            var response =
                    send(probe[0], probe[1], cookie, "Origin", OidcStubConfiguration.PUBLIC_BASE_URL);
            // 404(매핑 없음)·405(메서드 없음)·403(보호 경로의 CSRF 거절) 셋 다 "그 경로가 없다"의
            // 답이다. 어느 쪽이든 2xx가 아니어야 한다.
            assertThat(response.statusCode())
                    .as("%s %s", probe[0], probe[1])
                    .isIn(403, 404, 405);
            assertThat(response.body()).doesNotContain(userId.toString());
        }

        // 어떤 탐침도 세션 상태를 바꾸지 않았다.
        assertThat(sessionCountOf(userId)).isEqualTo(1);
        assertThat(sessionState(cookie).body()).contains("\"authenticated\":true");
    }

    @Test
    void 재인증_요구는_행을_남기고_폐기는_지운다() throws Exception {
        String subject = "guard-rows-" + UUID.randomUUID();
        sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(sessionGuard().sessionCountOf(userId)).isEqualTo(1);

        assertThat(sessionGuard().requireReauthenticationOf(userId)).isEqualTo(1);
        // 표시는 행을 남긴다. 1.8의 재인증이 회전시킬 대상이 여기 있다.
        assertThat(sessionGuard().sessionCountOf(userId)).isEqualTo(1);
        assertThat(sessionCountOf(userId)).isEqualTo(1);
        // 표시가 저장소에 실제로 남는다. 세션 속성 하나이며 새 컬럼을 만들지 않았다.
        assertThat(storedAttributeNames()).contains(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE);

        assertThat(sessionGuard().revokeSessionsOf(userId)).isEqualTo(1);
        // 폐기는 지운다. 속성 행도 함께 사라진다(`ON DELETE CASCADE`).
        assertThat(sessionGuard().sessionCountOf(userId)).isZero();
        assertThat(sessionCountOf(userId)).isZero();
        assertThat(storedAttributeNames()).doesNotContain(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE);
    }

    @Test
    void 사용자_단위_조작은_그_principal의_행만_건드린다() throws Exception {
        String subjectA = "guard-scope-a-" + UUID.randomUUID();
        String subjectB = "guard-scope-b-" + UUID.randomUUID();
        // A는 세션 둘, B는 하나. 전체 개수만 세면 구분되지 않는 상태를 일부러 만든다.
        sessionCookieOf(signIn(subjectA)).orElseThrow();
        sessionCookieOf(signIn(subjectA)).orElseThrow();
        sessionCookieOf(signIn(subjectB)).orElseThrow();
        UUID userA = userIdOf(OidcStubConfiguration.issuer(), subjectA).orElseThrow();
        UUID userB = userIdOf(OidcStubConfiguration.issuer(), subjectB).orElseThrow();
        assertThat(sessionCountOf(userA)).isEqualTo(2);
        assertThat(sessionCountOf(userB)).isEqualTo(1);

        assertThat(sessionGuard().revokeSessionsOf(userA)).isEqualTo(2);

        assertThat(sessionCountOf(userA)).isZero();
        assertThat(sessionCountOf(userB)).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
        // 계정과 매핑은 어느 쪽도 사라지지 않는다. 세션 조작은 계정 상태를 바꾸지 않는다.
        assertThat(accountCount()).isEqualTo(2);
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 세션_단위_조작은_그_세션만_건드린다() throws Exception {
        String subject = "guard-single-" + UUID.randomUUID();
        String first = sessionCookieOf(signIn(subject)).orElseThrow();
        String second = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(sessionCountOf(userId)).isEqualTo(2);
        String firstId = sessionIdOf(first);
        // 인코딩 가정이 맞는지 먼저 본다. 틀리면 아래 조작이 조용히 0건이 된다.
        assertThat(storedSessionIds()).contains(firstId);

        assertThat(sessionGuard().requireReauthentication(firstId)).isTrue();

        // 표시된 세션만 차단된다. 같은 사용자의 다른 세션은 그대로다.
        assertThat(sessionState(first).body()).contains("\"authenticated\":false");
        assertThat(sessionState(second).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(userId)).isEqualTo(2);

        assertThat(sessionGuard().revokeSession(firstId)).isTrue();

        assertThat(sessionCountOf(userId)).isEqualTo(1);
        assertThat(sessionState(second).body()).contains("\"authenticated\":true");
    }

    @Test
    void 없는_대상에_대한_조작은_실패가_아니라_0건이다() {
        // 존재 여부를 예외나 값으로 알려 주지 않는다. 대상이 없는 것과 실패는 다른 일이다.
        assertThat(sessionGuard().revokeSessionsOf(UUID.randomUUID())).isZero();
        assertThat(sessionGuard().requireReauthenticationOf(UUID.randomUUID())).isZero();
        assertThat(sessionGuard().revokeSession("no-such-session-id")).isFalse();
        assertThat(sessionGuard().requireReauthentication("no-such-session-id")).isFalse();
        assertThat(sessionCount()).isZero();
    }

    @Test
    void 판정은_절대_수명과_재인증_표시만_본다() {
        Duration lifetime = sessionGuard().absoluteLifetime();
        long now = System.currentTimeMillis();

        assertThat(sessionGuard().evaluate(now, null)).isEqualTo(SessionGuard.Verdict.ACTIVE);
        assertThat(sessionGuard().evaluate(now, Boolean.FALSE))
                .isEqualTo(SessionGuard.Verdict.ACTIVE);
        assertThat(sessionGuard().evaluate(now, Boolean.TRUE))
                .isEqualTo(SessionGuard.Verdict.REAUTH_REQUIRED);
        assertThat(sessionGuard().evaluate(now - lifetime.toMillis() - 1_000, null))
                .isEqualTo(SessionGuard.Verdict.EXPIRED);
        /*
         * 두 상태가 겹치면 더 강한 쪽이 남는다. 재인증으로 되살릴 수 있는 세션과 수명이 끝난
         * 세션은 다르다.
         */
        assertThat(sessionGuard().evaluate(now - lifetime.toMillis() - 1_000, Boolean.TRUE))
                .isEqualTo(SessionGuard.Verdict.EXPIRED);
    }

    /** 저장된 세션 속성 이름 전부. 표시가 저장소에 실제로 남는지 본다. */
    private String storedAttributeNames() {
        return String.join(
                " ",
                jdbc().sql("SELECT attribute_name FROM spring_session_attributes")
                        .query(String.class)
                        .list());
    }

    /** 저장된 세션 id 전부. 쿠키 값의 인코딩 가정을 확인하는 데 쓴다. */
    private List<String> storedSessionIds() {
        return jdbc().sql("SELECT session_id FROM spring_session").query(String.class).list();
    }
}
