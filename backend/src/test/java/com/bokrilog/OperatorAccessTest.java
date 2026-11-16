package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.OperatorProperties;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Story 3.1 AC-1·AC-5: 운영자 판정과 일반 사용자 분리, 권한 분리, 회수의 즉시 반영, 복귀 목적지.
 *
 * <p>비운영자·권한 부족·미존재는 {@code /api/operator/**}의 어느 경로·메서드에서도 없는 경로와 본문이 같은 404다 —
 * correlation ID만 다르다. 미인증은 다른 보호 경로와 같은 401이다.
 */
class OperatorAccessTest extends OperatorTestBase {

    /** 비교를 위해 correlation ID만 뺀 본문. */
    private static Map<String, Object> withoutCorrelation(String body) {
        Map<String, Object> fields = new LinkedHashMap<>(topLevelFieldsOf(body));
        fields.remove("correlationId");
        return fields;
    }

    private void assertUnknownPath(HttpResponse<String> response, Map<String, Object> unknown) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(withoutCorrelation(response.body())).isEqualTo(unknown);
        assertThat(response.body()).doesNotContain("operator", "audit", "REAUTHENTICATION");
    }

    @Test
    void 운영자가_없으면_모든_운영_경로가_없는_경로와_같은_404다() throws Exception {
        Signed user = reauthenticated(signedIn("no-operator"));
        var unknown = get("/api/nope", user.cookie());
        assertThat(unknown.statusCode()).isEqualTo(404);
        Map<String, Object> expected = withoutCorrelation(unknown.body());

        assertUnknownPath(auditGet(user, ""), expected);
        assertUnknownPath(operationGet(user, "op_abcdef"), expected);
        assertUnknownPath(get(AuthSecurityConfig.OPERATOR_API_PATH + "/nope", user.cookie()), expected);
        // 매핑된 경로의 잘못된 메서드도 405가 아니라 같은 404다 — 경로의 존재가 새지 않는다.
        assertUnknownPath(get(EXPORT_PATH, user.cookie()), expected);
        assertUnknownPath(prepareExport(user, recentTarget(), "사유", key("no-operator")), expected);
        assertUnknownPath(confirmExport(user, "op_abcdef", "아무 문구", key("no-operator")), expected);
        assertUnknownPath(cancelExport(user), expected);
        assertThat(auditCount()).isZero();
        assertThat(actionGrantCountOf(user.user())).isZero();
    }

    @Test
    void 미인증은_401이고_상태_변경은_CSRF가_먼저_막는다() throws Exception {
        assertThat(get(AUDIT_PATH, null).statusCode()).isEqualTo(401);
        assertThat(get(AuthSecurityConfig.OPERATOR_API_PATH, null).statusCode()).isEqualTo(401);
        Signed operator = operator("operator-csrf", "command,audit");
        var withoutToken = sendJson("POST", EXPORT_PATH, exportBody("AUDIT_LEDGER", recentTarget(), "사유"),
                operator.cookie(), "Origin", OidcStubConfiguration.PUBLIC_BASE_URL, "Idempotency-Key",
                key("operator-csrf"));
        assertThat(withoutToken.statusCode()).isEqualTo(403);
        var otherOrigin = sendJson("POST", EXPORT_PATH, exportBody("AUDIT_LEDGER", recentTarget(), "사유"),
                operator.cookie(), "Origin", "https://evil.test", AuthSecurityConfig.CSRF_HEADER_NAME,
                operator.csrf(), "Idempotency-Key", key("operator-origin"));
        assertThat(otherOrigin.statusCode()).isEqualTo(403);
        assertThat(auditCount()).isZero();
    }

    @Test
    void 감사_권한과_명령_권한은_서로를_열지_않는다() throws Exception {
        Signed auditor = signedIn("auditor");
        Signed commander = signedIn("commander");
        operatorAccess().reinterpret(entry(auditor.subject(), "audit") + ";" + entry(commander.subject(), "command"));
        auditor = reauthenticated(auditor);
        commander = reauthenticated(commander);

        assertThat(auditGet(auditor, "").statusCode()).isEqualTo(200);
        assertThat(prepareExport(auditor, recentTarget(), "사유", key("auditor")).statusCode()).isEqualTo(404);

        assertThat(auditGet(commander, "").statusCode()).isEqualTo(404);
        // 감사 내보내기는 command이면서 감사 데이터를 꺼낸다 — command 권한만으로는 열리지 않는다.
        assertThat(prepareExport(commander, recentTarget(), "사유", key("commander")).statusCode()).isEqualTo(404);
        assertThat(actionGrantCountOf(commander.user())).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test
    void 회수와_삭제_시작은_다음_요청에_바로_반영된다() throws Exception {
        Signed operator = operator("revoked-operator", "command,audit");
        assertThat(auditGet(operator, "").statusCode()).isEqualTo(200);

        operatorAccess().reinterpret("");
        assertThat(auditGet(operator, "").statusCode()).isEqualTo(404);

        operatorAccess().reinterpret(entry(operator.subject(), "audit"));
        assertThat(auditGet(operator, "").statusCode()).isEqualTo(200);
        // 삭제가 시작된 계정의 세션은 그대로 인증돼 있고(세션 폐기는 삭제 orchestration의 몫이다) 운영자가 아니다.
        accountDeletion().beginDeletion(operator.user());
        assertThat(auditGet(operator, "").statusCode()).isEqualTo(404);
    }

    /**
     * 보호 술어는 원시 URI가 아니라 MVC가 매칭하는 디코딩 경로를 본다. 비예약 문자를 인코딩한 경로가 인증·운영자
     * 게이트·CSRF를 비켜 가지 않는다.
     */
    @Test
    void 인코딩한_경로도_같은_보호를_지난다() throws Exception {
        String encodedAudit = "/api/%6Fperator/audit";
        assertThat(get(encodedAudit, null).statusCode()).isEqualTo(401);

        Signed user = reauthenticated(signedIn("encoded-user"));
        assertThat(get(encodedAudit, user.cookie()).statusCode()).isEqualTo(404);
        assertThat(get("/api/operator/%61udit", user.cookie()).statusCode()).isEqualTo(404);

        Signed operator = operator("encoded-operator", "audit");
        assertThat(get(encodedAudit, operator.cookie()).statusCode())
                .as("MVC가 디코딩한 경로로 매칭한다 — 운영자에게는 같은 핸들러다").isEqualTo(200);

        // 기존 접두사의 인코딩된 상태 변경도 CSRF 판정을 지난다.
        var withoutToken = send("POST", "/api/dashb%6Fard/sync", user.cookie(), "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL, "Idempotency-Key", key("encoded-sync"));
        assertThat(withoutToken.statusCode()).isEqualTo(403);
        var encodedExport = sendJson("POST", "/api/%6Fperator/commands/audit-export",
                exportBody("AUDIT_LEDGER", recentTarget(), "사유"), operator.cookie(), "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL, "Idempotency-Key", key("encoded-export"));
        assertThat(encodedExport.statusCode()).isEqualTo(403);
        assertThat(auditCount()).isZero();
    }

    /** 운영자 화면 목적지는 지금 운영자일 때만이다 — 세션에 굳은 값도 회수 뒤에는 대시보드로 접힌다. */
    @Test
    void 운영자_목적지로_굳은_세션도_회수_뒤에는_대시보드다() throws Exception {
        Signed operator = signedIn("destination-fold");
        operatorAccess().reinterpret(entry(operator.subject(), "audit"));
        String cookie = sessionCookieOf(signInWithNext(operator.subject(), "/operator")).orElseThrow();
        assertThat(topLevelFieldsOf(sessionState(cookie).body())).containsEntry("destination", "operator");
        // 인증된 세션의 인증 시작은 지금 목적지로 되돌린다.
        assertThat(locationOf(get(AuthSecurityConfig.AUTHORIZATION_BASE_URI + "/" + GOOGLE, cookie)))
                .isEqualTo("/operator");

        operatorAccess().reinterpret("");
        assertThat(topLevelFieldsOf(sessionState(cookie).body())).containsEntry("destination", "dashboard");
        assertThat(locationOf(get(AuthSecurityConfig.AUTHORIZATION_BASE_URI + "/" + GOOGLE, cookie)))
                .isEqualTo("/dashboard");
    }

    @Test
    void 운영자에게만_운영자_화면이_복귀_목적지다() throws Exception {
        Signed operator = signedIn("destination-operator");
        Signed user = signedIn("destination-user");
        operatorAccess().reinterpret(entry(operator.subject(), "audit"));

        // 두 번째 로그인(신규가 아니다)이 요청 목적지를 본다.
        assertThat(locationOf(signInWithNext(operator.subject(), "/operator"))).isEqualTo("/operator");
        assertThat(locationOf(signInWithNext(user.subject(), "/operator"))).isEqualTo("/dashboard");

        var operatorAgain = signInWithNext(operator.subject(), "/operator");
        String cookie = sessionCookieOf(operatorAgain).orElseThrow();
        assertThat(topLevelFieldsOf(sessionState(cookie).body())).containsEntry("destination", "operator");

        // 로그인 뒤 회수되면 재인증의 복귀가 대시보드로 접힌다.
        operatorAccess().reinterpret("");
        String csrf = csrfTokenOf(cookie);
        var returned = reauthenticate(GOOGLE, cookie, csrf, plan -> plan.subject(operator.subject()));
        assertThat(locationOf(returned)).isEqualTo("/dashboard");
    }

    @Test
    void 운영자가_재인증하면_운영자_화면으로_돌아온다() throws Exception {
        Signed first = signedIn("destination-return");
        operatorAccess().reinterpret(entry(first.subject(), "command,audit"));
        var again = signInWithNext(first.subject(), "/operator");
        String cookie = sessionCookieOf(again).orElseThrow();
        var returned = reauthenticate(GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(first.subject()));
        assertThat(locationOf(returned)).isEqualTo("/operator");
    }

    /**
     * 위조 콜백 가드(Epic 1 회고 F2 — {@code epic-1-retro-2026-09-25.md}, 액션 {@code epic-1-retro-item-2})의 되돌림도 운영자
     * 목적지를 지킨다. 실패 핸들러에서는 thread-local 인증이 이미 비어 있으므로 저장된 주체로 접지 않으면 운영자의
     * {@code /operator}가 흐름 경계의 되돌림과 다르게 대시보드로 접힌다.
     */
    @Test
    void 위조_콜백의_되돌림도_운영자_목적지를_지킨다() throws Exception {
        Signed operator = signedIn("destination-forged");
        operatorAccess().reinterpret(entry(operator.subject(), "audit"));
        String cookie = sessionCookieOf(signInWithNext(operator.subject(), "/operator")).orElseThrow();
        assertThat(topLevelFieldsOf(sessionState(cookie).body())).containsEntry("destination", "operator");
        assertThat(startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie)).statusCode()).isEqualTo(204);
        startAuthorization(GOOGLE, null, cookie);
        String sessionId = sessionIdOf(cookie);
        Object armed = sessionAttribute(sessionId, ReauthenticationFlow.FLOW_ATTRIBUTE);
        assertThat(armed).isInstanceOf(ReauthenticationFlow.Armed.class);

        for (HttpResponse<String> forged : forgedCallbacks(GOOGLE, cookie)) {
            assertThat(forged.statusCode()).isEqualTo(302);
            assertThat(locationOf(forged)).isEqualTo("/operator");
        }
        assertThat(sessionAttribute(sessionId, ReauthenticationFlow.FLOW_ATTRIBUTE)).isEqualTo(armed);
    }

    @Test
    void allowlist_형식은_엄격하다() {
        assertThat(OperatorProperties.parseOperators(null)).isEmpty();
        assertThat(OperatorProperties.parseOperators("  ")).isEmpty();
        var parsed = OperatorProperties.parseOperators(" https://a|s1|command, audit ;https://a|s2|audit;");
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).permissions()).containsExactlyInAnyOrder(OperatorPermission.COMMAND,
                OperatorPermission.AUDIT);
        for (String malformed : List.of("https://a|s1", "https://a|s1|admin", "|s1|audit", "https://a||audit",
                "https://a|s1|", "https://a|s1|audit|x", "https://a|s1|audit;https://a|s1|command")) {
            assertThatThrownBy(() -> OperatorProperties.parseOperators(malformed))
                    .as(malformed).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("s1");
        }
    }

    @Test
    void 경로별_권한은_접두사_경계를_지킨다() {
        assertThat(OperatorGate.requiredFor("/api/operator/commands/audit-export"))
                .containsExactly(OperatorPermission.COMMAND);
        assertThat(OperatorGate.requiredFor("/api/operator/audit")).containsExactly(OperatorPermission.AUDIT);
        assertThat(OperatorGate.requiredFor("/api/operator/auditx")).isEmpty();
        // 삭제 재대기열 목록(Epic 5 회고 AI-1)은 command 권한이다. 접두사 경계 밖은 걸리지 않는다.
        assertThat(OperatorGate.requiredFor("/api/operator/deletions")).containsExactly(OperatorPermission.COMMAND);
        assertThat(OperatorGate.requiredFor("/api/operator/deletionsx")).isEmpty();
        assertThat(OperatorGate.requiredFor("/api/operator/operations/op_x")).isEmpty();
        assertThat(AuthSecurityConfig.isOperatorPath("/api/operatorx")).isFalse();
        assertThat(AuthSecurityConfig.isOperatorPath("/api/operator")).isTrue();
    }

    /** 그 subject로 {@code next}를 실은 로그인. */
    private HttpResponse<String> signInWithNext(String subject, String next) throws Exception {
        Authorization authorization = startAuthorization(GOOGLE, next, null);
        String code = issueCode(authorization, DevOidcStub.TokenPlan.valid(authorization.nonce()).subject(subject));
        return callback(authorization, code);
    }
}
