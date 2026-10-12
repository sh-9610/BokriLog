package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.PostgresTestcontainersConfig;
import com.bokrilog.sync.WorkerExit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Story 3.8: 단일 Worker 잠금.
 *
 * <p>Worker는 풀 밖의 전용 연결에서 PostgreSQL session advisory lock을 잡아야 뜬다. 두 번째 Worker는 잠금 실패로 기동 실패다. 앞
 * Worker가 끝나면(잠금을 풀고 연결을 닫는다) 다음 Worker가 뜬다. 잠금 세션이 서버에서 끊기면 다음 확인이 잠금을 잃은 것으로 보고
 * 종료 코드 76으로 한 번 끝낸다. 실제 엔진의 잠금이 계약이라 시험 컨테이너를 쓴다(스키마는 필요 없다).
 */
class WorkerSingletonTest {

    /** pg_locks의 bigint 키(상위·하위 32비트)를 잠금 이름의 hash와 맞춘다. */
    private static final String LOCK_KEY = "((CAST(CAST(l.classid AS text) AS bigint) << 32) "
            + "| CAST(CAST(l.objid AS text) AS bigint)) = hashtextextended(?, 0)";

    private static JdbcConnectionDetails details() {
        PostgreSQLContainer container = PostgresTestcontainersConfig.shared();
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return container.getUsername();
            }

            @Override
            public String getPassword() {
                return container.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return container.getJdbcUrl();
            }
        };
    }

    private static Connection admin() throws Exception {
        JdbcConnectionDetails details = details();
        return DriverManager.getConnection(details.getJdbcUrl(), details.getUsername(), details.getPassword());
    }

    @Test
    void 두_번째_Worker는_잠금_실패로_뜨지_못하고_앞이_끝나면_뜬다() throws Exception {
        JdbcConnectionDetails details = details();
        Connection first = WorkerSingleton.acquire(details);
        try {
            assertThat(WorkerSingleton.held(first)).isTrue();
            assertThatThrownBy(() -> WorkerSingleton.acquire(details)).isInstanceOf(IllegalStateException.class);
            // 실패한 쪽은 연결을 남기지 않는다 — 다시 시도해도 같은 실패다(잠금은 여전히 첫 Worker의 것).
            assertThatThrownBy(() -> WorkerSingleton.acquire(details)).isInstanceOf(IllegalStateException.class);
        } finally {
            first.close();
        }
        assertThat(WorkerSingleton.held(first)).isFalse();
        Connection next = WorkerSingleton.acquire(details);
        try {
            assertThat(WorkerSingleton.held(next)).isTrue();
        } finally {
            next.close();
        }
    }

    @Test
    void 연결이_없거나_닫혔거나_잠금이_없으면_쥐지_않은_것이다() throws Exception {
        assertThat(WorkerSingleton.held(null)).isFalse();
        Connection connection = WorkerSingleton.acquire(details());
        connection.close();
        assertThat(WorkerSingleton.held(connection)).isFalse();
        // 잠금을 쥐지 않은 살아 있는 세션도 쥔 것이 아니다(연결 생존만으로 판정하지 않는다).
        try (Connection plain = admin()) {
            assertThat(WorkerSingleton.held(plain)).isFalse();
        }
    }

    @Test
    void 빈은_풀_밖_연결로_잡고_destroy가_잠금을_풀어_다음_Worker가_뜬다() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        WorkerSingleton singleton = new WorkerSingleton(details(), factory.getBeanProvider(WorkerDrain.class));
        assertThat(singleton.held()).isTrue();
        assertThat(lockSessions()).isEqualTo(1);
        singleton.destroy();
        assertThat(lockSessions()).isZero();
        Connection next = WorkerSingleton.acquire(details());
        next.close();
    }

    @Test
    void 잠금_세션이_끊기면_확인이_lockLost로_76을_한_번_낸다() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            List<Integer> exits = new ArrayList<>();
            WorkerDrain drain = new WorkerDrain(new IdleTarget(), Duration.ofSeconds(1), context);
            drain.terminator = exits::add;
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("workerDrain", drain);
            WorkerSingleton singleton = new WorkerSingleton(details(), factory.getBeanProvider(WorkerDrain.class));
            try {
                singleton.verify();
                assertThat(exits).as("쥐고 있는 동안은 아무 일도 없다").isEmpty();

                // 서버 쪽에서 그 세션을 끊는다(DB 재시작·네트워크 단절과 같은 결과).
                try (Connection admin = admin();
                        PreparedStatement kill = admin.prepareStatement("SELECT pg_terminate_backend(l.pid) "
                                + "FROM pg_locks l WHERE l.locktype = 'advisory' AND l.granted "
                                + "AND l.pid <> pg_backend_pid() AND " + LOCK_KEY)) {
                    kill.setString(1, WorkerSingleton.LOCK_NAME);
                    try (ResultSet result = kill.executeQuery()) {
                        assertThat(result.next()).as("끊을 잠금 세션이 있다").isTrue();
                    }
                }
                assertThat(singleton.held()).isFalse();
                singleton.verify();
                singleton.verify();
                assertThat(exits).containsExactly(WorkerExit.LOCK_LOST);
                assertThat(drain.exitCode()).isEqualTo(WorkerExit.LOCK_LOST);
            } finally {
                singleton.destroy();
            }
        }
    }

    private static long lockSessions() throws Exception {
        try (Connection admin = admin();
                PreparedStatement count = admin.prepareStatement("SELECT count(*) FROM pg_locks l "
                        + "WHERE l.locktype = 'advisory' AND l.granted AND " + LOCK_KEY)) {
            count.setString(1, WorkerSingleton.LOCK_NAME);
            try (ResultSet result = count.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    /** 진행 중 작업이 없는 작업자. */
    private static final class IdleTarget implements WorkerDrain.Target {

        @Override
        public void stopClaims() {}

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void heartbeat() {}
    }
}
