package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.engagement.AdConsentApi;
import com.bokrilog.engagement.AdTransitionApi;
import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.engagement.internal.AdProviderTestHook;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.sync.SyncJobApi;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 4.3: 중립 전환 화면의 transition 소비 — SPEC I/O 매트릭스의 서버 행 전부.
 *
 * <p>한 시험(자격에서 소비까지)은 실제 수동 동기화 성공 → 자격 판정(Story 4.2) → 소비를 HTTP로 잇는다. 나머지는 소비가 읽는 것이
 * 자격 행 하나뿐이므로 그 사용자의 자격 행을 DB 시각 식으로 직접 넣는다 — 결정 시각(만료 창)을 결정적으로 고르기 위해서다. 공급자는
 * 시험 컨텍스트에서 켜져 있고(플래그와 형식에 맞는 단위 경로), 꺼짐은 {@link AdProviderTestHook}으로 잠시 끈다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdTransitionTest extends OperatorTestBase {

    private static final String PATH = AdTransitionController.AD_TRANSITION_PATH;

    private static final String ELIGIBILITY_PATH = AdEligibilityController.AD_ELIGIBILITY_PATH;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private AdConsentApi consents;

    @Autowired
    private AdTransitionApi transitions;

    @Autowired
    private EngagementDeletionApi deletion;

    @Autowired
    private ApplicationContext context;

    @AfterEach
    void providerBackOn() {
        AdProviderTestHook.providerEnabled(this.context, true);
    }

    // ------------------------------------------------------------------ 자격 → 소비

    @Test
    void 자격의_transition은_처음_소비만_SHOW이고_재소비와_자격_재전송_뒤_소비는_CONSUMED다(CapturedOutput output)
            throws Exception {
        Signed signed = signedIn("ad-transition-flow");
        store(signed.user(), DevBybitStub.Scenario.UTA2);
        publishedPlan(signed.user(), "ad-transition-flow", null);
        UUID job = admitManual(signed.user(), key("ad-transition-flow-manual"));
        grant(signed.user());

        var judged = sendJson("POST", ELIGIBILITY_PATH, "{\"syncJobId\":\"" + job + "\"}", signed.cookie(),
                headers(signed.csrf(), null));
        assertThat(judged.statusCode()).as(judged.body()).isEqualTo(200);
        String transition = (String) topLevelFieldsOf(judged.body()).get("transition");
        assertThat(transition).matches("[A-Za-z0-9_-]{43}");
        assertThat(consumedAt(transition)).isNull();

        var shown = claim(signed, transition);
        assertThat(shown.statusCode()).as(shown.body()).isEqualTo(200);
        assertThat(shown.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
        assertThat(topLevelFieldsOf(shown.body())).containsOnlyKeys("outcome", "adUnitPath")
                .containsEntry("outcome", "SHOW").containsEntry("adUnitPath", AD_UNIT_PATH);
        // 사용자·결과·transition을 싣지 않는다.
        assertThat(shown.body()).doesNotContain(signed.user().toString()).doesNotContain(job.toString())
                .doesNotContain(transition);
        OffsetDateTime first = consumedAt(transition);
        assertThat(first).isNotNull();

        // 같은 참조의 재소비(새로고침·뒤로 가기·재전송): CONSUMED, 첫 소비 시각은 그대로다.
        assertSkip(claim(signed, transition), "CONSUMED");
        assertThat(consumedAt(transition)).isEqualTo(first);

        // 4.2의 자격 재전송은 같은 참조를 돌려주지만(판정 계약은 그대로다), 그 참조의 소비는 CONSUMED다 — 노출이 다시 없다.
        var replayed = sendJson("POST", ELIGIBILITY_PATH, "{\"syncJobId\":\"" + job + "\"}", signed.cookie(),
                headers(signed.csrf(), null));
        assertThat(topLevelFieldsOf(replayed.body())).containsEntry("outcome", "ELIGIBLE")
                .containsEntry("transition", transition).containsEntry("replayed", true);
        assertSkip(claim(signed, transition), "CONSUMED");
        assertThat(consumedAt(transition)).isEqualTo(first);

        // 로그는 단계·결과 code뿐이다. transition 값은 응답 밖 어디에도 없다.
        String all = output.getOut() + output.getErr();
        List<String> events = LogLines.events(all, "ad transition request");
        assertThat(events).anySatisfy(event -> assertThat(event).contains("result=SHOW").contains("status=200"))
                .anySatisfy(event -> assertThat(event).contains("result=CONSUMED"));
        assertThat(all).doesNotContain(transition);
        for (String event : events) {
            assertThat(event).doesNotContain(signed.user().toString());
        }
    }

    // ------------------------------------------------------------------ 만료

    @Test
    void 결정_뒤_120초를_넘은_참조는_EXPIRED이고_소비를_기록하며_창_안은_SHOW다() throws Exception {
        Signed signed = signedIn("ad-transition-expiry");
        grant(signed.user());
        String expired = insertEligible(signed.user(), "statement_timestamp() - interval '121 seconds'");
        assertSkip(claim(signed, expired), "EXPIRED");
        assertThat(consumedAt(expired)).isNotNull();
        assertSkip(claim(signed, expired), "CONSUMED");

        String inWindow = insertEligible(signed.user(), "statement_timestamp() - interval '100 seconds'");
        assertShow(claim(signed, inWindow));
        assertThat(consumedAt(inWindow)).isNotNull();
        assertThat(AdTransitionApi.CLAIM_WINDOW_SECONDS).isEqualTo(120);
    }

    // ------------------------------------------------------------------ 관문 재확인

    @Test
    void 자격_뒤_철회_운영_제한_공급자_꺼짐은_각_사유로_건너뛰고_소비를_기록한다() throws Exception {
        Signed signed = signedIn("ad-transition-gates");

        // 판정 뒤 동의 철회(여기서는 결정 없음 → 비동의).
        String undecided = insertEligible(signed.user(), "statement_timestamp()");
        assertSkip(claim(signed, undecided), "CONSENT_REQUIRED");
        assertThat(consumedAt(undecided)).isNotNull();
        this.consents.decide(signed.user(), key("ad-transition-deny"), AdConsentApi.Choice.DENIED,
                AdConsentApi.POLICY_VERSION);
        String denied = insertEligible(signed.user(), "statement_timestamp()");
        assertSkip(claim(signed, denied), "CONSENT_REQUIRED");

        // 전역 ADVERTISING 제한. 공급자 꺼짐이 함께여도 처음 걸린 운영 제한이 사유다.
        grant(signed.user());
        UUID restriction = restrictGlobally(RestrictionFeature.ADVERTISING);
        String restricted = insertEligible(signed.user(), "statement_timestamp()");
        AdProviderTestHook.providerEnabled(this.context, false);
        assertSkip(claim(signed, restricted), "RESTRICTED");
        assertThat(consumedAt(restricted)).isNotNull();
        lift(restriction);

        // 공급자 꺼짐.
        String disabled = insertEligible(signed.user(), "statement_timestamp()");
        assertSkip(claim(signed, disabled), "PROVIDER_DISABLED");
        assertThat(consumedAt(disabled)).isNotNull();
        AdProviderTestHook.providerEnabled(this.context, true);

        // 관문이 열린 뒤에도 이미 소비된 참조는 다시 시도하지 않는다.
        assertSkip(claim(signed, disabled), "CONSUMED");
        String open = insertEligible(signed.user(), "statement_timestamp()");
        assertShow(claim(signed, open));
    }

    /**
     * 판독 실패는 fail-closed다: 동의 판독이 예외면 500 공통 본문·{@code no-store}이고 소비가 롤백된다(다음 소비는 다시 처음
     * 소비다). 실패는 WARN 한 줄(예외 단순 이름만)로 보인다. 실패를 만드는 방법은 4.2 판독 실패 시험과 같다 — 동의 표의 CHECK를 잠시
     * 내리고 읽을 수 없는 선택 값을 넣는다.
     */
    @Test
    void 판독_실패는_500이고_소비하지_않으며_WARN으로_보인다(CapturedOutput output) throws Exception {
        Signed signed = signedIn("ad-transition-failure");
        String transition = insertEligible(signed.user(), "statement_timestamp()");
        String definition = jdbc().sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'engagement_ad_consent_decision_choice_check'").query(String.class).single();
        jdbc().sql("ALTER TABLE engagement_ad_consent_decision DROP CONSTRAINT engagement_ad_consent_decision_choice_check")
                .update();
        try {
            jdbc().sql("INSERT INTO engagement_ad_consent_decision (user_id, policy_version, choice, "
                            + "idempotency_key_digest, request_digest) VALUES (:u, :p, 'UNREADABLE', :k, :r)")
                    .param("u", signed.user()).param("p", AdConsentApi.POLICY_VERSION)
                    .param("k", sha256(UUID.randomUUID().toString())).param("r", sha256(UUID.randomUUID().toString()))
                    .update();
            var failed = claim(signed, transition);
            assertThat(failed.statusCode()).as(failed.body()).isEqualTo(500);
            assertThat(failed.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                    .contains("no-store"));
            assertThat(topLevelFieldsOf(failed.body())).containsOnlyKeys("code", "message", "correlationId")
                    .containsEntry("code", ApiErrorHandler.CODE_INTERNAL_ERROR);
            assertThat(consumedAt(transition)).isNull();
        } finally {
            jdbc().sql("DELETE FROM engagement_ad_consent_decision WHERE choice NOT IN ('GRANTED', 'DENIED')").update();
            jdbc().sql("ALTER TABLE engagement_ad_consent_decision ADD CONSTRAINT "
                    + "engagement_ad_consent_decision_choice_check " + definition).update();
        }
        String all = output.getOut() + output.getErr();
        assertThat(LogLines.events(all, "ad transition request")).anySatisfy(event -> assertThat(event)
                .contains("result=ERROR").contains("status=500"));
        List<Map<String, Object>> warnings = LogLines.records(all).stream()
                .filter(record -> String.valueOf(record.get("event")).contains("ad transition claim failed closed"))
                .toList();
        assertThat(warnings).isNotEmpty().allSatisfy(record -> {
            assertThat(record).containsEntry("level", "WARN").containsEntry("exception", "IllegalArgumentException");
            assertThat(record.toString()).doesNotContain(transition).doesNotContain(signed.user().toString())
                    .doesNotContain("\tat ");
        });
        assertThat(all).doesNotContain(transition);

        // 실패가 소비를 남기지 않았으므로 동의 뒤의 소비는 처음 소비다.
        grant(signed.user());
        assertShow(claim(signed, transition));
    }

    // ------------------------------------------------------------------ 소유·삭제

    @Test
    void 남의_참조_없는_참조_삭제가_시작된_계정은_공통_404이고_소비하지_않는다() throws Exception {
        Signed owner = signedIn("ad-transition-owner");
        Signed other = signedIn("ad-transition-other");
        grant(owner.user());
        grant(other.user());
        String missing = topLevelFieldsOf(get("/api/engagement/nope", owner.cookie()).body()).get("code").toString();
        String transition = insertEligible(owner.user(), "statement_timestamp()");

        assertNotFound(claim(other, transition), missing, "other user's");
        assertThat(consumedAt(transition)).isNull();
        assertNotFound(claim(owner, newTransition()), missing, "missing");

        // 삭제 시작: 접근 판정이 막고(404), 접근 판정 뒤 삭제가 커밋된 경합도 쓰기 잠금이 막는다.
        accountDeletion().beginDeletion(owner.user());
        assertNotFound(claim(owner, transition), missing, "deletion in progress");
        assertThat(this.transitions.claim(owner.user(), transition)).isInstanceOf(AdTransitionApi.Claim.NotFound.class);
        assertThat(consumedAt(transition)).isNull();

        // 소비된 행도 계정 삭제의 deletion port가 행째 지운다.
        String consumed = insertEligible(other.user(), "statement_timestamp()");
        assertShow(claim(other, consumed));
        assertThat(this.deletion.eraseAdAttempts(other.user())).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM engagement_ad_attempt WHERE user_id = :u").param("u", other.user())
                .query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 같은_참조의_동시_소비는_하나만_SHOW다() throws Exception {
        Signed signed = signedIn("ad-transition-race");
        grant(signed.user());
        String transition = insertEligible(signed.user(), "statement_timestamp()");
        var start = new CountDownLatch(1);
        List<HttpResponse<String>> both = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> {
                start.await();
                return claim(signed, transition);
            });
            var b = executor.submit(() -> {
                start.await();
                return claim(signed, transition);
            });
            start.countDown();
            both.add(a.get());
            both.add(b.get());
        }
        assertThat(both).extracting(HttpResponse::statusCode).containsExactly(200, 200);
        assertThat(both).extracting(response -> topLevelFieldsOf(response.body()).get("outcome") + ":"
                + topLevelFieldsOf(response.body()).get("reason")).containsExactlyInAnyOrder("SHOW:null",
                "SKIP:CONSUMED");
    }

    // ------------------------------------------------------------------ 형식·보안·스키마

    @Test
    void 본문_모양_오류는_400이고_CSRF_없음과_교차_출처는_403이며_아무것도_소비하지_않는다() throws Exception {
        Signed signed = signedIn("ad-transition-shape");
        grant(signed.user());
        String transition = insertEligible(signed.user(), "statement_timestamp()");
        for (String malformed : List.of("", "{}", "{\"transition\":null}", "{\"transition\":1}",
                "{\"transition\":\"" + transition.substring(1) + "\"}", "{\"transition\":\"" + transition + "x\"}",
                "{\"transition\":\"" + transition.substring(1) + "=\"}", "[\"" + transition + "\"]", "not json",
                "{\"transition\":\"" + transition + "\",\"pad\":\"" + "x".repeat(300) + "\"}")) {
            var refused = sendJson("POST", PATH, malformed, signed.cookie(), headers(signed.csrf(), null));
            assertThat(refused.statusCode()).as(malformed).isEqualTo(400);
            assertThat(topLevelFieldsOf(refused.body())).as(malformed)
                    .containsOnlyKeys("code", "message", "correlationId").containsEntry("code", "BAD_REQUEST");
        }
        assertThat(send("POST", PATH, signed.cookie(), headers(signed.csrf(), null)).statusCode()).isEqualTo(400);
        String body = "{\"transition\":\"" + transition + "\"}";
        assertThat(sendJson("POST", PATH, body, signed.cookie(), headers(null, null)).statusCode()).isEqualTo(403);
        assertThat(sendJson("POST", PATH, body, signed.cookie(), "Origin", "https://attacker.example",
                AuthSecurityConfig.CSRF_HEADER_NAME, signed.csrf()).statusCode()).isEqualTo(403);
        assertThat(get(PATH, null).statusCode()).isEqualTo(401);
        var read = get(PATH, signed.cookie());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(405);
        assertThat(consumedAt(transition)).isNull();
    }

    /** V28: 소비는 ELIGIBLE 행에만 있다. */
    @Test
    void 소비_시각은_자격_행에만_쓸_수_있다() throws Exception {
        Signed signed = signedIn("ad-transition-check");
        assertThatThrownBy(() -> jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, "
                        + "skip_reason, transition_consumed_at) VALUES (:u, :j, 'SKIPPED', 'INTERVAL', statement_timestamp())")
                .param("u", signed.user()).param("j", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("engagement_ad_attempt_consumed_check");
        String transition = insertEligible(signed.user(), "statement_timestamp()");
        assertThat(jdbc().sql("UPDATE engagement_ad_attempt SET transition_consumed_at = statement_timestamp() "
                + "WHERE transition_ref = :t").param("t", transition).update()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 도구

    /** 수동 동기화 하나를 접수하고 성공을 기다린다. */
    private UUID admitManual(UUID user, String key) throws Exception {
        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key);
        assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        UUID job = ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        return job;
    }

    private void grant(UUID user) {
        var decided = this.consents.decide(user, key("ad-transition-grant"), AdConsentApi.Choice.GRANTED,
                AdConsentApi.POLICY_VERSION);
        assertThat(decided).isInstanceOf(AdConsentApi.Decision.Saved.class);
    }

    private HttpResponse<String> claim(Signed signed, String transition) throws Exception {
        return sendJson("POST", PATH, "{\"transition\":\"" + transition + "\"}", signed.cookie(),
                headers(signed.csrf(), null));
    }

    /** 그 사용자의 자격 행 하나를 그 DB 시각 식으로 넣고 transition 참조를 돌려준다. */
    private String insertEligible(UUID user, String decidedAt) {
        String transition = newTransition();
        jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, transition_ref, decided_at) "
                        + "VALUES (:u, :j, 'ELIGIBLE', :t, " + decidedAt + ")")
                .param("u", user).param("j", UUID.randomUUID()).param("t", transition).update();
        return transition;
    }

    private static String newTransition() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private @Nullable OffsetDateTime consumedAt(String transition) {
        return jdbc().sql("SELECT transition_consumed_at FROM engagement_ad_attempt WHERE transition_ref = :t")
                .param("t", transition).query(OffsetDateTime.class).optional().orElse(null);
    }

    private static void assertShow(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(response.body())).as(response.body()).containsOnlyKeys("outcome", "adUnitPath")
                .containsEntry("outcome", "SHOW").containsEntry("adUnitPath", AD_UNIT_PATH);
    }

    private static void assertSkip(HttpResponse<String> response, String reason) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
        assertThat(topLevelFieldsOf(response.body())).as(response.body()).containsOnlyKeys("outcome", "reason")
                .containsEntry("outcome", "SKIP").containsEntry("reason", reason);
    }

    private static void assertNotFound(HttpResponse<String> response, String code, String what) {
        assertThat(response.statusCode()).as(what + ": " + response.body()).isEqualTo(404);
        assertThat(topLevelFieldsOf(response.body())).as(what).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", code);
    }
}
