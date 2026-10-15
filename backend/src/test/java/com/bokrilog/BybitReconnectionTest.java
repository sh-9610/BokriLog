package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.sync.SyncJobApi;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 2.17(재연결, {@code RECONNECT-1.0}): 해제한 Bybit 연결을 같은 흐름(Story 2.1)으로 다시 연결하는 것을 실제 스택
 * (Docker PostgreSQL, worker, 로컬 OIDC·Bybit 스텁)에서 끝까지 본다. 시험마다 스텁으로 로그인하고 첫 공식 버전(Story 2.7)을
 * 발행한 뒤 HTTP로 해제하고(Story 2.16), 새 재인증 위에서 같은 경로로 다시 연결한다.
 *
 * <p>같은 main UID는 기존 provider account를 다시 쓰고 connection·credential·membership은 새로 만든다. 활성 계획은 기존
 * watermark부터 해제 기간의 사건까지 이어 동기화하고, 30일을 넘은 공백은 Bybit 호출 없이 장기 미동기화 선택이다. 다른 main
 * UID는 활성 계획에 붙지 않으며(422 {@code CONNECTION_ACCOUNT_MISMATCH}), 종단 계획만 있으면 새 provider account로
 * 연결한다. 다른 사용자의 UID·운영 제한·두 탭·늦은 Worker·계정 삭제의 경합도 함께 본다.
 */
// 계획 잠금은 본문이 이름을 부르지 않는 자원이 쥔다 — 쥐고 있는 것이 요점이다.
@SuppressWarnings("try")
class BybitReconnectionTest extends PlanSyncTestBase {
    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;
    private static final String REVOCATION_PATH = AuthSecurityConfig.BYBIT_CONNECTION_PATH + "/revocation";

    @Autowired PlanEndApi ends;
    @Autowired OperationalRestrictionApi restrictions;
    @Autowired AccountErasure erasure;

    record Signed(UUID user, String subject, String cookie, @Nullable String csrf, String uid) {}

    // ------------------------------------------------------------------------------------------ 같은 UID

    /**
     * 표 1행·AC-2·AC-3·AC-5: 같은 UID의 재연결은 같은 provider account를 다시 쓰고 새 connection·credential·membership을
     * 만든다. 해제된 이전 연결 행·과거 membership 구간·계획 행은 그대로이고 세션이 회전한다. 대시보드는 해제 표시를 거두고,
     * 다음 동기화는 새 연결 ID의 attempt로 기존 watermark부터 해제 기간의 입금까지 수집해 같은 계획의 다음 공식 버전을
     * 발행한다. 그 버전의 계보는 새 연결을 가리키고 근거·조정 이력이 200이다. 운영 줄은 결과 code만 싣는다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theSameUidReusesTheAccountAndResumesThePlanFromItsWatermark(CapturedOutput output) throws Exception {
        Signed signed = signedInWithPlan("reconnect-same", DevBybitStub.Scenario.INCREMENTAL, null);
        UUID user = signed.user();
        var v1 = ready(user);
        UUID oldConnection = activeConnectionOf(user);
        UUID account = providerAccountsOf(user).getFirst();
        String planRow = planRow(user);
        UUID[] planSource = planDefinitionSourceOf(user);

        Signed afterRevoke = revoke(signed);
        String revokedRow = connectionRow(oldConnection);
        String oldMembership = membershipRow(oldConnection);
        assertThat(sync.dashboard(user).dashboard()).isInstanceOfSatisfying(SyncJobApi.Dashboard.Ready.class,
                ready -> assertThat(ready.sync().reason()).isEqualTo("CONNECTION_REVOKED"));
        // 해제 기간에 입금이 하나 생긴다(스텁의 UID별 10초 간격 입금). 재연결 뒤 동기화가 이것까지 수집해야 한다.
        Instant deposit = BybitStubConfiguration.nextIncrementalDeposit(signed.uid(), v1.basisAt());
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), deposit.plusSeconds(1)).toMillis()));

        Signed fresh = reauthenticate(afterRevoke);
        var connected = reconnect(fresh, DevBybitStub.Scenario.INCREMENTAL, signed.uid());
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        assertThat(connected.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        UUID newConnection = activeConnectionOf(user);
        assertThat(topLevelFieldsOf(connected.body())).containsEntry("connected", true)
                .containsEntry("revoked", false).containsEntry("planResumed", true)
                .containsEntry("connectionId", newConnection.toString());
        assertThat(connected.body()).doesNotContain(signed.uid()).doesNotContain(account.toString());
        String rotated = sessionCookieOf(connected).orElseThrow();
        assertThat(rotated).isNotEqualTo(fresh.cookie());

        // 같은 provider account, 새 connection·credential·membership. 이전 행과 과거 구간·계획 행은 그대로다.
        assertThat(newConnection).isNotEqualTo(oldConnection);
        assertThat(providerAccountsOf(user)).containsExactly(account);
        assertThat(jdbc().sql("SELECT provider_account_id FROM source_connection WHERE connection_id=:c")
                .param("c", newConnection).query(UUID.class).single()).isEqualTo(account);
        assertThat(sourceConnectionCountOf(user)).isEqualTo(2);
        assertThat(credentialCount(newConnection)).isEqualTo(1);
        assertThat(credentialCount(oldConnection)).isZero();
        assertThat(connectionRow(oldConnection)).isEqualTo(revokedRow);
        assertThat(membershipRow(oldConnection)).isEqualTo(oldMembership);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_source_membership WHERE connection_id=:c "
                + "AND ended_at IS NULL AND provider_account_id=:a").param("c", newConnection).param("a", account)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(planRow(user)).isEqualTo(planRow);
        assertThat(planDefinitionSourceOf(user)).containsExactly(planSource);
        assertThat(planSource[0]).as("the plan keeps the connection it started on").isEqualTo(oldConnection);

        // 해제 표시가 거둬지고 동기화 조작이 돌아온다.
        var dashboard = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
        assertThat(dashboard.sync().availability()).isEqualTo("AVAILABLE");
        assertThat(dashboard.sync().reason()).isNull();
        assertThat(dashboard.result().trust().freshness().cause()).isNotEqualTo("CONNECTION_REVOKED");
        assertThat(dashboard.result().pointerVersion()).isEqualTo(1);

        UUID job = manualSync(user, key("reconnect-same-sync"));
        assertThat(jdbc().sql("SELECT connection_id::text||'|'||provider_account_id FROM sync_plan_sync_job "
                + "WHERE job_id=:j").param("j", job).query(String.class).single())
                .isEqualTo(newConnection + "|" + account);
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        assertThat(v2.planId()).isEqualTo(v1.planId());
        assertThat(v2.pointerVersion()).isEqualTo(2);
        assertThat(v2.flowCount()).as("the deposit of the revoked period is applied").isGreaterThan(v1.flowCount());
        assertThat(v2.basisAt()).isAfter(deposit);
        assertThat(jdbc().sql("SELECT previous_calculation_id FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", v2.calculationId()).query(UUID.class).single()).isEqualTo(v1.calculationId());
        assertThat(jdbc().sql("SELECT connection_id FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", v2.calculationId()).query(UUID.class).single()).isEqualTo(newConnection);
        assertThat(planRow(user)).isEqualTo(planRow);
        String reference = "?planId=" + v2.planId() + "&pointerVersion=2&calculationId=" + v2.calculationId();
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence" + reference, rotated).statusCode())
                .isEqualTo(200);
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/adjustments" + reference, rotated).statusCode())
                .isEqualTo(200);

        assertThat(lines(output, "bybit reconnection: ")).singleElement().satisfies(line -> assertThat(line)
                .contains("result=SAME_ACCOUNT planResumed=true"));
        for (String line : lines(output, "bybit reconnection: ")) {
            for (String forbidden : List.of(user.toString(), newConnection.toString(), oldConnection.toString(),
                    account.toString(), signed.uid()))
                assertThat(line).as(forbidden).doesNotContain(forbidden);
        }
    }

    /**
     * 표 2·3행·AC-3: 같은 UID로 재연결한 뒤 watermark에서 30일이 넘었으면 동기화 가용성은 {@code LONG_GAP}이고 Bybit을
     * 부르지 않는다(이어 가기 선택). 30일 안이면 일반 동기화가 새 연결로 접수된다.
     */
    @Test
    void afterTheReconnectionALongGapWaitsForTheChoiceAndAShorterOneSyncs() throws Exception {
        Signed signed = signedInWithPlan("reconnect-gap", DevBybitStub.Scenario.UTA2,
                Instant.now().minus(Duration.ofDays(45)));
        UUID user = signed.user();
        var v1 = ready(user);
        Signed fresh = reauthenticate(revoke(signed));
        assertThat(reconnect(fresh, DevBybitStub.Scenario.UTA2, signed.uid()).statusCode()).isEqualTo(200);
        UUID newConnection = activeConnectionOf(user);

        moveVerification(user, v1.calculationId(), "31 days");
        BybitStubConfiguration.resetRequestCount();
        var gap = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key("reconnect-gap-long"));
        assertThat(gap).isEqualTo(new SyncJobApi.SyncAdmission.Deferred("LONG_GAP", null, null));
        assertThat(countOf("sync_plan_sync_job", user)).isZero();
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call").isZero();
        var dashboard = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
        assertThat(dashboard.sync().availability()).isEqualTo("LONG_GAP");
        assertThat(dashboard.nextAction()).isEqualTo("CHOOSE_CONTINUATION");

        // 사용자가 기존 계획 계속을 고르면 그 attempt도 새 연결로 접수되고 끝까지 간다.
        var shown = ready(user);
        var admission = sync.admitPlanVerification(user, SyncJobApi.VerificationKind.CATCH_UP,
                new SyncJobApi.EvidenceReference(shown.planId(), shown.pointerVersion(), shown.calculationId()),
                key("reconnect-gap-catchup"));
        assertThat(admission).isInstanceOf(SyncJobApi.VerificationAdmission.Accepted.class);
        UUID catchUp = ((SyncJobApi.VerificationAdmission.Accepted) admission).job().jobId();
        assertThat(jdbc().sql("SELECT connection_id::text||'|'||membership_id FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", catchUp).query(String.class).single())
                .isEqualTo(newConnection + "|" + activeMembershipOf(user));
        assertThat(awaitSync(catchUp)).isEqualTo("SUCCEEDED");
        assertThat(ready(user).pointerVersion()).isEqualTo(2);
        assertThat(jdbc().sql("SELECT connection_id FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", ready(user).calculationId()).query(UUID.class).single()).isEqualTo(newConnection);
    }

    /** 표 3행·AC-3: 같은 UID로 재연결한 뒤 30일 안쪽이면 일반 동기화가 새 연결로 접수된다. */
    @Test
    void afterTheReconnectionAGapWithinThirtyDaysSyncsOnTheNewConnection() throws Exception {
        Signed signed = signedInWithPlan("reconnect-gap-within", DevBybitStub.Scenario.UTA2,
                Instant.now().minus(Duration.ofDays(45)));
        UUID user = signed.user();
        var v1 = ready(user);
        Signed fresh = reauthenticate(revoke(signed));
        assertThat(reconnect(fresh, DevBybitStub.Scenario.UTA2, signed.uid()).statusCode()).isEqualTo(200);
        UUID newConnection = activeConnectionOf(user);
        // 30일 안쪽: 일반 동기화가 허용된다(정확히 30일의 경계는 SyncWindow 단위 시험이 고정한다).
        moveVerification(user, v1.calculationId(), "29 days 23 hours 59 minutes");
        UUID job = manualSync(user, key("reconnect-gap-within"));
        assertThat(jdbc().sql("SELECT connection_id FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(UUID.class).single()).isEqualTo(newConnection);
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        assertThat(ready(user).pointerVersion()).isEqualTo(2);
    }

    /** 표 4행: 종단 계획만 있는 사용자의 같은 UID 재연결은 성공하고 후속 계획 기준을 준비할 수 있다. */
    @Test
    void theSameUidWithOnlyAnEndedPlanConnectsAndCanPrepareTheNextPlan() throws Exception {
        Signed signed = signedInWithPlan("reconnect-ended-same", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        endPlan(user);
        Signed fresh = reauthenticate(revoke(signed));
        var connected = reconnect(fresh, DevBybitStub.Scenario.UTA2, signed.uid());
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(connected.body())).doesNotContainKey("planResumed");
        assertThat(providerAccountsOf(user)).hasSize(1);
        var state = sync.nextBaselineState(user);
        assertThat(state.eligible()).isTrue();
        assertThat(state.reason()).isEqualTo("AVAILABLE");
    }

    // ------------------------------------------------------------------------------------------ 계획 없음

    /**
     * 표 "같은 UID·계획 없음"(범위 추가): 첫 스냅샷 뒤·첫 계획 전에 해제하고 같은 UID로 다시 연결하면, 이전 연결로 받은 첫
     * snapshot이 그대로 계획 조건의 기준이고 첫 계획이 발행된다. 새 계획의 connection·membership은 발행 시점의 활성(새) 연결의
     * 것이며 기준 snapshot은 원래 그대로다. 그 버전의 근거가 200이다.
     */
    @Test
    void theSameUidWithNoPlanKeepsItsFirstSnapshotAndPublishesTheFirstPlanOnTheNewConnection() throws Exception {
        Signed signed = signedInWithFirstSnapshot("reconnect-noplan-same", DevBybitStub.Scenario.UTA2);
        UUID user = signed.user();
        UUID oldConnection = activeConnectionOf(user);
        UUID firstSnapshot = officialSnapshotOf(user);
        UUID account = providerAccountsOf(user).getFirst();

        Signed fresh = reauthenticate(revoke(signed));
        var connected = reconnect(fresh, DevBybitStub.Scenario.UTA2, signed.uid());
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(connected.body())).doesNotContainKey("planResumed");
        String rotated = sessionCookieOf(connected).orElseThrow();
        UUID newConnection = activeConnectionOf(user);
        assertThat(newConnection).isNotEqualTo(oldConnection);
        assertThat(providerAccountsOf(user)).containsExactly(account);

        // 같은 계정이라 새 첫 확인은 받지 않고, 이전 연결로 받은 snapshot이 그대로 기준이다.
        var snapshotState = sync.state(user).orElseThrow();
        assertThat(snapshotState.accountChanged()).isFalse();
        assertThat(snapshotState.officialValue()).isNotNull();
        assertThat(sync.admit(user, key("reconnect-noplan-same-seed"))).isEmpty();
        var conditions = plans.state(user);
        assertThat(conditions.eligible()).as(String.valueOf(conditions.reason())).isTrue();
        assertThat(conditions.snapshot().snapshotId()).isEqualTo(firstSnapshot);
        assertThat(conditions.snapshot().connectionId()).isEqualTo(newConnection);

        publishedPlanFromBaseline(user, "reconnect-noplan-same", null, "CONVERTED");
        var v1 = ready(user);
        UUID[] planSource = planDefinitionSourceOf(user);
        assertThat(planSource[0]).as("the plan records the connection it was published on").isEqualTo(newConnection);
        assertThat(planSource[1]).isEqualTo(activeMembershipOf(user));
        assertThat(planSource[2]).isEqualTo(account);
        assertThat(v1.basisPortfolioSnapshotId()).isEqualTo(firstSnapshot);
        String reference = "?planId=" + v1.planId() + "&pointerVersion=1&calculationId=" + v1.calculationId();
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence" + reference, rotated).statusCode())
                .isEqualTo(200);
        assertThat(((SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard()).sync().reason()).isNull();
    }

    /**
     * 표 "다른 UID·계획 없음"(범위 추가): 첫 스냅샷·초기 수집 뒤·첫 계획 전에 해제하고 다른 UID로 다시 연결하면 새 provider
     * account로 연결된다. 이전 계정의 snapshot 기준 계획 조건은 거절이고(첫 확인 상태는 이전 값을 싣지 않는다), 새 계정의 첫
     * 확인이 포인터를 그 snapshot으로 옮긴다. 첫 계획은 그 snapshot만 기준으로 발행되고 이전 계정의 사건은 섞이지 않는다.
     * 이전 snapshot·사건 기록은 그대로 남는다.
     */
    @Test
    void anotherUidWithNoPlanTakesANewFirstSnapshotAndPublishesFromItAlone() throws Exception {
        Signed signed = signedInWithFirstSnapshot("reconnect-noplan-other", DevBybitStub.Scenario.INCREMENTAL);
        UUID user = signed.user();
        UUID oldAccount = providerAccountsOf(user).getFirst();
        UUID oldSnapshot = officialSnapshotOf(user);
        // 이전 계정의 초기 수집까지 해 둔다 — 그 사건이 새 계획에 섞이지 않는지 보려는 것이다.
        Instant deposit = BybitStubConfiguration.nextIncrementalDeposit(signed.uid(),
                plans.state(user).snapshot().measuredAt());
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), deposit.plusSeconds(1)).toMillis()));
        collectInputs(user, "reconnect-noplan-old");
        int oldEvents = eventsOf(user, oldAccount);
        assertThat(oldEvents).isPositive();

        Signed fresh = reauthenticate(revoke(signed));
        String newUid = "reconnect-noplan-new-" + UUID.randomUUID();
        var connected = reconnect(fresh, DevBybitStub.Scenario.INCREMENTAL, newUid);
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        UUID newAccount = providerAccountsOf(user).stream().filter(a -> !a.equals(oldAccount)).findFirst()
                .orElseThrow();
        UUID newConnection = activeConnectionOf(user);

        // 이전 계정의 snapshot은 기준이 아니다: 계획 조건은 거절이고, 첫 확인 상태는 새 첫 확인을 받는다.
        var refused = plans.state(user);
        assertThat(refused.eligible()).isFalse();
        assertThat(refused.reason()).isEqualTo("NOT_ELIGIBLE");
        assertThat(plans.preview(user, "reconnect-noplan-other-session",
                new com.bokrilog.plan.PlanConditionApi.PreviewInput("2099-01-01T00:00", "DAILY", "0.1"))).isEmpty();
        var before = sync.state(user).orElseThrow();
        assertThat(before.accountChanged()).isTrue();
        assertThat(before.officialValue()).isNull();
        assertThat(before.job()).isNull();

        UUID seed = sync.admit(user, key("reconnect-noplan-other-seed")).orElseThrow().jobId();
        pollUntil(Duration.ofSeconds(10), () -> sync.job(user, seed).orElseThrow().status(), PlanSyncTestBase::finished);
        assertThat(sync.job(user, seed).orElseThrow().status()).isEqualTo("SUCCEEDED");
        UUID newSnapshot = officialSnapshotOf(user);
        assertThat(newSnapshot).isNotEqualTo(oldSnapshot);
        var after = sync.state(user).orElseThrow();
        assertThat(after.accountChanged()).isFalse();
        assertThat(after.officialValue().snapshotId()).isEqualTo(newSnapshot);
        assertThat(sync.admit(user, key("reconnect-noplan-other-again"))).as("only one new first check").isEmpty();
        var conditions = plans.state(user);
        assertThat(conditions.eligible()).as(String.valueOf(conditions.reason())).isTrue();
        assertThat(conditions.snapshot().snapshotId()).isEqualTo(newSnapshot);
        assertThat(conditions.snapshot().providerAccountId()).isEqualTo(newAccount);

        Instant newDeposit = BybitStubConfiguration.nextIncrementalDeposit(newUid, conditions.snapshot().measuredAt());
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), newDeposit.plusSeconds(1)).toMillis()));
        publishedPlanFromBaseline(user, "reconnect-noplan-new", null, "CONVERTED");
        var v1 = ready(user);
        assertThat(v1.basisPortfolioSnapshotId()).isEqualTo(newSnapshot);
        assertThat(v1.flowCount()).as("the new account's deposits").isPositive();
        UUID[] planSource = planDefinitionSourceOf(user);
        assertThat(planSource[0]).isEqualTo(newConnection);
        assertThat(planSource[2]).isEqualTo(newAccount);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow WHERE calculation_id=:c "
                + "AND provider_account_id<>:a").param("c", v1.calculationId()).param("a", newAccount)
                .query(Integer.class).single()).as("no event of the old account").isZero();
        // 이전 기록은 지우거나 고치지 않는다.
        assertThat(eventsOf(user, oldAccount)).isEqualTo(oldEvents);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE portfolio_snapshot_id=:s")
                .param("s", oldSnapshot).query(Integer.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ 다른 UID

    /**
     * 표 5행·AC-4: 활성 계획이 있는데 다른 UID로 다시 연결하면 422 {@code CONNECTION_ACCOUNT_MISMATCH}이고 아무것도
     * 저장하지 않는다 — 새 provider account·connection·credential·membership이 없고 허가는 소비되지 않으며 세션도 회전하지
     * 않는다. 대시보드는 그대로 해제 표시다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anotherUidIsRefusedWhileThePlanIsActiveAndStoresNothing(CapturedOutput output) throws Exception {
        Signed signed = signedInWithPlan("reconnect-mismatch", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        Signed fresh = reauthenticate(revoke(signed));
        var before = recordsOf(user);
        var prepared = prepareBybitConnection(fresh.cookie(), fresh.csrf());
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        String grant = connectionGrantOf(user);
        String other = "reconnect-other-" + UUID.randomUUID();
        var refused = connectBybit(fresh.cookie(), fresh.csrf(),
                BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, other), BybitStubConfiguration.newSecret(),
                confirmationIn(prepared.body()));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", "CONNECTION_ACCOUNT_MISMATCH");
        assertThat(refused.body()).doesNotContain(other).doesNotContain(signed.uid());
        assertThat(sessionCookieOf(refused)).as("nothing committed: no rotation").isEmpty();
        assertThat(recordsOf(user)).isEqualTo(before);
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(sync.dashboard(user).dashboard()).isInstanceOfSatisfying(SyncJobApi.Dashboard.Ready.class,
                ready -> assertThat(ready.sync().reason()).isEqualTo("CONNECTION_REVOKED"));
        assertThat(lines(output, "bybit reconnection: ")).singleElement().satisfies(line -> assertThat(line)
                .contains("result=ACCOUNT_MISMATCH"));

        // 같은 무장으로 같은 UID의 키를 넣으면 이어진다 — 거절은 아무것도 소비하지 않았다.
        var same = connectBybit(fresh.cookie(), fresh.csrf(),
                BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, signed.uid()),
                BybitStubConfiguration.newSecret(), confirmationIn(prepared.body()));
        assertThat(same.statusCode()).as(same.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(same.body())).containsEntry("planResumed", true);
    }

    /**
     * 표 6행·AC-4: 종단 계획만 있으면 다른 UID는 새 provider account로 연결한다. 후속 기준은 Story 2.15 경로로 새 계정의
     * 연결에서 준비되고, 이전 계정의 사건·snapshot은 그 입력에 섞이지 않는다.
     */
    @Test
    void anotherUidWithOnlyAnEndedPlanConnectsAsANewAccount() throws Exception {
        Signed signed = signedInWithPlan("reconnect-ended-other", DevBybitStub.Scenario.INCREMENTAL, null);
        UUID user = signed.user();
        UUID oldAccount = providerAccountsOf(user).getFirst();
        // 이전 계정의 입금을 하나 이상 수집해 둔다 — 그 사건이 후속 계획에 섞이지 않는지 보려는 것이다.
        Instant oldDeposit = BybitStubConfiguration.nextIncrementalDeposit(signed.uid(), ready(user).basisAt());
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), oldDeposit.plusSeconds(1)).toMillis()));
        assertThat(awaitSync(manualSync(user, key("reconnect-ended-other-sync")))).isEqualTo("SUCCEEDED");
        int oldEvents = eventsOf(user, oldAccount);
        assertThat(oldEvents).as("the old account's deposits were collected").isPositive();
        endPlan(user);
        Signed fresh = reauthenticate(revoke(signed));
        String newUid = "reconnect-new-" + UUID.randomUUID();
        var connected = reconnect(fresh, DevBybitStub.Scenario.INCREMENTAL, newUid);
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(connected.body())).doesNotContainKey("planResumed");
        var accounts = providerAccountsOf(user);
        assertThat(accounts).hasSize(2).contains(oldAccount);
        UUID newAccount = accounts.stream().filter(a -> !a.equals(oldAccount)).findFirst().orElseThrow();

        assertThat(sync.nextBaselineState(user).eligible()).isTrue();
        UUID job = sync.admitNextBaseline(user, key("reconnect-ended-other-baseline")).orElseThrow().jobId();
        awaitTable("sync_job", job, "SUCCEEDED");
        assertThat(jdbc().sql("SELECT provider_account_id FROM sync_job WHERE job_id=:j").param("j", job)
                .query(UUID.class).single()).isEqualTo(newAccount);
        var baseline = sync.currentNextBaseline(user).orElseThrow();
        var snapshot = portfolio.ownedSnapshot(user, baseline.snapshotId()).orElseThrow();
        assertThat(snapshot.providerAccountId()).isEqualTo(newAccount);
        // 2.15 경로로 후속 계획을 실제로 발행한다: 그 계산의 흐름은 새 계정의 입금뿐이고 이전 계정의 사건은 섞이지 않는다.
        Instant deposit = BybitStubConfiguration.nextIncrementalDeposit(newUid, snapshot.measuredAt());
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), deposit.plusSeconds(1)).toMillis()));
        publishedPlanFromBaseline(user, "reconnect-ended-other-next", null, "CONVERTED");
        var successor = ready(user);
        assertThat(successor.basisPortfolioSnapshotId()).isEqualTo(baseline.snapshotId());
        assertThat(successor.flowCount()).as("the new account's deposits").isPositive();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow WHERE calculation_id=:c "
                + "AND provider_account_id<>:a").param("c", successor.calculationId()).param("a", newAccount)
                .query(Integer.class).single()).as("no flow of the old account").isZero();
        assertThat(eventsOf(user, oldAccount)).as("the old account's events are untouched").isEqualTo(oldEvents);
        assertThat(planDefinitionSourceOf(user)[2]).isEqualTo(newAccount);
    }

    /** 표 7행·AC-1: 다른 사용자가 쓰는 UID로는 다시 연결하지 않는다 — 기존 {@code CONNECTION_ACCOUNT_TAKEN}, 저장 없음. */
    @Test
    void anotherUsersUidIsTheExistingTakenRefusal() throws Exception {
        Signed owner = signedInWithPlan("reconnect-owner", DevBybitStub.Scenario.UTA2, null);
        Signed signed = signedInWithPlan("reconnect-taker", DevBybitStub.Scenario.UTA2, null);
        Signed fresh = reauthenticate(revoke(signed));
        var before = recordsOf(signed.user());
        var refused = reconnect(fresh, DevBybitStub.Scenario.UTA2, owner.uid());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "CONNECTION_ACCOUNT_TAKEN");
        assertThat(refused.body()).doesNotContain(owner.uid()).doesNotContain(owner.user().toString());
        assertThat(recordsOf(signed.user())).isEqualTo(before);
        assertThat(sourceConnectionCountOf(owner.user())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ 제한·경합

    /**
     * 표 8행·AC-1: {@code NEW_CONNECTION} 제한 중에는 재연결 준비도 확정도 기존 단일 거부이고 아무것도 저장하지 않는다.
     * 확정 직전에 켠 제한은 제공자를 부르기 전에 닫는다.
     */
    @Test
    void aNewConnectionRestrictionRefusesTheReconnection() throws Exception {
        Signed signed = signedInWithPlan("reconnect-restricted", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        Signed fresh = reauthenticate(revoke(signed));
        var before = recordsOf(user);
        UUID restriction = restrictGlobally(RestrictionFeature.NEW_CONNECTION);
        try {
            assertThat(prepareBybitConnection(fresh.cookie(), fresh.csrf()).statusCode()).isEqualTo(403);
        } finally {
            lift(restriction);
        }
        var prepared = prepareBybitConnection(fresh.cookie(), fresh.csrf());
        assertThat(prepared.statusCode()).isEqualTo(200);
        restriction = restrictGlobally(RestrictionFeature.NEW_CONNECTION);
        try {
            BybitStubConfiguration.resetRequestCount();
            var refused = connectBybit(fresh.cookie(), fresh.csrf(),
                    BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, signed.uid()),
                    BybitStubConfiguration.newSecret(), confirmationIn(prepared.body()));
            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(BybitStubConfiguration.requestCount()).isZero();
        } finally {
            lift(restriction);
        }
        assertThat(recordsOf(user)).isEqualTo(before);
    }

    /**
     * 표 9행·AC-5·AC-6: 한 무장을 두 탭이 동시에 확정하면 둘 다 200이고 활성 연결은 하나다 — 진 쪽은 같은 operation의
     * 재전송이라 저장하지 않으며, 그 응답의 이어 가기 여부는 지금 상태에서 다시 계산해 첫 응답과 같다.
     */
    @Test
    void twoTabsConfirmingOneArmingAnswerTheSameReconnection() throws Exception {
        Signed signed = signedInWithPlan("reconnect-tabs", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        Signed fresh = reauthenticate(revoke(signed));
        var prepared = prepareBybitConnection(fresh.cookie(), fresh.csrf());
        String confirmation = confirmationIn(prepared.body());
        CompletableFuture<HttpResponse<String>> first;
        CompletableFuture<HttpResponse<String>> second;
        try (AutoCloseable held = holdPlanLock(user)) {
            first = async(() -> connectBybit(fresh.cookie(), fresh.csrf(),
                    BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, signed.uid()),
                    BybitStubConfiguration.newSecret(), confirmation));
            awaitPlanLockWaiters(user, 1);
            second = async(() -> connectBybit(fresh.cookie(), fresh.csrf(),
                    BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, signed.uid()),
                    BybitStubConfiguration.newSecret(), confirmation));
            awaitRowLockWaiter(user);
        }
        var a = first.get();
        var b = second.get();
        assertThat(List.of(a.statusCode(), b.statusCode())).containsExactly(200, 200);
        assertThat(topLevelFieldsOf(a.body())).containsEntry("planResumed", true);
        assertThat(topLevelFieldsOf(b.body())).as("the replay answers the same continuation")
                .containsEntry("planResumed", true);
        assertThat(activeConnectionsOf(user)).isEqualTo(1);
        assertThat(sourceConnectionCountOf(user)).isEqualTo(2);
        assertThat(membershipCount(user, true)).isEqualTo(1);
    }

    /**
     * 표 9행·AC-6: 확정이 계획 잠금을 기다리는 동안 다른 경로가 활성 연결을 먼저 커밋하면, 확정은 저장의 활성 연결 재검사에서
     * 기존 단일 거부(403)로 끝나고 아무것도 더 저장하지 않는다 — 500이 새지 않는다. (같은 사용자의 두 허가는 동시에 살 수
     * 없다: 새 준비가 같은 command·target의 앞 허가를 지운다. 그래서 두 번째 쓰기는 직접 저장으로 만든다.)
     */
    @Test
    void anActiveConnectionCommittedWhileTheConfirmationWaitsLeavesOneConnectionAndOneRefusal() throws Exception {
        Signed signed = signedInWithPlan("reconnect-sessions", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        Signed fresh = reauthenticate(revoke(signed));
        String confirmation = confirmationIn(prepareBybitConnection(fresh.cookie(), fresh.csrf()).body());
        CompletableFuture<HttpResponse<String>> confirmed;
        try (AutoCloseable held = holdPlanLock(user)) {
            confirmed = async(() -> connectBybit(fresh.cookie(), fresh.csrf(),
                    BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, signed.uid()),
                    BybitStubConfiguration.newSecret(), confirmation));
            awaitPlanLockWaiters(user, 1);
            store(user, DevBybitStub.Scenario.UTA2, signed.uid());
        }
        var refused = confirmed.get();
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", ApiErrorHandler.CODE_FORBIDDEN);
        assertThat(activeConnectionsOf(user)).isEqualTo(1);
        assertThat(sourceConnectionCountOf(user)).isEqualTo(2);
        assertThat(membershipCount(user, true)).isEqualTo(1);
    }

    /**
     * 준비 → 확정 사이에 계획이 끝나는 경합: 확정은 계획 잠금 아래에서 활성 계획을 다시 보므로, 그 잠금을 쥔 채 계획을 끝낸
     * 트랜잭션이 커밋되면 기다리던 다른 UID의 확정은 새 provider account로 성공한다.
     */
    @Test
    void aPlanEndThatCommitsWhileTheConfirmationWaitsLetsAnotherUidConnect() throws Exception {
        Signed signed = signedInWithPlan("reconnect-end-race", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        Signed fresh = reauthenticate(revoke(signed));
        String confirmation = confirmationIn(prepareBybitConnection(fresh.cookie(), fresh.csrf()).body());
        var result = ready(user);
        var request = new PlanEndApi.Request(PlanEndApi.Reason.USER_REQUEST, result.planId(), result.pointerVersion(),
                result.calculationId());
        var ended = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread ender = Thread.ofVirtual().start(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            ends.end(user, request, key("reconnect-end-race-end"));
            ended.countDown();
            try {
                if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(ended.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var confirmed = async(() -> connectBybit(fresh.cookie(), fresh.csrf(),
                BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, "reconnect-race-" + UUID.randomUUID()),
                BybitStubConfiguration.newSecret(), confirmation));
        awaitPlanLockWaiters(user, 1);
        release.countDown();
        ender.join(10_000);
        var answer = confirmed.get();
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(answer.body())).doesNotContainKey("planResumed");
        assertThat(providerAccountsOf(user)).hasSize(2);
        assertThat(planStatusOf(result.planId())).isEqualTo("ENDED_EARLY");
    }

    /**
     * 표 10행·AC-6: 해제 전에 수집하던 Worker는 해제가 종단시켰고, 같은 UID로 재연결한 뒤에도 발행하지 못한다 — 활성 연결은
     * 있지만 그 attempt의 연결이 아니다. 버전·포인터·snapshot이 그대로이고 동기화 조작은 돌아온다.
     */
    @Test
    void aLateWorkerOfTheRevokedConnectionPublishesNothingAfterTheReconnection() throws Exception {
        Signed signed = signedInWithPlan("reconnect-late", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        UUID oldConnection = activeConnectionOf(user);
        var v1 = ready(user);
        int snapshots = countOf("portfolio_snapshot", user);
        slowPageStores(user);
        UUID late;
        try {
            late = manualSync(user, key("reconnect-late-sync"));
            awaitStage(late, "COLLECTION");
            Signed fresh = reauthenticate(revoke(signed));
            assertThat(reconnect(fresh, DevBybitStub.Scenario.UTA2, signed.uid()).statusCode()).isEqualTo(200);
        } finally {
            dropSlowPageStores();
        }
        assertThat(awaitSync(late)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT connection_id::text||'|'||failure_code FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", late).query(String.class).single()).isEqualTo(oldConnection + "|NOT_PERMITTED");
        assertNoChildInProgress(late);
        assertThat(countOf("sync_plan_calculation", user)).isEqualTo(1);
        assertThat(countOf("portfolio_snapshot", user)).isEqualTo(snapshots);
        assertThat(ready(user).calculationId()).isEqualTo(v1.calculationId());
        assertThat(((SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard()).sync().reason()).isNull();
    }

    /**
     * 표 "이전 연결 제한"(사용자 결정 2026-10-14): 이전 연결에 건 열린 {@code PROVIDER_CONNECTION} 동기화 제한은 같은 계정의 뒤
     * 연결에도 적용된다. 재연결은 막지 않지만, 재연결 뒤 동기화·계획 종료·대시보드 가용성은 제한 응답이고, 그 제한이 닫히면
     * 재개된다. 계획 대상(운영자 PLAN 미리보기의 차원)의 연결은 새 연결이다.
     */
    @Test
    void aRestrictionOfTheOldConnectionFollowsTheAccountAcrossTheReconnection() throws Exception {
        Signed signed = signedInWithPlan("reconnect-scope", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        var v1 = ready(user);
        UUID oldConnection = activeConnectionOf(user);
        UUID old = restrict(RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION, oldConnection.toString());
        try {
            Signed fresh = reauthenticate(revoke(signed));
            var connected = reconnect(fresh, DevBybitStub.Scenario.UTA2, signed.uid());
            assertThat(connected.statusCode()).as("the reconnection itself is not held").isEqualTo(200);
            UUID newConnection = activeConnectionOf(user);
            assertThat(plans.planTarget(v1.planId()).orElseThrow().connectionId())
                    .as("the plan's restriction dimension is the current connection").isEqualTo(newConnection);
            String rotated = sessionCookieOf(connected).orElseThrow();
            Signed after = reauthenticate(new Signed(user, signed.subject(), rotated, csrfTokenOf(rotated),
                    signed.uid()));
            assertTheOldConnectionsRestrictionHoldsThePlan(user, after, v1, newConnection);

            // 제한이 닫히면(운영자 해제와 같은 행 상태 — effective_until) 재개된다.
            jdbc().sql("UPDATE operations_restriction SET effective_until=statement_timestamp() "
                    + "WHERE restriction_id=:r").param("r", old).update();
            var reopened = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
            assertThat(reopened.sync().availability()).isEqualTo("AVAILABLE");
            assertThat(reopened.planEnd().reason()).isNotEqualTo("RESTRICTED");

            // 새 연결에 건 제한도 계획을 막는다.
            UUID current = restrict(RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION,
                    newConnection.toString());
            try {
                var held = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
                assertThat(held.sync().reason()).isEqualTo("RESTRICTED");
                assertThat(held.planEnd()).isEqualTo(new SyncJobApi.PlanEndAvailability("UNAVAILABLE", "RESTRICTED"));
            } finally {
                lift(current);
            }
        } finally {
            lift(old);
        }
    }

    /**
     * 같은 계정의 이전 연결 제한이 재연결 뒤의 계획을 막는 단정 모음(사용자 결정 2026-10-14 — 결정이 바뀌면 이 메서드만
     * 고친다): 판독, 동기화 가용성·접수, 계획 종료 가용성과 그 준비.
     */
    private void assertTheOldConnectionsRestrictionHoldsThePlan(UUID user, Signed signed,
            SyncJobApi.DashboardResult v1, UUID newConnection) throws Exception {
        assertThat(restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withConnection(newConnection))).isTrue();
        var held = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
        assertThat(held.sync().availability()).isEqualTo("UNAVAILABLE");
        assertThat(held.sync().reason()).isEqualTo("RESTRICTED");
        assertThat(held.planEnd()).isEqualTo(new SyncJobApi.PlanEndAvailability("UNAVAILABLE", "RESTRICTED"));
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key("reconnect-scope-sync")))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        String body = "{\"reason\":\"USER_REQUEST\",\"planId\":\"" + v1.planId() + "\",\"pointerVersion\":"
                + v1.pointerVersion() + ",\"calculationId\":\"" + v1.calculationId() + "\"}";
        var planEnd = sendJson("POST", AuthSecurityConfig.DASHBOARD_PATH + "/plan-end", body, signed.cookie(),
                headers(signed.csrf(), null));
        assertThat(planEnd.statusCode()).as(planEnd.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(planEnd.body())).containsEntry("code", "PLAN_END_UNAVAILABLE")
                .containsEntry("reason", "RESTRICTED");
    }

    /**
     * 연결 단위 제한은 계정을 따르므로 다른 계정의 연결에는 적용되지 않는다: 종단 계획만 있는 사용자가 이전 연결 제한을 남긴 채
     * 다른 UID로 다시 연결하면, 새 연결의 판독과 후속 기준 준비는 막히지 않는다(이전 연결의 판독은 그대로 막힌다).
     */
    @Test
    void aRestrictionOfTheOldConnectionDoesNotReachAnotherAccountsConnection() throws Exception {
        Signed signed = signedInWithPlan("reconnect-scope-other", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        UUID oldConnection = activeConnectionOf(user);
        endPlan(user);
        UUID old = restrict(RestrictionFeature.SYNC, RestrictionScope.PROVIDER_CONNECTION, oldConnection.toString());
        try {
            Signed fresh = reauthenticate(revoke(signed));
            assertThat(reconnect(fresh, DevBybitStub.Scenario.UTA2, "reconnect-scope-other-" + UUID.randomUUID())
                    .statusCode()).isEqualTo(200);
            UUID newConnection = activeConnectionOf(user);
            assertThat(restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.SYNC),
                    RestrictionSubject.none().withConnection(newConnection))).isFalse();
            assertThat(restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.SYNC),
                    RestrictionSubject.none().withConnection(oldConnection))).isTrue();
            assertThat(sync.nextBaselineState(user).eligible()).isTrue();
        } finally {
            lift(old);
        }
    }

    /**
     * 강한 신호 7(미인식 Transaction Log enum)이 연 {@code SYNC:PROVIDER_CONNECTION} 제한도 계정을 따른다: 해제 → 같은 UID로
     * 재연결해도 첫 확인 상태는 제한이고 계획 조건은 열리지 않는다.
     */
    @Test
    void aStrongSignalsConnectionRestrictionSurvivesASameAccountReconnection() throws Exception {
        Signed signed = signedInWithFirstSnapshot("reconnect-signal", DevBybitStub.Scenario.UNKNOWN_LOG_EVENT);
        UUID user = signed.user();
        UUID oldConnection = activeConnectionOf(user);
        UUID intent = confirmConditions(user, "reconnect-signal");
        UUID collection = sync.admitInitialInputs(user, intent, key("reconnect-signal-inputs")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", collection, "NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction WHERE feature='SYNC' "
                + "AND scope='PROVIDER_CONNECTION' AND target=:c AND origin='SIGNAL' AND effective_until IS NULL")
                .param("c", oldConnection.toString()).query(Integer.class).single()).isEqualTo(1);

        Signed fresh = reauthenticate(revoke(signed));
        assertThat(reconnect(fresh, DevBybitStub.Scenario.UNKNOWN_LOG_EVENT, signed.uid()).statusCode())
                .isEqualTo(200);
        UUID newConnection = activeConnectionOf(user);
        assertThat(restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.SYNC),
                RestrictionSubject.none().withConnection(newConnection))).isTrue();
        assertThat(sync.state(user).orElseThrow().restricted()).isTrue();
        assertThat(plans.state(user).eligible()).isFalse();
    }

    // ------------------------------------------------------------------------------------------ 삭제

    /**
     * 표 11행·AC-6(삭제 우선): 확정이 계획 잠금을 기다리는 동안 삭제가 시작되면, 확정은 잠금을 얻은 뒤 생명주기 잠금에서 닫혀
     * 정확히 403 단일 거부이고 아무것도 저장하지 않는다.
     */
    @Test
    void aDeletionThatBeginsWhileTheConfirmationWaitsWinsAndStoresNothing() throws Exception {
        Signed deleting = signedInWithPlan("reconnect-deleting", DevBybitStub.Scenario.UTA2, null);
        Signed fresh = reauthenticate(revoke(deleting));
        String confirmation = confirmationIn(prepareBybitConnection(fresh.cookie(), fresh.csrf()).body());
        var before = recordsOf(deleting.user());
        CompletableFuture<HttpResponse<String>> confirmed;
        try (AutoCloseable held = holdPlanLock(deleting.user())) {
            confirmed = async(() -> connectBybit(fresh.cookie(), fresh.csrf(),
                    BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, deleting.uid()),
                    BybitStubConfiguration.newSecret(), confirmation));
            awaitPlanLockWaiters(deleting.user(), 1);
            accountDeletion().beginDeletion(deleting.user());
        }
        var refused = confirmed.get();
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", ApiErrorHandler.CODE_FORBIDDEN);
        assertThat(recordsOf(deleting.user())).isEqualTo(before);
    }

    /**
     * 표 11행·AC-6: 재연결 뒤의 계정 삭제(루트 {@code AccountErasure} 한 트랜잭션)는 이전·새 connection과 credential, provider
     * account를 모두 지우고 다른 사용자의 것은 남긴다.
     */
    @Test
    void theErasureAfterAReconnectionRemovesBothConnections() throws Exception {
        Signed kept = signedInWithPlan("reconnect-kept", DevBybitStub.Scenario.UTA2, null);
        Signed erased = signedInWithPlan("reconnect-erased", DevBybitStub.Scenario.UTA2, null);
        Signed erasedFresh = reauthenticate(revoke(erased));
        assertThat(reconnect(erasedFresh, DevBybitStub.Scenario.UTA2, erased.uid()).statusCode()).isEqualTo(200);
        assertThat(sourceConnectionCountOf(erased.user())).isEqualTo(2);
        assertThat(erasure.erase(erased.user(), "reconnect-erasure-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        for (String table : List.of("source_connection", "source_provider_account", "plan_definition",
                "sync_plan_calculation", "action_grant")) {
            assertThat(countOf(table, erased.user())).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM source_credential c JOIN source_connection s ON "
                + "s.connection_id=c.connection_id WHERE s.user_id=:u").param("u", erased.user())
                .query(Integer.class).single()).isZero();
        assertThat(sourceConnectionCountOf(kept.user())).isEqualTo(1);
        assertThat(sync.dashboard(kept.user()).dashboard()).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
    }

    // ------------------------------------------------------------------------------------------ 기간 지난 활성 계획

    /**
     * 표 "다른 UID·기간 지난 활성 계획"(사용자 결정 2026-10-14): 종료 시각이 지난 ACTIVE 계획은 확정 트랜잭션이 계정 대조 앞에서
     * 자연 완료(2.14)로 전환하므로 다른 UID도 새 provider account로 연결된다 — 완료 기록 행이 남는다.
     */
    @Test
    void anotherUidOverAnExpiredActivePlanCompletesItAndConnects() throws Exception {
        Signed signed = signedInWithPlan("reconnect-expired-other", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        var v1 = ready(user);
        Signed fresh = reauthenticate(revoke(signed));
        moveEnd(user, v1.basisAt().plusMillis(1));
        var connected = reconnect(fresh, DevBybitStub.Scenario.UTA2, "reconnect-expired-" + UUID.randomUUID());
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(connected.body())).doesNotContainKey("planResumed");
        assertThat(planStatusOf(v1.planId())).isEqualTo("COMPLETED");
        assertThat(countOf("plan_completion", user)).isEqualTo(1);
        assertThat(providerAccountsOf(user)).hasSize(2);
    }

    /** 같은 UID·기간 지난 활성 계획: 연결은 성공하고 계획은 완료로 전환된다 — 이어 가기가 아니다({@code planResumed} 없음). */
    @Test
    void theSameUidOverAnExpiredActivePlanCompletesItWithoutResuming() throws Exception {
        Signed signed = signedInWithPlan("reconnect-expired-same", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        var v1 = ready(user);
        Signed fresh = reauthenticate(revoke(signed));
        moveEnd(user, v1.basisAt().plusMillis(1));
        var connected = reconnect(fresh, DevBybitStub.Scenario.UTA2, signed.uid());
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(connected.body())).doesNotContainKey("planResumed");
        assertThat(planStatusOf(v1.planId())).isEqualTo("COMPLETED");
        assertThat(countOf("plan_completion", user)).isEqualTo(1);
        assertThat(providerAccountsOf(user)).hasSize(1);
    }

    /** 종료 시각 직전(아직 기간 안)의 활성 계획에는 다른 UID가 여전히 422 {@code CONNECTION_ACCOUNT_MISMATCH}다. */
    @Test
    void anotherUidJustBeforeThePlansEndIsStillRefused() throws Exception {
        Signed signed = signedInWithPlan("reconnect-expired-before", DevBybitStub.Scenario.UTA2, null);
        UUID user = signed.user();
        var v1 = ready(user);
        Signed fresh = reauthenticate(revoke(signed));
        moveEnd(user, Instant.now().plus(Duration.ofMinutes(5)));
        var refused = reconnect(fresh, DevBybitStub.Scenario.UTA2, "reconnect-before-" + UUID.randomUUID());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "CONNECTION_ACCOUNT_MISMATCH");
        assertThat(planStatusOf(v1.planId())).isEqualTo("ACTIVE");
        assertThat(countOf("plan_completion", user)).isZero();
    }

    // ------------------------------------------------------------------------------------------ 계획 없음 변형

    /**
     * 계획 없음·A→B→A: 다른 계정 B의 첫 확인 뒤 다시 A로 돌아오면, A의 처음 snapshot(B보다 앞)은 기준이 아니다 — A의 새 첫 확인을
     * 받고 그 snapshot이 기준이다. 두 재연결 모두 직전 해제 연결과 다른 계정이라 운영 줄은 NEW_ACCOUNT다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aReturnToTheFirstAccountAfterAnotherOneTakesANewBaseline(CapturedOutput output) throws Exception {
        Signed signed = signedInWithFirstSnapshot("reconnect-aba", DevBybitStub.Scenario.UTA2);
        UUID user = signed.user();
        UUID firstSnapshot = officialSnapshotOf(user);
        UUID accountA = providerAccountsOf(user).getFirst();
        Signed toB = reauthenticate(revoke(signed));
        var connectedB = reconnect(toB, DevBybitStub.Scenario.UTA2, "reconnect-aba-b-" + UUID.randomUUID());
        assertThat(connectedB.statusCode()).isEqualTo(200);
        firstCheck(user, "reconnect-aba-b");
        UUID snapshotB = officialSnapshotOf(user);

        String rotated = sessionCookieOf(connectedB).orElseThrow();
        Signed afterB = reauthenticate(new Signed(user, signed.subject(), rotated, csrfTokenOf(rotated), signed.uid()));
        Signed toA = reauthenticate(revoke(afterB));
        assertThat(reconnect(toA, DevBybitStub.Scenario.UTA2, signed.uid()).statusCode()).isEqualTo(200);
        assertThat(providerAccountsOf(user)).hasSize(2).contains(accountA);
        assertThat(sync.state(user).orElseThrow().accountChanged()).isTrue();
        assertThat(plans.state(user).eligible()).isFalse();
        firstCheck(user, "reconnect-aba-a");
        var conditions = plans.state(user);
        assertThat(conditions.eligible()).isTrue();
        assertThat(conditions.snapshot().snapshotId()).isNotIn(firstSnapshot, snapshotB);
        assertThat(conditions.snapshot().providerAccountId()).isEqualTo(accountA);
        assertThat(lines(output, "bybit reconnection: ")).hasSize(2)
                .allSatisfy(line -> assertThat(line).contains("result=NEW_ACCOUNT"));
    }

    /**
     * 계획 없음·같은 UID: 해제 전에 확인한 계획 조건(이전 연결의 intent)은 재연결 뒤 수집·발행되지 않는다 — 기준 snapshot은 같지만
     * 수집·기록할 연결이 바뀌었다. 그 intent는 무효가 되고 계획은 생기지 않는다.
     */
    @Test
    void anIntentConfirmedBeforeTheRevocationIsNotPublishedAfterTheReconnection() throws Exception {
        Signed signed = signedInWithFirstSnapshot("reconnect-old-intent", DevBybitStub.Scenario.UTA2);
        UUID user = signed.user();
        UUID oldIntent = confirmConditions(user, "reconnect-old-intent");
        Signed fresh = reauthenticate(revoke(signed));
        assertThat(reconnect(fresh, DevBybitStub.Scenario.UTA2, signed.uid()).statusCode()).isEqualTo(200);
        assertThat(plans.state(user).eligible()).isTrue();
        assertThat(sync.admitInitialInputs(user, oldIntent, key("reconnect-old-intent-inputs"))).isEmpty();
        assertThat(sync.admitPlanPublication(user, oldIntent, key("reconnect-old-intent-publication")))
                .isNotInstanceOf(SyncJobApi.PublicationAdmission.Accepted.class);
        assertThat(jdbc().sql("SELECT status FROM plan_condition_intent WHERE intent_id=:i").param("i", oldIntent)
                .query(String.class).single()).isNotEqualTo("OPEN");
        assertThat(countOf("plan_definition", user)).isZero();
    }

    // ------------------------------------------------------------------------------------------ 도우미

    /** 저장소에 남는 이 사용자의 것 전부 — 거절이 아무것도 바꾸지 않았는지 보는 창이다. */
    private record Records(long providerAccounts, long connections, long credentials, long memberships,
            long openMemberships, long portfolios, long plans, long calculations) {}

    private Records recordsOf(UUID user) {
        return new Records(countOf("source_provider_account", user), sourceConnectionCountOf(user),
                jdbc().sql("SELECT count(*) FROM source_credential c JOIN source_connection s ON "
                        + "s.connection_id=c.connection_id WHERE s.user_id=:u").param("u", user).query(Long.class)
                        .single(),
                membershipCount(user, false), membershipCount(user, true), countOf("portfolio", user),
                countOf("plan_definition", user), countOf("sync_plan_calculation", user));
    }

    private long membershipCount(UUID user, boolean openOnly) {
        return jdbc().sql("SELECT count(*) FROM portfolio_source_membership m JOIN portfolio p ON "
                + "p.portfolio_id=m.portfolio_id WHERE p.user_id=:u" + (openOnly ? " AND m.ended_at IS NULL" : ""))
                .param("u", user).query(Long.class).single();
    }

    private Signed signedInWithPlan(String prefix, DevBybitStub.Scenario scenario, @Nullable Instant start)
            throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String uid = store(user, scenario);
        publishedPlan(user, prefix, start);
        return reauthenticate(new Signed(user, subject, cookie, csrfTokenOf(cookie), uid));
    }

    /** 첫 공식 snapshot까지만 — 계획이 없다(범위 추가 두 행의 출발점). */
    private Signed signedInWithFirstSnapshot(String prefix, DevBybitStub.Scenario scenario) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String uid = store(user, scenario);
        UUID job = sync.admit(user, key(prefix + "-seed")).orElseThrow().jobId();
        pollUntil(Duration.ofSeconds(10), () -> sync.job(user, job).orElseThrow().status(), PlanSyncTestBase::finished);
        assertThat(sync.job(user, job).orElseThrow().status()).isEqualTo("SUCCEEDED");
        return reauthenticate(new Signed(user, subject, cookie, csrfTokenOf(cookie), uid));
    }

    /** 계획 조건을 확인하고 그 intent의 초기 입력을 수집한다(발행하지 않는다). */
    private void collectInputs(UUID user, String prefix) throws Exception {
        UUID intent = confirmConditions(user, prefix);
        UUID collection = sync.admitInitialInputs(user, intent, key(prefix + "-inputs")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", collection, "INPUTS_COMPLETE");
    }

    /** 계획 조건을 미리 보고 확인한다(30일·매일·0.1). intent ID를 돌려준다. */
    private UUID confirmConditions(UUID user, String prefix) {
        var snapshot = plans.state(user).snapshot();
        String end = java.time.LocalDateTime.ofInstant(snapshot.measuredAt(), java.time.ZoneId.of("Asia/Seoul"))
                .plusDays(30).withSecond(0).withNano(0).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview = plans.preview(user, prefix + "-session",
                new com.bokrilog.plan.PlanConditionApi.PreviewInput(end, "DAILY", "0.1")).orElseThrow();
        return plans.confirm(user, prefix + "-session", preview.confirmationRef(), key(prefix + "-confirm"))
                .intent().intentId();
    }

    /** 첫 확인 하나를 접수하고 성공을 기다린다. */
    private void firstCheck(UUID user, String prefix) throws InterruptedException {
        UUID job = sync.admit(user, key(prefix + "-seed")).orElseThrow().jobId();
        pollUntil(Duration.ofSeconds(10), () -> sync.job(user, job).orElseThrow().status(), PlanSyncTestBase::finished);
        assertThat(sync.job(user, job).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    private int activeConnectionsOf(UUID user) {
        return jdbc().sql("SELECT count(*) FROM source_connection WHERE user_id=:u AND status='ACTIVE'")
                .param("u", user).query(Integer.class).single();
    }

    private String planStatusOf(UUID plan) {
        return jdbc().sql("SELECT status FROM plan_definition WHERE plan_id=:p").param("p", plan)
                .query(String.class).single();
    }

    /** 이 사용자의 계획 잠금을 기다리는 트랜잭션({@code BybitConnectionRevocationTest}와 같은 질의). */
    private static final String PLAN_LOCK_WAITERS = "SELECT pid FROM pg_locks WHERE locktype='advisory' AND NOT granted "
            + "AND objsubid=1 AND ((classid::bigint<<32)|objid::bigint)=hashtextextended(:u,0)";

    private void awaitPlanLockWaiters(UUID user, int count) throws InterruptedException {
        int waiting = pollUntil(Duration.ofSeconds(10), () -> jdbc().sql("SELECT count(*) FROM (" + PLAN_LOCK_WAITERS
                + ") w").param("u", user.toString()).query(Integer.class).single(), found -> found >= count);
        if (waiting < count) throw new AssertionError("only " + waiting + " waited on the plan lock");
    }

    /** 계획 잠금을 기다리는 트랜잭션의 행(허가)을 기다리는 트랜잭션이 생길 때까지 — 같은 무장의 두 번째 확정이다. */
    private void awaitRowLockWaiter(UUID user) throws InterruptedException {
        int waiting = pollUntil(Duration.ofSeconds(10), () -> jdbc().sql("SELECT count(*) FROM pg_locks w "
                + "JOIN pg_locks h ON h.locktype='transactionid' AND h.granted AND h.transactionid=w.transactionid "
                + "WHERE w.locktype='transactionid' AND NOT w.granted AND h.pid IN (" + PLAN_LOCK_WAITERS + ")")
                .param("u", user.toString()).query(Integer.class).single(), found -> found > 0);
        if (waiting == 0) throw new AssertionError("the second confirmation never waited on the grant");
    }

    private int eventsOf(UUID user, UUID account) {
        return jdbc().sql("SELECT count(*) FROM sync_initial_source_event WHERE user_id=:u AND provider_account_id=:a")
                .param("u", user).param("a", account).query(Integer.class).single();
    }

    /** portfolio의 지금 공식 포인터가 가리키는 snapshot. */
    private UUID officialSnapshotOf(UUID user) {
        return portfolio.officialValue(user).orElseThrow().snapshotId();
    }

    private UUID activeMembershipOf(UUID user) {
        return portfolio.activeSource(user).orElseThrow().membershipId();
    }

    private Signed reauthenticate(Signed signed) throws Exception {
        var reauthenticated = reauthenticate(GOOGLE, signed.cookie(), signed.csrf(),
                plan -> plan.subject(signed.subject()));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        return new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated), signed.uid());
    }

    /** HTTP 해제(Story 2.16). 해제가 재인증 시각을 지우므로 다음 민감 조작은 새 재인증 위에서 한다. */
    private Signed revoke(Signed signed) throws Exception {
        UUID connection = activeConnectionOf(signed.user());
        String body = "{\"connectionId\":\"" + connection + "\"}";
        var prepared = sendJson("POST", REVOCATION_PATH, body, signed.cookie(), headers(signed.csrf(), null));
        if (prepared.statusCode() != 200)
            throw new IllegalStateException("revocation: " + prepared.statusCode() + " " + prepared.body());
        var confirmed = sendJson("POST", REVOCATION_PATH + "/confirm", "{\"connectionId\":\"" + connection
                + "\",\"confirmation\":\"" + confirmationIn(prepared.body()) + "\"}", signed.cookie(),
                headers(signed.csrf(), key("reconnect-revoke")));
        if (confirmed.statusCode() != 200)
            throw new IllegalStateException("revocation: " + confirmed.statusCode() + " " + confirmed.body());
        String rotated = sessionCookieOf(confirmed).orElseThrow();
        return new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated), signed.uid());
    }

    /** Story 2.1의 연결 흐름 그대로의 재연결: 준비 → 그 UID의 키로 확정. */
    private HttpResponse<String> reconnect(Signed signed, DevBybitStub.Scenario scenario, String uid)
            throws Exception {
        return connectBybitRoundTrip(signed.cookie(), signed.csrf(), BybitStubConfiguration.apiKey(scenario, uid),
                BybitStubConfiguration.newSecret());
    }

    private void endPlan(UUID user) {
        var result = ready(user);
        var request = new PlanEndApi.Request(PlanEndApi.Reason.USER_REQUEST, result.planId(), result.pointerVersion(),
                result.calculationId());
        new TransactionTemplate(manager).executeWithoutResult(status -> ends.end(user, request, key("reconnect-end")));
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

    /** 호출마다 실행 시각에 만든 키 — 소스에 키 모양 리터럴이 없다. */
    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private UUID activeConnectionOf(UUID user) {
        return jdbc().sql("SELECT connection_id FROM source_connection WHERE user_id=:u AND status='ACTIVE'")
                .param("u", user).query(UUID.class).single();
    }

    private List<UUID> providerAccountsOf(UUID user) {
        return jdbc().sql("SELECT provider_account_id FROM source_provider_account WHERE user_id=:u ORDER BY created_at")
                .param("u", user).query(UUID.class).list();
    }

    private UUID[] planDefinitionSourceOf(UUID user) {
        return jdbc().sql("SELECT connection_id,membership_id,provider_account_id FROM plan_definition WHERE user_id=:u AND status='ACTIVE'")
                .param("u", user).query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)}).single();
    }

    private String connectionRow(UUID connection) {
        return jdbc().sql("SELECT status||'|'||revoked_at||'|'||verified_at||'|'||provider_account_id FROM "
                + "source_connection WHERE connection_id=:c").param("c", connection).query(String.class).single();
    }

    private String membershipRow(UUID connection) {
        return jdbc().sql("SELECT membership_id||'|'||started_at||'|'||ended_at FROM portfolio_source_membership "
                + "WHERE connection_id=:c").param("c", connection).query(String.class).single();
    }

    private int credentialCount(UUID connection) {
        return jdbc().sql("SELECT count(*) FROM source_credential WHERE connection_id=:c").param("c", connection)
                .query(Integer.class).single();
    }

    private String connectionGrantOf(UUID user) {
        return jdbc().sql("SELECT grant_id FROM action_grant WHERE user_id=:u AND command='bybit-connection-create' "
                + "AND consumed_at IS NULL").param("u", user).query(String.class).single();
    }

    private UUID manualSync(UUID user, String key) {
        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key);
        assertThat(admission).as("admission").isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        return ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
    }

    private void awaitStage(UUID job, String stage) throws InterruptedException {
        String state = pollUntil(Duration.ofSeconds(30), () -> jdbc().sql("SELECT status||'|'||COALESCE(stage,'-') "
                + "FROM sync_plan_sync_job WHERE job_id=:j").param("j", job).query(String.class).single(),
                found -> found.equals("RUNNING|" + stage));
        assertThat(state).isEqualTo("RUNNING|" + stage);
    }

    /** 이 사용자의 수집 페이지 저장이 잠든다 — 동기화가 수집에 오래 머물러 해제·재연결에 따라잡힌다. */
    private void slowPageStores(UUID user) {
        jdbc().sql("CREATE OR REPLACE FUNCTION slow_reconnect_pages() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id='" + user + "'::uuid THEN PERFORM pg_sleep(1.5); END IF; RETURN NEW; END $$")
                .update();
        jdbc().sql("CREATE OR REPLACE TRIGGER slow_reconnect_pages BEFORE INSERT ON sync_initial_page_evidence "
                + "FOR EACH ROW EXECUTE FUNCTION slow_reconnect_pages()").update();
    }

    private void dropSlowPageStores() {
        jdbc().sql("DROP TRIGGER IF EXISTS slow_reconnect_pages ON sync_initial_page_evidence").update();
        jdbc().sql("DROP FUNCTION IF EXISTS slow_reconnect_pages()").update();
    }

    private interface Call {
        HttpResponse<String> send() throws Exception;
    }

    private static CompletableFuture<HttpResponse<String>> async(Call call) {
        CompletableFuture<HttpResponse<String>> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(call.send());
            } catch (Exception failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }
}
