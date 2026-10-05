package com.bokrilog;

import java.sql.SQLException;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only cleanup: never wait while holding a subset of the TRUNCATE closure's table locks. */
final class PostgresStoreReset {
    private PostgresStoreReset() {}

    static void run(JdbcClient jdbc, PlatformTransactionManager manager, Runnable cleanup) {
        run(jdbc, manager, cleanup, Duration.ofSeconds(20), () -> {});
    }

    /** The bound applies to lock-contention retries; the callback observes a completed rollback. */
    static void run(JdbcClient jdbc, PlatformTransactionManager manager, Runnable cleanup,
            Duration lockWait, Runnable afterContentionRollback) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Test store reset cannot run inside a transaction");
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        long deadline = System.nanoTime() + lockWait.toNanos();
        while (true) {
            boolean[] cleanupStarted = {false};
            try {
                transaction.executeWithoutResult(status -> {
                    // This test schema and every CASCADE target are in public; lock the complete closure.
                    // PostgreSQL quotes identifiers; no provider or user input enters this statement.
                    String tables = jdbc.sql("""
                            SELECT string_agg(format('%I.%I', n.nspname, c.relname), ', ' ORDER BY c.oid)
                            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                            WHERE n.nspname='public' AND c.relkind IN ('r','p')
                            """).query(String.class).single();
                    jdbc.sql("LOCK TABLE " + tables + " IN ACCESS EXCLUSIVE MODE NOWAIT").update();
                    cleanupStarted[0] = true;
                    cleanup.run();
                });
                return;
            } catch (DataAccessException failure) {
                if (cleanupStarted[0] || !lockUnavailable(failure) || System.nanoTime() - deadline >= 0) throw failure;
                // The failed transaction has already rolled back ALL acquired locks before this wait.
                afterContentionRollback.run();
                try {
                    Thread.sleep(Duration.ofMillis(20));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Test store reset interrupted", interrupted);
                }
            }
        }
    }

    private static boolean lockUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
