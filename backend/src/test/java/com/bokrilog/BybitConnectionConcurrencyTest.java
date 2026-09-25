package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceProvider;
import com.bokrilog.source.SourceVerification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 사용자의 두 연결 저장이 동시에 커밋에 도달할 때(Story 2.1).
 *
 * <p>HTTP를 지나지 않는다. 두 요청을 실제로 겹치게 하려면 <b>둘 다 트랜잭션을 연 상태</b>여야
 * 하는데, HTTP 층에서는 그 창이 밀리초 단위라 겹침을 우연에 맡기게 된다. 여기서는 장벽 하나로 그
 * 겹침을 만들고, 그래서 이 시험은 "가끔 통과"가 아니라 계약을 본다
 * ({@code AccountConcurrencyTest}가 {@code pg_locks} 폴링으로 같은 문제를 푼 것과 같은 판단).
 *
 * <p>확인하는 것은 애플리케이션의 판정이 아니라 <b>DB 제약</b>이다. 두 트랜잭션이 사전 확인을 모두
 * 통과한 뒤 부분 유일 색인이 하나를 거절해야 하며, 진 쪽은 자격정보·portfolio·membership을 하나도
 * 남기지 않아야 한다.
 *
 * <p>{@code AuthFlowTestBase}를 상속한다. 새 {@code @DynamicPropertySource}를 만들지 않으므로
 * 컨텍스트가 갈라지지 않고, 빈 주입만 더하는 것은 컨텍스트 캐시 키를 바꾸지 않는다.
 */
class BybitConnectionConcurrencyTest extends AuthFlowTestBase {

    @Autowired
    private SourceConnectionApi sourceConnections;

    @Autowired
    private PortfolioMembershipApi portfolioMemberships;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 동시에_도달한_두_저장_중_정확히_하나만_성공한다() throws Exception {
        UUID userId = accountLifecycle().createAccount();
        SourceVerification.Verified verified =
                new SourceVerification.Verified(
                        "concurrent-" + UUID.randomUUID(), Instant.now(), "test-policy");
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<Future<Boolean>> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> store(userId, verified, barrier);
            results = pool.invokeAll(List.of(attempt, attempt), 30, TimeUnit.SECONDS);
        }

        long succeeded = 0;
        for (Future<Boolean> result : results) {
            if (outcomeOf(result)) {
                succeeded++;
            }
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(sourceConnectionCount()).isEqualTo(1);
        assertThat(sourceCredentialCount()).isEqualTo(1);
        assertThat(providerAccountCount()).isEqualTo(1);
        assertThat(portfolioCount()).isEqualTo(1);
        assertThat(membershipCount()).isEqualTo(1);
    }

    /**
     * 저장 하나를 자기 트랜잭션에서 시도한다.
     *
     * <p>장벽을 트랜잭션 <b>안에서</b> 지난다. 밖에서 맞추면 두 스레드가 거의 동시에 출발할 뿐
     * 트랜잭션이 겹친다는 보장은 없다.
     *
     * @return 커밋에 성공했으면 참. 제약 위반은 예외로 올라가 거짓이 된다
     */
    private boolean store(UUID userId, SourceVerification.Verified verified, CyclicBarrier barrier) {
        TransactionTemplate transactions = new TransactionTemplate(this.transactionManager);
        Boolean stored =
                transactions.execute(
                        status -> {
                            await(barrier);
                            SourceConnectionApi.StoreOutcome outcome =
                                    this.sourceConnections.store(
                                            userId,
                                            SourceProvider.BYBIT,
                                            verified,
                                            new SourceCredentials("uta2.1", "secret-" + UUID.randomUUID()));
                            if (!(outcome instanceof SourceConnectionApi.StoreOutcome.Stored saved)) {
                                return false;
                            }
                            this.portfolioMemberships.attachActiveSource(
                                    userId,
                                    saved.connectionId(),
                                    saved.providerAccountId(),
                                    verified.verifiedAt());
                            return true;
                        });
        return Boolean.TRUE.equals(stored);
    }

    /**
     * 그 시도가 성공했는가.
     *
     * <p>예외로 끝난 시도는 거짓이다. 제약 위반은 값이 아니라 예외이며, 그 트랜잭션이 통째로
     * 롤백됐다는 사실이 아래 행 수 단정으로 관측된다.
     */
    private static boolean outcomeOf(Future<Boolean> result) {
        try {
            return Boolean.TRUE.equals(result.get());
        } catch (Exception failure) {
            return false;
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("장벽을 지나지 못했다.", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("장벽을 지나지 못했다.", failure);
        }
    }
}
