package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import com.bokrilog.operations.SignalType;
import com.bokrilog.operations.internal.OperationsTestHook;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 삭제의 operations 비식별과 감사 원장 추가 잠금의 직렬화(Epic 3 회고 B-2, {@code spec-erasure-audit-lock.md}).
 *
 * <p>삭제는 operations 단계에서 원장 잠금을 쥐고 커밋까지 놓지 않는다. 그래서 잠금을 먼저 쥔 쓰기가 커밋한 사용자 귀속 행은
 * 삭제가 보고 비식별하며, 삭제 뒤에 깨어난 강한 신호는 삭제가 시작된 것을 보고 자기가 쓴 귀속을 같은 트랜잭션에서 비식별한다.
 * 활성 사용자의 신호는 원문 target·귀속을 그대로 둔다.
 */
class ErasureAuditLockTest extends OperatorTestBase {

    /** 원장 추가 잠금의 이름({@code OperatorAuditRepository}의 상수와 같은 문자열). */
    private static final String APPEND_LOCK = "bokrilog.operations_audit_event.append";

    /**
     * 문의 삭제가 쥐는 사용자 잠금 이름의 접두사({@code SupportInquiryRepository}의 상수와 같은 문자열). 계정 삭제의 operations
     * 단계 바로 뒤 단계라, 시험이 이것을 쥐면 삭제가 원장 잠금을 쥔 채 커밋하지 못하고 멈춘다.
     */
    private static final String INQUIRY_USER_LOCK = "bokrilog.support_inquiry.user:";

    /**
     * 그 이름의 advisory 잠금(bigint 키)을 기다리는 backend 수({@code AdEligibilityTest}와 같은 모양). 시험은 여기에 pid 조건을
     * 더해 기다리는 backend가 바로 그 삭제·신호인지 본다 — scheduler·worker의 다른 대기와 섞이지 않는다.
     */
    private static final String LOCK_WAITERS = "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted "
            + "AND objsubid = 1 AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(:name, 0)";

    private static final String DIGEST = "[0-9a-f]{64}";

    private static final String CALC = "CALC-1.0";

    @Autowired
    private AccountErasure erasure;

    @Autowired
    private OperationalSignalApi signals;

    @Autowired
    private OperationsDeletionApi deletion;

    @Autowired
    private DeletionTombstoneApi tombstones;

    @Autowired
    private EngagementDeletionApi engagement;

    @Autowired
    private ApplicationContext context;

    /**
     * I/O 1: 원장 잠금을 쥔 트랜잭션이 사용자 귀속 제한을 INSERT한 채 커밋을 미루는 동안 같은 사용자의 삭제가 시작되면, 삭제는
     * operations 단계에서 그 잠금의 대기자로 보이고(삭제 자신의 backend pid로 — 시간으로 추측하지 않는다) 잠금이 풀린 뒤 커밋된
     * 그 행까지 비식별한다.
     */
    @Test
    void 삭제는_원장_잠금을_쥔_쓰기_뒤에_줄_서고_그_행까지_비식별한다() throws Exception {
        UUID user = signalOwner();
        UUID connection = UUID.randomUUID();
        UUID restriction = UUID.randomUUID();

        AccountErasure.Outcome outcome = eraseBehindAppendLockWriter(user, "erasure-lock-1", () ->
                jdbc().sql("INSERT INTO operations_restriction (restriction_id, feature, scope, target, target_user_id, "
                                + "operation_id) VALUES (:id, 'SYNC', 'PROVIDER_CONNECTION', :target, :user, :op)")
                        .param("id", restriction).param("target", connection.toString()).param("user", user)
                        .param("op", "erasure-lock-writer").update());
        assertThat(outcome).isEqualTo(AccountErasure.Outcome.ERASED);

        assertThat(attributedRestrictions(user)).isZero();
        var row = jdbc().sql("SELECT target, target_user_id IS NULL AS unowned, target_erased_at IS NOT NULL AS erased "
                        + "FROM operations_restriction WHERE restriction_id = :id")
                .param("id", restriction)
                .query((rs, n) -> List.of(rs.getString("target"), rs.getBoolean("unowned"), rs.getBoolean("erased")))
                .single();
        assertThat((String) row.get(0)).matches(DIGEST).isNotEqualTo(connection.toString());
        assertThat(row.subList(1, 3)).containsExactly(true, true);
        assertThat(this.deletion.remainingRowsOf(user)).isZero();
    }

    /**
     * I/O 1의 수행자 귀속판: 원장 잠금을 쥔 트랜잭션이 그 사용자를 수행자로 한 감사 행을 append한 채 커밋을 미뤄도, 삭제의 수행자
     * 비식별({@code eraseActor})이 자기 잠금으로 줄을 서 그 행의 {@code actor_user_id}까지 비운다. 제한 행이 없어 제한 비식별의
     * 잠금에 기대지 못하는 경우다.
     */
    @Test
    void 삭제는_원장_잠금을_쥔_감사_append_뒤에_줄_서고_그_수행자_귀속까지_비운다() throws Exception {
        UUID user = signalOwner();
        UUID audit = UUID.randomUUID();

        AccountErasure.Outcome outcome = eraseBehindAppendLockWriter(user, "erasure-lock-actor-1", () ->
                jdbc().sql("INSERT INTO operations_audit_event (audit_id, operation_id, actor_user_id, actor_digest, "
                                + "actor_kind, command, scope, target, reason, outcome, policy_version, requested_at, "
                                + "correlation_id) VALUES (:id, :op, :user, :digest, 'OPERATOR', 'audit-export', 'AUDIT', "
                                + "'ALL', 'erasure lock', 'REQUESTED', 'OPS-1.0', statement_timestamp(), :op)")
                        .param("id", audit).param("op", "erasure-lock-actor").param("user", user)
                        .param("digest", "a".repeat(64)).update());
        assertThat(outcome).isEqualTo(AccountErasure.Outcome.ERASED);

        assertThat(jdbc().sql("SELECT (actor_user_id IS NULL) || '|' || (actor_erased_at IS NOT NULL) "
                        + "FROM operations_audit_event WHERE audit_id = :id").param("id", audit)
                .query(String.class).single()).isEqualTo("true|true");
        assertThat(this.deletion.remainingRowsOf(user)).isZero();
    }

    /**
     * 핵심 경합: 삭제가 원장 잠금을 쥔 채 아직 커밋하지 않았을 때(operations 단계 뒤 문의 단계에서 막혀 있다) 같은 사용자의 계획
     * 신호가 오면, 신호는 원장 잠금을 기다리고(그 신호의 backend pid로 확인한다) 삭제가 커밋된 뒤 깨어나 삭제를 보고 자기 귀속을
     * 비식별한다. 둘이 끝난 뒤 귀속된 제한·신호 행은 없고 제한 target은 digest다.
     */
    @Test
    void 삭제가_원장_잠금을_쥔_동안_온_신호는_기다렸다가_자기_귀속을_비식별한다() throws Exception {
        UUID user = signalOwner();
        UUID plan = UUID.randomUUID();
        UUID connection = UUID.randomUUID();
        AtomicInteger erasurePid = new AtomicInteger();
        AtomicInteger signalPid = new AtomicInteger();
        OperationalSignalApi reporting = OperationsTestHook.signalsReportingBackend(this.context, signalPid::set);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 삭제를 operations 단계 바로 뒤(문의 삭제의 사용자 잠금)에서 멈춘다 — 그때 삭제는 원장 잠금을 쥐고 있다.
            try (Held inquiryLock = hold(executor, INQUIRY_USER_LOCK + user, () -> {})) {
                var erasing = executor.submit(() -> eraseReportingBackend(user, "erasure-race-1", erasurePid));
                awaitWaiting(INQUIRY_USER_LOCK + user, erasurePid);
                assertThat(holders(APPEND_LOCK)).as("원장 잠금을 쥔 삭제").containsExactly(erasurePid.get());

                var raising = executor.submit(() -> reporting.raise(new Signal(SignalType.SOURCE_ID_CONFLICT,
                        RestrictionSubject.none().withPlan(plan).withConnection(connection), user, references(), null)));
                awaitWaiting(APPEND_LOCK, signalPid);
                assertThat(raising.isDone()).isFalse();
                assertThat(erasing.isDone()).isFalse();

                inquiryLock.commit();
                assertThat(erasing.get(30, TimeUnit.SECONDS)).isEqualTo(AccountErasure.Outcome.ERASED);
                assertThat(raising.get(30, TimeUnit.SECONDS).decisions()).singleElement()
                        .satisfies(decision -> assertThat(decision.outcome())
                                .isEqualTo(OperationalSignalApi.Outcome.CREATED));
            }
        }

        assertThat(attributedRestrictions(user)).isZero();
        assertThat(attributedSignals(user)).isZero();
        assertThat(restrictionRows()).containsExactly("SYNC|PLAN|SIGNAL|erased");
        assertThat(jdbc().sql("SELECT target FROM operations_restriction").query(String.class).single())
                .matches(DIGEST).isNotEqualTo(plan.toString());
        assertThat(this.deletion.remainingRowsOf(user)).isZero();
    }

    /**
     * 섞인 경우: 삭제된 사용자의 신호가 대상 차원이 없어 전역 제한으로 접히면 제한은 전역·원문 그대로(귀속이 처음부터 없다)이고,
     * 신호 행의 {@code target_user_id}만 비워진다.
     */
    @Test
    void 삭제된_사용자의_신호가_전역으로_접히면_신호_행의_귀속만_비운다() throws Exception {
        UUID user = signalOwner();
        assertThat(this.erasure.erase(user, "erasure-lock-global-1")).isEqualTo(AccountErasure.Outcome.ERASED);

        this.signals.raise(new Signal(SignalType.SOURCE_ID_CONFLICT, RestrictionSubject.none(), user, references(),
                null));

        assertThat(jdbc().sql("SELECT feature || '|' || scope || '|' || target || '|' || "
                        + "COALESCE(target_user_id::text, '-') || '|' || (target_erased_at IS NULL) FROM operations_restriction")
                .query(String.class).list()).containsExactlyInAnyOrder(
                "NEW_CONNECTION|GLOBAL|" + RestrictionScope.GLOBAL_TARGET + "|-|true",
                "SYNC|GLOBAL|" + RestrictionScope.GLOBAL_TARGET + "|-|true");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal").query(Integer.class).single()).isEqualTo(2);
        assertThat(attributedSignals(user)).isZero();
    }

    /**
     * I/O 2: 삭제가 커밋된 사용자를 대상으로 한 계획·portfolio·연결 신호. 정지 기록(제한·신호·감사 SYSTEM 행)은 남고 제한 target은
     * digest·귀속 없음·비식별 시각 있음, 신호 행의 귀속도 없다. 같은 대상의 다음 신호는 그 비식별된 열린 제한에 잇는다.
     */
    @Test
    void 삭제_뒤의_신호는_정지를_남기고_자기_귀속을_비식별한다() throws Exception {
        UUID user = signalOwner();
        assertThat(this.erasure.erase(user, "erasure-lock-2")).isEqualTo(AccountErasure.Outcome.ERASED);
        UUID plan = UUID.randomUUID();
        UUID portfolio = UUID.randomUUID();
        UUID connection = UUID.randomUUID();

        this.signals.raise(new Signal(SignalType.SOURCE_ID_CONFLICT,
                RestrictionSubject.none().withPlan(plan).withConnection(connection), user, references(), null));
        this.signals.raise(new Signal(SignalType.PORTFOLIO_INVARIANT_VIOLATION,
                RestrictionSubject.none().withPortfolio(portfolio), user,
                Map.of("jobId", UUID.randomUUID().toString()), null));
        var unknown = this.signals.raise(unknownEnum(user, connection));

        assertThat(restrictionRows()).containsExactlyInAnyOrder("CALCULATION_DISPLAY|PORTFOLIO|SIGNAL|erased",
                "SYNC|PLAN|SIGNAL|erased", "SYNC|PORTFOLIO|SIGNAL|erased", "SYNC|PROVIDER_CONNECTION|SIGNAL|erased");
        List<String> targets = jdbc().sql("SELECT target FROM operations_restriction").query(String.class).list();
        assertThat(targets).allMatch(target -> target.matches(DIGEST))
                .doesNotContain(plan.toString(), portfolio.toString(), connection.toString());
        // 감사 SYSTEM 행은 신호마다 하나이고, 그 target은 제한 행의 digest와 같다.
        List<String> auditTargets = jdbc().sql("SELECT target FROM operations_audit_event WHERE actor_kind = 'SYSTEM' "
                + "AND actor_user_id IS NULL").query(String.class).list();
        assertThat(auditTargets).hasSize(4).containsExactlyInAnyOrderElementsOf(targets);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal").query(Integer.class).single()).isEqualTo(4);
        assertThat(attributedSignals(user)).isZero();

        // 같은 대상의 다음 신호: 새 제한 없이 비식별된 열린 제한에 잇는다(같은 digest의 열린 제한이 둘이 되지 않는다).
        var again = this.signals.raise(unknownEnum(user, connection));
        assertThat(again.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.outcome()).isEqualTo(OperationalSignalApi.Outcome.LINKED);
            assertThat(decision.restrictionId()).isEqualTo(unknown.decisions().getFirst().restrictionId());
        });
        assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction").query(Integer.class).single()).isEqualTo(4);
        assertThat(attributedSignals(user)).isZero();
        assertThat(this.deletion.remainingRowsOf(user)).isZero();
    }

    /** I/O 3: 삭제가 없는 사용자의 신호는 지금과 같다 — 원문 target·귀속이 남는다. */
    @Test
    void 활성_사용자의_신호는_원문_target과_귀속을_남긴다() throws Exception {
        UUID user = signalOwner();
        UUID connection = UUID.randomUUID();
        this.signals.raise(unknownEnum(user, connection));

        assertThat(jdbc().sql("SELECT target || '|' || target_user_id || '|' || (target_erased_at IS NULL) "
                        + "FROM operations_restriction").query(String.class).list())
                .containsExactly(connection + "|" + user + "|true");
        assertThat(attributedSignals(user)).isEqualTo(1);
    }

    /**
     * I/O 4: 귀속 사용자가 없는 신호(전역·계산 정책 버전)는 계정 상태를 묻지 않고 지금과 같다 — target은 원문 그대로다. 대조로
     * 귀속 사용자가 있는 신호는 같은 port를 한 번 묻는다(시험 port가 실제로 배선됐다).
     */
    @Test
    void 귀속_없는_신호는_계정_상태를_묻지_않고_지금과_같다() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        OperationalSignalApi counted = OperationsTestHook.signalsAskingVia(this.context, user -> {
            asked.incrementAndGet();
            return false;
        });

        counted.raise(new Signal(SignalType.RESULT_HASH_MISMATCH, RestrictionSubject.none().withCalculationPolicy(CALC),
                null, Map.of("calculationId", UUID.randomUUID().toString()), null));
        // 계산 정책을 모르면 전역 대안으로 접힌다.
        counted.raise(new Signal(SignalType.RESULT_HASH_MISMATCH, RestrictionSubject.none(), null,
                Map.of("calculationId", UUID.randomUUID().toString()), null));

        assertThat(asked).as("귀속 없는 신호의 계정 상태 질의").hasValue(0);
        assertThat(jdbc().sql("SELECT feature || '|' || scope || '|' || target || '|' || "
                        + "COALESCE(target_user_id::text, '-') || '|' || (target_erased_at IS NULL) FROM operations_restriction")
                .query(String.class).list()).containsExactlyInAnyOrder(
                "CALCULATION_DISPLAY|CALCULATION_POLICY_VERSION|" + CALC + "|-|true",
                "CALCULATION_DISPLAY|GLOBAL|" + RestrictionScope.GLOBAL_TARGET + "|-|true",
                "SYNC|GLOBAL|" + RestrictionScope.GLOBAL_TARGET + "|-|true");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_signal WHERE target_user_id IS NOT NULL")
                .query(Integer.class).single()).isZero();

        counted.raise(unknownEnum(signalOwner(), UUID.randomUUID()));
        assertThat(asked).as("귀속 사용자가 있는 신호는 묻는다").hasValue(1);
    }

    /**
     * I/O 5: digest 키가 없는 역할에서 비식별할 제한 행이 있으면 삭제 port가 {@link IllegalStateException}으로 실패하고, 그 port를
     * 쓴 계정 삭제 전체가 되돌아간다 — 상태 전진·tombstone·앞선 모듈 삭제·신호 귀속 비우기까지 아무것도 남지 않는다.
     */
    @Test
    void digest_키가_없는_역할은_귀속_행이_있으면_실패하고_삭제_전체가_되돌아간다() throws Exception {
        UUID user = signalOwner();
        UUID connection = UUID.randomUUID();
        this.signals.raise(unknownEnum(user, connection));
        OperationsDeletionApi keyless = OperationsTestHook.keylessDeletion(this.context);

        assertThatThrownBy(() -> keyless.eraseRestrictionTargets(user)).isInstanceOf(IllegalStateException.class);
        assertStillAttributed(user, connection);

        AccountErasure erasureWithoutKey = new AccountErasure(accountDeletion(), this.tombstones, portfolio, sync, plans,
                source, keyless, this.engagement, this.manager);
        assertThatThrownBy(() -> erasureWithoutKey.erase(user, "erasure-keyless-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc().sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :u").param("u", user)
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone").query(Integer.class).single())
                .isZero();
        assertStillAttributed(user, connection);

        // 대조: 비식별할 행이 없으면 키 없이도 0으로 끝난다.
        assertThat(keyless.eraseRestrictionTargets(signalOwner())).isZero();
    }

    /** 그 사용자의 제한 한 행이 원문 target·귀속을 그대로 갖고, 신호 행의 귀속도 그대로다. */
    private void assertStillAttributed(UUID user, UUID connection) {
        assertThat(jdbc().sql("SELECT target || '|' || (target_erased_at IS NULL) FROM operations_restriction "
                        + "WHERE target_user_id = :u").param("u", user).query(String.class).list())
                .containsExactly(connection + "|true");
        assertThat(attributedSignals(user)).isEqualTo(1);
    }

    private static Signal unknownEnum(UUID user, UUID connection) {
        return new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM, RestrictionSubject.none().withConnection(connection),
                user, references(), null);
    }

    private static Map<String, String> references() {
        return Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString());
    }

    /** 제한 행: 기능|scope|출처|비식별 여부(귀속 없음·비식별 시각 있음이면 {@code erased}). */
    private List<String> restrictionRows() {
        return jdbc().sql("SELECT feature || '|' || scope || '|' || origin || '|' || CASE WHEN target_user_id IS NULL "
                + "AND target_erased_at IS NOT NULL THEN 'erased' ELSE 'attributed' END FROM operations_restriction")
                .query(String.class).list();
    }

    private int attributedRestrictions(UUID user) {
        return jdbc().sql("SELECT count(*) FROM operations_restriction WHERE target_user_id = :u").param("u", user)
                .query(Integer.class).single();
    }

    private int attributedSignals(UUID user) {
        return jdbc().sql("SELECT count(*) FROM operations_signal WHERE target_user_id = :u").param("u", user)
                .query(Integer.class).single();
    }

    /**
     * 원장 잠금을 쥔 트랜잭션이 {@code write}를 한 채 커밋을 미루는 동안 그 사용자를 지운다. 삭제가 그 잠금을 기다리는 것을 삭제
     * 자신의 backend pid로 확인한 뒤에야 쓰기를 커밋시키고, 삭제의 결과를 돌려준다.
     */
    private AccountErasure.Outcome eraseBehindAppendLockWriter(UUID user, String requestId, Runnable write)
            throws Exception {
        AtomicInteger erasurePid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (Held writer = hold(executor, APPEND_LOCK, write)) {
                var erasing = executor.submit(() -> eraseReportingBackend(user, requestId, erasurePid));
                awaitWaiting(APPEND_LOCK, erasurePid);
                assertThat(erasing.isDone()).isFalse();
                writer.commit();
                return erasing.get(30, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 바깥 트랜잭션에서 그 backend의 pid를 알린 뒤 지운다. 삭제의 트랜잭션({@code REQUIRED})이 이 트랜잭션에 참여하므로 같은
     * backend다.
     */
    private AccountErasure.Outcome eraseReportingBackend(UUID user, String requestId, AtomicInteger pid) {
        return new TransactionTemplate(this.manager).execute(status -> {
            pid.set(jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return this.erasure.erase(user, requestId);
        });
    }

    /** 그 backend가 그 이름의 advisory 잠금을 기다리는 것으로 {@code pg_locks}에 보일 때까지 기다린다. */
    private void awaitWaiting(String lockName, AtomicInteger pid) throws InterruptedException {
        boolean waiting = pollUntil(Duration.ofSeconds(10), () -> pid.get() != 0 && jdbc().sql(LOCK_WAITERS
                        + " AND pid = :pid").param("name", lockName).param("pid", pid.get()).query(Integer.class)
                .single() > 0, found -> found);
        assertThat(waiting).as("%s 잠금을 기다리는 backend %s", lockName, pid.get()).isTrue();
    }

    /** 그 이름의 advisory 잠금을 쥔 backend들. */
    private List<Integer> holders(String lockName) {
        return jdbc().sql("SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND granted AND objsubid = 1 "
                        + "AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(:name, 0)")
                .param("name", lockName).query(Integer.class).list();
    }

    /** 그 이름의 advisory 잠금을 쥐고 {@code inside}를 한 트랜잭션을 {@link Held#close}까지 커밋하지 않고 둔다. */
    private Held hold(ExecutorService executor, String lockName, Runnable inside) throws InterruptedException {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var template = new TransactionTemplate(this.manager);
        Future<?> holder = executor.submit(() -> template.executeWithoutResult(status -> {
            jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))").param("name", lockName)
                    .query((rs, row) -> 1).single();
            inside.run();
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
        assertThat(locked.await(10, TimeUnit.SECONDS)).as("%s 잠금을 쥐었다", lockName).isTrue();
        return new Held(release, holder);
    }

    /**
     * 쥐고 있는 잠금 트랜잭션. {@link #commit}이 그 트랜잭션을 커밋시키고 끝을 기다린다. 닫기는 시험이 도중에 실패해도 잠금을
     * 남기지 않게 같은 일을 한 번 더 한다(두 번 해도 된다).
     */
    private record Held(CountDownLatch latch, Future<?> holder) implements AutoCloseable {

        void commit() throws ExecutionException, TimeoutException {
            this.latch.countDown();
            try {
                this.holder.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        @Override
        public void close() throws ExecutionException, TimeoutException {
            commit();
        }
    }
}
