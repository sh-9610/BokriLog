package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.DeletionTombstoneApi.Tombstone;
import com.bokrilog.operations.DeletionTombstoneApi.TombstoneFile;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.sync.SyncJobApi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.10 AC-2~AC-4: 복원 적용({@link RestoreApplication})을 실제 계획·발행 데이터 위에서 본다. 외부 tombstone이 가리키는 계정은 다시
 * 지워져 {@code DELETED}이고 잔여 행이 0이며, 세션은 모두 사라지고, 유지 계정은 그대로이며, 불변식이 통과한다. 키 ID·digest가 어긋나거나
 * 공식 포인터·portfolio 포인터·자격정보·끝나지 않은 삭제·잔여 행이 어긋나면 판정이 실패한다(서비스를 열지 않는다).
 *
 * <p>외부 tombstone은 백업 뒤의 삭제를 흉내 낸다 — 이 DB에는 그 삭제가 없고(복원된 DB), 시험이 전용 키로 digest를 직접 계산해 사본을
 * 만든다(제품의 digest 정의를 시험이 따로 다시 적는다).
 */
class RestoreApplyTest extends PlanSyncTestBase {

    @Autowired
    RestoreApplication restore;

    @Autowired
    AccountErasure erasure;

    @Autowired
    AccountAccessApi access;

    @Autowired
    DeletionTombstoneApi tombstones;

    @Autowired
    OperationsDeletionApi operations;

    @Autowired
    com.bokrilog.engagement.EngagementDeletionApi engagement;

    @Autowired
    RestoreInvariants invariants;

    @Test
    void 외부_tombstone의_계정을_다시_지우고_세션을_폐기하고_불변식이_통과한다() throws Exception {
        UUID kept = signedInWithPlan("restore-kept");
        UUID sample = signedInWithPlan("restore-sample");
        // 백업 전에 이미 지워진 계정(그 tombstone은 복원된 outbox에도 사본에도 있다).
        UUID gone = connect(DevBybitStub.Scenario.UTA2);
        assertThat(erasure.erase(gone, "restore-gone-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        assertThat(sessionCountOf(kept)).isPositive();
        assertThat(sessionCountOf(sample)).isPositive();

        Instant at = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        TombstoneFile external = copy(tombstoneOf(sample, "restore-sample-1", at),
                tombstoneOf(gone, "restore-gone-1", outboxRequestedAt("restore-gone-1")),
                new Tombstone("d".repeat(64), "restore-unknown-1", at, at.plus(DeletionTombstoneApi.RETENTION)));

        RestoreApplication.Report report = restore.apply(external);

        assertThat(report.passed()).as(report.line()).isTrue();
        assertThat(report.codes()).isEmpty();
        assertThat(report.tombstones()).isEqualTo(3);
        assertThat(report.matched()).isEqualTo(2);
        assertThat(report.erased()).isEqualTo(1);
        assertThat(report.alreadyDeleted()).isEqualTo(1);
        assertThat(report.carried()).as("표본의 것(다시 지울 때)과 모르는 계정의 것 — 이미 있던 gone의 것은 빼고").isEqualTo(2);
        // 표본의 세션은 다시 지울 때(payload 삭제) 이미 사라졌다. 남은 것은 유지 계정의 세션이고 그것도 모두 폐기한다.
        assertThat(report.sessions()).isPositive();
        assertThat(report.violations().total()).isZero();
        assertThat(report.credentials().sampled()).as("유지 계정의 자격정보").isEqualTo(1);
        assertThat(report.credentials().opened()).isEqualTo(1);
        assertThat(report.credentials().activeKey()).isTrue();
        // 보고는 수만 담는다.
        assertThat(report.line()).doesNotContain(sample.toString()).doesNotContain(kept.toString())
                .doesNotContain("d".repeat(64)).doesNotContain(tombstones.keyId()).contains("result=PASSED");
        assertThat(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}").matcher(report.line()).find()).isFalse();

        // 표본: DELETED, 잔여 0, 세션 0.
        assertThat(lifecycleState(sample)).isEqualTo("DELETED");
        for (String table : List.of("plan_definition", "sync_plan_calculation", "sync_official_plan_result",
                "sync_source_snapshot", "portfolio", "portfolio_snapshot", "source_connection", "source_provider_account",
                "identity_account", "identity_social_link", "action_grant")) {
            assertThat(countOf(table, sample)).as(table).isZero();
        }
        assertThat(sessionCount()).isZero();
        // 유지 계정: 그대로다(세션만 폐기됐다).
        assertThat(lifecycleState(kept)).isEqualTo("ACTIVE");
        assertThat(countOf("plan_definition", kept)).isEqualTo(1);
        assertThat(countOf("identity_social_link", kept)).isEqualTo(1);
        assertThat(ready(kept).planId()).isNotNull();
        // 사본의 tombstone 셋이 모두 이 DB의 outbox에 있다(다음 내보내기가 잃지 않는다).
        assertThat(jdbc().sql("SELECT request_id FROM operations_deletion_tombstone ORDER BY request_id")
                .query(String.class).list()).containsExactly("restore-gone-1", "restore-sample-1", "restore-unknown-1");

        // 늦은 접근: 복원 뒤 새 세션·발행·동기화는 DENIED다.
        assertThat(accountLifecycle().decideSessionCreation(sample)).isEqualTo(AccessDecision.DENIED);
        assertThat(access.decide(sample, sample, AccountAccessKind.READ)).isEqualTo(AccessDecision.DENIED);
        assertThat(sync.admitPlanSync(sample, SyncJobApi.SyncTrigger.MANUAL, "restore-late-sync"))
                .isInstanceOf(SyncJobApi.SyncAdmission.NotPermitted.class);
        assertThat(sync.admit(sample, "restore-late-seed")).isEmpty();

        // 다시 적용해도 같다(멱등) — 새로 지울 것도 옮길 것도 없다.
        RestoreApplication.Report again = restore.apply(external);
        assertThat(again.passed()).as(again.line()).isTrue();
        assertThat(again.erased()).isZero();
        assertThat(again.alreadyDeleted()).isEqualTo(2);
        assertThat(again.carried()).isZero();
    }

    @Test
    void 중간에_멈춘_적용은_같은_사본으로_다시_돌리면_이어진다() throws Exception {
        UUID first = connect(DevBybitStub.Scenario.UTA2);
        UUID second = connect(DevBybitStub.Scenario.UTA2);
        Instant at = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        TombstoneFile external = copy(tombstoneOf(first, "restore-partial-1", at),
                tombstoneOf(second, "restore-partial-2", at.plusMillis(1)));
        // 앞선 적용이 첫 계정을 다시 지우고(그 트랜잭션은 커밋됐다) 둘째 계정 앞에서 멈췄다 — 세션 폐기·옮기기·검증은 없었다.
        assertThat(erasure.reapply(first, external.tombstones().getFirst())).isEqualTo(AccountErasure.Outcome.ERASED);

        RestoreApplication.Report report = restore.apply(external);
        assertThat(report.passed()).as(report.line()).isTrue();
        assertThat(report.erased()).isEqualTo(1);
        assertThat(report.alreadyDeleted()).isEqualTo(1);
        assertThat(lifecycleState(first)).isEqualTo("DELETED");
        assertThat(lifecycleState(second)).isEqualTo("DELETED");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void 사본의_키_ID가_다르면_아무것도_바꾸지_않고_실패한다() throws Exception {
        UUID sample = connect(DevBybitStub.Scenario.UTA2);
        Instant at = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        TombstoneFile other = new TombstoneFile("ffffffffffffffff", Instant.now(),
                List.of(tombstoneOf(sample, "restore-other-key-1", at)));

        RestoreApplication.Report report = restore.apply(other);
        assertThat(report.passed()).isFalse();
        assertThat(report.codes()).containsExactly(RestoreApplication.KEY_ID_MISMATCH);
        assertThat(report.line()).contains("key_id=MISMATCHED").contains("codes=TOMBSTONE_KEY_ID_MISMATCH");
        assertThat(lifecycleState(sample)).isEqualTo("ACTIVE");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone").query(Integer.class).single()).isZero();
    }

    @Test
    void 복원된_outbox가_사본에_없으면_키가_어긋난_것으로_보고_아무것도_바꾸지_않는다() throws Exception {
        // 백업 하루 전에 지워진 계정 — 그 tombstone은 복원된 outbox에 있다.
        UUID gone = connect(DevBybitStub.Scenario.UTA2);
        assertThat(erasure.erase(gone, "restore-mismatch-gone")).isEqualTo(AccountErasure.Outcome.ERASED);
        jdbc().sql("UPDATE operations_deletion_tombstone SET requested_at = requested_at - interval '1 day', "
                + "destroy_after = destroy_after - interval '1 day'").update();
        UUID sample = connect(DevBybitStub.Scenario.UTA2);
        // 다른 digest 키로 만든 사본: 키 ID는 맞게 적혔지만(손으로 고친 머리) digest가 어느 계정과도 맞지 않는다.
        Instant at = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        TombstoneFile wrong = copy(new Tombstone(hmac(newKey(), sample), "restore-mismatch-1", at,
                at.plus(DeletionTombstoneApi.RETENTION)));

        RestoreApplication.Report report = restore.apply(wrong);
        assertThat(report.passed()).isFalse();
        assertThat(report.codes()).containsExactly(RestoreApplication.KEY_MISMATCH);
        assertThat(report.reconciliation().outboxMissingFromCopy()).isEqualTo(1);
        assertThat(report.line()).contains("outbox_missing_from_copy=1").contains("codes=TOMBSTONE_KEY_MISMATCH");
        assertThat(lifecycleState(sample)).isEqualTo("ACTIVE");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void 지금_키로_어느_계정과도_맞지_않는_outbox_행이_있으면_실패한다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        // 다른 키로 기록된 tombstone이 복원된 outbox에 있다(복원 역할의 키가 운영 키와 다르다).
        Instant at = databaseNow().truncatedTo(ChronoUnit.MILLIS).minus(2, ChronoUnit.DAYS);
        Tombstone foreign = new Tombstone(hmac(newKey(), user), "restore-foreign-1", at, at.plus(DeletionTombstoneApi.RETENTION));
        jdbc().sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, origin) "
                + "VALUES (:r, :d, :a, :b, 'DELETION')").param("r", foreign.requestId()).param("d", foreign.userDigest())
                .param("a", java.time.OffsetDateTime.ofInstant(foreign.requestedAt(), java.time.ZoneOffset.UTC))
                .param("b", java.time.OffsetDateTime.ofInstant(foreign.destroyAfter(), java.time.ZoneOffset.UTC)).update();

        RestoreApplication.Report report = restore.apply(copy(foreign));
        assertThat(report.passed()).isFalse();
        assertThat(report.reconciliation().outboxWithoutAccount()).isEqualTo(1);
        assertThat(report.codes()).containsExactly(RestoreApplication.KEY_MISMATCH);
    }

    @Test
    void tombstone이_다시_지우지_않은_끝나지_않은_삭제가_있으면_실패한다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        accountDeletion().beginDeletion(user);

        RestoreApplication.Report report = restore.apply(copy());
        assertThat(report.passed()).isFalse();
        assertThat(report.violations().unfinishedDeletions()).isEqualTo(1);
        assertThat(report.codes()).containsExactly(RestoreApplication.INVARIANT_VIOLATED);

        // 사본이 그 계정을 가리키면 다시 지워 끝낸다.
        Instant at = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        RestoreApplication.Report finished = restore.apply(copy(tombstoneOf(user, "restore-unfinished-1", at)));
        assertThat(finished.passed()).as(finished.line()).isTrue();
        assertThat(lifecycleState(user)).isEqualTo("DELETED");
    }

    @Test
    void 공식_포인터가_계획에_속하지_않으면_판정이_실패한다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "restore-bundle", null);
        assertThat(restore.apply(copy()).passed()).isTrue();
        // 계획의 조건만 바꾼다 — 공식 포인터의 계산 버전이 더는 그 계획의 것이 아니다.
        jdbc().sql("UPDATE plan_definition SET target_rate='0.2' WHERE user_id=:u").param("u", user).update();

        RestoreApplication.Report report = restore.apply(copy());
        assertThat(report.passed()).isFalse();
        assertThat(report.violations().officialBundles()).isEqualTo(1);
        assertThat(report.line()).contains("official_bundle_violations=1").contains("result=FAILED");
    }

    @Test
    void portfolio_현재_포인터가_가장_큰_순번이_아니면_판정이_실패한다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "restore-pointer", null);
        // 포인터를 움직이지 않은 채 다음 순번의 snapshot이 생겼다(전진은 snapshot과 한 트랜잭션이어야 한다).
        jdbc().sql("INSERT INTO portfolio_snapshot (portfolio_snapshot_id, portfolio_id, user_id, amount_decimal, base_currency, "
                + "measured_at, publication_sequence) SELECT gen_random_uuid(), p.portfolio_id, p.user_id, '1', 'USD', now(), "
                + "c.publication_sequence + 1 FROM portfolio p JOIN portfolio_current_snapshot c ON c.portfolio_id = p.portfolio_id "
                + "WHERE p.user_id = :u").param("u", user).update();

        RestoreApplication.Report report = restore.apply(copy());
        assertThat(report.passed()).isFalse();
        assertThat(report.violations().portfolioPointers()).isEqualTo(1);
    }

    @Test
    void 표본_자격정보가_지금_key_ring으로_풀리지_않으면_판정이_실패한다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        jdbc().sql("UPDATE source_credential SET ciphertext = decode('00', 'hex') WHERE connection_id IN "
                + "(SELECT connection_id FROM source_connection WHERE user_id = :u)").param("u", user).update();

        RestoreApplication.Report report = restore.apply(copy());
        assertThat(report.violations().total()).isZero();
        assertThat(report.credentials().sampled()).isEqualTo(1);
        assertThat(report.credentials().opened()).isZero();
        assertThat(report.passed()).isFalse();
        assertThat(report.codes()).containsExactly(RestoreApplication.CREDENTIALS_UNAVAILABLE);
        assertThat(report.line()).contains("credentials_opened=0/1");
    }

    @Test
    void 표본은_key_version마다_최신_행을_넣는다() throws Exception {
        UUID current = connect(DevBybitStub.Scenario.UTA2);
        UUID retired = connect(DevBybitStub.Scenario.UTA2);
        // 지금 key ring에 없는 과거 version의 오래된 행. 표본 크기가 1이어도 그 version의 최신 행으로 들어온다.
        jdbc().sql("UPDATE source_credential SET key_version = 'retired-version', created_at = now() - interval '30 days' "
                + "WHERE connection_id IN (SELECT connection_id FROM source_connection WHERE user_id = :u)")
                .param("u", retired).update();
        var probe = source.probeCredentials(1);
        assertThat(probe.sampled()).isEqualTo(2);
        assertThat(probe.opened()).isEqualTo(1);
        assertThat(probe.passed()).isFalse();
        assertThat(current).isNotEqualTo(retired);
        // active key가 없으면 표본이 모두 풀려도 통과가 아니다.
        assertThat(new com.bokrilog.source.SourceConnectionApi.CredentialProbe(0, 0, false).passed()).isFalse();
        assertThat(new com.bokrilog.source.SourceConnectionApi.CredentialProbe(0, 0, true).passed()).isTrue();
    }

    @Test
    void 삭제된_계정에_남은_행은_모듈마다_세어져_판정을_실패시킨다() throws Exception {
        // 끝까지 채운 계정(세션·연결·첫 공식 발행·허가·감사 귀속)을 지우지 않은 채 DELETED로만 바꾼다 — 모든 모듈에 행이 남는다.
        UUID user = signedInWithPlan("restore-residue");
        jdbc().sql("INSERT INTO action_grant (grant_id, user_id, session_id, command, target, confirmation_hash, expires_at) "
                + "VALUES (:g, :u, 'residue-session', 'residue', 'residue', :h, now() + interval '5 minutes')")
                .param("g", "g".repeat(40)).param("u", user).param("h", "0".repeat(64)).update();
        insertAuditRowOf(user);
        insertConsentRowOf(user);
        jdbc().sql("UPDATE identity_account_lifecycle SET state = 'DELETED' WHERE user_id = :u").param("u", user).update();

        long identity = accountDeletion().remainingRowsOf(user);
        long portfolioRows = portfolio.remainingRowsOf(user);
        long syncRows = sync.remainingRowsOf(user);
        long planRows = plans.remainingRowsOf(user);
        long sourceRows = source.remainingRowsOf(user);
        long operationRows = operations.remainingRowsOf(user);
        long engagementRows = engagement.remainingRowsOf(user);
        long grants = countOf("action_grant", user);
        // identity는 payload·매핑·세션 셋 모두.
        assertThat(identity).as("identity").isGreaterThanOrEqualTo(3);
        assertThat(sessionCountOf(user)).as("spring_session").isPositive();
        for (var part : List.of(portfolioRows, syncRows, planRows, sourceRows, operationRows, engagementRows, grants)) {
            assertThat(part).isPositive();
        }
        assertThat(sourceRows).as("source_* + 자격정보").isGreaterThanOrEqualTo(countOf("source_connection", user)
                + countOf("source_provider_account", user) + 1);
        assertThat(engagementRows).as("engagement_* 두 표(동의·자격)").isEqualTo(2);
        assertThat(invariants.check().residualRows())
                .isEqualTo(identity + portfolioRows + syncRows + planRows + sourceRows + operationRows + engagementRows
                        + grants);

        // 하나씩 늘리면 그만큼 는다: 세션 하나, 허가 하나.
        long before = invariants.check().residualRows();
        jdbc().sql("INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, "
                + "expiry_time, principal_name) VALUES (:p, :s, 0, 0, 1800, 4102444800000, :u)")
                .param("p", UUID.randomUUID().toString()).param("s", UUID.randomUUID().toString())
                .param("u", user.toString()).update();
        assertThat(invariants.check().residualRows()).isEqualTo(before + 1);
        jdbc().sql("INSERT INTO action_grant (grant_id, user_id, session_id, command, target, confirmation_hash, expires_at) "
                + "VALUES (:g, :u, 'residue-session', 'residue', 'residue-2', :h, now() + interval '5 minutes')")
                .param("g", "h".repeat(40)).param("u", user).param("h", "0".repeat(64)).update();
        assertThat(invariants.check().residualRows()).isEqualTo(before + 2);
        assertThat(restore.apply(copy()).codes()).contains(RestoreApplication.INVARIANT_VIOLATED);
    }

    @Test
    void 삭제가_끝난_계정에_감사_귀속이_남으면_세어진다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        assertThat(erasure.erase(user, "restore-residue-1")).isEqualTo(AccountErasure.Outcome.ERASED);
        assertThat(restore.apply(copy()).violations().residualRows()).isZero();
        // 비식별되지 않은 감사 수행자 귀속이 남았다(외래키 없는 표 — 카탈로그 세기가 잡아야 한다).
        insertAuditRowOf(user);
        assertThat(restore.apply(copy()).violations().residualRows()).isEqualTo(1);
    }

    /**
     * 사용자를 가리킬 수 있는 열({@code *user_id}·{@code principal_name}) 모두가 정확히 한 모듈의 세기에 든다. 모듈마다 세는 (표, 열)의
     * 규칙을 여기에 다시 적는다 — 모듈 SQL의 카탈로그 술어와 같다. 새 열이 어느 규칙에도 맞지 않으면(예: {@code sync_*.actor_user_id}) 이
     * 시험이 실패한다. 생명주기 guard 행은 남는 것이 계약이라 뺀다.
     */
    @Test
    void 사용자를_가리키는_모든_열은_정확히_한_모듈이_센다() {
        Set<String> uncovered = new TreeSet<>();
        Set<String> doubled = new TreeSet<>();
        for (String[] column : jdbc().sql("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = "
                + "current_schema() AND (column_name LIKE '%user\\_id' OR column_name = 'principal_name')")
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list()) {
            String table = column[0];
            String name = column[1];
            if (table.equals("identity_account_lifecycle")) continue;
            List<String> modules = new ArrayList<>();
            if ((table.equals("identity_account") || table.equals("identity_social_link")) && name.equals("user_id")
                    || table.equals("spring_session") && name.equals("principal_name")) modules.add("identity");
            if (table.equals("action_grant") && name.equals("user_id")) modules.add("root");
            if ((table.equals("portfolio") || table.startsWith("portfolio_")) && name.equals("user_id")) modules.add("portfolio");
            if (table.startsWith("sync_") && name.equals("user_id")) modules.add("sync");
            if (table.startsWith("plan_") && name.equals("user_id")) modules.add("plan");
            if (table.startsWith("source_") && name.equals("user_id")) modules.add("source");
            if ((table.startsWith("operations_") || table.startsWith("support_"))
                    && Set.of("user_id", "actor_user_id", "target_user_id").contains(name)) modules.add("operations");
            if (table.startsWith("engagement_") && name.equals("user_id")) modules.add("engagement");
            if (modules.isEmpty()) uncovered.add(table + "." + name);
            if (modules.size() > 1) doubled.add(table + "." + name);
        }
        assertThat(uncovered).isEmpty();
        assertThat(doubled).isEmpty();
        // 자격정보 표는 사용자 열이 없어 source가 연결을 거쳐 센다 — 그 열이 생기면 위 규칙이 바로 센다.
        assertThat(jdbc().sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                + "AND table_name = 'source_credential'").query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * 광고 동의 결정 하나(Story 4.1)와 광고 자격 결정 하나(Story 4.2). 카탈로그 세기가 engagement 두 표의 행을 모두 잡는지 본다.
     */
    private void insertConsentRowOf(UUID user) {
        jdbc().sql("INSERT INTO engagement_ad_consent_decision (user_id, policy_version, choice, idempotency_key_digest, "
                + "request_digest) VALUES (:u, 'AD-CONSENT-1.0', 'GRANTED', :k, :r)")
                .param("u", user).param("k", "c".repeat(64)).param("r", "d".repeat(64)).update();
        jdbc().sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, skip_reason) "
                + "VALUES (:u, :j, 'SKIPPED', 'CONSENT_REQUIRED')")
                .param("u", user).param("j", UUID.randomUUID()).update();
    }

    private void insertAuditRowOf(UUID user) {
        jdbc().sql("INSERT INTO operations_audit_event (audit_id, operation_id, actor_user_id, actor_digest, actor_kind, command, "
                + "scope, target, reason, outcome, policy_version, requested_at, correlation_id) VALUES (gen_random_uuid(), "
                + "'restore-residue-op', :u, :d, 'OPERATOR', 'restrict-sync', 'GLOBAL', 'GLOBAL', 'test', 'REQUESTED', 'X', "
                + "now(), 'restore-residue-op')")
                .param("u", user).param("d", "a".repeat(64)).update();
    }

    /** 로그인(세션)·연결·첫 공식 발행까지 한 계정. */
    private UUID signedInWithPlan(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        publishedPlan(user, prefix, null);
        return user;
    }

    /** 이 역할의 키 ID로 지금 만든 사본. */
    private TombstoneFile copy(Tombstone... rows) {
        return new TombstoneFile(tombstones.keyId(), Instant.now().truncatedTo(ChronoUnit.SECONDS), List.of(rows));
    }

    /** 제품과 같은 정의의 tombstone: 전용 키로 {@code tombstone:<user_id>}의 HMAC-SHA256. */
    private static Tombstone tombstoneOf(UUID user, String request, Instant at) throws Exception {
        return new Tombstone(hmac(OperatorTestProperties.TOMBSTONE_DIGEST_KEY, user), request, at,
                at.plus(DeletionTombstoneApi.RETENTION));
    }

    private static String hmac(String base64Key, UUID user) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(("tombstone:" + user).getBytes(StandardCharsets.UTF_8)));
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private Instant outboxRequestedAt(String request) {
        return jdbc().sql("SELECT requested_at FROM operations_deletion_tombstone WHERE request_id = :r").param("r", request)
                .query(java.time.OffsetDateTime.class).single().toInstant();
    }

    private String lifecycleState(UUID user) {
        return jdbc().sql("SELECT state FROM identity_account_lifecycle WHERE user_id=:u").param("u", user)
                .query(String.class).single();
    }
}
