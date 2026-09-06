package com.bokrilog.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.PostgresTestcontainersConfig;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 삭제 전환과 쓰기·세션 생성 판정의 경합.
 *
 * <p>실제 PostgreSQL이 필요한 이유가 여기 다 있다. 확인하려는 것은 자바 코드의 분기가 아니라
 * {@code SELECT … FOR UPDATE}가 두 트랜잭션을 실제로 직렬화하는가, 단조 전진 술어가 붙은
 * UPDATE가 0행으로 끝나는가, 진 쪽이 정말 아무것도 남기지 않는가다.
 *
 * <p><b>겹침을 우연에 맡기지 않는다.</b> 스레드를 같은 순간에 출발시키는 것만으로는 두 트랜잭션이
 * 실제로 겹쳤다는 증거가 되지 못한다. 각 호출이 잠금을 잡고 놓기까지가 워낙 짧아, 늘 한쪽이 끝난
 * 뒤 다른 쪽이 시작해도 모든 단정이 통과한다. 그래서 시험이 먼저 생명주기 행 잠금을 잡고
 * ({@link #runQueuedOnLifecycleLocks}), 참여자들이 그 잠금 앞에 실제로 줄을 섰는지
 * {@code pg_locks}로 확인한 뒤에야 잠금을 푼다. 겹침이 관측된 사실이 되고, 잠금 획득 순서도
 * 시험이 정한 값이 된다.
 *
 * <p>그 위에서 단정은 여전히 불변식이다. "허용이면 매핑이 남고 거부면 남지 않는다", "상태는 결코
 * ACTIVE로 돌아가지 않는다"를 매 회차에서 확인한다. 더해서 라운드 전체에서 허용과 거부가 <b>모두</b>
 * 관측됐음을 단정한다. 한쪽 갈래만 계속 도는 시험은 반대편을 검증한 적이 없기 때문이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestcontainersConfig.class)
/*
 * 연결 풀 크기를 시험 구성에 적어 둔다.
 *
 * 이 시험은 참여 트랜잭션을 동시에 살려 둔다. 시험 자신의 잠금 트랜잭션 1개와 줄 세운 작업
 * 최대 8개가 같은 순간에 연결을 하나씩 붙든다. 풀이 그보다 작으면 뒤에 오는 작업이 데이터베이스
 * 잠금이 아니라 커넥션 대기에서 막히고, 그러면 이 시험이 보는 것은 잠금 계약이 아니라 Hikari의
 * timeout이 된다. 지금까지는 Hikari 기본값 10에 암묵적으로 기대고 있었다. 아래 스레드 수를
 * 늘리면 이 값도 함께 올린다.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=10")
class AccountConcurrencyTest {

    /** 경합을 여러 번 반복한다. 절반은 매핑 연결이, 절반은 삭제 전환이 먼저 줄을 선다. */
    private static final int ROUNDS = 12;

    private static final int DUPLICATE_TRANSITION_THREADS = 8;

    /** 참여자가 잠금 앞에 줄을 설 때까지 기다리는 상한. 넘으면 시험이 실패로 끝난다. */
    private static final long BLOCK_WAIT_TIMEOUT_MILLIS = 20_000L;

    @Autowired private AccountLifecycleApi lifecycle;

    @Autowired private AccountAccessApi access;

    @Autowired private AccountDeletionApi deletion;

    @Autowired private JdbcClient jdbc;

    @Autowired private TransactionTemplate transactionTemplate;

    private String storedState(UUID userId) {
        return jdbc
                .sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse("<행 없음>");
    }

    private long countLinks(UUID userId) {
        return jdbc
                .sql("SELECT count(*) FROM identity_social_link WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** 주어진 작업들을 같은 순간에 시작시키고 결과를 모은다. 잠금을 잡지 않는 판정에 쓴다. */
    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        int count = tasks.size();
        CyclicBarrier start = new CyclicBarrier(count);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> futures = new ArrayList<>(count);
            for (Callable<T> task : tasks) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await(20, TimeUnit.SECONDS);
                                    return task.call();
                                }));
            }
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
     * 잠금을 기다리는 백엔드 프로세스 수. 대기하는 트랜잭션 하나가 정확히 한 pid다.
     *
     * <p>승인되지 않은 잠금 행의 개수를 세지 않는 이유는 대기 하나가 tuple 잠금과 transactionid
     * 잠금을 함께 만들 수 있어서다. pid를 세면 "몇 개의 트랜잭션이 실제로 막혀 있는가"가 그대로
     * 나온다. 시험 클래스는 순차 실행이고 이 컨테이너에 붙는 것은 이 JVM뿐이라, 여기서 막혀 있는
     * 것은 이 시험의 참여자뿐이다.
     */
    private long blockedBackends() {
        return jdbc.sql("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted")
                .query(Long.class)
                .single();
    }

    private void awaitBlockedBackends(long expected) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BLOCK_WAIT_TIMEOUT_MILLIS);
        long observed = blockedBackends();
        while (observed < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("잠금 대기를 기다리는 중 인터럽트됐다.", interrupted);
            }
            observed = blockedBackends();
        }
        if (observed < expected) {
            throw new IllegalStateException(
                    ("참여자가 잠금 앞에 줄을 서지 않았다. 기대 %d, 실제 %d. "
                                    + "겹침이 강제되지 않으면 이 시험은 경합을 검증하지 못한다.")
                            .formatted(expected, observed));
        }
    }

    /**
     * 참여자들을 생명주기 행 잠금 앞에 순서대로 줄 세운 뒤 한꺼번에 푼다.
     *
     * <p>시험이 먼저 {@code lockedUserIds}의 행을 모두 {@code FOR UPDATE}로 잡는다. 그 뒤 작업을
     * 하나씩 제출하고, 매번 그 작업이 실제로 잠금 대기에 들어갔음을 확인한 뒤에야 다음 작업을
     * 제출한다. 그래서 대기열 순서가 곧 인자 순서다. 마지막에 시험 트랜잭션을 롤백해 잠금을 풀면
     * 참여자들이 그 순서대로 깨어난다.
     *
     * <p>이 helper가 보장하는 것이 둘이다. 참여 트랜잭션이 같은 순간에 살아 있었다는 사실과, 누가
     * 먼저 잠금을 잡는지가 스케줄러가 아니라 시험이 정한 값이라는 사실이다. 두 번째가 있어야
     * "이겼을 때"와 "졌을 때"를 골라서 관측할 수 있다.
     */
    private <T> List<T> runQueuedOnLifecycleLocks(List<UUID> lockedUserIds, List<Callable<T>> tasks)
            throws Exception {
        int count = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(count);
        List<Future<T>> futures = new ArrayList<>(count);
        try {
            transactionTemplate.execute(
                    status -> {
                        for (UUID lockedUserId : lockedUserIds) {
                            jdbc.sql(
                                            "SELECT state FROM identity_account_lifecycle "
                                                    + "WHERE user_id = :userId FOR UPDATE")
                                    .param("userId", lockedUserId)
                                    .query(String.class)
                                    .single();
                        }
                        long queued = 0;
                        for (Callable<T> task : tasks) {
                            futures.add(pool.submit(task));
                            queued++;
                            awaitBlockedBackends(queued);
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

    @Test
    void 동시에_들어온_중복_전환은_모두_성공하고_상태는_한_번만_전진한다() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID userId = lifecycle.createAccount();

            List<Callable<DeletionOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < DUPLICATE_TRANSITION_THREADS; i++) {
                tasks.add(() -> deletion.beginDeletion(userId));
            }

            // 여덟 트랜잭션이 모두 같은 행 잠금 앞에 실제로 줄을 선 뒤에 풀린다.
            assertThat(runQueuedOnLifecycleLocks(List.of(userId), tasks))
                    .describedAs("round=%d", round)
                    .containsOnly(DeletionOutcome.COMPLETED);
            assertThat(storedState(userId)).isEqualTo("DELETION_IN_PROGRESS");
        }
    }

    @Test
    void 매핑_연결과_삭제_전환이_경합해도_한쪽_결과만_남는다() throws Exception {
        Set<AccessDecision> observedWrites = EnumSet.noneOf(AccessDecision.class);

        for (int round = 0; round < ROUNDS; round++) {
            // 절반은 매핑 연결이, 절반은 삭제 전환이 먼저 대기열에 선다. 두 갈래를 모두 관측하기
            // 위한 배치이며, 어느 쪽이 이기든 아래 불변식은 같아야 한다.
            boolean linkFirst = round % 2 == 0;

            UUID userId = lifecycle.createAccount();
            String issuer = "https://accounts.example/" + UUID.randomUUID();
            String subject = UUID.randomUUID().toString();

            Callable<Object> link =
                    () -> lifecycle.linkSocialIdentity(userId, "google", issuer, subject);
            Callable<Object> begin = () -> deletion.beginDeletion(userId);

            List<Object> results =
                    runQueuedOnLifecycleLocks(
                            List.of(userId), linkFirst ? List.of(link, begin) : List.of(begin, link));

            AccessDecision write = (AccessDecision) results.get(linkFirst ? 0 : 1);
            Object transition = results.get(linkFirst ? 1 : 0);
            observedWrites.add(write);

            assertThat(transition)
                    .describedAs("round=%d 삭제 전환", round)
                    .isEqualTo(DeletionOutcome.COMPLETED);

            // 대기열 순서가 곧 승자다. 이 단정이 통과한다는 것 자체가 겹침이 실제로 강제됐다는
            // 증거다. 줄 세우기가 동작하지 않으면 승자는 스케줄러가 정하고 이 값은 흔들린다.
            assertThat(write)
                    .describedAs("round=%d 먼저 줄 선 쪽=%s", round, linkFirst ? "매핑 연결" : "삭제 전환")
                    .isEqualTo(linkFirst ? AccessDecision.ALLOWED : AccessDecision.DENIED);

            long links = countLinks(userId);
            if (write == AccessDecision.ALLOWED) {
                // 쓰기가 이겼다. 전환보다 먼저 커밋됐고 매핑은 남는다.
                assertThat(links).describedAs("round=%d 허용된 쓰기의 매핑", round).isEqualTo(1L);
            } else {
                // 쓰기가 졌다. 상태 술어가 0행으로 끝나 아무것도 남기지 않았다.
                assertThat(links).describedAs("round=%d 거부된 쓰기의 매핑", round).isZero();
            }

            // 어느 쪽이 이겼든 상태는 전진한 쪽으로만 간다.
            assertThat(storedState(userId)).isEqualTo("DELETION_IN_PROGRESS");
        }

        // 두 갈래가 모두 실제로 돌았다는 증거다. 이 단정이 없으면 늘 한쪽만 관측하고도 통과한다.
        assertThat(observedWrites)
                .describedAs("허용·거부 두 갈래가 모두 관측돼야 한다")
                .containsExactlyInAnyOrder(AccessDecision.ALLOWED, AccessDecision.DENIED);
    }

    @Test
    void 전환이_끝난_뒤의_쓰기는_거부되고_아무것도_남기지_않는다() {
        UUID userId = lifecycle.createAccount();
        assertThat(deletion.beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);

        assertThat(
                        lifecycle.linkSocialIdentity(
                                userId,
                                "google",
                                "https://accounts.example/" + UUID.randomUUID(),
                                UUID.randomUUID().toString()))
                .isEqualTo(AccessDecision.DENIED);

        assertThat(countLinks(userId)).isZero();
        // 거부된 쓰기가 상태를 건드리지 않는다.
        assertThat(storedState(userId)).isEqualTo("DELETION_IN_PROGRESS");
    }

    @Test
    void 세션_생성_판정은_전환과_경합해도_전환_뒤에_다시_허용되지_않는다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID userId = lifecycle.createAccount();

            // 두 판정은 잠금을 잡지 않는다(읽기 전용). 그래서 잠금 대기열로 줄을 세울 수 없고,
            // 같은 순간 출발이 여기서 쓸 수 있는 최대한이다. 확인하려는 것도 경합의 승자가 아니라
            // "전환이 끝난 뒤에는 어떤 판정도 되돌아오지 않는다"이다.
            List<Callable<Object>> tasks =
                    List.of(
                            () -> deletion.beginDeletion(userId),
                            () -> lifecycle.decideSessionCreation(userId),
                            () -> access.decide(userId, userId, AccountAccessKind.WRITE));

            assertThat(runTogether(tasks).get(0)).isEqualTo(DeletionOutcome.COMPLETED);

            // 경합 중 어느 값이 나왔든, 전환이 끝난 뒤에는 되돌아오지 않는다.
            assertThat(lifecycle.decideSessionCreation(userId)).isEqualTo(AccessDecision.DENIED);
            assertThat(access.decide(userId, userId, AccountAccessKind.WRITE))
                    .isEqualTo(AccessDecision.DENIED);
            assertThat(access.decide(userId, userId, AccountAccessKind.READ))
                    .isEqualTo(AccessDecision.ALLOWED);
            assertThat(storedState(userId)).isEqualTo("DELETION_IN_PROGRESS");
        }
    }

    @Test
    void 같은_매핑을_동시에_연결하면_하나만_성공한다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID first = lifecycle.createAccount();
            UUID second = lifecycle.createAccount();
            String issuer = "https://accounts.example/" + UUID.randomUUID();
            String subject = UUID.randomUUID().toString();

            List<Callable<AccessDecision>> tasks =
                    List.of(
                            () -> lifecycle.linkSocialIdentity(first, "google", issuer, subject),
                            () -> lifecycle.linkSocialIdentity(second, "google", issuer, subject));

            // 두 계정의 생명주기 행을 모두 잡아 두 트랜잭션을 함께 세운 뒤 동시에 푼다. 각자 다른
            // 행의 잠금을 기다렸으므로, 풀린 뒤에는 둘 다 살아 있는 채로 같은 유일 인덱스에 부딪힌다.
            List<AccessDecision> results = runQueuedOnLifecycleLocks(List.of(first, second), tasks);

            // `(issuer, subject)` 유일 인덱스가 최종 방어다. 두 트랜잭션이 같은 값을 동시에 넣으면
            // 한쪽은 반드시 거부되고 그 트랜잭션은 아무것도 남기지 않는다.
            assertThat(results).describedAs("round=%d", round).containsOnlyOnce(AccessDecision.ALLOWED);
            assertThat(results).describedAs("round=%d", round).containsOnlyOnce(AccessDecision.DENIED);
            assertThat(countLinks(first) + countLinks(second)).isEqualTo(1L);
        }
    }
}
