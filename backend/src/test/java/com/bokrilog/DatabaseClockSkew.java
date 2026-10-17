package com.bokrilog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 시험 JVM 시계와 시험 데이터베이스(Docker VM) 시계의 차이를 잰다(Epic 4 회고 AI-6, {@code deferred-work.md} 159).
 *
 * <p>시험 fixture는 측정 시각 같은 값을 JVM 시계({@code Instant.now()})로 만들고, 제품은 판정을 DB 시각으로 한다(예:
 * {@code DashboardTrust.resultTooRecent}). macOS·Windows의 Docker Desktop은 VM 안에서 PostgreSQL을 돌리고, 호스트가 잠들었다
 * 깨면 VM 시계가 뒤처질 수 있다. 그러면 fixture가 만든 결과가 DB에게는 "미래"가 되어 계획 종단 시험이 {@code RESULT_TOO_RECENT}로,
 * 작업이 {@code FAILED}로 전체 빌드에서만 간헐 실패한다. 그 원인을 시험 하나하나의 엉뚱한 단정 대신 먼저 말하게 한다.
 *
 * <p><b>언제 재는가.</b> 컨테이너를 얻는 두 경로({@link PostgresTestcontainersConfig}의 bean과 {@code shared()})가 시작 직후 부르고,
 * DB 시험의 저장소 초기화({@code AuthFlowTestBase.resetStore}, 시험 메서드마다)도 부른다. 결과는 JVM 안에 붙들어 두고
 * {@link #REMEASURE_AFTER}가 지난 뒤의 다음 진입에서만 다시 잰다 — 수십 분 걸리는 전체 빌드 도중에 VM 시계가 밀리는 경우를 잡으려는
 * 것이고, 그 사이의 진입은 잠금 하나와 경과 시간 비교뿐이다. 측정 자체가 실패하면(연결·조회 오류) 붙들지 않고 다음 진입에서 다시
 * 잰다.
 *
 * <p><b>실패가 어떻게 보이는가.</b> 차이가 상한을 넘으면 두 시각·방향·대처를 담은 {@link IllegalStateException}을 던지고, 붙들어
 * 둔 동안의 다음 진입도 같은 문장을 다시 던진다. 다만 Spring 컨텍스트를 띄우다 실패한 경우, 같은 구성의 뒤 시험 클래스는 Spring의
 * 컨텍스트 실패 임계({@code spring.test.context.failure.threshold}, 기본 1) 때문에 이 문장 없이 "임계 초과"로만 실패한다. 이 문장은
 * 그 구성을 처음 띄운 시험과, 다른 구성의 컨텍스트·{@code shared()}·이미 뜬 컨텍스트의 저장소 초기화에서 나온다 — 전체 빌드가
 * 무더기로 실패하면 가장 앞선 실패를 본다.
 *
 * <p><b>상한 1초.</b> 같은 호스트의 정상 VM에서 잰 차이는 수 ms다(2026-10-17 macOS 실측 2ms). 1초는 그 정상 잡음과 수면 뒤
 * 뒤처짐(수 초 이상)을 가르는 값이다. 상한을 넓히거나 차이를 시험에서 보정하지 않는다 — 시계를 맞추는 것이 대처다. 다만 1초
 * 미만의 차이로도 {@code RESULT_TOO_RECENT}는 날 수 있다(fixture가 측정 시각을 "지금"에 붙여 만들면 DB가 몇 ms만 뒤져도 미래다).
 * 그래서 통과할 때도 측정값을 방향과 함께 한 줄(표준 오류, {@code [test-clock]}) 남긴다 — 그 줄이 그런 실패의 진단 근거다.
 */
final class DatabaseClockSkew {

    /** 허용하는 JVM↔DB 시계 차이의 절댓값. */
    static final Duration MAX_SKEW = Duration.ofSeconds(1);

    /** 붙들어 둔 결과를 다시 재기까지의 시간. */
    static final Duration REMEASURE_AFTER = Duration.ofMinutes(5);

    /** DB 쪽 측정 하나 — JVM 시계를 받아 조회를 앞뒤로 감싸고 표본을 돌려준다. */
    @FunctionalInterface
    interface Probe {
        Sample sample(Supplier<Instant> jvmClock) throws SQLException;
    }

    /** 같은 순간으로 보는 JVM 시각과 DB 시각. */
    record Sample(Instant jvm, Instant database) {
    }

    /** 붙들어 둔 판정. {@code failure}가 비면 통과다. 경과는 단조 시계({@code System.nanoTime})로 잰다. */
    private record Verdict(Optional<String> failure, long measuredAtNanos) {
    }

    private static final Supplier<Instant> SYSTEM_CLOCK = Instant::now;

    private static final Object LOCK = new Object();

    private static Supplier<Instant> jvmClock = SYSTEM_CLOCK;

    private static Verdict verdict;

    private DatabaseClockSkew() {
    }

    /**
     * 그 컨테이너로 재거나, 붙들어 둔 결과가 아직 새로우면 그것을 쓴다.
     *
     * @throws IllegalStateException 차이가 {@link #MAX_SKEW}를 넘거나 측정하지 못했을 때
     */
    static void verify(PostgreSQLContainer container) {
        verify(jvm -> sampleOf(container, jvm));
    }

    static void verify(Probe probe) {
        Optional<String> failure;
        synchronized (LOCK) {
            if (verdict == null || System.nanoTime() - verdict.measuredAtNanos() >= REMEASURE_AFTER.toNanos()) {
                Sample sample;
                try {
                    sample = probe.sample(jvmClock);
                } catch (SQLException | RuntimeException e) {
                    // 붙들지 않는다. 다음 진입이 다시 잰다.
                    verdict = null;
                    throw new IllegalStateException("시험 데이터베이스 시계를 읽지 못했습니다: " + e.getMessage(), e);
                }
                verdict = new Verdict(judge(sample.jvm(), sample.database(), MAX_SKEW), System.nanoTime());
                if (verdict.failure().isEmpty()) {
                    System.err.println("[test-clock] " + describe(sample.jvm(), sample.database()) + " — 상한 "
                            + MAX_SKEW.toMillis() + "ms 안이라 통과");
                }
            }
            failure = verdict.failure();
        }
        failure.ifPresent(message -> {
            throw new IllegalStateException(message);
        });
    }

    /** 시험 전용: JVM 시계를 바꾼다(바꾼 뒤 처음 진입은 다시 잰다). */
    static void useJvmClockForTest(Supplier<Instant> clock) {
        synchronized (LOCK) {
            jvmClock = clock;
            verdict = null;
        }
    }

    /** 시험 전용: 시스템 시계와 빈 결과로 되돌린다. */
    static void resetForTest() {
        useJvmClockForTest(SYSTEM_CLOCK);
    }

    private static Sample sampleOf(PostgreSQLContainer container, Supplier<Instant> jvm) throws SQLException {
        try (Connection connection = container.createConnection("");
                Statement statement = connection.createStatement()) {
            // 같은 연결로 먼저 한 번 왕복해 연결 수립 비용이 측정 구간에 들지 않게 한다.
            statement.execute("SELECT 1");
            Instant before = jvm.get();
            Instant database;
            try (ResultSet rows = statement.executeQuery("SELECT clock_timestamp()")) {
                rows.next();
                database = rows.getObject(1, OffsetDateTime.class).toInstant();
            }
            Instant after = jvm.get();
            // 왕복의 가운데를 JVM 시각으로 본다. 왕복 자체는 차이가 아니다.
            return new Sample(before.plus(Duration.between(before, after).dividedBy(2)), database);
        }
    }

    /** 두 시각의 차이가 상한을 넘으면 원인·방향·대처를 담은 문장을 돌려준다. */
    static Optional<String> judge(Instant jvm, Instant database, Duration max) {
        Duration skew = Duration.between(jvm, database);
        if (skew.abs().compareTo(max) <= 0) {
            return Optional.empty();
        }
        return Optional.of(describe(jvm, database) + "(상한 " + max.toMillis() + "ms). DB 시각으로 판정하는 시험"
                + "(계획 종단의 RESULT_TOO_RECENT 등)이 엉뚱하게 실패하므로 진행하지 않습니다. Docker VM 시계를 재동기화한 뒤"
                + "(Docker Desktop 재시작) 다시 실행하십시오.");
    }

    /** 차이·방향·두 시각을 한 문장으로. */
    private static String describe(Instant jvm, Instant database) {
        Duration skew = Duration.between(jvm, database);
        String direction = skew.isZero() ? "같음" : skew.isNegative() ? "DB가 JVM보다 뒤처짐" : "DB가 JVM보다 앞섬";
        return "시험 JVM과 시험 데이터베이스(Docker VM)의 시계 차이 " + skew.abs().toMillis() + "ms, " + direction
                + " — JVM " + jvm + ", DB clock_timestamp() " + database;
    }
}
