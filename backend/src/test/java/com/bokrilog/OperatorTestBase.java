package com.bokrilog;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 표면 시험의 공통 배선(Story 3.1·3.2).
 *
 * <p>{@link AuthFlowTestBase}의 컨텍스트를 그대로 쓴다 — 새 {@code @DynamicPropertySource}가 없다. Story 3.2의 제한
 * 대상(계획·연결)을 실제로 만들려고 {@link PlanSyncTestBase}의 발행 도구를 물려받는다(그것도 같은 컨텍스트다). 운영자는 시험마다
 * {@link OperatorAccess#reinterpret} 훅으로 세우고 끝나면 비운다. 컨텍스트가 시험 클래스 사이에 공유되므로 비우지 않으면
 * 다음 클래스가 "운영자 없음"을 전제한 단정에서 엉뚱하게 통과하거나 실패한다.
 *
 * <p>멱등 키·확인 문구는 실행 시점에 만든다(키 모양 리터럴은 gitleaks가 잡는다).
 */
abstract class OperatorTestBase extends PlanSyncTestBase {

    static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    static final String AUDIT_PATH = OperatorGate.AUDIT_PATH;

    static final String EXPORT_PATH = OperatorGate.COMMANDS_PATH + "/audit-export";

    static final String EXPORT_CONFIRM_PATH = EXPORT_PATH + "/confirm";

    static final String EXPORT_CANCEL_PATH = EXPORT_PATH + "/cancel";

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private OperatorAccess operatorAccess;

    @Autowired
    private PlatformTransactionManager operatorTransactions;

    @AfterEach
    void clearOperators() {
        this.operatorAccess.reinterpret("");
    }

    record Signed(UUID user, String subject, String cookie, String csrf) {}

    OperatorAccess operatorAccess() {
        return this.operatorAccess;
    }

    /** allowlist 한 줄. 이 시험 컨텍스트의 Google 스텁 issuer를 쓴다. */
    static String entry(String subject, String permissions) {
        return OidcStubConfiguration.issuer() + "|" + subject + "|" + permissions;
    }

    /** 로그인한 사용자. 재인증하지 않았다. */
    Signed signedIn(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String csrf = csrfTokenOf(cookie);
        if (csrf == null) {
            throw new IllegalStateException("로그인 뒤 CSRF 토큰이 없다.");
        }
        return new Signed(user, subject, cookie, csrf);
    }

    /** 그 권한의 운영자로 세운 로그인 사용자. 재인증까지 마쳤다. */
    Signed operator(String prefix, String permissions) throws Exception {
        Signed signed = signedIn(prefix);
        this.operatorAccess.reinterpret(entry(signed.subject(), permissions));
        return reauthenticated(signed);
    }

    Signed reauthenticated(Signed signed) throws Exception {
        var response = reauthenticate(GOOGLE, signed.cookie(), signed.csrf(), plan -> plan.subject(signed.subject()));
        String rotated = sessionCookieOf(response).orElseThrow();
        String csrf = csrfTokenOf(rotated);
        if (csrf == null) {
            throw new IllegalStateException("재인증 뒤 CSRF 토큰이 없다.");
        }
        return new Signed(signed.user(), signed.subject(), rotated, csrf);
    }

    /** 세션이 회전했으면 새 쿠키·토큰으로 옮긴다. */
    Signed after(Signed signed, HttpResponse<String> response) throws Exception {
        String rotated = sessionCookieOf(response).orElse(null);
        if (rotated == null) {
            return signed;
        }
        String csrf = csrfTokenOf(rotated);
        return new Signed(signed.user(), signed.subject(), rotated, csrf == null ? "" : csrf);
    }

    /** 오늘을 끝으로 하는 KST 기간 target. 원장의 오늘 행이 모두 든다. */
    static String recentTarget() {
        LocalDate today = LocalDate.now(SEOUL);
        return today.minusDays(1) + "~" + today.plusDays(1);
    }

    static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    static String phraseOf(String target) {
        return "감사 내보내기 AUDIT_LEDGER " + target;
    }

    HttpResponse<String> prepareExport(Signed signed, String body, @Nullable String key) throws Exception {
        return sendJson("POST", EXPORT_PATH, body, signed.cookie(), headers(signed.csrf(), key));
    }

    HttpResponse<String> prepareExport(Signed signed, String target, String reason, String key) throws Exception {
        return prepareExport(signed, exportBody("AUDIT_LEDGER", target, reason), key);
    }

    HttpResponse<String> confirmExport(Signed signed, String operationId, String confirmation, @Nullable String key)
            throws Exception {
        return sendJson("POST", EXPORT_CONFIRM_PATH,
                "{\"operationId\":\"" + operationId + "\",\"confirmation\":\"" + confirmation + "\"}",
                signed.cookie(), headers(signed.csrf(), key));
    }

    HttpResponse<String> cancelExport(Signed signed) throws Exception {
        return send("POST", EXPORT_CANCEL_PATH, signed.cookie(), headers(signed.csrf(), null));
    }

    HttpResponse<String> auditGet(Signed signed, String query) throws Exception {
        return get(AUDIT_PATH + (query.isEmpty() ? "" : "?" + query), signed.cookie());
    }

    HttpResponse<String> operationGet(Signed signed, String operationId) throws Exception {
        return get(OperatorController.OPERATIONS_PATH + "/" + operationId, signed.cookie());
    }

    /** 준비 → 확정. 둘 다 200이어야 한다. */
    HttpResponse<String> export(Signed signed, String target, String key) throws Exception {
        var prepared = prepareExport(signed, target, "정기 점검 추출", key);
        if (prepared.statusCode() != 200) {
            throw new IllegalStateException("준비: " + prepared.statusCode() + " " + prepared.body());
        }
        var confirmed = confirmExport(signed, operationIdIn(prepared.body()), phraseOf(target), key);
        if (confirmed.statusCode() != 200) {
            throw new IllegalStateException("확정: " + confirmed.statusCode() + " " + confirmed.body());
        }
        return confirmed;
    }

    static String exportBody(@Nullable String scope, @Nullable String target, @Nullable String reason) {
        List<String> fields = new ArrayList<>();
        if (scope != null) {
            fields.add("\"scope\":\"" + scope + "\"");
        }
        if (target != null) {
            fields.add("\"target\":\"" + target + "\"");
        }
        if (reason != null) {
            fields.add("\"reason\":\"" + reason + "\"");
        }
        return "{" + String.join(",", fields) + "}";
    }

    static String operationIdIn(String body) {
        Object value = topLevelFieldsOf(body).get("operationId");
        return value instanceof String text ? text : "";
    }

    static String[] headers(@Nullable String csrf, @Nullable String key) {
        List<String> headers = new ArrayList<>(List.of("Origin", OidcStubConfiguration.PUBLIC_BASE_URL));
        if (csrf != null) {
            headers.add(AuthSecurityConfig.CSRF_HEADER_NAME);
            headers.add(csrf);
        }
        if (key != null) {
            headers.add("Idempotency-Key");
            headers.add(key);
        }
        return headers.toArray(String[]::new);
    }

    /** 그 operation의 결과 열, 순서대로. */
    List<String> outcomesOf(String operationId) {
        return jdbc().sql("SELECT outcome FROM operations_audit_event WHERE operation_id = :o ORDER BY sequence")
                .param("o", operationId).query(String.class).list();
    }

    /** 그 수행자의 결과·code, 순서대로({@code DENIED:CONFIRMATION_MISMATCH}). */
    List<String> decisionsOf(UUID user) {
        return jdbc().sql("SELECT outcome || COALESCE(':' || (details->>'code'), '') FROM operations_audit_event "
                        + "WHERE actor_user_id = :u ORDER BY sequence")
                .param("u", user).query(String.class).list();
    }

    /**
     * 강한 신호의 대상 소유자로 쓸 활성 계정 하나. 신호는 원장 잠금 뒤 소유자의 삭제가 시작됐는지 묻고, 없는 계정은 삭제가
     * 시작된 것으로 보아 자기 귀속을 바로 비식별한다(Epic 3 회고 B-2) — 원문 target·귀속을 보는 시험은 실제 계정을 쓴다.
     */
    UUID signalOwner() {
        return accountLifecycle().createAccount();
    }

    long auditCount() {
        return jdbc().sql("SELECT count(*) FROM operations_audit_event").query(Long.class).single();
    }

    /** 그 사용자의 허가 행 하나(운영 command). 없으면 빈 값이다. */
    @Nullable String operatorGrantOf(UUID user) {
        return jdbc().sql("SELECT grant_id FROM action_grant WHERE user_id = :u AND command = 'operator-audit-export' "
                        + "ORDER BY issued_at DESC LIMIT 1")
                .param("u", user).query(String.class).optional().orElse(null);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> itemsIn(String body, String field) {
        Object value = topLevelFieldsOf(body).get(field);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /**
     * 원장 추가 잠금을 쥔다. 확정은 소비 UPDATE 뒤 그 잠금에서 기다리므로, 쥔 동안 시험이 그 사이를 만든다 — 최종 권한
     * 재검사 직전의 회수, 같은 키의 동시 확정.
     */
    AutoCloseable holdAuditLock() throws InterruptedException {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> new TransactionTemplate(this.operatorTransactions)
                .executeWithoutResult(status -> {
                    jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                            .param("name", "bokrilog.operations_audit_event.append")
                            .query((rs, row) -> 1).single();
                    acquired.countDown();
                    try {
                        if (!release.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("감사 잠금이 풀리지 않았다.");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
        if (!acquired.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("감사 잠금을 쥐지 못했다.");
        }
        return () -> {
            release.countDown();
            holder.join(10_000);
        };
    }

    /** 동시에 보낼 요청 하나. */
    protected interface Call {
        HttpResponse<String> run() throws Exception;
    }

    /** 두 요청을 동시에 보낸다(가상 스레드 둘이 같은 출발선에서). 응답은 넘긴 순서다. */
    protected static List<HttpResponse<String>> concurrently(Call first, Call second) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> {
                start.await();
                return first.run();
            });
            var b = executor.submit(() -> {
                start.await();
                return second.run();
            });
            start.countDown();
            return List.of(a.get(), b.get());
        }
    }

    /** 그 수만큼 advisory 잠금 대기자가 생길 때까지 기다린다. */
    void awaitAdvisoryWaiters(int count) throws InterruptedException {
        int waiting = pollUntil(java.time.Duration.ofSeconds(10), () -> jdbc().sql(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                .query(Integer.class).single(), found -> found >= count);
        if (waiting < count) {
            throw new IllegalStateException("감사 잠금 대기자가 " + count + "명이 되지 않았다: " + waiting);
        }
    }

    /** 행 잠금(트랜잭션 id) 대기자가 생길 때까지 기다린다 — 같은 허가 행을 두 번째 확정이 기다린다. */
    void awaitRowLockWaiter() throws InterruptedException {
        int waiting = pollUntil(java.time.Duration.ofSeconds(10), () -> jdbc().sql(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted")
                .query(Integer.class).single(), found -> found >= 1);
        if (waiting < 1) {
            throw new IllegalStateException("허가 행 잠금 대기자가 없다.");
        }
    }

    // ------------------------------------------------------------------ Story 3.2 제한 command

    static String commandPath(String command) {
        return OperatorGate.COMMANDS_PATH + "/" + command;
    }

    HttpResponse<String> prepareRestriction(Signed signed, String command, @Nullable String scope,
            @Nullable String target, @Nullable String reason, @Nullable String key) throws Exception {
        return sendJson("POST", commandPath(command), exportBody(scope, target, reason), signed.cookie(),
                headers(signed.csrf(), key));
    }

    HttpResponse<String> confirmRestriction(Signed signed, String command, String operationId, String confirmation,
            @Nullable String key) throws Exception {
        return sendJson("POST", commandPath(command) + "/confirm",
                "{\"operationId\":\"" + operationId + "\",\"confirmation\":\"" + confirmation + "\"}",
                signed.cookie(), headers(signed.csrf(), key));
    }

    HttpResponse<String> restrictionsGet(Signed signed) throws Exception {
        return get(OperatorController.RESTRICTIONS_PATH, signed.cookie());
    }

    static String phraseIn(String body) {
        Object value = topLevelFieldsOf(body).get("confirmationPhrase");
        return value instanceof String text ? text : "";
    }

    /** 준비 → 확정. 둘 다 200이어야 한다. 확정 응답과 회전한 세션을 돌려준다. */
    record Restricted(Signed signed, HttpResponse<String> prepared, HttpResponse<String> confirmed) {}

    Restricted restrictVia(Signed signed, String command, String scope, String target, String key)
            throws Exception {
        var prepared = prepareRestriction(signed, command, scope, target, "장애 범위 제한", key);
        if (prepared.statusCode() != 200) {
            throw new IllegalStateException("준비: " + prepared.statusCode() + " " + prepared.body());
        }
        var confirmed = confirmRestriction(signed, command, operationIdIn(prepared.body()), phraseIn(prepared.body()),
                key);
        if (confirmed.statusCode() != 200) {
            throw new IllegalStateException("확정: " + confirmed.statusCode() + " " + confirmed.body());
        }
        return new Restricted(after(signed, confirmed), prepared, confirmed);
    }

    long restrictionCount() {
        return jdbc().sql("SELECT count(*) FROM operations_restriction").query(Long.class).single();
    }
}
