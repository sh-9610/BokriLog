package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.AdConsentApi;
import com.bokrilog.engagement.AdEligibilityApi;
import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.engagement.internal.AdProviderTestHook;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.sync.SyncJobApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.2: 수동 동기화 성공 결과에 광고 자격과 빈도 제한을 적용한다 — SPEC I/O 매트릭스 전 행과 수용 기준 셋.
 *
 * <p>결과 참조는 실제 수동 동기화 attempt의 {@code jobId}다. 한 결과에 결정이 하나라서 여러 판정이 필요한 시험은 실제 성공 attempt
 * 행을 복제한 attempt(같은 공식 결과, 새 {@code job_id})를 쓰고, 후보가 아닌 attempt는 복제하며 한 열(trigger·purpose·status)만
 * 바꾼다 — 판정은 attempt 행 하나로 하므로 그 열 하나가 후보 여부를 가른다. 공급자는 시험 컨텍스트에서 켜져 있고(시험 속성),
 * 꺼짐은 {@link AdProviderTestHook}으로 잠시 끈다. 빈도 제한의 시각 경계는 순수 판정 시험({@code AdFrequencyPolicyTest})이
 * 가상 시계로 보고, 여기서는 DB 시각에 상대적인 자격 행으로 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdEligibilityTest extends OperatorTestBase {

    private static final String PATH = AdEligibilityController.AD_ELIGIBILITY_PATH;

    /** 오늘 KST 자정(timestamptz). */
    private static final String KST_MIDNIGHT =
            "(date_trunc('day', statement_timestamp() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul')";

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 그 이름의 사용자 advisory 잠금(bigint 키)을 기다리는 backend 수(`BybitReconnectionTest`의 계획 잠금 질의와 같은 모양). */
    private static final String LOCK_WAITERS = "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted "
            + "AND objsubid = 1 AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(:name, 0)";

    @Autowired
    private AdConsentApi consents;

    @Autowired
    private AdEligibilityApi eligibility;

    @Autowired
    private EngagementDeletionApi deletion;

    @Autowired
    private AccountErasure erasure;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @LocalManagementPort
    private int managementPort;

    @AfterEach
    void providerBackOn() {
        AdProviderTestHook.providerEnabled(this.context, true);
    }

    /** 실제 수동 동기화 성공 하나를 가진 로그인 사용자. */
    record Fixture(Signed signed, UUID job) {

        UUID user() {
            return this.signed.user();
        }
    }

    // ------------------------------------------------------------------ 자격·재전송·결과 접근·계측

    @Test
    void 자격은_행_하나와_transition이고_재전송은_같은_결과이며_대시보드는_판정과_무관하다(CapturedOutput output)
            throws Exception {
        Fixture fixture = manualSync("ad-eligible");
        grant(fixture);
        String dashboardBefore = get(AuthSecurityConfig.DASHBOARD_PATH, fixture.signed().cookie()).body();
        double triggers = count(AdTelemetry.TRIGGER);
        double reservations = count(AdTelemetry.RESERVATION);
        double replays = skips(AdTelemetry.REPLAYED);

        var first = judge(fixture.signed(), fixture.job());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
        Map<String, Object> view = topLevelFieldsOf(first.body());
        assertThat(view).containsOnlyKeys("outcome", "reason", "transition", "replayed")
                .containsEntry("outcome", "ELIGIBLE").containsEntry("reason", null).containsEntry("replayed", false);
        String transition = (String) view.get("transition");
        assertThat(transition).matches("[A-Za-z0-9_-]{43}");
        // 사용자·결과·금융 값을 싣지 않는다. transition은 사용자·결과를 식별하지 않는다.
        assertThat(first.body()).doesNotContain(fixture.user().toString()).doesNotContain(fixture.job().toString());
        assertThat(transition).doesNotContain(fixture.user().toString().replace("-", ""))
                .doesNotContain(fixture.job().toString().replace("-", ""));
        assertThat(attempts(fixture.user())).containsExactly("ELIGIBLE:" + transition);
        assertThat(count(AdTelemetry.TRIGGER)).isEqualTo(triggers + 1);
        assertThat(count(AdTelemetry.RESERVATION)).isEqualTo(reservations + 1);

        // 같은 결과의 재전송: 처음 결과, replayed, 행·reservation 변화 없음.
        var again = judge(fixture.signed(), fixture.job());
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(again.body())).containsEntry("outcome", "ELIGIBLE")
                .containsEntry("transition", transition).containsEntry("replayed", true);
        assertThat(attempts(fixture.user())).containsExactly("ELIGIBLE:" + transition);
        assertThat(count(AdTelemetry.RESERVATION)).isEqualTo(reservations + 1);
        assertThat(count(AdTelemetry.TRIGGER)).isEqualTo(triggers + 2);
        assertThat(skips(AdTelemetry.REPLAYED)).isEqualTo(replays + 1);

        // 수용 기준: 판정은 결과 접근을 바꾸지 않는다 — 대시보드 응답이 판정 전과 같고, 대시보드 판독은 판정을 일으키지 않는다.
        var dashboardAfter = get(AuthSecurityConfig.DASHBOARD_PATH, fixture.signed().cookie());
        assertThat(dashboardAfter.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(dashboardAfter.body())).isEqualTo(topLevelFieldsOf(dashboardBefore));
        assertThat(dashboardAfter.body()).doesNotContain(transition);
        assertThat(count(AdTelemetry.TRIGGER)).isEqualTo(triggers + 2);
        assertThat(attempts(fixture.user())).hasSize(1);

        // 수용 기준: 관리 포트 출력에 bokrilog_ad_* 계열이 보이고 label에 사용자·결과·transition 값이 없다.
        String scraped = scrape();
        assertThat(scraped).contains("bokrilog_ad_trigger_total").contains("bokrilog_ad_reservation_total")
                .contains("bokrilog_ad_skip_total{");
        assertThat(scraped).doesNotContain(fixture.user().toString()).doesNotContain(fixture.job().toString())
                .doesNotContain(transition);

        // 로그는 단계·결과 code뿐이다.
        List<String> events = LogLines.events(output.getOut() + output.getErr(), "ad eligibility request");
        assertThat(events).hasSizeGreaterThanOrEqualTo(2)
                .anySatisfy(event -> assertThat(event).contains("result=ELIGIBLE").contains("replayed=false"))
                .anySatisfy(event -> assertThat(event).contains("result=ELIGIBLE").contains("replayed=true"));
        String all = output.getOut() + output.getErr();
        assertThat(all).doesNotContain(transition);
        for (String event : events) {
            assertThat(event).doesNotContain(fixture.user().toString()).doesNotContain(fixture.job().toString());
        }
    }

    // ------------------------------------------------------------------ 관문

    @Test
    void 관문에_걸리면_처음_걸린_사유로_건너뛰고_행은_남지만_빈도_제한을_소비하지_않는다() throws Exception {
        Fixture fixture = manualSync("ad-gates");

        // 비동의: 미결정과 비동의 모두.
        assertSkipped(judge(fixture.signed(), fixture.job()), "CONSENT_REQUIRED", false);
        this.consents.decide(fixture.user(), key("ad-gates-deny"), AdConsentApi.Choice.DENIED,
                AdConsentApi.POLICY_VERSION);
        UUID denied = cloneJob(fixture.job(), Map.of());
        assertSkipped(judge(fixture.signed(), denied), "CONSENT_REQUIRED", false);

        // 운영 제한: 동의 뒤 전역 ADVERTISING 제한.
        grant(fixture);
        UUID restriction = restrictGlobally(RestrictionFeature.ADVERTISING);
        UUID restricted = cloneJob(fixture.job(), Map.of());
        assertSkipped(judge(fixture.signed(), restricted), "RESTRICTED", false);
        // 공급자 비활성이 함께여도 처음 걸린 운영 제한이 사유다.
        AdProviderTestHook.providerEnabled(this.context, false);
        UUID both = cloneJob(fixture.job(), Map.of());
        assertSkipped(judge(fixture.signed(), both), "RESTRICTED", false);
        lift(restriction);

        // 공급자 꺼짐.
        UUID disabled = cloneJob(fixture.job(), Map.of());
        assertSkipped(judge(fixture.signed(), disabled), "PROVIDER_DISABLED", false);
        AdProviderTestHook.providerEnabled(this.context, true);

        // 건너뜀은 빈도 제한을 소비하지 않는다 — 바로 다음 결과가 자격이다(간격·상한에 걸리지 않는다).
        UUID eligible = cloneJob(fixture.job(), Map.of());
        var granted = judge(fixture.signed(), eligible);
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(granted.body())).containsEntry("outcome", "ELIGIBLE");

        // 재전송은 결론을 낮추기만 한다: 건너뜀의 재전송은 처음 판정 그대로다 — 동의했고, 제한이 풀렸고, 공급자가 켜졌어도
        // 다시 판정하지 않는다.
        assertSkipped(judge(fixture.signed(), fixture.job()), "CONSENT_REQUIRED", true);
        assertSkipped(judge(fixture.signed(), restricted), "RESTRICTED", true);
        assertSkipped(judge(fixture.signed(), disabled), "PROVIDER_DISABLED", true);
        assertThat(rowsOf(fixture.user())).isEqualTo(6);
        assertThat(attempts(fixture.user()).subList(0, 5)).containsExactly("SKIPPED:CONSENT_REQUIRED",
                "SKIPPED:CONSENT_REQUIRED", "SKIPPED:RESTRICTED", "SKIPPED:RESTRICTED", "SKIPPED:PROVIDER_DISABLED");
    }

    @Test
    void 자격_뒤_철회와_정지는_재전송부터_바로_적용되고_행은_그대로다() throws Exception {
        Fixture fixture = manualSync("ad-revoke");
        grant(fixture);
        var first = judge(fixture.signed(), fixture.job());
        String transition = (String) topLevelFieldsOf(first.body()).get("transition");
        assertThat(transition).isNotNull();

        this.consents.decide(fixture.user(), key("ad-revoke-deny"), AdConsentApi.Choice.DENIED,
                AdConsentApi.POLICY_VERSION);
        assertSkipped(judge(fixture.signed(), fixture.job()), "CONSENT_REQUIRED", true);
        assertThat(attempts(fixture.user())).containsExactly("ELIGIBLE:" + transition);

        grant(fixture);
        UUID restriction = restrictGlobally(RestrictionFeature.ADVERTISING);
        assertSkipped(judge(fixture.signed(), fixture.job()), "RESTRICTED", true);
        lift(restriction);
        AdProviderTestHook.providerEnabled(this.context, false);
        assertSkipped(judge(fixture.signed(), fixture.job()), "PROVIDER_DISABLED", true);
        AdProviderTestHook.providerEnabled(this.context, true);

        // 관문이 다시 열리면 같은 자격(같은 transition)이다. 빈도 제한은 다시 보지 않는다 — 이미 이 결과의 노출 시도다.
        var reopened = judge(fixture.signed(), fixture.job());
        assertThat(topLevelFieldsOf(reopened.body())).containsEntry("outcome", "ELIGIBLE")
                .containsEntry("transition", transition).containsEntry("replayed", true);
        assertThat(attempts(fixture.user())).containsExactly("ELIGIBLE:" + transition);
    }

    /** 최신 동의 행이 옛 정책 버전의 GRANTED면 동의가 아니다 — 첫 판정과 재전송 모두(자동 재동의 없음, Story 4.1). */
    @Test
    void 옛_정책_버전의_동의는_첫_판정과_재전송_모두_동의_필요다() throws Exception {
        Fixture fixture = manualSync("ad-old-policy");
        insertConsent(fixture.user(), "AD-CONSENT-0.9", "GRANTED");
        assertSkipped(judge(fixture.signed(), fixture.job()), "CONSENT_REQUIRED", false);
        assertSkipped(judge(fixture.signed(), fixture.job()), "CONSENT_REQUIRED", true);

        // 지금 버전으로 동의한 뒤의 자격도, 그 뒤 최신 행이 옛 버전의 동의가 되면 재전송에서 동의 필요로 낮아진다.
        grant(fixture);
        UUID current = cloneJob(fixture.job(), Map.of());
        var eligible = judge(fixture.signed(), current);
        assertThat(topLevelFieldsOf(eligible.body())).as(eligible.body()).containsEntry("outcome", "ELIGIBLE");
        insertConsent(fixture.user(), "AD-CONSENT-0.9", "GRANTED");
        assertSkipped(judge(fixture.signed(), current), "CONSENT_REQUIRED", true);
        assertThat(attempts(fixture.user())).hasSize(2).first().isEqualTo("SKIPPED:CONSENT_REQUIRED");
    }

    // ------------------------------------------------------------------ 빈도 제한

    @Test
    void 마지막_자격_30분_미만은_간격이고_KST_하루_3건은_상한이며_날짜가_바뀌면_다시_센다() throws Exception {
        Fixture fixture = manualSync("ad-frequency");
        grant(fixture);

        // 30분 간격: 마지막 자격이 25분 전이면 INTERVAL(정확한 경계는 순수 판정 시험이 가상 시계로 본다).
        insertEligibleRow(fixture.user(), "statement_timestamp() - interval '25 minutes'");
        assertSkipped(judge(fixture.signed(), cloneJob(fixture.job(), Map.of())), "INTERVAL", false);
        clearEligible(fixture.user());
        // 30분이 넘었으면 자격이다.
        insertEligibleRow(fixture.user(), "statement_timestamp() - interval '31 minutes'");
        var afterInterval = judge(fixture.signed(), cloneJob(fixture.job(), Map.of()));
        assertThat(topLevelFieldsOf(afterInterval.body())).as(afterInterval.body()).containsEntry("outcome", "ELIGIBLE");
        clearEligible(fixture.user());

        // 하루 3회: 오늘 KST의 자격 3건이면 DAILY_CAP(간격이 함께 걸려도 상한이 사유다).
        insertTodayThree(fixture.user());
        assertSkipped(judge(fixture.signed(), cloneJob(fixture.job(), Map.of())), "DAILY_CAP", false);
        clearEligible(fixture.user());

        // 3건이 전날 KST(오늘 KST 자정 33·32·31분 전)이면 날짜 경계를 넘어 상한이 아니고, 마지막 자격에서 이미 31분 넘게 지났으므로
        // 간격도 충족한다 — 언제 돌아도 결정적이다(DB의 KST 자정 기준).
        for (int minutes : new int[] {33, 32, 31}) {
            insertEligibleRow(fixture.user(), KST_MIDNIGHT + " - interval '" + minutes + " minutes'");
        }
        var yesterday = judge(fixture.signed(), cloneJob(fixture.job(), Map.of()));
        assertThat(topLevelFieldsOf(yesterday.body())).as(yesterday.body()).containsEntry("outcome", "ELIGIBLE");
    }

    /**
     * 빈도 제한은 순번이 큰(최근) 자격부터 읽는다 — 자격 행이 3건을 넘어도 그렇다. 오래된 행부터 읽으면({@code ORDER BY id ASC}) 첫
     * 단정은 이틀 전 3건만 보고 자격이 되고, 둘째 단정은 오늘 3건을 보지 못해 상한이 되지 않는다.
     */
    @Test
    void 자격_행이_3건을_넘어도_최근_자격으로_간격과_상한을_판정한다() throws Exception {
        Fixture fixture = manualSync("ad-recent-order");
        grant(fixture);
        for (int seconds = 1; seconds <= 3; seconds++) {
            insertEligibleRow(fixture.user(), "statement_timestamp() - interval '2 days' + interval '" + seconds
                    + " seconds'");
        }
        insertEligibleRow(fixture.user(), "statement_timestamp() - interval '5 minutes'");
        assertSkipped(judge(fixture.signed(), cloneJob(fixture.job(), Map.of())), "INTERVAL", false);

        // 5분 전 행을 빼고 오늘 KST 자정 직후의 3건을 더한다 — 이틀 전 3건 뒤의 순번이다. 상한이 사유다.
        jdbc().sql("DELETE FROM engagement_ad_attempt WHERE user_id = :u AND outcome = 'ELIGIBLE' "
                + "AND decided_at > statement_timestamp() - interval '1 day'").param("u", fixture.user()).update();
        insertTodayThree(fixture.user());
        assertThat(jdbc().sql("SELECT count(*) FROM engagement_ad_attempt WHERE user_id = :u AND outcome = 'ELIGIBLE'")
                .param("u", fixture.user()).query(Long.class).single()).isEqualTo(6);
        assertSkipped(judge(fixture.signed(), cloneJob(fixture.job(), Map.of())), "DAILY_CAP", false);
    }

    /** 오늘 KST 자정 직후(1·2·3초)의 자격 3건. 지금이 자정 뒤 30분 이후면 간격도 충족한 상태다. */
    private void insertTodayThree(UUID user) {
        for (int second = 1; second <= 3; second++) {
            insertEligibleRow(user, KST_MIDNIGHT + " + interval '" + second + " seconds'");
        }
    }

    // ------------------------------------------------------------------ 후보

    @Test
    void 수동_동기화_성공이_아닌_attempt와_남의_것_없는_것은_공통_404이고_행을_쓰지_않는다() throws Exception {
        Fixture fixture = manualSync("ad-candidate");
        grant(fixture);
        String missing = topLevelFieldsOf(get("/api/engagement/nope", fixture.signed().cookie()).body()).get("code")
                .toString();
        double notCandidates = skips(AdTelemetry.NOT_CANDIDATE);

        Map<String, Map<String, Object>> variants = new LinkedHashMap<>();
        variants.put("ENTRY", Map.of("trigger", "ENTRY"));
        variants.put("VISIBILITY", Map.of("trigger", "VISIBILITY"));
        variants.put("VERIFY", Map.of("purpose", "VERIFY", "reason_code", "USER_REQUEST", "mode", "VERIFY"));
        variants.put("SYSTEM", Map.of("purpose", "VERIFY", "trigger", "SYSTEM", "reason_code", "OPERATOR_INVESTIGATION",
                "mode", "VERIFY"));
        variants.put("CATCH_UP", Map.of("purpose", "CATCH_UP", "reason_code", "LONG_GAP_CONTINUATION", "mode",
                "CATCH_UP"));
        variants.put("COMPLETION", Map.of("purpose", "COMPLETION", "mode", "COMPLETION"));
        variants.put("FAILED", unpublished("FAILED", "SYNC_FAILED"));
        variants.put("NEEDS_REVIEW", unpublished("NEEDS_REVIEW", "SOURCE_REQUIRES_REVIEW"));
        variants.put("BLOCKED", unpublished("BLOCKED", "PLAN_CHANGED"));
        Map<String, Object> running = unpublished("RUNNING", null);
        running.put("finished_at", null);
        running.put("lease_until", java.time.Instant.now().plusSeconds(3600).toString());
        variants.put("RUNNING", running);

        for (var variant : variants.entrySet()) {
            UUID job = cloneJob(fixture.job(), variant.getValue());
            assertNotFound(judge(fixture.signed(), job), missing, variant.getKey());
            if (variant.getKey().equals("RUNNING")) {
                // 진행 중 행은 계획마다 하나다. 다른 시험 단계와 겹치지 않게 바로 지운다.
                jdbc().sql("DELETE FROM sync_plan_sync_job WHERE job_id = :j").param("j", job).update();
            }
        }
        // 없는 attempt.
        assertNotFound(judge(fixture.signed(), UUID.randomUUID()), missing, "missing");
        // 남의 attempt: 다른 사용자가 이 사용자의 성공한 수동 동기화를 보낸다.
        Signed other = signedIn("ad-candidate-other");
        this.consents.decide(other.user(), key("ad-candidate-other"), AdConsentApi.Choice.GRANTED,
                AdConsentApi.POLICY_VERSION);
        assertNotFound(judge(other, fixture.job()), missing, "other user's");
        assertThat(rowsOf(other.user())).isZero();
        assertThat(rowsOf(fixture.user())).isZero();
        assertThat(skips(AdTelemetry.NOT_CANDIDATE)).isEqualTo(notCandidates + variants.size() + 2);

        // 대조: 원래의 수동 동기화 성공은 후보다.
        var original = judge(fixture.signed(), fixture.job());
        assertThat(topLevelFieldsOf(original.body())).as(original.body()).containsEntry("outcome", "ELIGIBLE");
        assertThat(rowsOf(fixture.user())).isEqualTo(1);
        // 그 자격이 있어도 남의 요청은 여전히 404다(행 소유가 아니라 attempt 소유로 판정한다).
        assertNotFound(judge(other, fixture.job()), missing, "other user's replay");
        assertThat(rowsOf(other.user())).isZero();
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 서로_다른_두_성공_결과를_동시에_보내면_하나만_자격이고_판정은_사용자_잠금을_기다린다() throws Exception {
        Fixture fixture = manualSync("ad-race");
        UUID second = admitManual(fixture.user(), key("ad-race-manual"));
        grant(fixture);

        var both = concurrently(() -> judge(fixture.signed(), fixture.job()),
                () -> judge(fixture.signed(), second));
        assertThat(both).extracting(HttpResponse::statusCode).containsExactly(200, 200);
        List<String> outcomes = new ArrayList<>();
        for (var response : both) {
            Map<String, Object> view = topLevelFieldsOf(response.body());
            outcomes.add(view.get("outcome") + ":" + view.get("reason"));
        }
        assertThat(outcomes).containsExactlyInAnyOrder("ELIGIBLE:null", "SKIPPED:INTERVAL");
        assertThat(rowsOf(fixture.user())).isEqualTo(2);

        // 같은 결과의 동시 재전송은 행 하나다.
        UUID third = cloneJob(fixture.job(), Map.of());
        var same = concurrently(() -> judge(fixture.signed(), third), () -> judge(fixture.signed(), third));
        assertThat(same).extracting(response -> topLevelFieldsOf(response.body()).get("reason"))
                .containsOnly("INTERVAL");
        assertThat(same).extracting(response -> topLevelFieldsOf(response.body()).get("replayed"))
                .containsExactlyInAnyOrder(false, true);
        assertThat(rowsOf(fixture.user())).isEqualTo(3);

        // 잠금 선점: 시험 트랜잭션이 판정과 같은 잠금을 쥔 동안 판정은 끝나지 않고 행도 없다.
        UUID fourth = cloneJob(fixture.job(), Map.of());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var template = new TransactionTemplate(this.transactionManager);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> template.executeWithoutResult(status -> {
                jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                        .param("name", AdProviderTestHook.USER_LOCK_PREFIX + fixture.user()).query((rs, row) -> 1)
                        .single();
                locked.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("잠금을 풀 신호가 오지 않았다.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var pending = executor.submit(() -> judge(fixture.signed(), fourth));
            // 판정 트랜잭션이 그 advisory 잠금을 기다리는 backend로 pg_locks에 나타날 때까지(시간으로 추측하지 않는다).
            int waiting = pollUntil(java.time.Duration.ofSeconds(10), () -> jdbc().sql(LOCK_WAITERS)
                    .param("name", AdProviderTestHook.USER_LOCK_PREFIX + fixture.user()).query(Integer.class).single(),
                    found -> found > 0);
            assertThat(waiting).as("사용자 잠금을 기다리는 판정").isPositive();
            assertThat(pending.isDone()).isFalse();
            assertThat(rowsOf(fixture.user())).isEqualTo(3);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            var decided = pending.get(20, TimeUnit.SECONDS);
            assertSkipped(decided, "INTERVAL", false);
            assertThat(rowsOf(fixture.user())).isEqualTo(4);
        }
    }

    // ------------------------------------------------------------------ 삭제

    @Test
    void 삭제가_시작된_계정은_404이고_계정_삭제는_결정_행을_지우며_다시_만들지_않는다() throws Exception {
        Fixture fixture = manualSync("ad-deletion");
        Signed kept = signedIn("ad-deletion-kept");
        grant(fixture);
        assertThat(topLevelFieldsOf(judge(fixture.signed(), fixture.job()).body())).containsEntry("outcome", "ELIGIBLE");
        insertEligibleRow(kept.user(), "statement_timestamp() - interval '2 hours'");
        String missing = topLevelFieldsOf(get("/api/engagement/nope", fixture.signed().cookie()).body()).get("code")
                .toString();

        // 삭제 시작: 접근 판정이 막고(404), 접근 판정 뒤 삭제가 커밋된 경합도 쓰기 잠금이 막는다.
        accountDeletion().beginDeletion(fixture.user());
        UUID late = cloneJob(fixture.job(), Map.of());
        assertNotFound(judge(fixture.signed(), late), missing, "deletion in progress");
        assertThat(this.eligibility.decide(fixture.user(), late)).isInstanceOf(AdEligibilityApi.Decision.Denied.class);
        assertThat(rowsOf(fixture.user())).isEqualTo(1);

        // 수용 기준: 계정 삭제 뒤 잔여 행 0, 같은 jobId의 다시 판정은 거절이고 행을 만들지 않는다. 다른 사용자의 행은 그대로다.
        assertThat(this.erasure.erase(fixture.user(), "ad-erasure-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        assertThat(rowsOf(fixture.user())).isZero();
        assertThat(this.deletion.remainingRowsOf(fixture.user())).isZero();
        assertThat(this.eligibility.decide(fixture.user(), fixture.job()))
                .isInstanceOf(AdEligibilityApi.Decision.Denied.class);
        assertThat(rowsOf(fixture.user())).isZero();
        assertThat(rowsOf(kept.user())).isEqualTo(1);
        // 삭제 port는 멱등이고 그 사용자의 행만 지운다.
        assertThat(this.deletion.eraseAdAttempts(fixture.user())).isZero();
        assertThat(this.deletion.eraseAdAttempts(null)).isZero();
        assertThat(this.deletion.eraseAdAttempts(kept.user())).isEqualTo(1);
        assertThat(this.deletion.eraseAdAttempts(kept.user())).isZero();
    }

    // ------------------------------------------------------------------ 형식·보안

    @Test
    void 본문_모양_오류는_400이고_CSRF_없음과_교차_출처는_403이며_아무것도_쓰지_않는다() throws Exception {
        Signed user = signedIn("ad-shape");
        for (String malformed : List.of("", "{}", "{\"syncJobId\":null}", "{\"syncJobId\":1}",
                "{\"syncJobId\":\"not-a-uuid\"}", "{\"syncJobId\":\"1-1-1-1-1\"}",
                "{\"syncJobId\":\"" + UUID.randomUUID() + "x\"}", "[\"" + UUID.randomUUID() + "\"]", "not json",
                "{\"syncJobId\":\"" + UUID.randomUUID() + "\",\"pad\":\"" + "x".repeat(400) + "\"}")) {
            var refused = sendJson("POST", PATH, malformed, user.cookie(), headers(user.csrf(), null));
            assertThat(refused.statusCode()).as(malformed).isEqualTo(400);
            assertThat(topLevelFieldsOf(refused.body())).as(malformed)
                    .containsOnlyKeys("code", "message", "correlationId").containsEntry("code", "BAD_REQUEST");
        }
        var noBody = send("POST", PATH, user.cookie(), headers(user.csrf(), null));
        assertThat(noBody.statusCode()).isEqualTo(400);

        String body = "{\"syncJobId\":\"" + UUID.randomUUID() + "\"}";
        assertThat(sendJson("POST", PATH, body, user.cookie(), headers(null, null)).statusCode()).isEqualTo(403);
        assertThat(sendJson("POST", PATH, body, user.cookie(), "Origin", "https://attacker.example",
                AuthSecurityConfig.CSRF_HEADER_NAME, user.csrf()).statusCode()).isEqualTo(403);
        // 세션 없는 요청은 세션 귀속 CSRF 토큰이 없어 인증 판정 앞에서 403으로 끝난다(동의 변경과 같다).
        assertThat(sendJson("POST", PATH, body, null, headers(null, null)).statusCode()).isEqualTo(403);
        // 미인증 조회는 인가 필터가 401로 끝낸다.
        assertThat(get(PATH, null).statusCode()).isEqualTo(401);
        // 인증된 다른 메서드: 경로는 있고 메서드가 다르다 — Story 1.2가 고정한 405 METHOD_NOT_ALLOWED(공통 본문)다. 상태 변경 메서드는
        // CSRF·Origin 판정이 먼저라 토큰이 없으면 403이다.
        var read = get(PATH, user.cookie());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(405);
        assertThat(topLevelFieldsOf(read.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", "METHOD_NOT_ALLOWED");
        var put = sendJson("PUT", PATH, body, user.cookie(), headers(user.csrf(), null));
        assertThat(put.statusCode()).as(put.body()).isEqualTo(405);
        assertThat(topLevelFieldsOf(put.body())).containsEntry("code", "METHOD_NOT_ALLOWED");
        assertThat(sendJson("PUT", PATH, body, user.cookie(), headers(null, null)).statusCode()).isEqualTo(403);
        assertThat(jdbc().sql("SELECT count(*) FROM engagement_ad_attempt").query(Long.class).single()).isZero();
    }

    /**
     * 판독 실패는 fail-closed다: 동의 판독이 예외면 500 공통 본문·{@code no-store}이고 결정 행이 없으며, 실패는 WARN 한 줄(예외 단순
     * 이름만)과 결과 code {@code ERROR}로 운영에서 보인다. 실패는 운영 코드의 시험 분기 없이 만든다 — 시험이 동의 표의 CHECK를 잠시
     * 내리고 읽을 수 없는 선택 값 하나를 넣는다(그 표는 이 시험들만 쓴다). 끝나면 행을 지우고 같은 정의로 CHECK를 되살린다.
     */
    @Test
    void 판독_실패는_500_공통_본문이고_행이_없으며_WARN으로_보인다(CapturedOutput output) throws Exception {
        Fixture fixture = manualSync("ad-read-failure");
        String definition = jdbc().sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'engagement_ad_consent_decision_choice_check'").query(String.class).single();
        jdbc().sql("ALTER TABLE engagement_ad_consent_decision DROP CONSTRAINT engagement_ad_consent_decision_choice_check")
                .update();
        try {
            insertConsent(fixture.user(), AdConsentApi.POLICY_VERSION, "UNREADABLE");
            var failed = judge(fixture.signed(), fixture.job());
            assertThat(failed.statusCode()).as(failed.body()).isEqualTo(500);
            assertThat(failed.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                    .contains("no-store"));
            assertThat(topLevelFieldsOf(failed.body())).containsOnlyKeys("code", "message", "correlationId")
                    .containsEntry("code", ApiErrorHandler.CODE_INTERNAL_ERROR);
            assertThat(rowsOf(fixture.user())).isZero();
            // 재시도도 같은 결말이고 행을 만들지 않는다(실패를 자격이나 건너뜀으로 접어 기록하지 않는다).
            assertThat(judge(fixture.signed(), fixture.job()).statusCode()).isEqualTo(500);
            assertThat(rowsOf(fixture.user())).isZero();
        } finally {
            jdbc().sql("DELETE FROM engagement_ad_consent_decision WHERE choice NOT IN ('GRANTED', 'DENIED')").update();
            jdbc().sql("ALTER TABLE engagement_ad_consent_decision ADD CONSTRAINT "
                    + "engagement_ad_consent_decision_choice_check " + definition).update();
        }
        String all = output.getOut() + output.getErr();
        assertThat(LogLines.events(all, "ad eligibility request")).anySatisfy(event -> assertThat(event)
                .contains("result=ERROR").contains("status=500"));
        List<Map<String, Object>> warnings = LogLines.records(all).stream()
                .filter(record -> String.valueOf(record.get("event")).contains("ad eligibility judgment failed closed"))
                .toList();
        assertThat(warnings).isNotEmpty().allSatisfy(record -> {
            assertThat(record).containsEntry("level", "WARN").containsEntry("exception", "IllegalArgumentException");
            assertThat(record.toString()).doesNotContain("UNREADABLE").doesNotContain(fixture.user().toString())
                    .doesNotContain(fixture.job().toString()).doesNotContain("\tat ");
        });

        // CHECK가 되살아났고, 동의하면 같은 결과가 정상 판정된다(실패가 행을 남기지 않았으므로 새 판정이다).
        grant(fixture);
        var recovered = judge(fixture.signed(), fixture.job());
        assertThat(topLevelFieldsOf(recovered.body())).as(recovered.body()).containsEntry("outcome", "ELIGIBLE")
                .containsEntry("replayed", false);
    }

    /** 스키마 CHECK의 값 집합이 코드의 닫힌 집합과 같다 — 사유는 {@code SkipReason}, 결말은 ELIGIBLE·SKIPPED. */
    @Test
    void 결정_표의_CHECK_값은_코드의_닫힌_집합과_같다() {
        assertThat(checkValues("engagement_ad_attempt_reason_check")).containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(AdEligibilityApi.SkipReason.values()).map(Enum::name).toList());
        assertThat(checkValues("engagement_ad_attempt_outcome_check")).containsExactlyInAnyOrder("ELIGIBLE", "SKIPPED");
        // 계측의 skip 사유는 그 다섯과 비후보·재전송이다.
        assertThat(AdTelemetry.SKIP_REASONS).containsAll(checkValues("engagement_ad_attempt_reason_check"))
                .contains(AdTelemetry.NOT_CANDIDATE, AdTelemetry.REPLAYED).hasSize(7);
    }

    /** 그 CHECK 정의({@code pg_get_constraintdef})에 따옴표로 나오는 값들. */
    private List<String> checkValues(String constraint) {
        String definition = jdbc().sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = :c")
                .param("c", constraint).query(String.class).single();
        List<String> values = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(definition);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as(definition).isNotEmpty();
        return values;
    }

    // ------------------------------------------------------------------ 도구

    /** 로그인 → Bybit 연결 → 첫 공식 발행 → 수동 동기화 성공. */
    private Fixture manualSync(String prefix) throws Exception {
        Signed signed = signedIn(prefix);
        store(signed.user(), DevBybitStub.Scenario.UTA2);
        publishedPlan(signed.user(), prefix, null);
        return new Fixture(signed, admitManual(signed.user(), key(prefix + "-manual")));
    }

    /** 수동 동기화 하나를 접수하고 성공을 기다린다. 다음 수동 접수와 대시보드가 쿨다운에 흔들리지 않게 접수 시각을 1분 당긴다. */
    private UUID admitManual(UUID user, String key) throws Exception {
        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key);
        assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        UUID job = ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        jdbc().sql("UPDATE sync_plan_sync_job SET admitted_at = admitted_at - interval '1 minute' WHERE job_id = :j")
                .param("j", job).update();
        return job;
    }

    private void grant(Fixture fixture) {
        var decided = this.consents.decide(fixture.user(), key("ad-grant"), AdConsentApi.Choice.GRANTED,
                AdConsentApi.POLICY_VERSION);
        assertThat(decided).isInstanceOf(AdConsentApi.Decision.Saved.class);
    }

    private HttpResponse<String> judge(Signed signed, UUID job) throws Exception {
        return sendJson("POST", PATH, "{\"syncJobId\":\"" + job + "\"}", signed.cookie(), headers(signed.csrf(), null));
    }

    /**
     * 그 attempt 행을 복제한다 — 새 {@code job_id}·멱등 키에 {@code overrides}의 열만 바꾼다. 실제 성공 attempt의 공식 결과·단계 행을
     * 그대로 가리키므로 스키마 CHECK를 지나는 실제 모양이다.
     */
    private UUID cloneJob(UUID source, Map<String, Object> overrides) {
        UUID id = UUID.randomUUID();
        Map<String, Object> all = new HashMap<>(overrides);
        all.put("job_id", id.toString());
        all.put("idempotency_key", "ad-clone-" + id);
        jdbc().sql("INSERT INTO sync_plan_sync_job SELECT (jsonb_populate_record(t, CAST(:o AS jsonb))).* "
                        + "FROM sync_plan_sync_job t WHERE t.job_id = :j")
                .param("o", JSON.writeValueAsString(all)).param("j", source).update();
        return id;
    }

    /** 공식 결과가 없는 끝(또는 진행 중) 모양의 덮어쓰기. */
    private static Map<String, Object> unpublished(String status, @org.jspecify.annotations.Nullable String code) {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("status", status);
        overrides.put("failure_code", code);
        overrides.put("calculation_id", null);
        overrides.put("published_pointer_version", null);
        return overrides;
    }

    /** 다른 결과의 자격 행 하나를 그 DB 시각 식으로 넣는다(빈도 제한의 입력). */
    private void insertEligibleRow(UUID user, String decidedAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, transition_ref, decided_at) "
                        + "VALUES (:u, :j, 'ELIGIBLE', :t, " + decidedAt + ")")
                .param("u", user).param("j", UUID.randomUUID())
                .param("t", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)).update();
    }

    /** 동의 결정 행 하나를 직접 넣는다(옛 정책 버전·읽을 수 없는 값을 만드는 시험용). 최신 행이 된다. */
    private void insertConsent(UUID user, String policyVersion, String choice) {
        jdbc().sql("INSERT INTO engagement_ad_consent_decision (user_id, policy_version, choice, idempotency_key_digest, "
                        + "request_digest) VALUES (:u, :p, :c, :k, :r)")
                .param("u", user).param("p", policyVersion).param("c", choice).param("k", sha256(UUID.randomUUID().toString()))
                .param("r", sha256(UUID.randomUUID().toString())).update();
    }

    private void clearEligible(UUID user) {
        jdbc().sql("DELETE FROM engagement_ad_attempt WHERE user_id = :u AND outcome = 'ELIGIBLE'").param("u", user)
                .update();
    }

    private long rowsOf(UUID user) {
        return jdbc().sql("SELECT count(*) FROM engagement_ad_attempt WHERE user_id = :u").param("u", user)
                .query(Long.class).single();
    }

    /** 그 사용자의 결정 행들, 순번 순서({@code ELIGIBLE:<transition>}·{@code SKIPPED:<사유>}). */
    private List<String> attempts(UUID user) {
        return jdbc().sql("SELECT outcome || ':' || COALESCE(skip_reason, transition_ref) FROM engagement_ad_attempt "
                + "WHERE user_id = :u ORDER BY id").param("u", user).query(String.class).list();
    }

    private static void assertSkipped(HttpResponse<String> response, String reason, boolean replayed) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(response.body())).as(response.body())
                .containsOnlyKeys("outcome", "reason", "transition", "replayed").containsEntry("outcome", "SKIPPED")
                .containsEntry("reason", reason).containsEntry("transition", null).containsEntry("replayed", replayed);
    }

    private static void assertNotFound(HttpResponse<String> response, String code, String what) {
        assertThat(response.statusCode()).as(what + ": " + response.body()).isEqualTo(404);
        assertThat(topLevelFieldsOf(response.body())).as(what).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", code);
    }

    private double count(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double skips(String reason) {
        var counter = meters.find(AdTelemetry.SKIP).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String scrape() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.managementPort
                    + "/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }

    private interface Call {
        HttpResponse<String> run() throws Exception;
    }

    /** 두 요청을 동시에 보낸다(가상 스레드 둘이 같은 출발선에서). */
    private static List<HttpResponse<String>> concurrently(Call first, Call second) throws Exception {
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
}
