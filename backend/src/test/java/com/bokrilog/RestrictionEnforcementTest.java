package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevBybitStub.Scenario;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.sync.SyncJobApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.2 AC-4·AC-5: 검사 지점별 차단과 기능의 독립성, 실행 중 lease·발행의 재검사, 표시 제한 중 공식 기록의 불변.
 *
 * <p>대응표: 신규 연결 → {@code NEW_CONNECTION}, 첫 스냅샷·초기 수집·증분 동기화의 접수·lease·진행 → {@code SYNC}, 계획 조건·
 * 발행·계획 종료 → {@code SYNC}·{@code CALCULATION_DISPLAY}, 대시보드 결과·근거·조정 이력 → {@code CALCULATION_DISPLAY},
 * 광고 → 소비자 없음(Epic 4). 각 지점은 자기가 가진 대상 차원만 넘긴다.
 */
class RestrictionEnforcementTest extends OperatorTestBase {

    @Autowired
    private OperationalRestrictionApi restrictions;

    @Autowired
    private com.bokrilog.plan.PlanEndApi ends;

    /** 계획 하나를 가진 로그인 사용자. */
    private record Holder(UUID user, String cookie, SyncJobApi.DashboardResult result) {}

    private Holder holder(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, Scenario.UTA2);
        publishedPlan(user, prefix + "-" + UUID.randomUUID(), null);
        return new Holder(user, cookie, ready(user));
    }

    /**
     * AC-1(독립성 행렬 4×4): 네 기능 중 하나만 전역으로 막으면 그 기능의 대표 경로만 막힌다 — 신규 연결의 준비, 증분
     * 동기화의 가용성, 대시보드 결과의 표시, 광고 판독.
     */
    @Test
    void 한_기능의_전역_제한은_그_기능만_막는다() throws Exception {
        Holder holder = holder("matrix");
        Signed newcomer = reauthenticated(signedIn("matrix-newcomer"));
        for (RestrictionFeature restricted : RestrictionFeature.values()) {
            UUID id = restrictGlobally(restricted);
            try {
                Map<RestrictionFeature, Boolean> blocked = new EnumMap<>(RestrictionFeature.class);
                blocked.put(RestrictionFeature.NEW_CONNECTION,
                        prepareBybitConnection(newcomer.cookie(), newcomer.csrf()).statusCode() == 403);
                var read = sync.dashboard(holder.user());
                var ready = (SyncJobApi.Dashboard.Ready) read.dashboard();
                blocked.put(RestrictionFeature.SYNC, "RESTRICTED".equals(ready.sync().reason()));
                blocked.put(RestrictionFeature.CALCULATION_DISPLAY, read.displayRestricted());
                blocked.put(RestrictionFeature.ADVERTISING, this.restrictions.blocks(
                        EnumSet.of(RestrictionFeature.ADVERTISING), RestrictionSubject.none()));
                Map<RestrictionFeature, Boolean> expected = new EnumMap<>(RestrictionFeature.class);
                for (RestrictionFeature feature : RestrictionFeature.values()) {
                    expected.put(feature, feature == restricted);
                }
                assertThat(blocked).as(restricted.name()).isEqualTo(expected);
                // 공식 결과는 어느 제한에서도 그대로다.
                assertThat(ready.result().calculationId()).isEqualTo(holder.result().calculationId());
            } finally {
                lift(id);
            }
        }
    }

    /**
     * 계획 하나의 동기화 제한은 그 계획만 막는다. 다른 계획의 동기화와, 계획이 없는 첫 스냅샷(넘길 계획 차원이 없다)은
     * 정상이다.
     */
    @Test
    void 계획_범위_동기화_제한은_그_계획만_막는다() throws Exception {
        UUID a = connect(Scenario.UTA2);
        publishedPlan(a, "scope-a-" + UUID.randomUUID(), null);
        UUID b = connect(Scenario.UTA2);
        publishedPlan(b, "scope-b-" + UUID.randomUUID(), null);
        UUID fresh = connect(Scenario.UTA2);
        restrict(RestrictionFeature.SYNC, RestrictionScope.PLAN, ready(a).planId().toString());

        assertThat(sync.admitPlanSync(a, SyncJobApi.SyncTrigger.MANUAL, key("scope-a")))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        assertThat(((SyncJobApi.Dashboard.Ready) sync.dashboard(a).dashboard()).sync().reason())
                .isEqualTo("RESTRICTED");
        assertThat(sync.admitPlanSync(b, SyncJobApi.SyncTrigger.ENTRY, key("scope-b")))
                .isNotEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        assertThat(((SyncJobApi.Dashboard.Ready) sync.dashboard(b).dashboard()).sync().reason())
                .isNotEqualTo("RESTRICTED");
        assertThat(sync.state(fresh).orElseThrow().restricted()).isFalse();
        UUID first = sync.admit(fresh, key("scope-fresh")).orElseThrow().jobId();
        String status = pollUntil(java.time.Duration.ofSeconds(10),
                () -> sync.job(fresh, first).orElseThrow().status(), PlanSyncTestBase::finished);
        assertThat(status).isEqualTo("SUCCEEDED");
    }

    /**
     * lease가 새 검사 지점이다: 접수와 같은 트랜잭션에 커밋된 계획 제한은 worker가 작업을 잡는 그 트랜잭션에서 막는다 —
     * 제공자 호출이 없고 작업은 {@code BLOCKED}다.
     */
    @Test
    void 접수_뒤_생긴_계획_제한은_lease에서_작업을_막는다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        publishedPlan(user, "lease-" + UUID.randomUUID(), null);
        var v1 = ready(user);
        BybitStubConfiguration.resetRequestCount();
        UUID job = new TransactionTemplate(manager).execute(status -> {
            var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key("lease"));
            assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
            restrict(RestrictionFeature.SYNC, RestrictionScope.PLAN, v1.planId().toString());
            return ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
        });
        assertThat(awaitSync(job)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("NOT_PERMITTED");
        assertThat(BybitStubConfiguration.requestCount()).as("제공자 호출 없음").isZero();
        assertThat(jdbc().sql("SELECT stage FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).optional().orElse(null)).as("단계에 들어가지 않았다").isNull();
        assertThat(ready(user).calculationId()).isEqualTo(v1.calculationId());
    }

    /**
     * 실행 중 생긴 계산 표시 제한은 증분 발행을 롤백한다 — 공식 포인터·계산은 바뀌지 않고 작업은 {@code BLOCKED}다. 표시
     * 제한은 접수를 막지 않는다(동기화는 다른 기능이다).
     */
    @Test
    void 실행_중_생긴_계산_표시_제한은_발행을_되돌린다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        publishedPlan(user, "display-race-" + UUID.randomUUID(), null);
        var v1 = ready(user);
        UUID job;
        UUID restriction;
        AutoCloseable held = holdPlanLock(user);
        try {
            var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key("display-race"));
            assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
            job = ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
            awaitBlockedOnPlanLock();
            restriction = restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.CALCULATION_POLICY_VERSION,
                    "CALC-1.0");
        } finally {
            held.close();
        }
        assertThat(awaitSync(job)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT pointer_version FROM sync_official_plan_result WHERE user_id=:u")
                .param("u", user).query(Long.class).single()).isEqualTo(1);
        var read = sync.dashboard(user);
        assertThat(read.displayRestricted()).isTrue();
        assertThat(((SyncJobApi.Dashboard.Ready) read.dashboard()).result().calculationId())
                .isEqualTo(v1.calculationId());
        lift(restriction);
        assertThat(sync.dashboard(user).displayRestricted()).isFalse();
    }

    /**
     * AC-5: 계산 정책 버전의 표시 제한 — 대시보드는 상태·계획 정체성·{@code nextAction}을 유지하고 결과를 빼며
     * {@code calculationDisplay: RESTRICTED}를 싣는다. 근거·조정 이력은 409 {@code CALCULATION_DISPLAY_RESTRICTED}다.
     * 공식 계산 행·포인터는 바뀌지 않고, 제한이 풀리면 같은 결과가 다시 보인다.
     */
    @Test
    void 계산_표시_제한은_결과만_감추고_기록은_그대로_둔다() throws Exception {
        Holder holder = holder("display");
        var shown = holder.result();
        String reference = "?planId=" + shown.planId() + "&pointerVersion=" + shown.pointerVersion()
                + "&calculationId=" + shown.calculationId();
        String before = officialRows(holder.user());
        UUID restriction = restrict(RestrictionFeature.CALCULATION_DISPLAY,
                RestrictionScope.CALCULATION_POLICY_VERSION, "CALC-1.0");

        var dashboard = get(AuthSecurityConfig.DASHBOARD_PATH, holder.cookie());
        assertThat(dashboard.statusCode()).isEqualTo(200);
        assertThat(dashboard.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        Map<String, Object> fields = topLevelFieldsOf(dashboard.body());
        assertThat(fields).containsEntry("state", "READY").containsEntry("calculationDisplay", "RESTRICTED")
                .containsKey("nextAction").containsKey("sync").containsKey("verification").doesNotContainKey("result")
                .doesNotContainKey("change");
        assertThat(nested(fields, "plan")).containsEntry("planId", shown.planId().toString())
                .containsEntry("planStatus", "ACTIVE");
        assertThat(nested(fields, "planEnd")).containsEntry("availability", "UNAVAILABLE")
                .containsEntry("reason", "RESTRICTED");
        assertThat(dashboard.body()).doesNotContain(shown.calculationId().toString()).doesNotContain("metrics");

        for (String path : List.of(DashboardController.EVIDENCE_PATH, DashboardController.ADJUSTMENTS_PATH)) {
            var refused = get(path + reference, holder.cookie());
            assertThat(refused.statusCode()).as(path).isEqualTo(409);
            assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId")
                    .containsEntry("code", "CALCULATION_DISPLAY_RESTRICTED");
            // 현재 포인터가 아닌 참조는 여전히 같은 404다 — 제한이 대상의 존재를 알려 주지 않는다.
            String other = "?planId=" + UUID.randomUUID() + "&pointerVersion=1&calculationId=" + UUID.randomUUID();
            assertThat(get(path + other, holder.cookie()).statusCode()).as(path).isEqualTo(404);
        }
        // 동기화 접수는 정상이다(다른 기능이다). 그 작업의 발행은 표시 제한이 막는다.
        var admission = sync.admitPlanSync(holder.user(), SyncJobApi.SyncTrigger.MANUAL, key("display-sync"));
        assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        assertThat(awaitSync(((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId())).isEqualTo("BLOCKED");
        assertThat(officialRows(holder.user())).isEqualTo(before);

        lift(restriction);
        var restored = topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, holder.cookie()).body());
        assertThat(restored).doesNotContainKey("calculationDisplay").doesNotContainKey("plan");
        assertThat(nested(restored, "result")).containsEntry("calculationId", shown.calculationId().toString());
        assertThat(get(DashboardController.EVIDENCE_PATH + reference, holder.cookie()).statusCode()).isEqualTo(200);
    }

    /** 계획 범위 표시 제한: 그 계획의 결과만 감춘다. 다른 사용자의 결과는 그대로다. */
    @Test
    void 계획_범위_표시_제한은_그_계획만_감춘다() throws Exception {
        Holder restricted = holder("display-plan");
        Holder other = holder("display-plan-other");
        restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.PLAN,
                restricted.result().planId().toString());
        assertThat(topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, restricted.cookie()).body()))
                .containsEntry("calculationDisplay", "RESTRICTED").doesNotContainKey("result");
        assertThat(topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, other.cookie()).body()))
                .doesNotContainKey("calculationDisplay").containsKey("result");
    }

    // ------------------------------------------------------------------ 리뷰 patch: 실제 차원·상태별 응답

    /** 한 사용자의 계획 하나와 그 portfolio·연결. */
    private record Owner(UUID user, SyncJobApi.DashboardResult result, UUID portfolioId, UUID connectionId) {}

    private Owner owner(String prefix) throws Exception {
        UUID user = connect(Scenario.UTA2);
        publishedPlan(user, prefix + "-" + UUID.randomUUID(), null);
        var result = ready(user);
        UUID portfolio = jdbc().sql("SELECT portfolio_id FROM plan_definition WHERE plan_id = :p")
                .param("p", result.planId()).query(UUID.class).single();
        UUID connection = jdbc().sql("SELECT connection_id FROM source_connection WHERE user_id = :u "
                + "AND status = 'ACTIVE'").param("u", user).query(UUID.class).single();
        return new Owner(user, result, portfolio, connection);
    }

    private Optional<com.bokrilog.plan.PlanEndApi.Refusal> endCheck(Owner owner) {
        var shown = owner.result();
        return this.ends.check(owner.user(), new com.bokrilog.plan.PlanEndApi.Request(
                com.bokrilog.plan.PlanEndApi.Reason.USER_REQUEST, shown.planId(), shown.pointerVersion(),
                shown.calculationId()));
    }

    private static boolean restrictedEnd(Optional<com.bokrilog.plan.PlanEndApi.Refusal> refusal) {
        return refusal.filter(r -> com.bokrilog.plan.PlanEndApi.RESTRICTED.equals(r.reason())).isPresent();
    }

    /**
     * 사용자 A의 실제 portfolio·연결로 건 동기화 제한은 A의 증분 동기화 접수·첫 스냅샷 상태·계획 종료를 막고, 같은
     * portfolio의 계산 표시 제한은 A의 대시보드 결과를 막는다. B는 어느 것도 막히지 않는다.
     */
    @Test
    void portfolio와_연결_범위_제한은_그_사용자만_막는다() throws Exception {
        Owner a = owner("dims-a");
        Owner b = owner("dims-b");
        for (var restriction : List.of(
                new Object[] {RestrictionFeature.SYNC, RestrictionScope.PORTFOLIO, a.portfolioId()},
                new Object[] {RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION, a.connectionId()})) {
            UUID id = restrict((RestrictionFeature) restriction[0], (RestrictionScope) restriction[1],
                    restriction[2].toString());
            try {
                String label = restriction[1].toString();
                assertThat(sync.admitPlanSync(a.user(), SyncJobApi.SyncTrigger.MANUAL, key("dims-a-" + label)))
                        .as(label).isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
                assertThat(sync.state(a.user()).orElseThrow().restricted()).as(label).isTrue();
                assertThat(restrictedEnd(endCheck(a))).as(label).isTrue();
                assertThat(sync.dashboard(a.user()).displayRestricted()).as(label).isFalse();

                assertThat(((SyncJobApi.Dashboard.Ready) sync.dashboard(b.user()).dashboard()).sync().reason())
                        .as(label).isNotEqualTo("RESTRICTED");
                assertThat(sync.state(b.user()).orElseThrow().restricted()).as(label).isFalse();
                assertThat(restrictedEnd(endCheck(b))).as(label).isFalse();
            } finally {
                lift(id);
            }
        }
        UUID display = restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.PORTFOLIO,
                a.portfolioId().toString());
        assertThat(sync.dashboard(a.user()).displayRestricted()).isTrue();
        assertThat(restrictedEnd(endCheck(a))).isTrue();
        assertThat(sync.dashboard(b.user()).displayRestricted()).isFalse();
        assertThat(restrictedEnd(endCheck(b))).isFalse();
        // 표시 제한은 동기화가 아니다.
        assertThat(((SyncJobApi.Dashboard.Ready) sync.dashboard(a.user()).dashboard()).sync().reason())
                .isNotEqualTo("RESTRICTED");
        lift(display);
    }

    /**
     * 계산 정책 버전({@code CALC-1.0})의 표시 제한 아래 계획 조건 조회·미리보기·확정과 첫 발행 접수가 그 code로 거절된다.
     * 초기 수집·판정·환산은 동기화라 막히지 않는다.
     */
    @Test
    void 계산_정책_표시_제한은_계획_조건과_첫_발행을_막는다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        UUID firstJob = sync.admit(user, key("policy-seed")).orElseThrow().jobId();
        pollUntil(java.time.Duration.ofSeconds(10), () -> sync.job(user, firstJob).orElseThrow().status(),
                PlanSyncTestBase::finished);
        var snapshot = plans.state(user).snapshot();
        String end = java.time.LocalDateTime.ofInstant(snapshot.measuredAt(), java.time.ZoneId.of("Asia/Seoul"))
                .plusDays(30).withSecond(0).withNano(0).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var input = new com.bokrilog.plan.PlanConditionApi.PreviewInput(end, "DAILY", "0.1");
        var early = plans.preview(user, "policy-session", input).orElseThrow();

        UUID restriction = restrict(RestrictionFeature.CALCULATION_DISPLAY,
                RestrictionScope.CALCULATION_POLICY_VERSION, "CALC-1.0");
        var state = plans.state(user);
        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo("NOT_ELIGIBLE");
        assertThat(plans.preview(user, "policy-session", input)).isEmpty();
        var confirmation = plans.confirm(user, "policy-session", early.confirmationRef(), key("policy-confirm"));
        assertThat(confirmation.accepted()).isFalse();
        assertThat(confirmation.intent()).isNull();
        lift(restriction);

        var preview = plans.preview(user, "policy-session", input).orElseThrow();
        UUID intent = plans.confirm(user, "policy-session", preview.confirmationRef(), key("policy-confirm-2"))
                .intent().intentId();
        restriction = restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.CALCULATION_POLICY_VERSION,
                "CALC-1.0");
        UUID collection = sync.admitInitialInputs(user, intent, key("policy-inputs")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", collection, "INPUTS_COMPLETE");
        UUID classification = sync.admitCashflowClassification(user, intent, key("policy-classification"))
                .orElseThrow().jobId();
        awaitTable("sync_cashflow_classification_job", classification, "COMPLETE");
        UUID conversion = sync.admitCashflowConversion(user, intent, key("policy-conversion")).orElseThrow().jobId();
        awaitTable("sync_cashflow_conversion_job", conversion, "CONVERTED");
        assertThat(sync.admitPlanPublication(user, intent, key("policy-publication")))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("NOT_PERMITTED"));
        lift(restriction);
    }

    /** 신규 연결 제한 아래에서도 이미 연결된(계획 없는) 사용자의 첫 스냅샷·계획 조건은 정상이다(독립성 행렬의 열). */
    @Test
    void 신규_연결_제한은_연결된_사용자의_첫_스냅샷과_계획_조건을_막지_않는다() throws Exception {
        UUID user = connect(Scenario.UTA2);
        restrictGlobally(RestrictionFeature.NEW_CONNECTION);
        assertThat(sync.state(user).orElseThrow().restricted()).isFalse();
        UUID job = sync.admit(user, key("new-connection-seed")).orElseThrow().jobId();
        String status = pollUntil(java.time.Duration.ofSeconds(10), () -> sync.job(user, job).orElseThrow().status(),
                PlanSyncTestBase::finished);
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(plans.state(user).eligible()).isTrue();
    }

    /** 끝난 계획(현재·과거 조회)과 완료된 계획의 표시 제한 응답: 결과와 계산 값만 빠지고 기록 사실은 남는다. */
    @Test
    void 끝난_계획과_완료된_계획의_표시_제한_응답() throws Exception {
        String subject = "display-ended-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, Scenario.UTA2);
        publishedPlan(user, "display-ended-" + UUID.randomUUID(), null);
        var shown = ready(user);
        new TransactionTemplate(manager).executeWithoutResult(status -> this.ends.end(user,
                new com.bokrilog.plan.PlanEndApi.Request(com.bokrilog.plan.PlanEndApi.Reason.USER_REQUEST,
                        shown.planId(), shown.pointerVersion(), shown.calculationId()), key("display-ended-end")));
        restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.PLAN, shown.planId().toString());
        for (String path : List.of(AuthSecurityConfig.DASHBOARD_PATH,
                AuthSecurityConfig.DASHBOARD_PATH + "?planId=" + shown.planId())) {
            var response = get(path, cookie);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            Map<String, Object> fields = topLevelFieldsOf(response.body());
            assertThat(fields).as(path).containsEntry("state", "ENDED").containsEntry("calculationDisplay", "RESTRICTED")
                    .containsEntry("nextAction", "PREPARE_NEXT_PLAN").containsKey("end").doesNotContainKey("result")
                    .doesNotContainKey("change");
            assertThat(nested(fields, "plan")).containsEntry("planId", shown.planId().toString())
                    .containsEntry("planStatus", "ENDED_EARLY");
            assertThat(nested(fields, "end")).containsEntry("reason", "USER_REQUEST");
        }

        String completedSubject = "display-completed-" + UUID.randomUUID();
        String completedCookie = sessionCookieOf(signIn(completedSubject)).orElseThrow();
        UUID completed = userIdOf(OidcStubConfiguration.issuer(), completedSubject).orElseThrow();
        store(completed, Scenario.UTA2);
        publishedPlan(completed, "display-completed-" + UUID.randomUUID(), null);
        var v1 = ready(completed);
        java.time.Instant endAt = databaseNow().minusMillis(100).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (!endAt.isAfter(v1.basisAt())) {
            Thread.sleep(java.time.Duration.between(endAt, v1.basisAt()).toMillis() + 300);
            endAt = databaseNow().minusMillis(100).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        }
        moveEnd(completed, endAt);
        restrict(RestrictionFeature.CALCULATION_DISPLAY, RestrictionScope.PLAN, v1.planId().toString());
        Map<String, Object> fields = topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, completedCookie).body());
        assertThat(fields).containsEntry("state", "COMPLETED").containsEntry("calculationDisplay", "RESTRICTED")
                .containsKey("sync").containsKey("completion").doesNotContainKey("result").doesNotContainKey("change");
        assertThat(nested(fields, "completion")).containsEntry("completionTarget", null)
                .containsEntry("scheduledEndAt", endAt.toString());
    }

    /** 운영 소스에 옛 판독이 남지 않는다 — {@code isRestricted(String}도, 제한 표의 {@code command} 판독도. */
    @Test
    void 운영_소스에_command_판독이_남지_않는다() throws Exception {
        Path main = Path.of("src/main/java");
        List<Path> legacy = new ArrayList<>();
        List<Path> touching = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("isRestricted(")) {
                    legacy.add(file);
                }
                if (source.contains("operations_restriction")) {
                    touching.add(file);
                }
            }
        }
        assertThat(legacy).isEmpty();
        assertThat(touching).extracting(path -> path.getFileName().toString())
                .containsExactly("OperationalRestrictionRepository.java");
        String repository = Files.readString(touching.getFirst(), StandardCharsets.UTF_8);
        Matcher literal = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"").matcher(repository);
        List<String> sql = new ArrayList<>();
        while (literal.find()) {
            sql.add(literal.group());
        }
        assertThat(sql).isNotEmpty().noneMatch(text -> Pattern.compile("\\bcommand\\b").matcher(text).find());
    }

    /** 공식 기록의 지문: 계산 버전·포인터·포트폴리오 포인터. */
    private String officialRows(UUID user) {
        return jdbc().sql("SELECT (SELECT count(*) FROM sync_plan_calculation WHERE user_id=:u) || '|' || "
                + "(SELECT pointer_version || ':' || calculation_id FROM sync_official_plan_result WHERE user_id=:u) "
                + "|| '|' || (SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u)")
                .param("u", user).query(String.class).single();
    }
}
