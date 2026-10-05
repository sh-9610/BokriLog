package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;

class PostgresStoreResetTest extends AuthFlowTestBase {
    @Autowired private DataSource dataSource;

    @Test
    void aBusyLaterTableReleasesEarlierLocksBeforeRetryAndCleanupRunsAfterTheWorker() throws Exception {
        accountLifecycle().createAccount();
        assertThat(jdbc().sql("SELECT count(*) FROM identity_account").query(Long.class).single()).isEqualTo(1);
        var retried = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var cleanups = new java.util.concurrent.atomic.AtomicInteger();
        // Keep a worker transaction on a late table while it still needs an earlier one.
        try (var worker = dataSource.getConnection(); var resetConnection = dataSource.getConnection();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            worker.setAutoCommit(false);
            try (var statement = worker.createStatement()) {
                statement.execute("LOCK TABLE portfolio IN ACCESS SHARE MODE");
            }
            var resetSource = new SingleConnectionDataSource(resetConnection, true);
            var resetJdbc = JdbcClient.create(resetSource);
            var reset = executor.submit(() -> PostgresStoreReset.run(resetJdbc,
                    new DataSourceTransactionManager(resetSource),
                    () -> {
                        cleanups.incrementAndGet();
                        resetJdbc.sql("TRUNCATE TABLE identity_account CASCADE").update();
                    }, Duration.ofSeconds(20), () -> {
                        retried.countDown();
                        try {
                            if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Reset test barrier timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Reset test barrier interrupted", interrupted);
                        }
                    }));
            try {
                // The callback runs after a real NOWAIT failure has rolled back, with no timing window.
                assertThat(retried.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(cleanups).hasValue(0);
                assertThat(reset.isDone()).isFalse();
                // This would wait/deadlock if cleanup kept its earlier table locks while retrying.
                try (var statement = worker.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout='1s'");
                    statement.execute("SELECT count(*) FROM identity_account");
                }
            } finally {
                worker.rollback();
                resume.countDown();
            }
            reset.get(10, TimeUnit.SECONDS);
            assertThat(cleanups).hasValue(1);
            assertThat(jdbc().sql("SELECT count(*) FROM identity_account").query(Long.class).single()).isZero();
        }
    }

    @Test
    void aCleanupErrorRollsBackAndIsNotRetriedAsAContentionFailure() {
        accountLifecycle().createAccount();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> PostgresStoreReset.run(jdbc(),
                new DataSourceTransactionManager(dataSource), () -> {
                    attempts.incrementAndGet();
                    jdbc().sql("TRUNCATE TABLE identity_account CASCADE").update();
                    jdbc().sql("SELECT * FROM absent_reset_fixture").query().listOfRows();
                })).isInstanceOf(DataAccessException.class);
        assertThat(attempts).hasValue(1);
        assertThat(jdbc().sql("SELECT count(*) FROM identity_account").query(Long.class).single()).isEqualTo(1);
        // The failed transaction must not leave its exclusive locks behind.
        resetStore();
    }

    @Test
    void aContentionDeadlinePropagatesTheLockFailureWithoutRunningCleanup() throws Exception {
        try (var worker = dataSource.getConnection()) {
            worker.setAutoCommit(false);
            try (var statement = worker.createStatement()) {
                statement.execute("LOCK TABLE portfolio IN ACCESS SHARE MODE");
            }
            var cleanups = new java.util.concurrent.atomic.AtomicInteger();
            try {
                assertThatThrownBy(() -> PostgresStoreReset.run(jdbc(),
                        new DataSourceTransactionManager(dataSource), cleanups::incrementAndGet,
                        Duration.ZERO, () -> {})).isInstanceOf(DataAccessException.class);
                assertThat(cleanups).hasValue(0);
                try (var statement = worker.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout='1s'");
                    statement.execute("SELECT count(*) FROM identity_account");
                }
            } finally {
                worker.rollback();
            }
        }
    }

    @Test
    void anInterruptedRetryPreservesInterruptionAndReleasesItsLocks() throws Exception {
        try (var worker = dataSource.getConnection()) {
            worker.setAutoCommit(false);
            try (var statement = worker.createStatement()) {
                statement.execute("LOCK TABLE portfolio IN ACCESS SHARE MODE");
            }
            var cleanups = new java.util.concurrent.atomic.AtomicInteger();
            try {
                assertThatThrownBy(() -> PostgresStoreReset.run(jdbc(),
                        new DataSourceTransactionManager(dataSource), cleanups::incrementAndGet,
                        Duration.ofSeconds(20), () -> Thread.currentThread().interrupt()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(cleanups).hasValue(0);
            } finally {
                Thread.interrupted();
                worker.rollback();
            }
        }
        resetStore();
    }
}
