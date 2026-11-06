package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevOidcStub.TokenPlan;
import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountDeletionApi.DeletionRequestOutcome;
import com.bokrilog.identity.AccountDeletionRunApi.RunStatus;
import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.identity.AccountLifecycleApi.DeletedIdentityState;
import com.bokrilog.identity.DeletedIdentityDigests;
import com.bokrilog.identity.DeletionOutcome;
import com.bokrilog.identity.DeletionStep;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.sync.WorkerLease;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 5.4: 삭제가 끝난 계정이 다시 살아나지 않고, 요청 세션이 완료 확인 전용 세션으로 남아 서버 값으로 완료를 안내한다 — 실제 스택(Docker
 * PostgreSQL, local OIDC·Bybit 스텁)에서 HTTP로. 매트릭스의 같은 identity 재로그인·삭제 identity 연결·완료 세션·옛 쿠키·확정 재전송·전체
 * 완료·기한 초과·삭제 뒤 거절 행을 본다(복원은 {@code RestoreApplyTest}가 본다).
 */
class DeletionCompletionTest extends PlanSyncTestBase {

    private static final String DELETION_PATH = AccountDeletionController.DELETION_PATH;

    private static final String CONFIRM_PATH = AccountDeletionController.CONFIRM_PATH;

    private static final String PHRASE = "계정을 삭제합니다";

    private static final String VERSION = "ACCOUNT-DELETION-1.0";

    /** 일반 미인증 세션 확인 응답. 완료 세션의 표지가 다른 응답에 새지 않는다. */
    private static final String UNAUTHENTICATED = "{\"authenticated\":false,\"destination\":\"entry\"}";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 완료 세션이 남기는 두 속성 — Spring Security 저장소의 기본 이름과 같은 식(클래스 이름 + 접미사)으로 만든다. */
    private static final String CSRF_ATTRIBUTE = HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN";

    private static final String AUTHORIZATION_REQUEST_ATTRIBUTE =
            HttpSessionOAuth2AuthorizationRequestRepository.class.getName() + ".AUTHORIZATION_REQUEST";

    @Autowired AccountErasureWorker worker;

    @Autowired AccountDeletionRunApi runs;

    @Autowired AccountErasure erasure;

    @Autowired BackupDestructionJudge judge;

    @Autowired OperatorCommandApi commands;

    @Autowired DeletedIdentityDigests deletedIdentities;

    record Signed(UUID user, String subject, String cookie, String csrf) {}

    /** 삭제가 끝난 사용자와 그 요청 세션. */
    record Deleted(Signed signed, String requestId, String key, String otherCookie) {}

    @AfterEach
    void restoreLegalRetention() {
        this.judge.reinterpretLegalRetention(AccountDeletionPolicy.LEGAL_RETENTION);
    }

    // ------------------------------------------------------------------------------------------ 완료 세션

    /**
     * 매트릭스 "완료 세션"·"옛 쿠키"·AC-1: 삭제가 끝나는 커밋에서 요청 세션은 사용자 ID·인증 문맥 없이 요청 ID 하나로만 이어진 세션이 된다.
     * 그 세션은 완료 조회(서버 값 — 운영 삭제 시각·백업 파기 기한·상태·법정 보존·지원 연락처)와 로그아웃만 하고, 나머지 경로는 미인증과 같다.
     * 다른 세션(옛 쿠키)은 일반 미인증이다. 어떤 세션 행도 그 사용자 ID를 담지 않고 모든 모듈의 잔여는 0이다.
     */
    @Test
    void 요청_세션은_사용자_ID_없는_완료_세션이_되어_완료_조회와_로그아웃만_한다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-session");
        Signed signed = deleted.signed();
        UUID user = signed.user();

        // 세션 행: 그 사용자를 가리키는 행이 없고, 완료 세션 하나만 요청 ID로 이어진다(속성은 CSRF 토큰 하나).
        assertThat(sessionCountOf(user)).isZero();
        assertThat(accountDeletion().remainingRowsOf(user)).isZero();
        for (DeletionStep step : DeletionStep.values()) {
            assertThat(this.erasure.residueOf(step, user)).as(step.name()).isZero();
        }
        String sessionId = sessionIdOf(signed.cookie());
        assertThat(jdbc().sql("SELECT completion.request_id || '|' || (session.principal_name IS NULL) FROM "
                        + "identity_deletion_completion_session AS completion JOIN spring_session AS session "
                        + "ON session.primary_id = completion.session_primary_id WHERE session.session_id = :s")
                .param("s", sessionId).query(String.class).single()).isEqualTo(deleted.requestId() + "|true");
        assertThat(jdbc().sql("SELECT attribute_name FROM spring_session_attributes AS attribute JOIN spring_session AS "
                        + "session ON session.primary_id = attribute.session_primary_id WHERE session.session_id = :s")
                .param("s", sessionId).query(String.class).list())
                .containsExactly(CSRF_ATTRIBUTE);
        assertThat(jdbc().sql("SELECT count(*) FROM spring_session_attributes WHERE position(:u IN "
                        + "encode(attribute_bytes, 'escape')) > 0").param("u", user.toString()).query(Integer.class)
                .single()).isZero();

        // 세션 확인: 미인증이지만 완료 표지와 로그아웃용 토큰이 실린다(세션에 남긴 같은 토큰 — 응답마다 가림 값만 다르다. 아래 로그아웃이
        // 삭제 전에 받은 토큰으로 통과한다).
        var state = sessionState(signed.cookie());
        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(state.body())).containsOnlyKeys("authenticated", "destination", "csrfToken",
                "deletionCompleted").containsEntry("authenticated", false).containsEntry("deletionCompleted", true);
        assertThat(csrfTokenIn(state.body())).isNotBlank();
        // 일반 미인증·옛 쿠키는 글자까지 그대로다.
        assertThat(sessionState(null).body()).isEqualTo(UNAUTHENTICATED);
        assertThat(sessionState(deleted.otherCookie()).body()).isEqualTo(UNAUTHENTICATED);
        assertThat(get(DELETION_PATH, deleted.otherCookie()).statusCode()).isEqualTo(401);
        assertThat(get(DELETION_PATH, null).statusCode()).isEqualTo(401);

        // 완료 조회: 서버 값만. 요청은 없고 완료가 있다. 사용자 ID가 없다.
        var completion = get(DELETION_PATH, signed.cookie());
        assertThat(completion.statusCode()).as(completion.body()).isEqualTo(200);
        assertThat(completion.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(completion.body()).doesNotContain(user.toString()).doesNotContain(signed.subject());
        JsonNode view = json(completion.body());
        assertThat(view.get("request").isNull()).isTrue();
        assertThat(view.get("policy").get("legalRetention").isEmpty()).isTrue();
        assertThat(view.get("supportContact").asString()).isEqualTo(OperatorTestProperties.SUPPORT_CONTACT);
        JsonNode done = view.get("completion");
        assertThat(done.get("requestId").asString()).isEqualTo(deleted.requestId());
        Instant requestedAt = Instant.parse(done.get("requestedAt").asString());
        assertThat(Instant.parse(done.get("backupDeadline").asString())).isEqualTo(requestedAt.plus(Duration.ofDays(30)));
        Instant erasedAt = Instant.parse(done.get("operationalErasedAt").asString());
        assertThat(erasedAt).isEqualTo(this.runs.runOf(user).orElseThrow().operationalErasedAt());
        assertThat(done.get("backupDestruction").asString()).isEqualTo("PENDING");
        assertThat(done.get("backupDestroyedAt").isNull()).isTrue();
        assertThat(done.get("deletionCompleted").asBoolean()).isFalse();
        assertThat(done.get("deletionCompletedAt").isNull()).isTrue();

        // 다른 API는 미인증과 같다 — 데이터가 없다.
        assertThat(get("/api/dashboard", signed.cookie()).statusCode()).isEqualTo(401);
        // 연결 상태 조회는 주체 없는 세션에 403(미인증과 같은 계약), 다시 시도는 CSRF를 지난 뒤 인가 규칙의 401이다.
        assertThat(get("/api/auth/link", signed.cookie()).statusCode()).isEqualTo(403);
        var retry = send("POST", AccountDeletionController.RETRY_PATH, signed.cookie(),
                headers(signed.csrf(), "completion-retry-" + UUID.randomUUID()));
        assertThat(retry.statusCode()).isEqualTo(401);

        // 로그아웃: 세 조건(행 삭제·컨텍스트·쿠키 만료)을 그대로 만족한다. 그 뒤로는 일반 미인증이다.
        var logout = logout(signed.cookie(), signed.csrf());
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(setCookieOf(logout).orElse("")).contains("Max-Age=0");
        assertThat(jdbc().sql("SELECT count(*) FROM spring_session WHERE session_id = :s").param("s", sessionId)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM identity_deletion_completion_session").query(Integer.class).single())
                .isZero();
        assertThat(sessionState(signed.cookie()).body()).isEqualTo(UNAUTHENTICATED);
        assertThat(logout(signed.cookie(), signed.csrf()).statusCode()).isEqualTo(403);
    }

    /**
     * 매트릭스 "확정 재전송"·"같은 identity 재로그인": 삭제가 끝난 뒤 같은 쿠키·같은 키의 확정 재전송은 새 거부 code 하나이고 아무것도
     * 만들지 않는다. 같은 소셜 identity로 다시 로그인하면 계정·매핑·세션을 만들지 않고 표지 {@code deleted}로 닫힌다. 대기 Worker도 집을
     * 것이 없다.
     */
    @Test
    void 삭제가_끝난_뒤의_재전송과_같은_identity_로그인은_아무것도_다시_만들지_않는다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-replay");
        Signed signed = deleted.signed();
        long accounts = accountCount();
        long links = socialLinkCount();

        var replay = sendJson("POST", CONFIRM_PATH, body(VERSION, PHRASE), signed.cookie(),
                headers(signed.csrf(), deleted.key()));
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(replay.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", AccountDeletionController.COMPLETED);
        assertThat(countOf("identity_deletion_request", signed.user())).isZero();
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETED");
        assertThat(this.worker.runOnce()).isNull();

        var again = signIn(signed.subject());
        assertThat(again.statusCode()).isEqualTo(302);
        assertThat(locationOf(again)).isEqualTo("/?auth=" + AuthFlowHandlers.FAILURE_MARKER_DELETED);
        assertThat(accountCount()).isEqualTo(accounts);
        assertThat(socialLinkCount()).isEqualTo(links);
        assertThat(jdbc().sql("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL").query(Integer.class)
                .single()).isZero();
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETED");

        // 삭제가 시작됐지만 끝나지 않은 계정은 그대로 "failed" 하나다(삭제한 identity만 구분된다).
        Signed pending = signedIn("completion-pending");
        assertThat(accountDeletion().requestDeletion(pending.user(), AccountDeletionPolicy.VERSION,
                AccountDeletionPolicy.noticeHash(), sha256("completion-pending-" + UUID.randomUUID())))
                .isInstanceOf(DeletionRequestOutcome.Created.class);
        assertThat(locationOf(signIn(pending.subject()))).isEqualTo("/?auth=" + AuthFlowHandlers.FAILURE_MARKER_FAILED);
    }

    /**
     * 매트릭스 "삭제 identity 연결": 다른 활성 계정이 삭제가 끝난 계정의 identity를 연결하려 해도 거부되고 매핑이 생기지 않는다. 재가입
     * 차단 기록에는 원 식별자도 사용자 열도 없다(digest 하나와 기록 시각뿐이다).
     */
    @Test
    void 삭제한_identity는_다른_계정에도_연결되지_않고_기록에는_digest만_있다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-link");
        String issuer = OidcStubConfiguration.issuer();
        String subject = deleted.signed().subject();
        assertThat(accountLifecycle().isDeletedIdentity(issuer, subject)).isTrue();
        assertThat(accountLifecycle().isDeletedIdentity(issuer, subject + "-other")).isFalse();

        UUID other = accountLifecycle().createAccount();
        assertThat(accountLifecycle().linkSocialIdentity(other, "google", issuer, subject)).isEqualTo(AccessDecision.DENIED);
        assertThat(countOf("identity_social_link", other)).isZero();
        // 대조: 삭제되지 않은 identity는 같은 계정에 연결된다.
        assertThat(accountLifecycle().linkSocialIdentity(other, "google", issuer, "completion-fresh-" + UUID.randomUUID()))
                .isEqualTo(AccessDecision.ALLOWED);

        assertThat(jdbc().sql("SELECT column_name FROM information_schema.columns WHERE table_name = "
                + "'identity_deleted_identity' ORDER BY ordinal_position").query(String.class).list())
                .containsExactly("identity_digest", "request_id", "recorded_at");
        // 요청 ID는 삭제 요청의 불투명 ID(사용자 ID가 아니다)이고 단계 모드 실행의 것이다 — 표지 `deleted`가 그 계정의 삭제 완료를 볼 근거다.
        assertThat(jdbc().sql("SELECT request_id FROM identity_deleted_identity").query(String.class).single())
                .isEqualTo(deleted.requestId());
        assertThat(jdbc().sql("SELECT identity_digest FROM identity_deleted_identity").query(String.class).list())
                .singleElement().satisfies(digest -> assertThat(digest).matches("[0-9a-f]{64}")
                        .doesNotContain(subject).isNotEqualTo(sha256(issuer + subject)));
        assertThat(jdbc().sql("SELECT column_name FROM information_schema.columns WHERE table_name = "
                + "'identity_deletion_completion_session' ORDER BY ordinal_position").query(String.class).list())
                .containsExactly("session_primary_id", "request_id");
    }

    // ------------------------------------------------------------------------------------------ 전체 삭제 완료

    /**
     * 매트릭스 "전체 완료"·"기한 초과"·AC-2: 백업 파기가 완료({@code DESTROYED})되고 법정 분리 보존이 처리돼야만(목록이 비면 충족) 판정자가
     * identity에 전체 삭제 완료를 기록한다. 초과({@code OVERDUE})는 완료가 아니고 화면에 초과로 실린다. 보존 목록이 비지 않은 정책에서는
     * {@code DESTROYED}여도 기록하지 않는다. 다시 판정해도 멱등이다.
     */
    @Test
    void 백업_파기_완료와_법정_보존_처리가_있어야만_전체_삭제_완료다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-full");
        UUID user = deleted.signed().user();
        String requestId = deleted.requestId();
        assertThat(this.judge.completeNow()).isZero();
        assertThat(this.runs.runOf(user).orElseThrow().status()).isEqualTo(RunStatus.OPERATIONAL_DATA_ERASED);

        // 기한 초과: 완료가 아니고, 화면에는 초과로 실린다.
        jdbc().sql("UPDATE operations_deletion_tombstone SET status = 'OVERDUE', overdue_at = statement_timestamp() "
                + "WHERE request_id = :r").param("r", requestId).update();
        assertThat(this.judge.completeNow()).isZero();
        JsonNode overdue = completionOf(deleted.signed().cookie());
        assertThat(overdue.get("backupDestruction").asString()).isEqualTo("OVERDUE");
        assertThat(overdue.get("deletionCompleted").asBoolean()).isFalse();

        // 백업 파기 완료. 법정 분리 보존 항목이 있는 정책(시험용)에서는 증거가 없으므로 완료를 기록하지 않는다.
        jdbc().sql("UPDATE operations_deletion_tombstone SET status = 'DESTROYED', destroyed_at = statement_timestamp() "
                + "WHERE request_id = :r").param("r", requestId).update();
        this.judge.reinterpretLegalRetention(List.of(new AccountDeletionPolicy.LegalRetention("거래 기록",
                "시험용 근거", "시험용 목적", "5년")));
        assertThat(this.judge.completeNow()).isZero();
        assertThat(this.runs.runOf(user).orElseThrow().status()).isEqualTo(RunStatus.OPERATIONAL_DATA_ERASED);
        assertThat(completionOf(deleted.signed().cookie()).get("deletionCompleted").asBoolean()).isFalse();

        // 보존 항목이 없으면(지금 정책) 완료다 — 판정 한 번(judgeNow)이 sweep으로 옮긴다. 다시 판정해도 같다.
        this.judge.reinterpretLegalRetention(List.of());
        this.judge.judgeNow();
        var run = this.runs.runOf(user).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.DELETION_COMPLETED);
        assertThat(run.operationalErasedAt()).isNotNull();
        var completed = this.runs.completedDeletionOf(requestId).orElseThrow();
        assertThat(completed.deletionCompletedAt()).isNotNull().isAfterOrEqualTo(completed.operationalErasedAt());
        assertThat(this.judge.completeNow()).isZero();
        assertThat(this.runs.completedDeletionOf(requestId).orElseThrow()).isEqualTo(completed);

        JsonNode view = completionOf(deleted.signed().cookie());
        assertThat(view.get("deletionCompleted").asBoolean()).isTrue();
        assertThat(Instant.parse(view.get("deletionCompletedAt").asString())).isEqualTo(completed.deletionCompletedAt());
        assertThat(view.get("backupDestruction").asString()).isEqualTo("DESTROYED");
        assertThat(view.get("backupDestroyedAt").isNull()).isFalse();
        // 운영 완료 시각은 그대로 남는다(전체 완료와 다른 시각).
        assertThat(Instant.parse(view.get("operationalErasedAt").asString())).isEqualTo(completed.operationalErasedAt());
        // 운영 완료 전 실행은 판정자가 넘겨도 바뀌지 않는다(단조).
        assertThat(this.runs.recordDeletionCompleted(List.of("completion-unknown-1"))).isZero();

        // T1: 전체 완료 뒤 단일 모드로 다시 지워도(복원 재삭제 관용) 예외 없이 ERASED이고 실행의 상태·두 시각은 그대로다.
        assertThat(this.erasure.erase(user, "completion-again-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        var after = this.runs.runOf(user).orElseThrow();
        assertThat(after.status()).isEqualTo(RunStatus.DELETION_COMPLETED);
        assertThat(after.operationalErasedAt()).isEqualTo(completed.operationalErasedAt());
        assertThat(this.runs.completedDeletionOf(requestId).orElseThrow()).isEqualTo(completed);
    }

    // ------------------------------------------------------------------------------------------ 리뷰 보강(1차)

    /**
     * 표지 `deleted`는 삭제가 <b>끝난</b> 계정에만 나간다: 로그인 매핑을 지운 뒤(CREDENTIALS 성공) 뒤 단계가 재시도 필요로 멈춘 진행 중 계정의
     * 같은 identity 로그인은 계정을 만들지 않고 `failed`다. 그 사이 다른 계정의 연결도 막힌다(기록은 이미 있다).
     */
    @Test
    void 삭제가_끝나기_전의_같은_identity_로그인은_계정을_만들지_않고_failed다() throws Exception {
        Signed signed = signedIn("completion-unfinished");
        assertThat(accountDeletion().requestDeletion(signed.user(), AccountDeletionPolicy.VERSION,
                AccountDeletionPolicy.noticeHash(), sha256("completion-unfinished-" + UUID.randomUUID())))
                .isInstanceOf(DeletionRequestOutcome.Created.class);
        AccountErasureRun failing = new AccountErasureRun(new AccountErasure(accountDeletion(), this.tombstones,
                this.portfolio, this.sync, this.plans, this.source, this.operations, failingEngagement(), this.manager),
                this.runs, this.manager);
        var lease = this.runs.claim(WorkerLease.DURATION).orElseThrow();
        assertThat(failing.execute(lease, () -> false)).isEqualTo(AccountErasureRun.Result.RETRY_NEEDED);
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETION_IN_PROGRESS");
        assertThat(countOf("identity_social_link", signed.user())).isZero();
        String issuer = OidcStubConfiguration.issuer();
        assertThat(accountLifecycle().deletedIdentityStateOf(issuer, signed.subject()))
                .isEqualTo(DeletedIdentityState.DELETION_UNFINISHED);
        assertThat(accountLifecycle().isDeletedIdentity(issuer, signed.subject())).isFalse();
        long accounts = accountCount();

        var again = signIn(signed.subject());
        assertThat(locationOf(again)).isEqualTo("/?auth=" + AuthFlowHandlers.FAILURE_MARKER_FAILED);
        assertThat(accountCount()).isEqualTo(accounts);
        UUID other = accountLifecycle().createAccount();
        assertThat(accountLifecycle().linkSocialIdentity(other, "google", issuer, signed.subject()))
                .isEqualTo(AccessDecision.DENIED);
    }

    /**
     * 완료 세션에서 다른 identity로 로그인하면(세션 ID 회전은 행 키를 보존한다) 완료 연결이 지워지고, 새 인증 세션은 완료 표지도 옛 요청의 완료
     * 조회도 받지 않는다.
     */
    @Test
    void 완료_세션에서_다른_identity로_로그인하면_완료_연결이_남지_않는다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-relogin");
        String otherSubject = "completion-relogin-other-" + UUID.randomUUID();
        var authorization = startAuthorization(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, null, deleted.signed().cookie());
        var landed = callback(authorization,
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(otherSubject)));
        assertThat(landed.statusCode()).isEqualTo(302);
        String cookie = sessionCookieOf(landed).orElse(authorization.sessionCookie());

        var state = sessionState(cookie);
        assertThat(topLevelFieldsOf(state.body())).containsEntry("authenticated", true).doesNotContainKey("deletionCompleted");
        JsonNode view = json(get(DELETION_PATH, cookie).body());
        assertThat(view.has("completion")).isFalse();
        assertThat(view.get("request").isNull()).isTrue();
        assertThat(jdbc().sql("SELECT count(*) FROM identity_deletion_completion_session").query(Integer.class).single())
                .isZero();
    }

    /**
     * 완료 세션은 요청 행의 세션 행 키(primary_id)로 찾는다 — 확정 뒤 세션 ID가 회전해도 그 세션이 완료 세션이 된다. 요청 세션이 이미 없으면
     * (로그아웃) 바꿀 세션 없이 사실 한 줄만 남는다(사용자·세션 식별자 없음).
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 완료_세션은_세션_행_키로_찾아_회전에_강하고_없으면_한_줄을_남긴다(
            CapturedOutput output) throws Exception {
        Signed signed = signedIn("completion-rotated");
        confirmThroughHttp(signed, "completion-rotated");
        String oldId = sessionIdOf(signed.cookie());
        String primary = jdbc().sql("SELECT primary_id FROM spring_session WHERE session_id = :s").param("s", oldId)
                .query(String.class).single();
        assertThat(jdbc().sql("SELECT session_primary_id FROM identity_deletion_request WHERE user_id = :u")
                .param("u", signed.user()).query(String.class).single()).isEqualTo(primary);
        // 세션 ID 회전(쿠키 값만 바뀐다 — 행 키는 그대로다).
        String rotatedId = UUID.randomUUID().toString();
        jdbc().sql("UPDATE spring_session SET session_id = :n WHERE session_id = :o").param("n", rotatedId)
                .param("o", oldId).update();
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        String rotatedCookie = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rotatedId.getBytes(StandardCharsets.UTF_8));
        assertThat(topLevelFieldsOf(sessionState(rotatedCookie).body())).containsEntry("deletionCompleted", true);

        // 요청 세션이 먼저 끝난 삭제: 완료 세션 없이 한 줄.
        Signed gone = signedIn("completion-gone");
        confirmThroughHttp(gone, "completion-gone");
        assertThat(logout(gone.cookie(), gone.csrf()).statusCode()).isEqualTo(204);
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(output.getAll()).contains("REQUEST_SESSION_GONE").doesNotContain(gone.user().toString());
        assertThat(jdbc().sql("SELECT count(*) FROM identity_deletion_completion_session").query(Integer.class).single())
                .isEqualTo(1);
    }

    /**
     * 삭제 커밋과 겹친 요청의 저장이 완료 세션에 옛 주체 이름·속성을 되살려도 판정 주기의 정리가 비운다(CSRF 토큰은 남는다). 그 뒤에도 완료
     * 세션은 완료 표지를 받는다.
     */
    @Test
    void 되살아난_주체_이름은_판정_주기의_정리가_비운다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-scrub");
        UUID user = deleted.signed().user();
        String sessionId = sessionIdOf(deleted.signed().cookie());
        String primary = jdbc().sql("SELECT primary_id FROM spring_session WHERE session_id = :s").param("s", sessionId)
                .query(String.class).single();
        jdbc().sql("UPDATE spring_session SET principal_name = :u WHERE primary_id = :p").param("u", user.toString())
                .param("p", primary).update();
        jdbc().sql("INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes) "
                + "VALUES (:p, 'bokrilog.test.leftover', decode('00', 'hex'))").param("p", primary).update();
        assertThat(accountDeletion().remainingRowsOf(user)).isEqualTo(1);

        assertThat(this.judge.scrubNow()).isEqualTo(1);
        assertThat(accountDeletion().remainingRowsOf(user)).isZero();
        assertThat(jdbc().sql("SELECT attribute_name FROM spring_session_attributes WHERE session_primary_id = :p")
                .param("p", primary).query(String.class).list())
                .containsExactly(CSRF_ATTRIBUTE);
        assertThat(this.judge.scrubNow()).isZero();
        assertThat(topLevelFieldsOf(sessionState(deleted.signed().cookie()).body())).containsEntry("deletionCompleted", true);
    }

    /** 지원 연락처(170)는 요청이 있는 계정의 조회에 실리고 요청 없는 조회에는 없다. */
    @Test
    void 지원_연락처는_요청이_있을_때만_진행_조회에_실린다() throws Exception {
        Signed signed = signedIn("completion-support");
        assertThat(json(get(DELETION_PATH, signed.cookie()).body()).has("supportContact")).isFalse();
        confirmThroughHttp(signed, "completion-support");
        JsonNode view = json(get(DELETION_PATH, signed.cookie()).body());
        assertThat(view.get("request").isNull()).isFalse();
        assertThat(view.get("supportContact").asString()).isEqualTo(OperatorTestProperties.SUPPORT_CONTACT);
    }

    /** deferred 162: 커밋 뒤 별도 트랜잭션의 요청 행도 원장 잠금 뒤 수행자 계정의 삭제 시작을 보고 귀속 없이 쓴다. */
    @Test
    void 삭제가_시작된_수행자의_요청_행도_귀속_없이_쓰인다() throws Exception {
        UUID erased = signedIn("requested-erased").user();
        assertThat(accountDeletion().beginDeletion(erased)).isEqualTo(DeletionOutcome.COMPLETED);
        var envelope = new OperatorCommandApi.Envelope(this.commands.newOperationId(), erased, "audit-export", "AUDIT",
                "ALL", "요청 시험", "", "OPS-1.0", null, sha256("requested-key-" + UUID.randomUUID()), "");
        this.commands.recordRequested(envelope, "requested-correlation-1");
        assertThat(attributionOf(envelope.operationId())).isEqualTo("-|true");
    }

    // ------------------------------------------------------------------------------------------ 리뷰 보강(2차)

    /**
     * 정리는 되살아난 원래 사용자만 비운다: 완료 연결이 남은 세션이 <b>다른 사용자로</b> 인증돼 있으면(인증 성공의 연결 정리가 실패한 경우)
     * 그 세션의 주체·속성은 건드리지 않고 완료 연결 행만 지운다.
     */
    @Test
    void 다른_사용자로_인증된_세션은_정리가_건드리지_않고_완료_연결만_지운다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-scrub-other");
        UUID other = signedIn("completion-scrub-other-user").user();
        String primary = primaryIdOf(deleted.signed().cookie());
        jdbc().sql("UPDATE spring_session SET principal_name = :u WHERE primary_id = :p").param("u", other.toString())
                .param("p", primary).update();
        jdbc().sql("INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes) "
                + "VALUES (:p, 'bokrilog.test.other-user', decode('00', 'hex'))").param("p", primary).update();

        assertThat(this.judge.scrubNow()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT principal_name FROM spring_session WHERE primary_id = :p").param("p", primary)
                .query(String.class).single()).isEqualTo(other.toString());
        assertThat(jdbc().sql("SELECT attribute_name FROM spring_session_attributes WHERE session_primary_id = :p")
                .param("p", primary).query(String.class).list()).contains("bokrilog.test.other-user", CSRF_ATTRIBUTE);
        assertThat(completionLinks()).isZero();
        assertThat(this.judge.scrubNow()).isZero();
    }

    /**
     * 완료 세션에서 시작한 로그인의 진행 흐름(OAuth2 인가 요청 속성)은 정리가 남긴다 — 정리 뒤의 콜백이 그 흐름으로 다른 identity의 로그인을
     * 끝낸다(속성 이름이 Spring이 실제로 쓰는 이름이라는 대조이기도 하다).
     */
    @Test
    void 정리는_진행_중인_로그인의_인가_요청을_남긴다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-scrub-authorize");
        UUID user = deleted.signed().user();
        var authorization = startAuthorization(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, null, deleted.signed().cookie());
        String cookie = authorization.sessionCookie() == null ? deleted.signed().cookie() : authorization.sessionCookie();
        String primary = primaryIdOf(cookie);
        jdbc().sql("UPDATE spring_session SET principal_name = :u WHERE primary_id = :p").param("u", user.toString())
                .param("p", primary).update();

        assertThat(this.judge.scrubNow()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT attribute_name FROM spring_session_attributes WHERE session_primary_id = :p")
                .param("p", primary).query(String.class).list())
                .containsExactlyInAnyOrder(CSRF_ATTRIBUTE, AUTHORIZATION_REQUEST_ATTRIBUTE);
        assertThat(accountDeletion().remainingRowsOf(user)).isZero();

        var landed = callback(authorization, issueCode(authorization,
                TokenPlan.valid(authorization.nonce()).subject("completion-scrub-authorize-other-" + UUID.randomUUID())));
        assertThat(landed.statusCode()).isEqualTo(302);
        assertThat(locationOf(landed)).doesNotContain("auth=");
        String signedIn = sessionCookieOf(landed).orElse(cookie);
        assertThat(topLevelFieldsOf(sessionState(signedIn).body())).containsEntry("authenticated", true)
                .doesNotContainKey("deletionCompleted");
    }

    /**
     * 재가입 차단 기록의 요청 ID에 해당하는 실행 행이 없으면(보존 정리·다른 세대 복원 뒤) 끝난 삭제로 본다 — 진행 중인 계정에는 언제나 실행
     * 행이 있기 때문이다. 요청 ID가 비어 있어도(단일 모드) 끝난 삭제다.
     */
    @Test
    void 실행_행이_없는_차단_기록은_끝난_삭제다() {
        String issuer = OidcStubConfiguration.issuer();
        String orphan = "completion-orphan-" + UUID.randomUUID();
        String single = "completion-single-" + UUID.randomUUID();
        jdbc().sql("INSERT INTO identity_deleted_identity (identity_digest, request_id) VALUES (:d, :r)")
                .param("d", this.deletedIdentities.digestOf(issuer, orphan).orElseThrow())
                .param("r", "del_completion-no-run").update();
        jdbc().sql("INSERT INTO identity_deleted_identity (identity_digest) VALUES (:d)")
                .param("d", this.deletedIdentities.digestOf(issuer, single).orElseThrow()).update();

        assertThat(accountLifecycle().deletedIdentityStateOf(issuer, orphan)).isEqualTo(DeletedIdentityState.DELETED);
        assertThat(accountLifecycle().deletedIdentityStateOf(issuer, single)).isEqualTo(DeletedIdentityState.DELETED);
        assertThat(accountLifecycle().deletedIdentityStateOf(issuer, "completion-none-" + UUID.randomUUID()))
                .isEqualTo(DeletedIdentityState.NONE);
    }

    /** 완료 세션도 세션 수명(절대 12시간)을 따른다 — 넘으면 일반 미인증으로 폐기되고 완료 연결도 함께 사라진다. */
    @Test
    void 절대_수명이_지난_완료_세션은_완료_표지를_받지_않는다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-aged");
        ageCreation(sessionIdOf(deleted.signed().cookie()), sessionGuard().absoluteLifetime().plusMinutes(1));

        assertThat(sessionState(deleted.signed().cookie()).body()).isEqualTo(UNAUTHENTICATED);
        assertThat(get(DELETION_PATH, deleted.signed().cookie()).statusCode()).isEqualTo(401);
        assertThat(completionLinks()).isZero();
    }

    /**
     * 완료 세션에서 <b>같은</b>(삭제한) identity로 다시 로그인하면 표지 {@code deleted}로 닫히고, 그 실패 수렴이 세션을 폐기하므로 완료 세션도
     * 함께 사라진다(세션 정책은 바꾸지 않는다 — 완료 안내는 그 세션의 수명 동안만이다).
     */
    @Test
    void 완료_세션에서_삭제한_identity로_다시_로그인하면_완료_세션도_닫힌다() throws Exception {
        Deleted deleted = deleteThroughHttp("completion-relogin-same");
        long accounts = accountCount();
        var authorization = startAuthorization(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, null, deleted.signed().cookie());
        var landed = callback(authorization,
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(deleted.signed().subject())));
        assertThat(landed.statusCode()).isEqualTo(302);
        assertThat(locationOf(landed)).isEqualTo("/?auth=" + AuthFlowHandlers.FAILURE_MARKER_DELETED);

        assertThat(accountCount()).isEqualTo(accounts);
        assertThat(completionLinks()).isZero();
        assertThat(sessionState(deleted.signed().cookie()).body()).isEqualTo(UNAUTHENTICATED);
    }

    // ------------------------------------------------------------------------------------------ deferred 162

    /**
     * 매트릭스 "삭제 뒤 거절 행"(deferred 162): 운영 command의 거절·실패 행은 롤백 뒤 따로 쓰이므로 그 사이 수행자 계정의 삭제가 시작됐을 수
     * 있다. 원장 추가 잠금 뒤 계정 상태를 보고, 삭제가 시작됐으면 수행자 귀속 없이(비식별 시각과 함께) 쓴다 — 활성 계정의 행은 그대로 귀속된다.
     */
    @Test
    void 삭제가_시작된_수행자의_거절_행은_귀속_없이_쓰인다() throws Exception {
        UUID active = signedIn("refusal-active").user();
        UUID erased = signedIn("refusal-erased").user();
        assertThat(accountDeletion().beginDeletion(erased)).isEqualTo(DeletionOutcome.COMPLETED);

        String activeOperation = this.commands.recordRefusedProposal(active,
                new OperatorCommandApi.Proposal("audit-export", "AUDIT", "ALL", "거절 시험", false), "TEST_REFUSED",
                sha256("refusal-key-" + UUID.randomUUID()), "refusal-correlation-1");
        String erasedOperation = this.commands.recordRefusedProposal(erased,
                new OperatorCommandApi.Proposal("audit-export", "AUDIT", "ALL", "거절 시험", false), "TEST_REFUSED",
                sha256("refusal-key-" + UUID.randomUUID()), "refusal-correlation-2");
        var envelope = new OperatorCommandApi.Envelope(this.commands.newOperationId(), erased, "audit-export", "AUDIT",
                "ALL", "실패 시험", "", "OPS-1.0", null, sha256("refusal-key-" + UUID.randomUUID()), "");
        this.commands.recordDecision(envelope, OperatorCommandApi.AuditOutcome.FAILED, "EXECUTION_FAILED",
                "refusal-correlation-3");

        assertThat(attributionOf(activeOperation)).isEqualTo(active + "|false");
        assertThat(attributionOf(erasedOperation)).isEqualTo("-|true");
        assertThat(attributionOf(envelope.operationId())).isEqualTo("-|true");
        // 수행자 digest는 그대로 남아 키를 가진 쪽만 같은 수행자의 행을 묶는다.
        assertThat(jdbc().sql("SELECT actor_digest FROM operations_audit_event WHERE operation_id = :o")
                .param("o", erasedOperation).query(String.class).single()).matches("[0-9a-f]{64}");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE actor_user_id = :u").param("u", erased)
                .query(Integer.class).single()).isZero();
    }

    // ------------------------------------------------------------------------------------------ 도구

    @Autowired DeletionTombstoneApi tombstones;

    @Autowired OperationsDeletionApi operations;

    @Autowired EngagementDeletionApi engagement;

    /** 광고 동의 삭제에서 실패하는 engagement port(ENGAGEMENT 단계가 재시도 필요로 멈춘다 — CREDENTIALS는 이미 성공했다). */
    private EngagementDeletionApi failingEngagement() {
        var delegate = this.engagement;
        return (EngagementDeletionApi) Proxy.newProxyInstance(EngagementDeletionApi.class.getClassLoader(),
                new Class<?>[] {EngagementDeletionApi.class}, (proxy, method, args) -> {
                    if (method.getName().equals("eraseConsents")) {
                        throw new IllegalStateException("injected");
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** 실제 HTTP로 준비·확정만 한다(Worker는 돌리지 않는다). */
    private String confirmThroughHttp(Signed signed, String prefix) throws Exception {
        var prepared = sendJson("POST", DELETION_PATH, "{\"policyVersion\":\"" + VERSION + "\"}", signed.cookie(),
                headers(signed.csrf(), null));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        String key = prefix + "-confirm-" + UUID.randomUUID();
        var confirmed = sendJson("POST", CONFIRM_PATH, body(VERSION, PHRASE), signed.cookie(), headers(signed.csrf(), key));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        return key;
    }

    /**
     * 실제 HTTP로 삭제를 요청하고(같은 사용자의 다른 세션 하나를 먼저 만든다 — 확정이 그것을 끊는다) Worker 한 번으로 끝까지 지운다.
     */
    private Deleted deleteThroughHttp(String prefix) throws Exception {
        Signed signed = signedIn(prefix);
        String other = sessionCookieOf(signIn(signed.subject())).orElseThrow();
        var prepared = sendJson("POST", DELETION_PATH, "{\"policyVersion\":\"" + VERSION + "\"}", signed.cookie(),
                headers(signed.csrf(), null));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        String key = prefix + "-confirm-" + UUID.randomUUID();
        var confirmed = sendJson("POST", CONFIRM_PATH, body(VERSION, PHRASE), signed.cookie(), headers(signed.csrf(), key));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        String requestId = json(confirmed.body()).get("requestId").asString();
        assertThat(this.worker.runOnce()).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETED");
        return new Deleted(signed, requestId, key, other);
    }

    private JsonNode completionOf(String cookie) throws Exception {
        var response = get(DELETION_PATH, cookie);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response.body()).get("completion");
    }

    private Signed signedIn(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        var reauthenticated = reauthenticate(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, cookie, csrfTokenOf(cookie),
                plan -> plan.subject(subject));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        String csrf = csrfTokenOf(rotated);
        return new Signed(user, subject, rotated, csrf == null ? "" : csrf);
    }

    /** 감사 행의 수행자 귀속({@code user_id 또는 -}|{@code 비식별 시각이 있는가}). */
    private String attributionOf(String operationId) {
        return jdbc().sql("SELECT COALESCE(CAST(actor_user_id AS text), '-') || '|' || (actor_erased_at IS NOT NULL) "
                        + "FROM operations_audit_event WHERE operation_id = :o")
                .param("o", operationId).query(String.class).single();
    }

    private String primaryIdOf(String cookie) {
        return jdbc().sql("SELECT primary_id FROM spring_session WHERE session_id = :s").param("s", sessionIdOf(cookie))
                .query(String.class).single();
    }

    private int completionLinks() {
        return jdbc().sql("SELECT count(*) FROM identity_deletion_completion_session").query(Integer.class).single();
    }

    private String lifecycleOf(UUID user) {
        return jdbc().sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :u").param("u", user)
                .query(String.class).single();
    }

    private static String body(String version, String phrase) {
        return "{\"policyVersion\":\"" + version + "\",\"confirmation\":\"" + phrase + "\"}";
    }

    private static String[] headers(@Nullable String csrf, @Nullable String key) {
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

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
