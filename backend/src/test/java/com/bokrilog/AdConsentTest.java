package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.AdConsentApi;
import com.bokrilog.sync.IdempotencyKey;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 4.1: 문맥형 광고 동의를 선택하고 철회한다 — SPEC I/O 매트릭스 전 행.
 *
 * <p>사용자는 {@code GET /api/engagement/ad-consent}로 지금 상태를 보고 {@code POST}로 동의·비동의·철회를 결정한다. 결정은
 * 추가 전용 행이고 지금 상태는 최신 행이다. 옛 정책 버전의 행은 미결정으로 읽힌다. 쓰기는 사용자 잠금 → 생명주기 잠금 → 멱등
 * 판정 → INSERT 한 트랜잭션이고, 삭제가 시작된 계정은 어느 단계에서든 404이며 행을 만들지 않는다.
 *
 * <p>멱등 키는 실행 시점에 만든다(키 모양 리터럴은 gitleaks가 잡는다).
 */
@ExtendWith(OutputCaptureExtension.class)
class AdConsentTest extends AdTestBase {

    private static final String CONSENT = AdConsentController.AD_CONSENT_PATH;

    private static final String VERSION = AdConsentApi.POLICY_VERSION;

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @Autowired
    private AccountErasure erasure;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * 결정을 직렬화하는 사용자 advisory 잠금 이름의 접두사. 운영 값({@code AdConsentRepository.USER_LOCK_PREFIX})과 같아야 아래 잠금
     * 시험이 같은 잠금을 쥔다 — 운영 값이 바뀌면 그 시험이 "잠금을 쥐어도 응답이 끝났다"로 실패한다.
     */
    private static final String USER_LOCK_PREFIX = "bokrilog.engagement.ad_consent.user:";

    // ------------------------------------------------------------------ 상태 전이

    @Test
    void 첫_조회는_미결정이고_정책_버전을_싣고_결정_시각이_없다() throws Exception {
        Signed user = signedIn("consent-first");
        var read = get(CONSENT, user.cookie());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
        assertThat(topLevelFieldsOf(read.body())).containsOnlyKeys("state", "policyVersion", "decidedAt")
                .containsEntry("state", "UNDECIDED").containsEntry("policyVersion", VERSION)
                .containsEntry("decidedAt", null);
        assertThat(rows(user)).isZero();
    }

    @Test
    void 동의는_행_하나와_DB_시각이고_철회는_DENIED_행을_더해_다음_조회부터_적용된다() throws Exception {
        Signed user = signedIn("consent-grant");
        var granted = decide(user, "GRANTED", VERSION, key("consent-grant"));
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(200);
        assertThat(granted.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
        Map<String, Object> view = topLevelFieldsOf(granted.body());
        assertThat(view).containsOnlyKeys("state", "policyVersion", "decidedAt").containsEntry("state", "GRANTED")
                .containsEntry("policyVersion", VERSION);
        Instant decidedAt = Instant.parse((String) view.get("decidedAt"));
        assertThat(rows(user)).isEqualTo(1);
        assertThat(latestDecidedAt(user)).isEqualTo(decidedAt);
        assertThat(java.time.Duration.between(decidedAt, databaseNow()).abs()).isLessThan(java.time.Duration.ofMinutes(1));
        assertThat(topLevelFieldsOf(get(CONSENT, user.cookie()).body())).isEqualTo(view);
        assertThat(this.consents.current(user.user()).granted()).isTrue();

        var revoked = decide(user, "DENIED", VERSION, key("consent-revoke"));
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(revoked.body())).containsEntry("state", "DENIED");
        assertThat(rows(user)).isEqualTo(2);
        assertThat(jdbc().sql("SELECT choice FROM engagement_ad_consent_decision WHERE user_id = :u ORDER BY id")
                .param("u", user.user()).query(String.class).list()).containsExactly("GRANTED", "DENIED");
        assertThat(topLevelFieldsOf(get(CONSENT, user.cookie()).body())).containsEntry("state", "DENIED");
        assertThat(this.consents.current(user.user()).granted()).isFalse();

        // 다시 동의할 수 있다 — 결정은 쌓인다.
        var again = decide(user, "GRANTED", VERSION, key("consent-regrant"));
        assertThat(topLevelFieldsOf(again.body())).containsEntry("state", "GRANTED");
        assertThat(rows(user)).isEqualTo(3);
    }

    @Test
    void 지금_상태와_같은_선택은_새_키여도_행을_더하지_않고_지금_상태로_200이다() throws Exception {
        Signed user = signedIn("consent-same");
        var first = decide(user, "GRANTED", VERSION, key("consent-same"));
        assertThat(first.statusCode()).isEqualTo(200);
        var same = decide(user, "GRANTED", VERSION, key("consent-same"));
        assertThat(same.statusCode()).as(same.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(same.body())).isEqualTo(topLevelFieldsOf(first.body()));
        assertThat(rows(user)).isEqualTo(1);

        // 미결정에서 비동의를 고르는 것도 결정이다(행 하나).
        Signed other = signedIn("consent-deny-first");
        var denied = decide(other, "DENIED", VERSION, key("consent-deny"));
        assertThat(topLevelFieldsOf(denied.body())).containsEntry("state", "DENIED");
        assertThat(rows(other)).isEqualTo(1);
    }

    @Test
    void 최신_행이_옛_정책_버전이면_미결정이고_다시_고르면_새_버전의_행을_쓴다() throws Exception {
        Signed user = signedIn("consent-old-policy");
        insertConsent(user.user(), "AD-CONSENT-0.9", "GRANTED");
        assertThat(topLevelFieldsOf(get(CONSENT, user.cookie()).body())).containsEntry("state", "UNDECIDED")
                .containsEntry("policyVersion", VERSION).containsEntry("decidedAt", null);
        assertThat(this.consents.current(user.user()).granted()).isFalse();

        // 자동 재동의가 없다 — 같은 선택이어도 지금 버전으로 다시 써야 한다.
        var granted = decide(user, "GRANTED", VERSION, key("consent-old-policy"));
        assertThat(topLevelFieldsOf(granted.body())).containsEntry("state", "GRANTED");
        assertThat(rows(user)).isEqualTo(2);
        assertThat(jdbc().sql("SELECT policy_version FROM engagement_ad_consent_decision WHERE user_id = :u "
                + "ORDER BY id").param("u", user.user()).query(String.class).list())
                .containsExactly("AD-CONSENT-0.9", VERSION);
    }

    @Test
    void 요청의_정책_버전이_지금과_다르면_409이고_아무것도_쓰지_않는다() throws Exception {
        Signed user = signedIn("consent-policy");
        var refused = decide(user, "GRANTED", "AD-CONSENT-0.9", key("consent-policy"));
        assertRefused(refused, 409, AdConsentController.POLICY_CHANGED);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId");
        assertThat(rows(user)).isZero();
        assertThat(topLevelFieldsOf(get(CONSENT, user.cookie()).body())).containsEntry("state", "UNDECIDED");
    }

    // ------------------------------------------------------------------ 멱등

    @Test
    void 같은_키_같은_본문은_재전송_200이고_같은_키_다른_본문은_409다() throws Exception {
        Signed user = signedIn("consent-idem");
        String key = key("consent-idem");
        var first = decide(user, "GRANTED", VERSION, key);
        assertThat(first.statusCode()).isEqualTo(200);
        var again = decide(user, "GRANTED", VERSION, key);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(again.body())).isEqualTo(topLevelFieldsOf(first.body()));
        assertThat(rows(user)).isEqualTo(1);

        var conflict = decide(user, "DENIED", VERSION, key);
        assertRefused(conflict, 409, IdempotencyKey.CONFLICT_CODE);
        assertThat(rows(user)).isEqualTo(1);
        assertThat(topLevelFieldsOf(get(CONSENT, user.cookie()).body())).containsEntry("state", "GRANTED");

        // 재전송의 본문은 지금 상태다 — 그 사이 철회가 있었으면 철회가 보이고 행이 늘지 않는다.
        assertThat(decide(user, "DENIED", VERSION, key("consent-idem-revoke")).statusCode()).isEqualTo(200);
        var replay = decide(user, "GRANTED", VERSION, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replay.body())).containsEntry("state", "DENIED");
        assertThat(rows(user)).isEqualTo(2);

        // 키 원문은 남지 않는다(digest만). 같은 키라도 다른 사용자의 것은 별개다.
        assertThat(jdbc().sql("SELECT count(*) FROM engagement_ad_consent_decision WHERE CAST("
                + "row_to_json(engagement_ad_consent_decision) AS text) LIKE :k").param("k", "%" + key + "%")
                .query(Long.class).single()).isZero();
        Signed another = signedIn("consent-idem-other");
        var theirs = decide(another, "DENIED", VERSION, key);
        assertThat(theirs.statusCode()).as(theirs.body()).isEqualTo(200);
        assertThat(rows(another)).isEqualTo(1);
    }

    @Test
    void 키_없음과_형식_위반은_422이고_본문_모양_오류는_400이며_아무것도_쓰지_않는다() throws Exception {
        Signed user = signedIn("consent-shape");
        String body = body("GRANTED", VERSION);
        assertRefused(sendJson("POST", CONSENT, body, user.cookie(), headers(user.csrf(), null)), 422,
                "IDEMPOTENCY_KEY_REQUIRED");
        assertRefused(sendJson("POST", CONSENT, body, user.cookie(), headers(user.csrf(), "short")), 422,
                "IDEMPOTENCY_KEY_REQUIRED");
        assertRefused(sendJson("POST", CONSENT, body, user.cookie(), headers(user.csrf(), "-" + key("lead"))), 422,
                "IDEMPOTENCY_KEY_REQUIRED");

        for (String malformed : List.of("{\"policyVersion\":\"" + VERSION + "\"}",
                "{\"choice\":\"MAYBE\",\"policyVersion\":\"" + VERSION + "\"}",
                "{\"choice\":\"granted\",\"policyVersion\":\"" + VERSION + "\"}",
                "{\"choice\":\"GRANTED\"}",
                "{\"choice\":true,\"policyVersion\":\"" + VERSION + "\"}",
                "{\"choice\":\"GRANTED\",\"policyVersion\":1}",
                "[\"GRANTED\"]",
                "not json",
                "{\"choice\":\"GRANTED\",\"policyVersion\":\"" + "x".repeat(2000) + "\"}")) {
            var refused = sendJson("POST", CONSENT, malformed, user.cookie(), headers(user.csrf(), key("shape")));
            assertRefused(refused, 400, "BAD_REQUEST");
            assertThat(topLevelFieldsOf(refused.body())).as(malformed)
                    .containsOnlyKeys("code", "message", "correlationId");
        }
        assertThat(rows(user)).isZero();
    }

    @Test
    void CSRF_없음과_교차_출처는_403이고_미인증_조회는_401이며_아무것도_쓰지_않는다() throws Exception {
        Signed user = signedIn("consent-csrf");
        String body = body("GRANTED", VERSION);
        var noCsrf = sendJson("POST", CONSENT, body, user.cookie(), headers(null, key("consent-csrf")));
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        var crossOrigin = sendJson("POST", CONSENT, body, user.cookie(), "Origin", "https://attacker.example",
                AuthSecurityConfig.CSRF_HEADER_NAME, user.csrf(), "Idempotency-Key", key("consent-cross"));
        assertThat(crossOrigin.statusCode()).isEqualTo(403);
        assertThat(rows(user)).isZero();

        assertThat(get(CONSENT, null).statusCode()).isEqualTo(401);
        // 세션 없는 변경은 세션 귀속 CSRF 토큰이 없어 인증 판정 앞에서 403으로 끝난다(다른 상태 변경 표면과 같다).
        assertThat(sendJson("POST", CONSENT, body, null, headers(null, key("consent-anon"))).statusCode())
                .isEqualTo(403);
        assertThat(jdbc().sql("SELECT count(*) FROM engagement_ad_consent_decision").query(Long.class).single())
                .isZero();
        // 하위 경로도 같은 판정이다: 인증 없이는 401, 인증되면 없는 경로와 같은 404.
        assertThat(get(AuthSecurityConfig.ENGAGEMENT_PATH + "/nope", null).statusCode()).isEqualTo(401);
        assertThat(get(AuthSecurityConfig.ENGAGEMENT_PATH + "/nope", user.cookie()).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 삭제

    @Test
    void 삭제가_시작된_계정은_조회와_변경이_공통_404이고_행을_만들지_않는다() throws Exception {
        Signed user = signedIn("consent-deleting");
        assertThat(decide(user, "GRANTED", VERSION, key("consent-deleting")).statusCode()).isEqualTo(200);
        accountDeletion().beginDeletion(user.user());

        String missing = topLevelFieldsOf(get("/api/engagement/nope", user.cookie()).body()).get("code").toString();
        var read = get(CONSENT, user.cookie());
        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(read.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", missing);
        var refused = decide(user, "DENIED", VERSION, key("consent-deleting"));
        assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", missing);
        assertThat(rows(user)).isEqualTo(1);
    }

    @Test
    void 접근_판정_뒤_삭제가_커밋되면_쓰기_잠금이_거절하고_행이_다시_생기지_않는다() throws Exception {
        Signed user = signedIn("consent-race-delete");
        // 컨트롤러의 접근 판정을 지난 뒤 삭제가 커밋된 상황: 모듈 API를 직접 부른다.
        accountDeletion().beginDeletion(user.user());
        assertThat(this.consents.decide(user.user(), key("consent-race-delete"), AdConsentApi.Choice.GRANTED, VERSION))
                .isInstanceOf(AdConsentApi.Decision.Denied.class);
        assertThat(rows(user)).isZero();
        // 없는 계정도 같다.
        UUID nobody = UUID.randomUUID();
        assertThat(this.consents.decide(nobody, key("consent-nobody"), AdConsentApi.Choice.GRANTED, VERSION))
                .isInstanceOf(AdConsentApi.Decision.Denied.class);
        assertThat(jdbc().sql("SELECT count(*) FROM engagement_ad_consent_decision WHERE user_id = :u")
                .param("u", nobody).query(Long.class).single()).isZero();
    }

    @Test
    void eraseConsents는_그_사용자의_행만_지우고_멱등이다() throws Exception {
        Signed erased = signedIn("consent-erased");
        Signed kept = signedIn("consent-kept");
        String key = key("consent-erase");
        decide(erased, "GRANTED", VERSION, key);
        decide(erased, "DENIED", VERSION, key("consent-erase"));
        decide(kept, "GRANTED", VERSION, key("consent-erase"));

        assertThat(this.deletion.remainingRowsOf(erased.user())).isEqualTo(2);
        assertThat(this.deletion.eraseConsents(erased.user())).isEqualTo(2);
        assertThat(rows(erased)).isZero();
        assertThat(this.deletion.remainingRowsOf(erased.user())).isZero();
        assertThat(rows(kept)).isEqualTo(1);
        assertThat(this.deletion.remainingRowsOf(kept.user())).isEqualTo(1);
        assertThat(this.deletion.eraseConsents(erased.user())).isZero();
        assertThat(this.deletion.eraseConsents(null)).isZero();
        // 멱등 기록도 사라졌다 — 같은 키가 새 결정이 된다.
        var again = decide(erased, "GRANTED", VERSION, key);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(rows(erased)).isEqualTo(1);
    }

    @Test
    void 계정_삭제_orchestrator가_동의_행을_지우고_잔여_행이_0이다() throws Exception {
        Signed erased = signedIn("consent-account-erased");
        Signed kept = signedIn("consent-account-kept");
        decide(erased, "GRANTED", VERSION, key("consent-account"));
        decide(kept, "GRANTED", VERSION, key("consent-account"));

        assertThat(this.erasure.erase(erased.user(), "consent-erasure-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        assertThat(this.deletion.remainingRowsOf(erased.user())).isZero();
        assertThat(rows(erased)).isZero();
        assertThat(rows(kept)).isEqualTo(1);
        // 지워진 계정의 늦은 결정도 행을 만들지 않는다.
        assertThat(this.consents.decide(erased.user(), key("consent-late"), AdConsentApi.Choice.GRANTED, VERSION))
                .isInstanceOf(AdConsentApi.Decision.Denied.class);
        assertThat(rows(erased)).isZero();
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 결정은_사용자_잠금을_기다리고_잠금이_풀린_뒤에만_쓴다() throws Exception {
        Signed user = signedIn("consent-lock");
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var template = new org.springframework.transaction.support.TransactionTemplate(this.transactionManager);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            // 시험 트랜잭션이 결정 트랜잭션과 같은 잠금을 먼저 쥔다.
            var holder = executor.submit(() -> template.executeWithoutResult(status -> {
                jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                        .param("name", USER_LOCK_PREFIX + user.user()).query((rs, row) -> 1).single();
                locked.countDown();
                try {
                    if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("잠금을 풀 신호가 오지 않았다.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var pending = executor.submit(() -> decide(user, "GRANTED", VERSION, key("consent-lock")));

            // 잠금을 쥔 동안 결정은 끝나지 않고 행도 없다.
            Thread.sleep(1500);
            assertThat(pending.isDone()).isFalse();
            assertThat(rows(user)).isZero();

            release.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var decided = pending.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(decided.statusCode()).as(decided.body()).isEqualTo(200);
            assertThat(topLevelFieldsOf(decided.body())).containsEntry("state", "GRANTED");
            assertThat(rows(user)).isEqualTo(1);
        }
    }

    @Test
    void 서로_다른_키로_같은_선택을_동시에_보내면_행은_하나다() throws Exception {
        Signed user = signedIn("consent-race-same-choice");
        var both = concurrently(() -> decide(user, "GRANTED", VERSION, key("consent-same-choice-a")),
                () -> decide(user, "GRANTED", VERSION, key("consent-same-choice-b")));
        assertThat(both).extracting(HttpResponse::statusCode).containsExactly(200, 200);
        assertThat(both).extracting(response -> topLevelFieldsOf(response.body()).get("state"))
                .containsOnly("GRANTED");
        // 나중 결정은 잠금 아래에서 앞선 결정을 보고 같은 선택이라 쓰지 않는다.
        assertThat(rows(user)).isEqualTo(1);
    }

    @Test
    void 두_탭의_서로_다른_동시_선택은_직렬화되어_둘_다_200이고_최종_상태는_나중_커밋이다() throws Exception {
        Signed user = signedIn("consent-race");
        var both = concurrently(() -> decide(user, "GRANTED", VERSION, key("consent-race-a")),
                () -> decide(user, "DENIED", VERSION, key("consent-race-b")));
        assertThat(both).extracting(HttpResponse::statusCode).containsExactly(200, 200);
        assertThat(rows(user)).isEqualTo(2);
        // 나중에 커밋한 결정이 순번도 시각도 뒤다.
        record Decided(String choice, OffsetDateTime at) {}
        List<Decided> ordered = jdbc().sql("SELECT choice, decided_at FROM engagement_ad_consent_decision "
                + "WHERE user_id = :u ORDER BY id").param("u", user.user())
                .query((rs, n) -> new Decided(rs.getString(1), rs.getObject(2, OffsetDateTime.class))).list();
        assertThat(ordered.get(1).at()).isAfterOrEqualTo(ordered.get(0).at());
        assertThat(ordered.get(1).choice()).isNotEqualTo(ordered.get(0).choice());
        assertThat(topLevelFieldsOf(get(CONSENT, user.cookie()).body())).containsEntry("state", ordered.get(1).choice());

        // 같은 키의 동시 결정은 행 하나다.
        Signed other = signedIn("consent-race-same");
        String key = key("consent-race-same");
        var same = concurrently(() -> decide(other, "GRANTED", VERSION, key),
                () -> decide(other, "GRANTED", VERSION, key));
        assertThat(same).extracting(HttpResponse::statusCode).containsExactly(200, 200);
        assertThat(rows(other)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 로그

    @Test
    void 로그는_단계와_결과_code뿐이고_사용자_선택값_키가_없다(CapturedOutput output) throws Exception {
        Signed user = signedIn("consent-log");
        String grantKey = key("consent-log");
        decide(user, "GRANTED", VERSION, grantKey);
        decide(user, "GRANTED", VERSION, grantKey);
        decide(user, "DENIED", VERSION, key("consent-log"));
        decide(user, "DENIED", "AD-CONSENT-0.9", key("consent-log"));
        get(CONSENT, user.cookie());

        List<String> events = LogLines.events(output.getOut() + output.getErr(), "ad consent request");
        assertThat(events).hasSizeGreaterThanOrEqualTo(5);
        assertThat(events).anySatisfy(event -> assertThat(event).contains("result=SAVED"))
                .anySatisfy(event -> assertThat(event).contains("result=REPLAYED"))
                .anySatisfy(event -> assertThat(event).contains("result=" + AdConsentController.POLICY_CHANGED));
        for (String event : events) {
            assertThat(event).doesNotContain(user.user().toString()).doesNotContain(user.subject())
                    .doesNotContain("GRANTED").doesNotContain("DENIED").doesNotContain(grantKey);
        }
        String all = output.getOut() + output.getErr();
        assertThat(all).doesNotContain(grantKey);
    }

    /**
     * 판독 실패는 500 공통 본문·{@code no-store}이고, 운영에서 보이도록 WARN 한 줄(예외 단순 이름만)과 결과 code {@code ERROR}의
     * 요약 줄로 남는다(Epic 4 회고 D-2 — 4.2·4.3 컨트롤러와 같은 관용). 실패는 운영 코드의 시험 분기 없이 만든다 — 동의 표의 CHECK를
     * 잠시 내리고 읽을 수 없는 선택 값 하나를 넣는다({@code AdEligibilityTest}·{@code AdTransitionTest}와 같은 방법). 끝나면 행을
     * 지우고 같은 정의로 CHECK를 되살린다. WARN 줄의 문장은 요약 줄의 접두사({@code "ad consent request"})를 담지 않아 요약 줄을
     * 세는 필터(위 로그 시험)에 섞이지 않는다.
     */
    @Test
    void 판독_실패는_500_공통_본문이고_WARN으로_보이며_요약_줄_필터에_섞이지_않는다(CapturedOutput output) throws Exception {
        Signed user = signedIn("consent-read-failure");
        String definition = jdbc().sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'engagement_ad_consent_decision_choice_check'").query(String.class).single();
        jdbc().sql("ALTER TABLE engagement_ad_consent_decision DROP CONSTRAINT engagement_ad_consent_decision_choice_check")
                .update();
        try {
            insertConsent(user.user(), VERSION, "UNREADABLE");
            var failed = get(CONSENT, user.cookie());
            assertThat(failed.statusCode()).as(failed.body()).isEqualTo(500);
            assertThat(failed.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                    .contains("no-store"));
            assertThat(topLevelFieldsOf(failed.body())).containsOnlyKeys("code", "message", "correlationId")
                    .containsEntry("code", ApiErrorHandler.CODE_INTERNAL_ERROR);
            assertThat(failed.body()).doesNotContain("UNREADABLE").doesNotContain(user.user().toString());
        } finally {
            jdbc().sql("DELETE FROM engagement_ad_consent_decision WHERE choice NOT IN ('GRANTED', 'DENIED')").update();
            jdbc().sql("ALTER TABLE engagement_ad_consent_decision ADD CONSTRAINT "
                    + "engagement_ad_consent_decision_choice_check " + definition).update();
        }
        String all = output.getOut() + output.getErr();
        List<String> summaries = LogLines.events(all, "ad consent request");
        assertThat(summaries).anySatisfy(event -> assertThat(event).contains("step=read").contains("result=ERROR")
                .contains("status=500"));
        // 요약 줄 필터는 실패 줄을 세지 않는다 — 걸린 줄은 모두 요약 줄이다.
        assertThat(summaries).allSatisfy(event -> assertThat(event).startsWith("ad consent request: "));
        List<Map<String, Object>> warnings = LogLines.records(all).stream()
                .filter(record -> String.valueOf(record.get("event")).contains("ad consent failed closed"))
                .toList();
        assertThat(warnings).isNotEmpty().allSatisfy(record -> {
            assertThat(record).containsEntry("level", "WARN").containsEntry("exception", "IllegalArgumentException");
            assertThat(record.toString()).doesNotContain("UNREADABLE").doesNotContain("GRANTED")
                    .doesNotContain("DENIED").doesNotContain(user.user().toString()).doesNotContain(user.subject())
                    .doesNotContain("\tat ");
        });
    }

    // ------------------------------------------------------------------ 도구

    private HttpResponse<String> decide(Signed signed, String choice, String policyVersion, String key)
            throws Exception {
        return sendJson("POST", CONSENT, body(choice, policyVersion), signed.cookie(), headers(signed.csrf(), key));
    }

    private static String body(String choice, String policyVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("choice", choice);
        body.put("policyVersion", policyVersion);
        return JSON.writeValueAsString(body);
    }

    private long rows(Signed signed) {
        return jdbc().sql("SELECT count(*) FROM engagement_ad_consent_decision WHERE user_id = :u")
                .param("u", signed.user()).query(Long.class).single();
    }

    private Instant latestDecidedAt(Signed signed) {
        return jdbc().sql("SELECT decided_at FROM engagement_ad_consent_decision WHERE user_id = :u "
                + "ORDER BY id DESC LIMIT 1").param("u", signed.user())
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(topLevelFieldsOf(response.body())).containsEntry("code", code);
    }
}
