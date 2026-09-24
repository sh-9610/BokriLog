package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.AccessDecision;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 순간에 도착한 소비.
 *
 * <p>조건부 UPDATE와 {@code (user_id, operation_id)} 유일 인덱스가 실제로 원자적인지 보는 유일한
 * 층이다. 확인하려는 것은 자바 분기가 아니라 PostgreSQL이 잠금 뒤에 술어를 다시 평가하는가와
 * 인덱스가 두 번째를 거절하는가이므로 실제 엔진이 필요하다.
 *
 * <p><b>겹침을 우연에 맡기지 않는다</b>({@code AccountConcurrencyTest}와 같은 형태). 스레드를 같은
 * 순간에 출발시키는 것만으로는 두 트랜잭션이 실제로 겹쳤다는 증거가 되지 못한다 — 소비 하나가
 * 워낙 짧아 늘 한쪽이 끝난 뒤 다른 쪽이 시작해도 모든 단정이 통과한다. 그래서 시험이 먼저 허가
 * 행을 잠그고, 참여자들이 <b>그 테이블에서</b> 줄을 섰는지 확인한 뒤에야 푼다.
 */
class ActionGrantConcurrencyTest extends AuthFlowTestBase {

    /** 경합을 여러 번 반복한다. 한 번만 돌면 우연히 통과한 회차를 구분할 수 없다. */
    private static final int ROUNDS = 5;

    /** 참여자가 잠금 앞에 줄을 설 때까지 기다리는 상한. 넘으면 시험이 실패로 끝난다. */
    private static final long BLOCK_WAIT_TIMEOUT_MILLIS = 20_000L;

    @Autowired private ActionGrantService actionGrants;

    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void 같은_허가로_동시에_들어온_소비는_정확히_하나만_승인된다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID userId = accountLifecycle().createAccount();
            HttpSession session = grantSession(userId);
            String reference = issue(session, userId);
            AtomicInteger executions = new AtomicInteger();

            List<Consumption> results =
                    runQueuedOnGrantLocks(
                            List.of(reference),
                            List.of(
                                    consumer(session, userId, reference, "op-a-" + round, executions),
                                    consumer(session, userId, reference, "op-b-" + round, executions)));

            // 정확히 하나만 승인된다. 나머지는 상태를 바꾸지 않고 하나의 거부로 끝난다.
            assertThat(results)
                    .describedAs("round=%d", round)
                    .filteredOn(Consumption.ConsumedRequiringSessionRotation.class::isInstance)
                    .hasSize(1);
            assertThat(results)
                    .describedAs("round=%d", round)
                    .filteredOn(Consumption.Denied.class::isInstance)
                    .hasSize(1);

            // 업무 변경은 한 번만 일어났다. 판정과 소비가 두 문장으로 갈렸다면 여기가 2가 된다.
            assertThat(executions).describedAs("round=%d", round).hasValue(1);
            assertThat(actionGrantConsumed(reference)).isTrue();
            assertThat(socialLinkCount()).describedAs("round=%d", round).isEqualTo(1);

            // 다음 회차를 위해 이 회차가 남긴 행을 지운다. `@BeforeEach`는 라운드 사이에 돌지 않는다.
            resetStore();
        }
    }

    @Test
    void 같은_요청이_동시에_두_번_도착해도_업무는_한_번만_실행된다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID userId = accountLifecycle().createAccount();
            HttpSession session = grantSession(userId);
            String reference = issue(session, userId);
            AtomicInteger executions = new AtomicInteger();
            String operationId = "op-retry-" + round;

            List<Consumption> results =
                    runQueuedOnGrantLocks(
                            List.of(reference),
                            List.of(
                                    consumer(session, userId, reference, operationId, executions),
                                    consumer(session, userId, reference, operationId, executions)));

            /*
             * 응답을 잃은 클라이언트의 재전송이 앞 요청과 같은 순간에 도착한 모양이다. 둘 다 같은
             * 결과를 받아야 하고 업무 변경은 한 번만 일어나야 한다. 애플리케이션이 "이미 있나?"를
             * 먼저 조회하는 형태였다면 여기서 둘 다 통과한다.
             */
            assertThat(results)
                    .describedAs("round=%d", round)
                    .containsOnly(new Consumption.ConsumedRequiringSessionRotation("연결됨"));
            assertThat(executions).describedAs("round=%d", round).hasValue(1);
            assertThat(socialLinkCount()).describedAs("round=%d", round).isEqualTo(1);

            resetStore();
        }
    }

    @Test
    void 다른_허가에_같은_operation_id가_동시에_오면_인덱스가_하나를_거절한다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID userId = accountLifecycle().createAccount();
            HttpSession session = grantSession(userId);
            // 두 작업이 다르면 발급이 서로를 무효화하지 않는다.
            String linkGrant = issue(session, userId);
            String unlinkGrant =
                    this.actionGrants
                            .issue(session, userId, "unlink-provider", GRANT_TARGET, GRANT_CONFIRMATION)
                            .orElseThrow();
            AtomicInteger executions = new AtomicInteger();
            String operationId = "op-collide-" + round;

            List<Consumption> results =
                    runQueuedOnGrantLocks(
                            List.of(linkGrant, unlinkGrant),
                            List.of(
                                    consumer(session, userId, linkGrant, GRANT_COMMAND, operationId, executions),
                                    consumer(
                                            session,
                                            userId,
                                            unlinkGrant,
                                            "unlink-provider",
                                            operationId,
                                            executions)));

            /*
             * 두 트랜잭션이 서로 다른 행을 잠그므로 조건부 UPDATE는 둘 다 통과한다. 그 뒤를 가르는
             * 것은 `(user_id, operation_id)` 유일 인덱스 하나이며, 진 쪽은 업무 변경을 실행하지
             * 못한 채 롤백된다.
             */
            assertThat(results)
                    .describedAs("round=%d", round)
                    .filteredOn(Consumption.ConsumedRequiringSessionRotation.class::isInstance)
                    .hasSize(1);
            assertThat(results)
                    .describedAs("round=%d", round)
                    .filteredOn(Consumption.Denied.class::isInstance)
                    .hasSize(1);
            assertThat(executions).describedAs("round=%d", round).hasValue(1);
            assertThat(socialLinkCount()).describedAs("round=%d", round).isEqualTo(1);

            resetStore();
        }
    }

    // ----------------------------------------------------------------------
    // 도구
    // ----------------------------------------------------------------------

    private String issue(HttpSession session, UUID userId) {
        return this.actionGrants
                .issue(session, userId, GRANT_COMMAND, GRANT_TARGET, GRANT_CONFIRMATION)
                .orElseThrow();
    }

    private Callable<Consumption> consumer(
            HttpSession session,
            UUID userId,
            String reference,
            String operationId,
            AtomicInteger executions) {
        return consumer(session, userId, reference, GRANT_COMMAND, operationId, executions);
    }

    private Callable<Consumption> consumer(
            HttpSession session,
            UUID userId,
            String reference,
            String command,
            String operationId,
            AtomicInteger executions) {
        return () ->
                this.actionGrants.consume(
                        new ConsumptionRequest(
                                session,
                                userId,
                                reference,
                                command,
                                GRANT_TARGET,
                                GRANT_CONFIRMATION,
                                operationId),
                        linking(userId, executions));
    }

    /** 업무 변경 하나. {@code ActionGrantTransactionTest}와 같은 근거로 실제 쓰기다. */
    private Supplier<String> linking(UUID userId, AtomicInteger executions) {
        return () -> {
            executions.incrementAndGet();
            AccessDecision decision =
                    accountLifecycle()
                            .linkSocialIdentity(
                                    userId,
                                    GRANT_TARGET + "-" + UUID.randomUUID(),
                                    "https://provider.example/" + UUID.randomUUID(),
                                    UUID.randomUUID().toString());
            assertThat(decision).isEqualTo(AccessDecision.ALLOWED);
            return "연결됨";
        };
    }

    /**
     * 참여자들을 허가 행 잠금 앞에 순서대로 줄 세운 뒤 한꺼번에 푼다.
     *
     * <p>{@code AccountConcurrencyTest.runQueuedOnLifecycleLocks}와 같은 도구이고 잠그는 행만 다르다.
     * 보장하는 것도 같다 — 참여 트랜잭션이 같은 순간에 살아 있었다는 사실과, 누가 먼저 잠금을
     * 잡는지가 스케줄러가 아니라 시험이 정한 값이라는 사실이다.
     */
    private <T> List<T> runQueuedOnGrantLocks(List<String> references, List<Callable<T>> tasks)
            throws Exception {
        int count = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(count);
        List<Future<T>> futures = new ArrayList<>(count);
        try {
            this.transactionTemplate.execute(
                    status -> {
                        for (String reference : references) {
                            jdbc()
                                    .sql("SELECT grant_id FROM action_grant WHERE grant_id = :reference FOR UPDATE")
                                    .param("reference", reference)
                                    .query(String.class)
                                    .single();
                        }
                        long queued = 0;
                        for (Callable<T> task : tasks) {
                            futures.add(pool.submit(task));
                            queued++;
                            awaitBlockedOnGrants(queued);
                        }
                        // 잠금은 이 트랜잭션이 끝나야 풀린다. 시험은 아무것도 바꾸지 않았으므로
                        // 롤백으로 끝낸다.
                        status.setRollbackOnly();
                        return null;
                    });

            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * {@code action_grant}를 건드리다 막힌 백엔드 프로세스 수.
     *
     * <p>대기하는 트랜잭션 하나가 정확히 한 pid다({@code AccountConcurrencyTest}와 같은 근거 — 승인되지
     * 않은 잠금 <b>행</b>을 세면 대기 하나가 여러 행을 만들 수 있어 수가 흔들린다).
     *
     * <p>대상을 이 테이블로 좁힌다. 좁히지 않으면 같은 컨테이너의 다른 대기가 이 수를 흔든다.
     * 좁히는 축이 <b>승인되지 않은 잠금 쪽이 아니라 이미 들고 있는 잠금 쪽</b>인 이유가 둘이다.
     * 첫 대기자는 상대 트랜잭션의 {@code transactionid}를 기다리고 그 잠금 행에는 relation이 없으며,
     * {@code pg_stat_activity}의 질의 문자열은 대기 중인 백엔드에서 직전 값으로 남아 있을 수 있다
     * (실측에서 두 번째 대기자가 {@code idle}·{@code COMMIT}으로 보였다). 반면 그 테이블을 UPDATE하는
     * 백엔드는 예외 없이 relation 잠금을 <b>승인받아</b> 들고 있으므로, "이 테이블의 잠금을 들고
     * 있으면서 무언가를 기다리는 pid"가 정확히 우리 참여자다.
     */
    private long blockedOnGrants() {
        return jdbc()
                .sql(
                        "SELECT count(DISTINCT blocked.pid) FROM pg_locks AS blocked "
                                + "JOIN pg_locks AS held ON held.pid = blocked.pid "
                                + "AND held.granted AND held.relation = 'action_grant'::regclass "
                                + "WHERE NOT blocked.granted")
                .query(Long.class)
                .single();
    }

    private void awaitBlockedOnGrants(long expected) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BLOCK_WAIT_TIMEOUT_MILLIS);
        long observed = blockedOnGrants();
        while (observed < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("잠금 대기를 기다리는 중 인터럽트됐다.", interrupted);
            }
            observed = blockedOnGrants();
        }
        if (observed < expected) {
            throw new IllegalStateException(
                    ("참여자가 action_grant 잠금 앞에 줄을 서지 않았다. 기대 %d, 실제 %d. "
                                    + "겹침이 강제되지 않으면 이 시험은 동시 소비를 검증하지 못한다.")
                            .formatted(expected, observed));
        }
    }
}
