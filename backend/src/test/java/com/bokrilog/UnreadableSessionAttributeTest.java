package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * 세션 속성을 읽을 수 없을 때의 판독 규칙(운영 command 공통화 리뷰 patch). 세션 저장소는 풀 수 없는 바이트를
 * {@link UnreadableSessionValue}로 읽고, 각 판독이 fail-closed로 접는다 — 로그인 문맥은 미인증, 재인증 표지는 표시된 것.
 *
 * <p>실제 JDBC 세션 저장소를 지난다 — 속성의 {@code ATTRIBUTE_BYTES}를 SQL로 깨뜨린 뒤 HTTP로 부른다.
 */
class UnreadableSessionAttributeTest extends OperatorTestBase {

    private static final byte[] GARBAGE = "not a serialized object".getBytes(StandardCharsets.UTF_8);

    @Test
    void 읽을_수_없는_로그인_문맥은_미인증이고_보호된_경로는_401이며_로그아웃은_계약대로다() throws Exception {
        Signed signed = signedIn("unreadable-context");
        String sessionId = sessionIdOf(signed.cookie());
        String unauthenticated = sessionState(null).body();
        corrupt(sessionId, HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

        var state = sessionState(signed.cookie());
        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(state.body()).as("미인증 응답과 글자까지 같다").isEqualTo(unauthenticated);
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH, signed.cookie()).statusCode()).isEqualTo(401);
        assertThat(get(OperatorGate.AUDIT_PATH, signed.cookie()).statusCode()).isEqualTo(401);

        // 로그아웃은 미인증 세션의 로그아웃과 같은 결말이다 — 그 뒤에도 인증으로 되살아나지 않는다.
        Signed plain = signedIn("unreadable-context-baseline");
        String plainSession = sessionIdOf(plain.cookie());
        corrupt(plainSession, HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var logout = logout(signed.cookie(), signed.csrf());
        var baseline = logout(plain.cookie(), plain.csrf());
        assertThat(logout.statusCode()).isEqualTo(baseline.statusCode());
        assertThat(code(logout)).isEqualTo(code(baseline));
        assertThat(logout.statusCode()).isIn(204, 403);
        assertThat(sessionState(signed.cookie()).body()).isEqualTo(unauthenticated);
    }

    @Test
    void 읽을_수_없는_재인증_표지는_표시된_것이고_민감_command를_같은_방식으로_막는다() throws Exception {
        Signed marked = operator("unreadable-marker-real", "command,audit");
        Signed corrupted = signedIn("unreadable-marker-corrupt");
        operatorAccess().reinterpret(entry(marked.subject(), "command,audit") + ";"
                + entry(corrupted.subject(), "command,audit"));
        corrupted = reauthenticated(corrupted);
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(marked.cookie()))).isTrue();
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(corrupted.cookie()))).isTrue();
        corrupt(sessionIdOf(corrupted.cookie()), SessionGuard.REAUTH_REQUIRED_ATTRIBUTE);

        // 세션 응답: 표시된 세션과 같은 모양(인증 아님·재인증 요구·토큰).
        Map<String, Object> real = topLevelFieldsOf(sessionState(marked.cookie()).body());
        Map<String, Object> broken = topLevelFieldsOf(sessionState(corrupted.cookie()).body());
        assertThat(broken.keySet()).containsExactlyElementsOf(real.keySet());
        assertThat(broken).containsEntry("authenticated", false).containsEntry("reauthRequired", true)
                .containsEntry("destination", real.get("destination"));

        // 보호된 경로와 민감 command(운영 command 준비)가 표시된 세션과 같은 응답이다.
        var realRead = get(OperatorGate.AUDIT_PATH, marked.cookie());
        var brokenRead = get(OperatorGate.AUDIT_PATH, corrupted.cookie());
        assertThat(brokenRead.statusCode()).isEqualTo(realRead.statusCode()).isNotEqualTo(200);
        var realPrepare = prepareExport(marked, recentTarget(), "표지 판정", key("unreadable-marker-real"));
        var brokenPrepare = prepareExport(corrupted, recentTarget(), "표지 판정", key("unreadable-marker-corrupt"));
        assertThat(brokenPrepare.statusCode()).isEqualTo(realPrepare.statusCode()).isNotEqualTo(200);
        assertThat(code(brokenPrepare)).isEqualTo(code(realPrepare));
    }

    // ------------------------------------------------------------------ 도구

    private static Object code(HttpResponse<String> response) {
        return response.body() == null || response.body().isBlank() ? "" : topLevelFieldsOf(response.body())
                .getOrDefault("code", "");
    }

    /** 세션 하나의 그 속성 바이트를 깨뜨린다. 속성이 있어야 한다. */
    private void corrupt(String sessionId, String attribute) {
        int updated = jdbc().sql("UPDATE spring_session_attributes a SET attribute_bytes = :bytes FROM spring_session s "
                        + "WHERE a.session_primary_id = s.primary_id AND s.session_id = :id AND a.attribute_name = :name")
                .param("bytes", GARBAGE).param("id", sessionId).param("name", attribute).update();
        assertThat(updated).as("깨뜨릴 속성이 그 세션에 있다: " + attribute).isEqualTo(1);
    }
}
