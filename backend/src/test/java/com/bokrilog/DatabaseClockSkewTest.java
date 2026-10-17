package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 시험 시계 차이 확인({@link DatabaseClockSkew})의 문장·경계와, 공유 컨테이너에 대한 실제 측정·붙들기·재측정.
 *
 * <p>판정 결과는 JVM 전역에 붙들리므로 시험마다 시스템 시계와 빈 결과로 되돌린다 — 밀어 둔 시계나 실패한 결과가 이 JVM의 다른
 * 시험으로 새지 않게.
 */
class DatabaseClockSkewTest {

    private static final Instant JVM = Instant.parse("2026-10-17T03:00:00Z");

    private PostgreSQLContainer container;

    @BeforeEach
    void startFresh() {
        DatabaseClockSkew.resetForTest();
        this.container = PostgresTestcontainersConfig.shared();
        // 컨테이너를 얻는 경로도 재고 붙든다. 각 시험이 빈 결과에서 시작하게 다시 비운다.
        DatabaseClockSkew.resetForTest();
    }

    @AfterEach
    void restoreClock() {
        DatabaseClockSkew.resetForTest();
    }

    @Test
    void withinTheLimitInEitherDirectionPasses() {
        assertThat(DatabaseClockSkew.judge(JVM, JVM, DatabaseClockSkew.MAX_SKEW)).isEmpty();
        assertThat(DatabaseClockSkew.judge(JVM, JVM.plus(DatabaseClockSkew.MAX_SKEW), DatabaseClockSkew.MAX_SKEW))
                .isEmpty();
        assertThat(DatabaseClockSkew.judge(JVM, JVM.minus(DatabaseClockSkew.MAX_SKEW), DatabaseClockSkew.MAX_SKEW))
                .isEmpty();
    }

    @Test
    void aLaggingDatabaseClockNamesBothTimesTheDirectionAndTheRemedy() {
        Instant database = JVM.minus(Duration.ofMillis(2_345));

        assertThat(DatabaseClockSkew.judge(JVM, database, DatabaseClockSkew.MAX_SKEW)).hasValueSatisfying(message ->
                assertThat(message).contains("2345ms", "DB가 JVM보다 뒤처짐", "상한 1000ms", "JVM 2026-10-17T03:00:00Z",
                        "DB clock_timestamp() 2026-10-17T02:59:57.655Z", "Docker VM 시계를 재동기화",
                        "Docker Desktop 재시작"));
    }

    @Test
    void aLeadingDatabaseClockAlsoFailsAndSaysSo() {
        assertThat(DatabaseClockSkew.judge(JVM, JVM.plus(Duration.ofMillis(1_001)), DatabaseClockSkew.MAX_SKEW))
                .hasValueSatisfying(message -> assertThat(message).contains("1001ms", "DB가 JVM보다 앞섬"));
    }

    @Test
    void theSharedContainerPassesWithTheSystemClock() {
        DatabaseClockSkew.verify(this.container);
    }

    @Test
    void aJvmClockPushedAheadFailsAgainstTheSharedContainerAndTheNextEntryRepeatsTheSentence() {
        DatabaseClockSkew.useJvmClockForTest(() -> Instant.now().plusSeconds(5));

        Throwable first = catchThrowable(() -> DatabaseClockSkew.verify(this.container));
        assertThat(first).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB가 JVM보다 뒤처짐")
                .hasMessageContaining("Docker Desktop 재시작");
        // 붙들어 둔 동안의 다음 진입은 다시 재지 않고 같은 문장으로 던진다.
        assertThatThrownBy(() -> DatabaseClockSkew.verify(this.container))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(first.getMessage());
    }

    @Test
    void aFreshResultIsHeldAndNotMeasuredAgain() {
        AtomicInteger samples = new AtomicInteger();
        DatabaseClockSkew.Probe counting = jvm -> {
            samples.incrementAndGet();
            Instant now = jvm.get();
            return new DatabaseClockSkew.Sample(now, now);
        };

        DatabaseClockSkew.verify(counting);
        DatabaseClockSkew.verify(counting);

        assertThat(samples).hasValue(1);
    }

    @Test
    void aFailedMeasurementIsNotHeldAndTheNextEntryMeasuresAgain() {
        assertThatThrownBy(() -> DatabaseClockSkew.verify(jvm -> {
            throw new SQLException("연결 거부");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("시계를 읽지 못했습니다");

        // 실패를 붙들었다면 이 진입도 같은 실패로 던진다. 다시 재므로 공유 컨테이너의 실제 측정이 통과한다.
        DatabaseClockSkew.verify(this.container);
    }
}
