package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.OperationsDeletionApi;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.10 AC-4: 계정 삭제 orchestrator({@link AccountErasure})는 한 트랜잭션이다 — 삭제 전환·tombstone outbox 행·모듈 삭제·payload
 * 삭제가 함께 커밋되고, 어느 단계든 실패하면 전부 되돌아간다. outbox 행은 keyed digest·요청 ID·시각·+35일 기한뿐이다. 기한 지난 행은
 * 내보내기가 지우고 파일에서 빠진다.
 */
class AccountErasureTest extends PlanSyncTestBase {

    @Autowired
    AccountErasure erasure;

    @Autowired
    DeletionTombstoneApi tombstones;

    @Autowired
    AccountAccessApi access;

    @Autowired
    OperationsDeletionApi operations;

    @Autowired
    com.bokrilog.engagement.EngagementDeletionApi engagement;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void 한_트랜잭션에서_계획과_자격정보까지_지우고_outbox_행을_남긴다() throws Exception {
        UUID kept = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(kept, "erasure-kept", null);
        UUID erased = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(erased, "erasure-erased", null);

        assertThat(erasure.erase(erased, "erasure-request-1")).isEqualTo(AccountErasure.Outcome.ERASED);

        assertThat(lifecycleState(erased)).isEqualTo("DELETED");
        for (String table : List.of("plan_definition", "sync_plan_calculation", "sync_official_plan_result",
                "portfolio", "portfolio_snapshot", "source_connection", "identity_account", "identity_social_link")) {
            assertThat(countOf(table, erased)).as(table).isZero();
        }
        assertThat(countOf("plan_definition", kept)).isEqualTo(1);
        assertThat(ready(kept).planId()).isNotNull();

        List<Map<String, Object>> rows = outbox();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.keySet()).containsExactlyInAnyOrder("request_id", "user_digest", "requested_at", "destroy_after");
        assertThat(row).containsEntry("request_id", "erasure-request-1");
        assertThat((String) row.get("user_digest")).matches("[0-9a-f]{64}")
                .doesNotContain(erased.toString()).doesNotContain(erased.toString().replace("-", ""));
        var times = jdbc().sql("SELECT requested_at, destroy_after FROM operations_deletion_tombstone")
                .query((rs, n) -> new OffsetDateTime[] {rs.getObject(1, OffsetDateTime.class),
                        rs.getObject(2, OffsetDateTime.class)}).single();
        assertThat(Duration.between(times[0], times[1])).isEqualTo(Duration.ofDays(35));
        assertThat(Duration.between(times[0].toInstant(), databaseNow()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(times[0].toInstant().getNano() % 1_000_000).as("ms로 자른 시각").isZero();

        // 늦은 접근: 삭제된 계정은 세션·쓰기·동기화 어느 것도 다시 열지 못한다.
        assertThat(accountLifecycle().decideSessionCreation(erased)).isEqualTo(AccessDecision.DENIED);
        assertThat(access.decide(erased, erased, AccountAccessKind.READ)).isEqualTo(AccessDecision.DENIED);
        assertThat(sync.admit(erased, "erasure-late-seed")).isEmpty();
        assertThat(sync.admitPlanSync(erased, com.bokrilog.sync.SyncJobApi.SyncTrigger.MANUAL, "erasure-late-sync"))
                .isInstanceOf(com.bokrilog.sync.SyncJobApi.SyncAdmission.NotPermitted.class);
    }

    @Test
    void 다시_지우면_멱등이고_없는_계정은_아무것도_쓰지_않는다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        assertThat(erasure.erase(user, "erasure-repeat-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        assertThat(erasure.erase(user, "erasure-repeat-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        assertThat(erasure.erase(user, "erasure-repeat-2")).isEqualTo(AccountErasure.Outcome.ERASED);
        assertThat(outbox()).extracting(row -> row.get("request_id")).containsExactly("erasure-repeat-1");

        assertThat(erasure.erase(UUID.randomUUID(), "erasure-missing-1")).isEqualTo(AccountErasure.Outcome.NOT_FOUND);
        assertThat(outbox()).hasSize(1);
        assertThatThrownBy(() -> erasure.erase(connect(DevBybitStub.Scenario.UTA2), "bad/request"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void 요청_ID가_다른_계정에_쓰였으면_삭제_전체가_되돌아간다() throws Exception {
        UUID first = connect(DevBybitStub.Scenario.UTA2);
        UUID second = connect(DevBybitStub.Scenario.UTA2);
        assertThat(erasure.erase(first, "erasure-shared-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        assertThatThrownBy(() -> erasure.erase(second, "erasure-shared-1")).isInstanceOf(IllegalStateException.class);
        assertThat(lifecycleState(second)).isEqualTo("ACTIVE");
        assertThat(countOf("source_connection", second)).isEqualTo(1);
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void 모듈_삭제가_실패하면_전환_tombstone_모듈_삭제가_모두_되돌아간다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "erasure-failing", null);
        // operations 삭제 port 하나가 마지막 단계 직전에 실패한다.
        OperationsDeletionApi failing = new OperationsDeletionApi() {
            @Override
            public int eraseActor(@Nullable UUID userId) {
                return operations.eraseActor(userId);
            }

            @Override
            public int eraseRestrictionTargets(@Nullable UUID userId) {
                return operations.eraseRestrictionTargets(userId);
            }

            @Override
            public int eraseInquiries(@Nullable UUID userId) {
                throw new IllegalStateException("injected");
            }

            @Override
            public long remainingRowsOf(UUID userId) {
                return operations.remainingRowsOf(userId);
            }
        };
        AccountErasure broken = new AccountErasure(accountDeletion(), tombstones, portfolio, sync, plans, source,
                failing, engagement, transactionManager);
        assertThatThrownBy(() -> broken.erase(user, "erasure-failing-1")).hasMessage("injected");

        assertThat(lifecycleState(user)).isEqualTo("ACTIVE");
        assertThat(outbox()).isEmpty();
        assertThat(countOf("plan_definition", user)).isEqualTo(1);
        assertThat(countOf("source_connection", user)).isEqualTo(1);
        assertThat(countOf("identity_account", user)).isEqualTo(1);
        assertThat(ready(user).planId()).isNotNull();
    }

    /**
     * engagement 삭제 port의 어느 단계(동의 결정·광고 자격 결정)가 실패해도 그 단계가 지운 행과 앞선 모듈 삭제까지 모두 되돌아간다.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"eraseConsents", "eraseAdAttempts"})
    void engagement_삭제가_실패하면_앞선_모듈_삭제까지_모두_되돌아간다(String failingStep) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "erasure-engagement-failing-" + failingStep, null);
        jdbc().sql("INSERT INTO engagement_ad_consent_decision (user_id, policy_version, choice, idempotency_key_digest, "
                        + "request_digest) VALUES (:u, 'AD-CONSENT-1.0', 'GRANTED', :k, :r)")
                .param("u", user).param("k", "c".repeat(64)).param("r", "d".repeat(64)).update();
        // 광고 자격 결정(Story 4.2) 한 행도 둔다.
        jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, skip_reason) "
                        + "VALUES (:u, :j, 'SKIPPED', 'CONSENT_REQUIRED')")
                .param("u", user).param("j", UUID.randomUUID()).update();
        // engagement 삭제 port의 한 단계가 자기 행을 지운 뒤 실패한다 — payload 삭제 직전, 앞선 모듈 erase가 모두 끝난 자리다.
        com.bokrilog.engagement.EngagementDeletionApi failing = new com.bokrilog.engagement.EngagementDeletionApi() {
            @Override
            public int eraseConsents(@Nullable UUID userId) {
                int erased = engagement.eraseConsents(userId);
                if (failingStep.equals("eraseConsents")) {
                    throw new IllegalStateException("injected");
                }
                return erased;
            }

            @Override
            public int eraseAdAttempts(@Nullable UUID userId) {
                int erased = engagement.eraseAdAttempts(userId);
                if (failingStep.equals("eraseAdAttempts")) {
                    throw new IllegalStateException("injected");
                }
                return erased;
            }

            @Override
            public long remainingRowsOf(UUID userId) {
                return engagement.remainingRowsOf(userId);
            }
        };
        AccountErasure broken = new AccountErasure(accountDeletion(), tombstones, portfolio, sync, plans, source,
                operations, failing, transactionManager);
        assertThatThrownBy(() -> broken.erase(user, "erasure-engagement-1")).hasMessage("injected");

        assertThat(lifecycleState(user)).isEqualTo("ACTIVE");
        assertThat(outbox()).isEmpty();
        assertThat(countOf("plan_definition", user)).isEqualTo(1);
        assertThat(countOf("source_connection", user)).isEqualTo(1);
        assertThat(countOf("identity_account", user)).isEqualTo(1);
        assertThat(countOf("engagement_ad_consent_decision", user)).isEqualTo(1);
        assertThat(countOf("engagement_ad_attempt", user)).isEqualTo(1);
        assertThat(ready(user).planId()).isNotNull();
    }

    @Test
    void 내보내기는_기한_지난_행을_지우고_24시간_넘게_남은_행만_싣는다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        assertThat(erasure.erase(user, "erasure-live-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        // 40일 전의 요청(기한 5일 지남), 기한 6시간 전의 요청(outbox에는 남지만 사본에는 싣지 않는다), 기한 25시간 전의 요청(싣는다).
        insertTombstone("e".repeat(64), "erasure-expired-1", "40 days");
        insertTombstone("f".repeat(64), "erasure-nearly-1", "834 hours");
        insertTombstone("9".repeat(64), "erasure-margin-1", "815 hours");

        DeletionTombstoneApi.Export export = tombstones.export();
        assertThat(export.purged()).isEqualTo(1);
        assertThat(export.keyId()).isEqualTo(tombstones.keyId()).matches("[0-9a-f]{16}");
        assertThat(export.live()).extracting(DeletionTombstoneApi.Tombstone::requestId)
                .containsExactlyInAnyOrder("erasure-live-1", "erasure-margin-1");
        assertThat(outbox()).extracting(row -> row.get("request_id"))
                .containsExactlyInAnyOrder("erasure-live-1", "erasure-nearly-1", "erasure-margin-1");
        assertThat(tombstones.outbox()).extracting(DeletionTombstoneApi.Tombstone::requestId)
                .containsExactlyInAnyOrder("erasure-live-1", "erasure-nearly-1", "erasure-margin-1");
        String file = DeletionTombstoneApi.render(export.keyId(), java.time.Instant.now(), export.live());
        assertThat(file).doesNotContain("erasure-expired-1").doesNotContain("erasure-nearly-1")
                .doesNotContain(user.toString());
        assertThat(DeletionTombstoneApi.read(file.lines().toList()).tombstones()).isEqualTo(export.live());
    }

    @Test
    void 사본을_옮길_때_같은_요청_ID가_다른_계정을_가리키면_실패한다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        assertThat(erasure.erase(user, "erasure-carry-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        var stored = tombstones.outbox().getFirst();
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // 같은 요청·같은 digest: 멱등.
        assertThat(Boolean.TRUE.equals(template.<Boolean>execute(status -> tombstones.carry(stored)))).isFalse();
        // 같은 계정의 다른 요청: 앞선 tombstone이 그 계정을 지킨다.
        var sameAccount = new DeletionTombstoneApi.Tombstone(stored.userDigest(), "erasure-carry-2", stored.requestedAt(),
                stored.destroyAfter());
        assertThat(Boolean.TRUE.equals(template.<Boolean>execute(status -> tombstones.carry(sameAccount)))).isFalse();
        // 같은 요청 ID가 다른 digest를 가리킨다: 사본과 DB가 어긋난다.
        var conflicting = new DeletionTombstoneApi.Tombstone("1".repeat(64), "erasure-carry-1", stored.requestedAt(),
                stored.destroyAfter());
        assertThatThrownBy(() -> template.execute(status -> tombstones.carry(conflicting)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(outbox()).hasSize(1);
    }

    @Test
    void tombstone_기록은_호출자_트랜잭션_밖에서_쓰지_않는다() {
        assertThatThrownBy(() -> tombstones.record(UUID.randomUUID(), "erasure-outside-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(outbox()).isEmpty();
    }

    private void insertTombstone(String digest, String request, String ago) {
        jdbc().sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, origin) "
                        + "SELECT :r, :d, t.at, t.at + interval '840 hours', 'DELETION' FROM (SELECT date_trunc('milliseconds', now()) "
                        + "- CAST(:ago AS interval) AS at) t")
                .param("r", request).param("d", digest).param("ago", ago).update();
    }

    private List<Map<String, Object>> outbox() {
        return jdbc().sql("SELECT request_id, user_digest, requested_at, destroy_after FROM operations_deletion_tombstone "
                + "ORDER BY requested_at").query().listOfRows();
    }

    private String lifecycleState(UUID user) {
        return jdbc().sql("SELECT state FROM identity_account_lifecycle WHERE user_id=:u").param("u", user)
                .query(String.class).single();
    }
}
