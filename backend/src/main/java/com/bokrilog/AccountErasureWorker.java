package com.bokrilog;

import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.identity.AccountDeletionRunApi.Lease;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.sync.DrainParticipant;
import com.bokrilog.sync.WorkerLease;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사용자 계정 삭제의 Worker(Story 5.2). 스케줄링 역할({@code worker}·{@code all})에만 있다({@link BokrilogRole.Schedules}).
 *
 * <p><b>집기.</b> dispatch마다 삭제 실행 하나를 lease(작업 lease와 같은 30초, {@link WorkerLease})로 집고 가상 스레드에서 단계를 돈다
 * ({@link AccountErasureRun}). 한 Worker가 동시에 도는 실행은 {@link #MAX_IN_FLIGHT}개까지다 — 몰린 요청이 감사 원장 추가 잠금 앞에 줄줄이
 * 쌓이지 않게 한다. heartbeat는 {@link #HEARTBEAT}마다 진행 중 lease를 lease마다 따로 민다(단계 트랜잭션이 실행 행을 쥐고 있으면 짧은 잠금
 * 대기 상한에서 건너뛴다 — 정확성은 fencing이 지킨다). {@code RETRY_NEEDED} 실행은 집지 않는다 — 자동 재시도가 없다.
 *
 * <p><b>집지 않는 프로세스.</b> {@code BOKRILOG_ENV=production}(법정 분리 보존 정책 G-5 승인 전)이거나 tombstone digest 키가 없으면 삭제를
 * 집지 않는다 — 요청은 진행 중으로 남는다. 마지막 단계가 tombstone 없이는 커밋될 수 없으므로 키 없는 프로세스는 시작하지 않는다.
 *
 * <p><b>drain.</b> Worker 역할의 종료 drain({@code sync.internal.WorkerDrain})에 {@link DrainParticipant}로 선다 — 종료 신호나 단일 Worker
 * 잠금 상실에서 {@link #stopClaims()}로 새 claim을 멈추고, 진행 중 실행은 다음 단계 앞에서 lease를 놓으며({@code QUEUED}), drain은 같은
 * {@code drain-timeout} 예산 안에서 이 Worker의 진행 중이 0이 되기를 함께 기다린다(비지 않으면 종료 코드 75).
 *
 * <p><b>단계 밖 예외.</b> 실행자가 단계 처리 밖에서 예상하지 못한 예외로 끝나면 {@link AccountErasureRun#failUnexpected}로 재시도 필요를
 * 기록한다 — 조용한 자동 재시도로 남기지 않는다.
 *
 * <p><b>스케줄 스위치.</b> {@code bokrilog.account-deletion.worker.scheduled}(기본 참)가 거짓이면 주기 dispatch만 쉰다 — 백엔드 시험이
 * 삭제 요청 시험(5.1)의 계정을 Worker가 지우지 않게 끄고, 이 Worker의 시험은 {@link #runOnce}로 한 번씩 몬다. 운영 값은 바꾸지 않는다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(BokrilogRole.Schedules.class)
class AccountErasureWorker implements DrainParticipant {

    private static final String DISPATCH_INTERVAL = "${bokrilog.sync.worker.dispatch-interval}";

    /** heartbeat 간격(ISO-8601). 스케줄과 {@link #HEARTBEAT}가 이 한 값을 쓴다. 작업 Worker와 같은 5초다. */
    static final String HEARTBEAT_INTERVAL = "PT5S";

    /** heartbeat 간격. */
    static final Duration HEARTBEAT = Duration.parse(HEARTBEAT_INTERVAL);

    /** heartbeat 실패의 warn code. 단계 트랜잭션이 실행 행을 쥔 동안의 잠금 대기 초과도 여기 남는다. */
    static final String HEARTBEAT_FAILED = "ACCOUNT_ERASURE_HEARTBEAT_FAILED";

    /** 한 Worker가 동시에 도는 삭제 실행의 상한. */
    static final int MAX_IN_FLIGHT = 2;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AccountErasureWorker.class);

    private final AccountErasureRun runner;

    private final AccountDeletionRunApi runs;

    private final DeletionTombstoneApi tombstones;

    private final Map<UUID, Lease> inFlight = new ConcurrentHashMap<>();

    /** 진행 중인 claim 구간 수(drain이 함께 센다). */
    private final java.util.concurrent.atomic.AtomicInteger claiming = new java.util.concurrent.atomic.AtomicInteger();

    private final AtomicBoolean stopping = new AtomicBoolean();

    /** production인가. 기동 시각의 {@code bokrilog.telemetry.env}에서 정하고 시험만 {@link #reinterpret}으로 바꾼다. */
    private volatile boolean production;

    private final boolean scheduled;

    AccountErasureWorker(AccountErasureRun runner, AccountDeletionRunApi runs, DeletionTombstoneApi tombstones,
            @Value("${bokrilog.telemetry.env:development}") String env,
            @Value("${bokrilog.account-deletion.worker.scheduled:true}") boolean scheduled) {
        this.runner = runner;
        this.runs = runs;
        this.tombstones = tombstones;
        this.scheduled = scheduled;
        reinterpret(env);
    }

    /** 환경 값을 다시 해석한다. 운영 경로는 생성자 한 번뿐이고 시험이 production 차단을 같은 컨텍스트에서 보려고 부른다. */
    final void reinterpret(String env) {
        this.production = BokrilogRoleConfig.PRODUCTION.equals(env);
    }

    /** 이 프로세스가 삭제를 집을 수 있는가 — production이 아니고, tombstone 키가 있고, 종료 중이 아니다. */
    boolean claimsAllowed() {
        return !this.production && !this.stopping.get() && this.tombstones.keyAvailable();
    }

    /** 주기 dispatch: 하나를 집어 가상 스레드에서 돈다. */
    @Scheduled(fixedDelayString = DISPATCH_INTERVAL)
    void dispatch() {
        if (!this.scheduled) {
            return;
        }
        try {
            claim().ifPresent(this::start);
        } catch (RuntimeException failure) {
            // 한 번의 claim 실패로 스케줄을 멈추지 않는다. 다음 tick이 다시 본다.
            LOG.warn("account erasure claim failed: failure={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 집은 실행을 가상 스레드에서 시작한다. 스레드를 시작하지 못하면 진행 중에서 빼고 lease를 놓아 본 뒤(놓지 못해도 lease 만료가 권위다)
     * 다시 던진다 — 진행 중 수가 영원히 남아 drain을 붙들지 않게.
     */
    void start(Lease lease) {
        try {
            this.threads.accept(() -> execute(lease));
        } catch (RuntimeException | Error failure) {
            this.inFlight.remove(lease.userId(), lease);
            try {
                this.runs.release(lease);
            } catch (RuntimeException unreleased) {
                LOG.warn("account erasure release failed: code={} failure={}", AccountErasureRun.RELEASE_FAILED,
                        unreleased.getClass().getSimpleName());
            }
            throw failure;
        }
    }

    /** 실행 스레드를 만드는 곳. 시험만 바꾼다 — 스레드 시작 실패를 흉내 낸다. */
    volatile java.util.function.Consumer<Runnable> threads =
            task -> Thread.ofVirtual().name("account-erasure").start(task);

    /**
     * 하나를 집어 <b>이 스레드에서</b> 끝까지 돈다(시험·수동 구동). 집을 수 없거나 집은 것이 없으면 {@code null}이다.
     */
    AccountErasureRun.@Nullable Result runOnce() {
        Optional<Lease> lease = claim();
        return lease.map(this::execute).orElse(null);
    }

    /**
     * 진행 중 lease를 민다. lease마다 따로다 — 단계 트랜잭션이 쥔 실행 행에서 막힌 갱신 하나(짧은 잠금 대기 상한에서 실패)가 다른 실행의
     * lease나 drain을 붙들지 않는다. 동기화하지 않는다 — 스케줄러와 drain이 함께 불러도 갱신은 멱등이다.
     */
    @Scheduled(fixedDelayString = HEARTBEAT_INTERVAL)
    @Override
    public void heartbeat() {
        for (Lease lease : this.inFlight.values()) {
            try {
                this.runs.heartbeat(lease, WorkerLease.DURATION);
            } catch (RuntimeException failure) {
                // lease 만료와 fencing이 여전히 권위다. 다음 tick이 다시 민다. 식별자 없이 code와 예외 이름만 남긴다.
                LOG.warn("account erasure heartbeat failed: code={} failure={}", HEARTBEAT_FAILED,
                        failure.getClass().getSimpleName());
            }
        }
    }

    /** 새 claim을 멈춘다(종료 drain·단일 Worker 잠금 상실). 되돌리지 않는다 — 진행 중 실행은 다음 단계 앞에서 lease를 놓는다. */
    @Override
    public void stopClaims() {
        this.stopping.set(true);
    }

    /** 진행 중인 claim 구간과 실행의 수. */
    @Override
    public int inFlight() {
        return this.claiming.get() + this.inFlight.size();
    }

    private Optional<Lease> claim() {
        this.claiming.incrementAndGet();
        try {
            if (!claimsAllowed() || this.inFlight.size() >= MAX_IN_FLIGHT) {
                return Optional.empty();
            }
            Optional<Lease> lease = this.runs.claim(WorkerLease.DURATION);
            lease.ifPresent(claimed -> this.inFlight.put(claimed.userId(), claimed));
            return lease;
        } finally {
            this.claiming.decrementAndGet();
        }
    }

    private AccountErasureRun.Result execute(Lease lease) {
        try {
            return this.runner.execute(lease, this.stopping::get);
        } catch (RuntimeException failure) {
            // 실행자는 단계 실패를 값으로 돌려준다. 여기 온 것은 단계 처리 밖의 결함이다 — 재시도 필요로 기록해 둔다.
            LOG.warn("account erasure run failed: failure={}", failure.getClass().getSimpleName());
            return this.runner.failUnexpected(lease, failure);
        } finally {
            this.inFlight.remove(lease.userId(), lease);
        }
    }
}
