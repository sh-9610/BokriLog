package com.bokrilog.sync.internal;

import com.bokrilog.sync.DrainParticipant;
import com.bokrilog.sync.WorkerExit;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Worker 종료 신호의 drain(Story 3.8 AC-3). Worker 역할({@code bokrilog.role=worker})에만 있다.
 *
 * <p><b>언제.</b> 종료 신호를 받으면 Spring이 컨텍스트를 닫으며 맨 먼저 {@link ContextClosedEvent}를 낸다. 이 listener는 가장 먼저
 * 불리고({@link Ordered#HIGHEST_PRECEDENCE}) 끝날 때까지 닫기를 붙든다 — 스케줄러·연결 풀·웹 서버는 그 뒤에 내려간다. 그래서
 * {@code spring.lifecycle.timeout-per-shutdown-phase}(5초)가 drain을 자르지 않는다. 컨테이너의 {@code stop_grace_period}는
 * drain-timeout보다 길어야 한다(운영 compose는 150초 대 120초).
 *
 * <p><b>무엇을.</b> 새 lease claim을 멈추고({@link Target#stopClaims()}), 진행 중이 0이 되거나 {@code
 * bokrilog.sync.worker.drain-timeout}(기본 120초)이 지날 때까지 기다린다. 그동안 heartbeat를 5초마다 직접 민다 — 스케줄러가
 * 먼저 멈춰도 진행 중 lease가 만료되지 않는다. 다 비면 종료 코드 {@link WorkerExit#DRAINED}이고, 시간이 다하면 {@code
 * worker.drain.incomplete}를 남기고 {@link WorkerExit#DRAIN_INCOMPLETE}다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "bokrilog.role", havingValue = "worker")
@Order(Ordered.HIGHEST_PRECEDENCE)
class WorkerDrain implements ApplicationListener<ContextClosedEvent>, WorkerExit {

    /** drain이 시간 안에 끝나지 않았을 때의 로그 본문. */
    static final String INCOMPLETE_EVENT = "worker.drain.incomplete";

    /** 단일 Worker 잠금을 잃었을 때의 로그 본문. */
    static final String LOCK_LOST_EVENT = "worker.lock.lost";

    /** 함께 서는 작업자의 claim 멈춤이 실패했을 때의 warn code(Story 5.2). */
    static final String PARTICIPANT_STOP_FAILED = "WORKER_DRAIN_PARTICIPANT_STOP_FAILED";

    /** 함께 서는 작업자의 heartbeat가 실패했을 때의 warn code(Story 5.2). */
    static final String PARTICIPANT_HEARTBEAT_FAILED = "WORKER_DRAIN_PARTICIPANT_HEARTBEAT_FAILED";

    /** drain 중 heartbeat 간격. {@code FirstSnapshotWorker}의 스케줄과 같다. */
    static final Duration HEARTBEAT = Duration.ofSeconds(5);

    /** 진행 중 수를 다시 읽는 간격. */
    static final Duration POLL = Duration.ofMillis(100);

    private static final Logger LOG = LoggerFactory.getLogger(WorkerDrain.class);

    /** drain이 다루는 작업자. */
    interface Target {

        /** 새 claim을 멈춘다. 되돌리지 않는다. */
        void stopClaims();

        /** 진행 중인 claim 구간과 작업의 수. */
        int inFlight();

        /** 진행 중 lease를 민다. */
        void heartbeat();
    }

    private final Target target;

    /** 함께 drain하는 sync 밖 작업자(Story 5.2). 닫기·잠금 상실 때 그 순간의 목록을 읽는다. */
    private final Supplier<List<DrainParticipant>> participants;

    private final Duration timeout;

    private final ApplicationContext context;

    private final AtomicBoolean started = new AtomicBoolean();

    private final AtomicInteger exitCode = new AtomicInteger(DRAINED);

    /**
     * @param target 이 프로세스의 작업자({@code FirstSnapshotWorker})
     * @param participants 같은 drain에 서는 sync 밖 작업자(계정 삭제 Worker, Story 5.2)
     */
    @Autowired
    WorkerDrain(Target target, ObjectProvider<DrainParticipant> participants,
            @Value("${bokrilog.sync.worker.drain-timeout}") Duration timeout, ApplicationContext context) {
        this(target, () -> participants.orderedStream().toList(), timeout, context);
    }

    /** sync 밖 작업자가 없는 drain(시험). */
    WorkerDrain(Target target, Duration timeout, ApplicationContext context) {
        this(target, List::of, timeout, context);
    }

    WorkerDrain(Target target, Supplier<List<DrainParticipant>> participants, Duration timeout,
            ApplicationContext context) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("bokrilog.sync.worker.drain-timeout must be positive");
        }
        this.target = target;
        this.participants = participants;
        this.timeout = timeout;
        this.context = context;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // 관리 포트의 자식 컨텍스트가 닫힐 때도 같은 사건이 부모로 올라온다. 이 컨텍스트의 닫기에서 한 번만 돈다.
        if (event.getApplicationContext() != this.context || !this.started.compareAndSet(false, true)) {
            return;
        }
        if (!drain(combined(), this.timeout, HEARTBEAT, POLL)) {
            this.exitCode.compareAndSet(DRAINED, DRAIN_INCOMPLETE);
        }
    }

    /**
     * 단일 Worker 잠금을 잃었다({@link WorkerSingleton}). 새 claim을 멈추고 이 프로세스를 끝낸다 — 끝내기는 별도 스레드에서
     * 시작하므로 부른 쪽을 막지 않고, 닫기는 위 drain을 그대로 지난다.
     */
    void lockLost() {
        try {
            combined().stopClaims();
        } catch (RuntimeException failure) {
            // 이 프로세스의 작업자가 던져도 끝내기는 반드시 시작한다(함께 서는 작업자의 실패는 위 묶음이 이미 따로 삼킨다).
            LOG.warn("worker drain stop failed: code={} failure={}", PARTICIPANT_STOP_FAILED,
                    failure.getClass().getSimpleName());
        }
        // 이미 닫는 중이면(drain 시작 뒤 연결을 닫은 것) 잃은 것이 아니다. 같은 일을 두 번 시작하지도 않는다.
        if (this.started.get() || this.exitCode.getAndSet(LOCK_LOST) == LOCK_LOST) {
            return;
        }
        LOG.error(LOCK_LOST_EVENT);
        this.terminator.accept(LOCK_LOST);
    }

    /**
     * 이 프로세스의 작업자와 함께 서는 작업자를 하나의 drain 대상으로 묶는다 — claim 멈춤은 모두에게, 진행 중은 합, heartbeat는 각자
     * 따로(하나의 실패가 다른 lease를 막지 않는다). 그래서 같은 {@code drain-timeout} 예산 하나가 모두를 기다리고, 함께 서는 작업자가 비지
     * 않아도 {@link WorkerExit#DRAIN_INCOMPLETE}다.
     */
    Target combined() {
        List<DrainParticipant> others = this.participants.get();
        if (others.isEmpty()) {
            return this.target;
        }
        Target own = this.target;
        return new Target() {
            @Override
            public void stopClaims() {
                own.stopClaims();
                // 하나가 던져도 나머지와 그 뒤(잠금 상실의 종료)가 막히지 않는다.
                for (DrainParticipant other : others) {
                    try {
                        other.stopClaims();
                    } catch (RuntimeException failure) {
                        LOG.warn("worker drain participant stop failed: code={} failure={}", PARTICIPANT_STOP_FAILED,
                                failure.getClass().getSimpleName());
                    }
                }
            }

            @Override
            public int inFlight() {
                return own.inFlight() + others.stream().mapToInt(DrainParticipant::inFlight).sum();
            }

            @Override
            public void heartbeat() {
                own.heartbeat();
                for (DrainParticipant other : others) {
                    try {
                        other.heartbeat();
                    } catch (RuntimeException failure) {
                        // lease 만료가 여전히 권위다. 식별자 없이 code와 예외 이름만 남긴다.
                        LOG.warn("worker drain participant heartbeat failed: code={} failure={}",
                                PARTICIPANT_HEARTBEAT_FAILED, failure.getClass().getSimpleName());
                    }
                }
            }
        };
    }

    /** 프로세스 끝내기. 시험만 바꾼다 — 시험 JVM을 끝내지 않게. */
    volatile java.util.function.IntConsumer terminator =
            code -> Thread.ofPlatform().name("worker-lock-lost-exit").start(() -> System.exit(code));

    @Override
    public int exitCode() {
        return this.exitCode.get();
    }

    /**
     * claim을 멈추고 진행 중이 0이 될 때까지 기다린다. {@code timeout} 안에 비면 참, 아니면 {@code worker.drain.incomplete}를
     * 남기고 거짓이다. 기다리는 동안 {@code heartbeatEvery}마다 heartbeat를 민다.
     */
    static boolean drain(Target target, Duration timeout, Duration heartbeatEvery, Duration poll) {
        target.stopClaims();
        long start = System.nanoTime();
        long lastHeartbeat = start;
        int remaining = target.inFlight();
        while (remaining > 0) {
            long now = System.nanoTime();
            if (now - start >= timeout.toNanos()) {
                LOG.atWarn().addKeyValue("count", remaining).log(INCOMPLETE_EVENT);
                return false;
            }
            if (now - lastHeartbeat >= heartbeatEvery.toNanos()) {
                lastHeartbeat = now;
                try {
                    target.heartbeat();
                } catch (RuntimeException failure) {
                    // 한 번의 실패로 drain을 멈추지 않는다. lease 만료가 여전히 권위다.
                }
            }
            try {
                Thread.sleep(Math.max(1, Math.min(poll.toMillis(),
                        Duration.ofNanos(timeout.toNanos() - (now - start)).toMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.atWarn().addKeyValue("count", target.inFlight()).log(INCOMPLETE_EVENT);
                return false;
            }
            remaining = target.inFlight();
        }
        return true;
    }
}
