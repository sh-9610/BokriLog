package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.AdConsentApi;
import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.engagement.internal.AdProviderTestHook;
import com.bokrilog.sync.SyncJobApi;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 광고 통합 시험(동의·자격·transition 소비·계측 beacon — Story 4.1~4.5)의 공용 준비와 요청 모양.
 *
 * <p>{@link OperatorTestBase}의 컨텍스트를 그대로 쓴다 — {@code @DynamicPropertySource}·{@code @MockitoBean}·
 * {@code @TestPropertySource}·설정 클래스를 두지 않으므로 Spring 시험 컨텍스트가 하나 더 생기지 않는다({@link AuthFlowTestBase}).
 * 여기는 준비(사용자 동의·자격 행·수동 동기화 성공)와 요청 모양만 공유하고, 무엇을 기대하는지는 각 시험 메서드가 정한다. 공급자는
 * 시험 컨텍스트에서 켜져 있고(시험 속성) 꺼짐을 보는 시험이 {@link AdProviderTestHook}으로 잠시 끄므로, 시험마다 켜짐으로 되돌린다.
 *
 * <p>시험 값은 실행 시점에 만든다 — 멱등 키는 {@link #key}, digest는 무작위 값의 {@link #sha256}, transition은
 * {@link SecureRandom}의 {@link AdProviderTestHook#TRANSITION_BYTES} 바이트다(토큰 모양 리터럴은 gitleaks가 잡는다). 이 helper들의
 * 사본은 {@code AdTestBaseTest}가 막는다.
 */
abstract class AdTestBase extends OperatorTestBase {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    AdConsentApi consents;

    @Autowired
    EngagementDeletionApi deletion;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void providerBackOn() {
        AdProviderTestHook.providerEnabled(this.context, true);
    }

    /** 그 사용자의 지금 정책 버전 동의. 쓰기 경로({@link AdConsentApi#decide})로 저장한다. */
    void grant(UUID user) {
        var decided = this.consents.decide(user, key("ad-grant"), AdConsentApi.Choice.GRANTED,
                AdConsentApi.POLICY_VERSION);
        assertThat(decided).isInstanceOf(AdConsentApi.Decision.Saved.class);
    }

    /** 수동 동기화 하나를 접수하고 성공을 기다린다. */
    UUID admitManual(UUID user, String key) throws Exception {
        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key);
        assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        UUID job = ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        return job;
    }

    /** 그 attempt의 접수 시각을 1분 당긴다 — 다음 수동 접수와 대시보드가 쿨다운에 흔들리지 않게(자격 시험의 사정이다). */
    void backdateAdmission(UUID job) {
        int updated = jdbc()
                .sql("UPDATE sync_plan_sync_job SET admitted_at = admitted_at - interval '1 minute' WHERE job_id = :j")
                .param("j", job).update();
        // 없는 job이면 조용히 지나가 다음 접수가 쿨다운에 걸린다 — 그 원인을 여기서 드러낸다.
        assertThat(updated).as("당길 수동 동기화 job " + job).isEqualTo(1);
    }

    /** transition 소비 요청({@code POST /api/engagement/ad-transition}). */
    HttpResponse<String> claim(Signed signed, String transition) throws Exception {
        return sendJson("POST", AdTransitionController.AD_TRANSITION_PATH, "{\"transition\":\"" + transition + "\"}",
                signed.cookie(), headers(signed.csrf(), null));
    }

    /**
     * 동의 결정 행 하나를 직접 넣는다(옛 정책 버전·읽을 수 없는 값을 만드는 시험용). 최신 행이 된다. 쓰기 경로는 지금 정책 버전만
     * 받으므로 이런 행은 그 경로로 만들 수 없다.
     */
    void insertConsent(UUID user, String policyVersion, String choice) {
        jdbc().sql("INSERT INTO engagement_ad_consent_decision (user_id, policy_version, choice, idempotency_key_digest, "
                        + "request_digest) VALUES (:u, :p, :c, :k, :r)")
                .param("u", user).param("p", policyVersion).param("c", choice)
                .param("k", sha256(UUID.randomUUID().toString())).param("r", sha256(UUID.randomUUID().toString()))
                .update();
    }

    /** 그 사용자의 다른 결과 자격 행 하나(결정 시각은 표의 기본값)를 넣고 transition 참조를 돌려준다. */
    String insertEligible(UUID user) {
        String transition = newTransition();
        jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, transition_ref) "
                        + "VALUES (:u, :j, 'ELIGIBLE', :t)")
                .param("u", user).param("j", UUID.randomUUID()).param("t", transition).update();
        return transition;
    }

    /** 그 사용자의 다른 결과 자격 행 하나를 그 DB 시각 식으로 넣고 transition 참조를 돌려준다. */
    String insertEligible(UUID user, String decidedAtSql) {
        String transition = newTransition();
        jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, transition_ref, decided_at) "
                        + "VALUES (:u, :j, 'ELIGIBLE', :t, " + decidedAtSql + ")")
                .param("u", user).param("j", UUID.randomUUID()).param("t", transition).update();
        return transition;
    }

    /** 발급 모양(운영 바이트 수의 URL-safe Base64, 패딩 없음)의 새 transition 참조. */
    static String newTransition() {
        byte[] bytes = new byte[AdProviderTestHook.TRANSITION_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 없는 대상과 같은 404 공통 본문(세 키)과 그 code. */
    static void assertNotFound(HttpResponse<String> response, String code, String what) {
        assertThat(response.statusCode()).as(what + ": " + response.body()).isEqualTo(404);
        assertThat(topLevelFieldsOf(response.body())).as(what).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", code);
    }
}
