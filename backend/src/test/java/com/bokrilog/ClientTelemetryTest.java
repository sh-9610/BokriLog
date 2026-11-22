package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.engagement.ClientTelemetryApi;
import com.bokrilog.operations.AdvertisingStopApi;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionSubject;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 4.5: 동일 출처 beacon({@code POST /api/engagement/telemetry})의 계약, 광고 문서 Vitals 표본, 성능 kill switch 판정 — SPEC I/O 매트릭스의 서버 행 전부.
 *
 * <p>표본의 전제(SHOW로 소비된 attempt)는 자격·소비 왕복이 아니라 그 사용자의 attempt 행을 DB 시각 식으로 직접 넣어 만든다 — 소비
 * 시각(10분 창)을 결정적으로 고르기 위해서다. 판정은 스케줄을 기다리지 않고 {@link TelemetryProbe}로 지금 한 번 돌린다. 판정이 5분
 * 주기로 배경에서도 돌 수 있으므로 판정 시험은 반환값이 아니라 끝난 상태(제한·원장·표본)를 단정한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClientTelemetryTest extends AdTestBase {

    private static final String PATH = ClientTelemetryController.TELEMETRY_PATH;

    @Autowired
    private OperationalRestrictionApi restrictions;

    // ------------------------------------------------------------------ beacon 계약

    @Test
    void 어휘_안의_묶음은_204이고_닫힌_어휘의_카운터를_올린다(CapturedOutput output) throws Exception {
        Signed signed = signedIn("telemetry-ok");
        double lcpGood = vitals("LCP", "/dashboard", "GOOD");
        double clsPoor = vitals("CLS", "/settings", "POOR");
        double inpPoor = vitals("INP", "/plan-conditions", "POOR");
        double timeout = clientEvents("FAILED", "TIMEOUT");
        double requested = clientEvents("REQUESTED", "NONE");

        var recorded = beacon(signed, events(vital("LCP", "/dashboard", "1200.5", false),
                vital("CLS", "/settings", "0.3", false), vital("INP", "/plan-conditions", "201", false),
                ad("REQUESTED", null), ad("FAILED", "TIMEOUT")));
        assertThat(recorded.statusCode()).as(recorded.body()).isEqualTo(204);
        assertThat(recorded.body()).isEmpty();
        assertThat(recorded.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
        assertThat(vitals("LCP", "/dashboard", "GOOD")).isEqualTo(lcpGood + 1);
        assertThat(vitals("CLS", "/settings", "POOR")).isEqualTo(clsPoor + 1);
        assertThat(vitals("INP", "/plan-conditions", "POOR")).isEqualTo(inpPoor + 1);
        assertThat(clientEvents("FAILED", "TIMEOUT")).isEqualTo(timeout + 1);
        assertThat(clientEvents("REQUESTED", "NONE")).isEqualTo(requested + 1);

        // 임계와 같은 값은 GOOD이다(넘어야 POOR).
        double lcpEdge = vitals("LCP", "/ad-transition", "GOOD");
        double clsEdge = vitals("CLS", "/ad-transition", "GOOD");
        assertThat(beacon(signed, events(vital("LCP", "/ad-transition", "2500", false),
                vital("CLS", "/ad-transition", "0.1", false))).statusCode()).isEqualTo(204);
        assertThat(vitals("LCP", "/ad-transition", "GOOD")).isEqualTo(lcpEdge + 1);
        assertThat(vitals("CLS", "/ad-transition", "GOOD")).isEqualTo(clsEdge + 1);

        // 로그는 결과·상태·이벤트 수뿐이다. 사용자가 없다.
        String all = output.getOut() + output.getErr();
        assertThat(LogLines.events(all, "client telemetry request")).anySatisfy(event -> assertThat(event)
                .contains("result=RECORDED").contains("status=204").contains("events=5"))
                .allSatisfy(event -> assertThat(event).doesNotContain(signed.user().toString()));
    }

    @Test
    void 어휘_밖이나_모양_밖이_하나라도_있으면_400이고_아무것도_기록하지_않는다() throws Exception {
        Signed signed = signedIn("telemetry-shape");
        String good = vital("LCP", "/dashboard", "1000", false);
        List<String> sixteen = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            sixteen.add(good);
        }
        List<String> seventeen = new ArrayList<>(sixteen);
        seventeen.add(good);
        List<String> malformed = new ArrayList<>(List.of("", "null", "[]", "not json", "{}", "{\"events\":[]}",
                "{\"events\":{}}", "{\"events\":[" + good + "],\"extra\":1}",
                events(good, vital("FID", "/dashboard", "10", false)),
                events(good, vital("LCP", "/onboarding", "10", false)),
                events(good, vital("LCP", "/dashboard?x=1", "10", false)),
                events(good, vital("LCP", "/dashboard", "-1", false)),
                events(good, "{\"kind\":\"VITAL\",\"metric\":\"LCP\",\"route\":\"/dashboard\",\"value\":\"10\",\"ad\":false}"),
                events(good, "{\"kind\":\"VITAL\",\"metric\":\"LCP\",\"route\":\"/dashboard\",\"value\":1e400,\"ad\":false}"),
                events(good, "{\"kind\":\"VITAL\",\"metric\":\"LCP\",\"route\":\"/dashboard\",\"value\":10,\"ad\":\"true\"}"),
                events(good, "{\"kind\":\"VITAL\",\"metric\":\"LCP\",\"route\":\"/dashboard\",\"value\":10}"),
                events(good, "{\"kind\":\"VITAL\",\"metric\":\"LCP\",\"route\":\"/dashboard\",\"value\":10,\"ad\":false,"
                        + "\"user\":\"x\"}"),
                events(good, ad("FAILED", null)), events(good, ad("CLOSED", "TIMEOUT")),
                events(good, ad("FAILED", "SLOW")), events(good, ad("SHOWN", null)),
                events(good, "{\"kind\":\"OTHER\"}"), events(good, "[]"), events(seventeen.toArray(String[]::new))));
        // 8 KiB 초과: 어휘 안의 16건 뒤에 공백만 덧붙여도 거절한다.
        malformed.add(events(sixteen.toArray(String[]::new)) + " ".repeat(ClientTelemetryApi.MAX_BODY_BYTES));
        double before = total(AdTelemetry.WEB_VITALS) + total(AdTelemetry.CLIENT_EVENT);
        for (String body : malformed) {
            var refused = beaconBody(signed, body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(topLevelFieldsOf(refused.body())).as(body)
                    .containsOnlyKeys("code", "message", "correlationId").containsEntry("code", "BAD_REQUEST");
        }
        assertThat(total(AdTelemetry.WEB_VITALS) + total(AdTelemetry.CLIENT_EVENT)).isEqualTo(before);
        // 16건·8 KiB 이하는 받는다.
        assertThat(beaconBody(signed, events(sixteen.toArray(String[]::new))).statusCode()).isEqualTo(204);
    }

    @Test
    void CSRF_없음과_교차_출처는_403_미인증은_401_삭제가_시작된_계정은_404이고_기록하지_않는다() throws Exception {
        Signed signed = signedIn("telemetry-guard");
        String body = events(vital("LCP", "/dashboard", "1000", false), ad("RESULT", null));
        double before = total(AdTelemetry.WEB_VITALS) + total(AdTelemetry.CLIENT_EVENT);
        assertThat(sendJson("POST", PATH, body, signed.cookie(), headers(null, null)).statusCode()).isEqualTo(403);
        assertThat(sendJson("POST", PATH, body, signed.cookie(), "Origin", "https://attacker.example",
                AuthSecurityConfig.CSRF_HEADER_NAME, signed.csrf()).statusCode()).isEqualTo(403);
        assertThat(sendJson("POST", PATH, body, null, headers(signed.csrf(), null)).statusCode()).isIn(401, 403);
        assertThat(get(PATH, signed.cookie()).statusCode()).isEqualTo(405);
        String missing = topLevelFieldsOf(get("/api/engagement/nope", signed.cookie()).body()).get("code").toString();

        accountDeletion().beginDeletion(signed.user());
        var denied = beaconBody(signed, body);
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(404);
        assertThat(topLevelFieldsOf(denied.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", missing);
        assertThat(total(AdTelemetry.WEB_VITALS) + total(AdTelemetry.CLIENT_EVENT)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 광고 문서 Vitals 표본

    @Test
    void SHOW_소비_뒤_10분_안의_첫_광고_문서_Vitals만_metric마다_표본이_되고_두_번째_묶음은_카운터만_올린다()
            throws Exception {
        Signed signed = signedIn("telemetry-sample");
        long shown = insertConsumed(signed.user(), true, "statement_timestamp() - interval '5 minutes'");
        long samplesBefore = sampleCount();
        double lcp = vitals("LCP", "/ad-transition", "POOR");
        double lcpAd = vitals("LCP", "/ad-transition", "POOR", "true");

        String body = events(vital("LCP", "/ad-transition", "3000", true), vital("CLS", "/ad-transition", "0.05", true),
                vital("INP", "/ad-transition", "120", true), vital("LCP", "/ad-transition", "9999", true),
                vital("LCP", "/dashboard", "800", false), ad("RESULT", null));
        assertThat(beaconBody(signed, body).statusCode()).isEqualTo(204);
        assertThat(sampleCount()).isEqualTo(samplesBefore + 3);
        // metric마다 묶음의 첫 값 하나.
        assertThat(jdbc().sql("SELECT metric || ':' || value FROM engagement_ad_vital_sample ORDER BY id DESC LIMIT 3")
                .query(String.class).list()).containsExactlyInAnyOrder("LCP:3000", "CLS:0.05", "INP:120");
        assertThat(reportedMetrics(shown)).isEqualTo("CLS,INP,LCP");

        // 같은 attempt의 두 번째 묶음: 카운터만(같은 metric은 SHOW당 하나).
        assertThat(beaconBody(signed, body).statusCode()).isEqualTo(204);
        assertThat(sampleCount()).isEqualTo(samplesBefore + 3);
        assertThat(vitals("LCP", "/ad-transition", "POOR")).isEqualTo(lcp + 4);
        // 광고 문서의 값은 ad=true로 센다.
        assertThat(vitals("LCP", "/ad-transition", "POOR", "true")).isEqualTo(lcpAd + 4);
    }

    @Test
    void 한_문서의_Vitals가_여러_묶음으로_나뉘어_와도_metric마다_하나씩_표본이_된다() throws Exception {
        Signed signed = signedIn("telemetry-split");
        long shown = insertConsumed(signed.user(), true, "statement_timestamp() - interval '1 minute'");

        // 결과 보기 flush에 LCP만, 문서 hidden에 CLS·INP.
        assertThat(beaconBody(signed, events(vital("LCP", "/ad-transition", "2100", true), ad("RESULT", null)))
                .statusCode()).isEqualTo(204);
        assertThat(reportedMetrics(shown)).isEqualTo("LCP");
        assertThat(beaconBody(signed, events(vital("CLS", "/ad-transition", "0.02", true),
                vital("INP", "/ad-transition", "80", true))).statusCode()).isEqualTo(204);
        assertThat(sampleCount()).isEqualTo(3);
        assertThat(reportedMetrics(shown)).isEqualTo("CLS,INP,LCP");

        // 같은 metric을 다시 보내도 표본은 그 metric 하나다.
        assertThat(beaconBody(signed, events(vital("LCP", "/ad-transition", "5000", true))).statusCode())
                .isEqualTo(204);
        assertThat(jdbc().sql("SELECT count(*) FROM engagement_ad_vital_sample WHERE metric = 'LCP'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 같은_attempt의_동시_묶음_둘은_metric마다_표본_하나다() throws Exception {
        Signed signed = signedIn("telemetry-race");
        insertConsumed(signed.user(), true, "statement_timestamp() - interval '1 minute'");
        String body = events(vital("LCP", "/ad-transition", "1500", true), vital("CLS", "/ad-transition", "0.01", true),
                vital("INP", "/ad-transition", "90", true));
        var start = new java.util.concurrent.CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> {
                start.await();
                return beaconBody(signed, body).statusCode();
            });
            var b = executor.submit(() -> {
                start.await();
                return beaconBody(signed, body).statusCode();
            });
            start.countDown();
            statuses.add(a.get());
            statuses.add(b.get());
        }
        assertThat(statuses).containsExactly(204, 204);
        assertThat(jdbc().sql("SELECT metric FROM engagement_ad_vital_sample ORDER BY metric").query(String.class)
                .list()).containsExactly("CLS", "INP", "LCP");
    }

    @Test
    void 표본은_SHOW_소비_뒤_10분_경계_안에서만_된다() throws Exception {
        Signed inside = signedIn("telemetry-edge-inside");
        Signed outside = signedIn("telemetry-edge-outside");
        long in = insertConsumed(inside.user(), true, "statement_timestamp() - interval '595 seconds'");
        long out = insertConsumed(outside.user(), true, "statement_timestamp() - interval '605 seconds'");
        String body = events(vital("LCP", "/ad-transition", "1000", true));

        assertThat(beaconBody(inside, body).statusCode()).isEqualTo(204);
        assertThat(beaconBody(outside, body).statusCode()).isEqualTo(204);
        assertThat(sampleCount()).isEqualTo(1);
        assertThat(reportedMetrics(in)).isEqualTo("LCP");
        assertThat(reportedMetrics(out)).isEmpty();
        assertThat(ClientTelemetryApi.SAMPLE_WINDOW_MINUTES).isEqualTo(10);
    }

    @Test
    void SHOW가_아니거나_10분이_지났거나_남의_attempt이거나_광고_문서가_아니면_표본이_없다() throws Exception {
        Signed owner = signedIn("telemetry-none-owner");
        Signed other = signedIn("telemetry-none-other");
        long skipped = insertConsumed(owner.user(), false, "statement_timestamp() - interval '1 minute'");
        long stale = insertConsumed(owner.user(), true, "statement_timestamp() - interval '11 minutes'");
        long others = insertConsumed(other.user(), true, "statement_timestamp() - interval '1 minute'");
        long before = sampleCount();

        String adVitals = events(vital("LCP", "/ad-transition", "3000", true), vital("CLS", "/ad-transition", "0.5", true));
        assertThat(beaconBody(owner, adVitals).statusCode()).isEqualTo(204);
        // 광고를 시도하지 않은 문서(ad=false)와 다른 화면의 ad=true는 표본 후보가 아니다.
        long fresh = insertConsumed(owner.user(), true, "statement_timestamp() - interval '1 minute'");
        assertThat(beaconBody(owner, events(vital("LCP", "/ad-transition", "3000", false),
                vital("LCP", "/dashboard", "3000", true))).statusCode()).isEqualTo(204);
        assertThat(sampleCount()).isEqualTo(before);
        for (long attempt : List.of(skipped, stale, others, fresh)) {
            assertThat(reportedMetrics(attempt)).as("attempt " + attempt).isEmpty();
        }
    }

    // ------------------------------------------------------------------ kill switch 판정

    @Test
    void 창_표본이_40개이고_p75가_임계를_넘으면_광고만_막는_PERFORMANCE_제한을_만들고_표본을_지운다() throws Exception {
        // 29개는 임계 안, 11개는 3초 — 임계 초과 비율 27.5% > 25%, p75 = 3000.
        insertSamples("LCP", 29, 2000, "statement_timestamp() - interval '1 hour'");
        insertSamples("LCP", 11, 3000, "statement_timestamp() - interval '1 hour'");
        insertSamples("CLS", 5, 0.01, "statement_timestamp() - interval '1 hour'");
        judge();

        List<Map<String, Object>> rows = jdbc().sql("SELECT restriction_id, feature, scope, target, origin, "
                        + "operation_id FROM operations_restriction WHERE effective_until IS NULL")
                .query().listOfRows();
        assertThat(rows).hasSize(1).first().satisfies(row -> assertThat(row).containsEntry("feature", "ADVERTISING")
                .containsEntry("scope", "GLOBAL").containsEntry("target", "ALL").containsEntry("origin", "PERFORMANCE"));
        String operationId = (String) rows.getFirst().get("operation_id");
        Map<String, Object> audit = jdbc().sql("SELECT command, actor_kind, scope, target, reason, outcome, "
                        + "policy_version, details::text AS details FROM operations_audit_event WHERE operation_id = :o")
                .param("o", operationId).query().singleRow();
        assertThat(audit).containsEntry("command", AdvertisingStopApi.AUDIT_COMMAND)
                .containsEntry("actor_kind", "SYSTEM").containsEntry("scope", "ADVERTISING:GLOBAL")
                .containsEntry("target", "ALL").containsEntry("reason", "PERFORMANCE_LCP")
                .containsEntry("outcome", "COMPLETED").containsEntry("policy_version", "AD-PERF-1.0");
        assertThat(topLevelFieldsOf((String) audit.get("details"))).containsEntry("metric", "LCP")
                .containsEntry("samples", 40).containsEntry("p75", 3000.0);
        // 그 시각까지의 표본은 모두 지웠다(모든 metric).
        assertThat(sampleCount()).isZero();
        assertThat(gauge("LCP")).isZero();

        // 광고만 막는다.
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.ADVERTISING), RestrictionSubject.none()))
                .isTrue();
        for (RestrictionFeature other : EnumSet.complementOf(EnumSet.of(RestrictionFeature.ADVERTISING))) {
            assertThat(this.restrictions.blocks(EnumSet.of(other), RestrictionSubject.none())).as(other.name())
                    .isFalse();
        }
        var active = this.restrictions.active().getFirst();
        assertThat(active.origin()).isEqualTo(RestrictionOrigin.PERFORMANCE);
        assertThat(active.requiresVerification()).isTrue();
        assertThat(active.reason()).isEqualTo("PERFORMANCE_LCP");

        // 다시 판정해도(표본 없음) 새 행이 없다. 자동 해제도 없다.
        insertSamples("INP", 40, 900, "statement_timestamp() - interval '1 minute'");
        judge();
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE command = :c")
                .param("c", AdvertisingStopApi.AUDIT_COMMAND).query(Long.class).single()).isEqualTo(1);
        assertThat(this.restrictions.active()).hasSize(1);
    }

    @Test
    void 운영자_화면에는_성능_자동_정지와_수동_검증_필요로_보이고_lift_restriction으로만_닫힌다() throws Exception {
        insertSamples("CLS", 40, 0.3, "statement_timestamp() - interval '2 hours'");
        judge();
        UUID restriction = jdbc().sql("SELECT restriction_id FROM operations_restriction WHERE origin = 'PERFORMANCE' "
                + "AND effective_until IS NULL").query(UUID.class).single();

        Signed operator = operator("telemetry-lift", "command");
        var listed = restrictionsGet(operator);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(itemsIn(listed.body(), "items")).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("origin", "PERFORMANCE").containsEntry("feature", "ADVERTISING")
                .containsEntry("scope", "GLOBAL").containsEntry("reason", "PERFORMANCE_CLS")
                .containsEntry("linkedSignals", 0).containsEntry("requiresVerification", true));

        String key = key("telemetry-lift");
        var prepared = sendJson("POST", commandPath(OperatorCommandApi.LIFT_RESTRICTION), "{\"target\":\"" + restriction
                        + "\",\"reason\":\"광고 성능 원인 수정\",\"verification\":\"Vitals 재측정 통과\","
                        + "\"evidenceRef\":\"PERF-7 기록\",\"deployVersion\":\"1.5.0+build.1\",\"conditions\":["
                        + String.join(",", OperatorCommandApi.RECOVERY_CONDITIONS.stream().map(c -> "\"" + c + "\"")
                                .toList()) + "]}", operator.cookie(), headers(operator.csrf(), key));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        var confirmed = confirmRestriction(operator, OperatorCommandApi.LIFT_RESTRICTION, operationIdIn(prepared.body()),
                "복구 광고 전역", key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.ADVERTISING), RestrictionSubject.none()))
                .isFalse();
    }

    @Test
    void 광고가_꺼진_동안_남은_표본은_판정이_지우고_해제_뒤_다음_판정은_다시_발동하지_않는다() throws Exception {
        insertSamples("LCP", 40, 4000, "statement_timestamp() - interval '2 hours'");
        judge();
        UUID restriction = jdbc().sql("SELECT restriction_id FROM operations_restriction WHERE origin = 'PERFORMANCE'")
                .query(UUID.class).single();
        assertThat(sampleCount()).isZero();

        // 발동 뒤 늦게 커밋된 묵은 표본: 광고가 꺼진 동안의 판정이 지운다.
        insertSamples("LCP", 40, 4000, "statement_timestamp()");
        judge();
        assertThat(sampleCount()).isZero();
        assertThat(gauge("LCP")).isZero();

        // 운영자 해제(lift-restriction) 뒤의 다음 판정은 표본이 없어 다시 발동하지 않는다.
        Signed operator = operator("telemetry-relift", "command");
        String key = key("telemetry-relift");
        var prepared = sendJson("POST", commandPath(OperatorCommandApi.LIFT_RESTRICTION), "{\"target\":\"" + restriction
                        + "\",\"reason\":\"광고 성능 원인 수정\",\"verification\":\"Vitals 재측정 통과\","
                        + "\"evidenceRef\":\"PERF-8 기록\",\"deployVersion\":\"1.5.0+build.2\",\"conditions\":["
                        + String.join(",", OperatorCommandApi.RECOVERY_CONDITIONS.stream().map(c -> "\"" + c + "\"")
                                .toList()) + "]}", operator.cookie(), headers(operator.csrf(), key));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(confirmRestriction(operator, OperatorCommandApi.LIFT_RESTRICTION, operationIdIn(prepared.body()),
                "복구 광고 전역", key).statusCode()).isEqualTo(200);
        judge();
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(this.restrictions.blocks(EnumSet.of(RestrictionFeature.ADVERTISING), RestrictionSubject.none()))
                .isFalse();
    }

    @Test
    void 이미_열린_전역_광고_제한이_있으면_새_행도_원장_행도_없다() throws Exception {
        UUID operatorRestriction = restrictGlobally(RestrictionFeature.ADVERTISING);
        insertSamples("LCP", 40, 4000, "statement_timestamp() - interval '1 hour'");
        judge();
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(this.restrictions.active()).singleElement().satisfies(active -> assertThat(active.restrictionId())
                .isEqualTo(operatorRestriction));
        assertThat(jdbc().sql("SELECT count(*) FROM operations_audit_event WHERE command = :c")
                .param("c", AdvertisingStopApi.AUDIT_COMMAND).query(Long.class).single()).isZero();
        // 광고가 이미 꺼져 있으면 판정하지 않고 표본을 비운다 — 운영자가 풀어도 묵은 표본으로 곧바로 발동하지 않는다.
        assertThat(sampleCount()).isZero();
        lift(operatorRestriction);
    }

    @Test
    void 표본이_39개이거나_p75가_임계_이하면_발동하지_않고_gauge는_창_표본_수이며_24시간_밖은_지운다() throws Exception {
        // 39개 전부 임계 초과여도 표본 부족.
        insertSamples("INP", 39, 900, "statement_timestamp() - interval '1 hour'");
        // 40개지만 정확히 25%만 임계 초과 → p75 = 2500(임계와 같다) → 발동 없음.
        insertSamples("LCP", 30, 2500, "statement_timestamp() - interval '1 hour'");
        insertSamples("LCP", 10, 9000, "statement_timestamp() - interval '1 hour'");
        // 창 밖 표본은 판정이 지운다(세지도 않는다).
        insertSamples("CLS", 50, 0.9, "statement_timestamp() - interval '25 hours'");
        judge();
        assertThat(restrictionCount()).isZero();
        assertThat(gauge("INP")).isEqualTo(39.0);
        assertThat(gauge("LCP")).isEqualTo(40.0);
        assertThat(gauge("CLS")).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM engagement_ad_vital_sample WHERE metric = 'CLS'")
                .query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ 삭제·스키마

    @Test
    void 표본_표에는_사용자_열이_없고_계정_삭제_뒤_잔여가_없다() throws Exception {
        Signed signed = signedIn("telemetry-erase");
        insertConsumed(signed.user(), true, "statement_timestamp() - interval '1 minute'");
        assertThat(beaconBody(signed, events(vital("LCP", "/ad-transition", "1000", true))).statusCode())
                .isEqualTo(204);
        assertThat(sampleCount()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() "
                        + "AND table_name = 'engagement_ad_vital_sample' ORDER BY ordinal_position")
                .query(String.class).list()).containsExactly("id", "recorded_at", "metric", "value");

        assertThat(this.deletion.eraseAdAttempts(signed.user())).isEqualTo(1);
        assertThat(this.deletion.remainingRowsOf(signed.user())).isZero();
        // 표본은 사용자 귀속이 아니라 남는다(식별 없는 집계).
        assertThat(sampleCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 도구

    private void judge() {
        TelemetryProbe.run(this.context, "adPerformanceJudge", "judgeNow");
    }

    private HttpResponse<String> beacon(Signed signed, String body) throws Exception {
        return beaconBody(signed, body);
    }

    private HttpResponse<String> beaconBody(Signed signed, String body) throws Exception {
        return sendJson("POST", PATH, body, signed.cookie(), headers(signed.csrf(), null));
    }

    private static String events(String... events) {
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    private static String vital(String metric, String route, String value, boolean ad) {
        return "{\"kind\":\"VITAL\",\"metric\":\"" + metric + "\",\"route\":\"" + route + "\",\"value\":" + value
                + ",\"ad\":" + ad + "}";
    }

    private static String ad(String event, @Nullable String reason) {
        return reason == null ? "{\"kind\":\"AD\",\"event\":\"" + event + "\"}"
                : "{\"kind\":\"AD\",\"event\":\"" + event + "\",\"reason\":\"" + reason + "\"}";
    }

    /** 그 사용자의 소비된 자격 행 하나(SHOW 표지·소비 시각 식)를 넣고 순번을 돌려준다. */
    private long insertConsumed(UUID user, boolean shown, String consumedAt) {
        return jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, transition_ref, "
                        + "decided_at, transition_consumed_at, transition_shown) VALUES (:u, :j, 'ELIGIBLE', :t, "
                        + consumedAt + ", " + consumedAt + ", :s) RETURNING id")
                .param("u", user).param("j", UUID.randomUUID()).param("t", newTransition()).param("s", shown)
                .query(Long.class).single();
    }

    private void insertSamples(String metric, int count, double value, String recordedAt) {
        jdbc().sql("INSERT INTO engagement_ad_vital_sample (metric, value, recorded_at) SELECT :m, :v, " + recordedAt
                        + " FROM generate_series(1, :n)")
                .param("m", metric).param("v", value).param("n", count).update();
    }

    /** 그 attempt에서 보고된 metric들, 이름 순으로 쉼표로 이어서. 없으면 빈 문자열이다. */
    private String reportedMetrics(long attempt) {
        return jdbc().sql("SELECT array_to_string(ARRAY(SELECT unnest(vitals_reported_metrics) ORDER BY 1), ',') "
                + "FROM engagement_ad_attempt WHERE id = :id").param("id", attempt).query(String.class).single();
    }

    private long sampleCount() {
        return jdbc().sql("SELECT count(*) FROM engagement_ad_vital_sample").query(Long.class).single();
    }

    /** 그 metric·화면·평가의 Web Vitals 수(ad 두 값의 합). */
    private double vitals(String metric, String route, String rating) {
        return meters.find(AdTelemetry.WEB_VITALS).tag("metric", metric).tag("route", route).tag("rating", rating)
                .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private double vitals(String metric, String route, String rating, String ad) {
        var counter = meters.find(AdTelemetry.WEB_VITALS).tag("metric", metric).tag("route", route)
                .tag("rating", rating).tag("ad", ad).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double clientEvents(String event, String reason) {
        var counter = meters.find(AdTelemetry.CLIENT_EVENT).tag("event", event).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double total(String name) {
        return meters.find(name).counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private double gauge(String metric) {
        var gauge = meters.find(AdTelemetry.VITAL_SAMPLES).tag("metric", metric).gauge();
        assertThat(gauge).as(metric).isNotNull();
        return gauge.value();
    }
}
