package com.bokrilog.sync.internal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 단일 Worker 잠금(Story 3.8). Worker 역할({@code bokrilog.role=worker})에만 있다.
 *
 * <p><b>잡기 전에는 dispatch하지 않는다.</b> 이 빈은 전용 연결 하나를 열어 PostgreSQL session advisory lock을 잡는다. 잡지 못하면
 * 생성이 실패하고 그것이 기동 실패다 — 스케줄링은 컨텍스트가 다 뜬 뒤에 시작하므로 잠금 없는 dispatch는 한 번도 없다. 두 번째
 * Worker를 띄우면 뒤에 뜬 것이 이 실패로 끝난다(로그 {@code worker.lock.unavailable}).
 *
 * <p><b>연결은 풀 밖의 전용 연결이다.</b> 연결 풀의 연결을 빌리면 풀 한 칸을 영원히 차지하고, 닫을 때 잠금을 쥔 채로 풀에 돌아가
 * 다른 요청이 그 세션을 이어 쓴다. 그래서 같은 접속 정보({@link JdbcConnectionDetails})로 드라이버 연결 하나를 따로 연다.
 *
 * <p><b>잠금의 수명은 그 세션의 수명이다.</b> session lock은 연결이 끊기면 서버가 푼다. 5초마다 이 세션이 그 잠금을 실제로 쥐고
 * 있는지 {@code pg_locks}로 확인하고, 아니면(연결이 끊겼거나 세션이 종료됐다) 새 claim을 멈추고 프로세스를 끝낸다({@link
 * WorkerDrain#lockLost()}). 확인 간격(최대 5초) 동안의 겹침은 작업 lease의 fencing이 막는다. 컨텍스트가 닫히면 잠금을 명시적으로
 * 풀고 연결을 닫는다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "bokrilog.role", havingValue = "worker")
class WorkerSingleton implements DisposableBean {

    /** 잠금 이름. 다른 advisory 잠금(원장 추가·계획·문의 접수)과 겹치지 않는 이름이다. */
    static final String LOCK_NAME = "bokrilog.sync.worker.singleton";

    /** 잠금을 잡지 못했을 때의 로그 본문. */
    static final String UNAVAILABLE_EVENT = "worker.lock.unavailable";

    /**
     * 이 세션이 그 잠금을 쥐고 있는가. bigint 키 하나의 advisory lock은 {@code pg_locks}에 상위 32비트({@code classid})·하위
     * 32비트({@code objid})·{@code objsubid=1}로 보인다.
     */
    private static final String HELD_SQL = "SELECT EXISTS (SELECT 1 FROM pg_locks l WHERE l.locktype = 'advisory' "
            + "AND l.pid = pg_backend_pid() AND l.granted AND l.objsubid = 1 "
            + "AND ((CAST(CAST(l.classid AS text) AS bigint) << 32) | CAST(CAST(l.objid AS text) AS bigint)) "
            + "= hashtextextended(?, 0))";

    private static final Logger LOG = LoggerFactory.getLogger(WorkerSingleton.class);

    private final Connection connection;

    private final ObjectProvider<WorkerDrain> drain;

    private volatile boolean closing;

    WorkerSingleton(JdbcConnectionDetails details, ObjectProvider<WorkerDrain> drain) throws SQLException {
        this.drain = drain;
        this.connection = acquire(details);
    }

    /**
     * 풀 밖의 전용 연결을 열고 잠금을 잡는다. 잡지 못하면 연결을 닫고 기동 실패다.
     *
     * @return 잠금을 쥔 연결
     */
    static Connection acquire(JdbcConnectionDetails details) throws SQLException {
        Properties properties = new Properties();
        String username = details.getUsername();
        if (username != null && !username.isEmpty()) {
            properties.setProperty("user", username);
        }
        String password = details.getPassword();
        if (password != null && !password.isEmpty()) {
            properties.setProperty("password", password);
        }
        properties.setProperty("ApplicationName", "bokrilog-worker-singleton");
        Connection connection = DriverManager.getConnection(details.getJdbcUrl(), properties);
        try {
            connection.setAutoCommit(true);
            if (!tryLock(connection)) {
                LOG.error(UNAVAILABLE_EVENT);
                throw new IllegalStateException("다른 Worker가 작업 잠금을 쥐고 있다. Worker는 하나만 뜬다.");
            }
            return connection;
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** 이 프로세스가 지금 잠금을 쥐고 있는가. */
    boolean held() {
        return held(this.connection);
    }

    /** 그 연결의 세션이 지금 잠금을 쥐고 있는가. 연결이 없거나 끊겼거나 읽지 못하면 거짓이다. */
    static boolean held(@Nullable Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            if (connection.isClosed()) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(HELD_SQL)) {
                statement.setQueryTimeout(2);
                statement.setString(1, LOCK_NAME);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && result.getBoolean(1);
                }
            }
        } catch (SQLException unreadable) {
            return false;
        }
    }

    /** 잠금을 확인한다. 잃었으면 새 claim을 멈추고 프로세스를 끝낸다. */
    @Scheduled(fixedDelay = 5000)
    void verify() {
        if (this.closing || held()) {
            return;
        }
        WorkerDrain current = this.drain.getIfAvailable();
        if (current != null) {
            current.lockLost();
        }
    }

    /** 컨텍스트가 닫히면(drain 뒤) 잠금을 명시적으로 풀고 연결을 닫는다. */
    @Override
    public void destroy() throws SQLException {
        this.closing = true;
        try (PreparedStatement statement =
                this.connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery().close();
        } catch (SQLException alreadyGone) {
            // 연결이 이미 끊겼으면 서버가 잠금을 풀었다.
        } finally {
            this.connection.close();
        }
    }
}
