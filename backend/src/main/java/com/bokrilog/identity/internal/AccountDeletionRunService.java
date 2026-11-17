package com.bokrilog.identity.internal;

import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.identity.DeletionStep;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 삭제 실행·단계 상태(Story 5.2). SQL은 {@link IdentityAccountRepository} 한 곳에 있다.
 *
 * <p>단계 커밋의 기록 메서드({@link #stepSucceeded}·{@link #stepsNeedRetry}·{@link #operationalDataErased})는 호출자 트랜잭션에 참여하고
 * 같은 트랜잭션의 앞선 {@link #hold}가 쥔 실행 행 잠금에 기댄다 — 그 잠금이 커밋까지 가므로 그 사이 다른 claim이 세대를 올릴 수 없다.
 * 단계 행 UPDATE에는 세대 술어가 없다(단계 표에 세대 열이 없다). 실행 행 UPDATE({@code markRunRetryNeeded}·{@code markRunErased})만
 * 세대·{@code RUNNING}을 다시 보고, 어긋나면 예외로 그 트랜잭션 전체를 되돌린다.
 *
 * <p>heartbeat는 실행 행에서 오래 기다리지 않는다 — 단계 트랜잭션이 그 행을 {@code FOR UPDATE}로 쥐고 있으면 잠금 대기 상한
 * ({@link #HEARTBEAT_LOCK_WAIT})에서 실패하고 다음 tick이 다시 민다. 정확성은 lease 만료와 fencing이 지킨다.
 */
@Service
class AccountDeletionRunService implements AccountDeletionRunApi {

    /** heartbeat 한 번이 실행 행 잠금을 기다리는 상한. */
    static final Duration HEARTBEAT_LOCK_WAIT = Duration.ofMillis(500);

    /** SHA-256 소문자 16진 64자. V31의 CHECK와 한 쌍이다. */
    private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final IdentityAccountRepository repository;

    AccountDeletionRunService(IdentityAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Optional<Lease> claim(Duration lease) {
        long millis = positiveMillis(lease);
        var existing = repository.claimExistingRun(millis);
        if (existing.isPresent()) {
            return existing.map(run -> new Lease(run.userId(), run.generation()));
        }
        var created = repository.claimNewRun(millis).orElse(null);
        if (created == null) {
            return Optional.empty();
        }
        for (DeletionStep step : DeletionStep.values()) {
            repository.insertStep(created.userId(), step.name(), step.position());
        }
        return Optional.of(new Lease(created.userId(), created.generation()));
    }

    @Override
    @Transactional
    public boolean heartbeat(Lease lease, Duration duration) {
        long millis = positiveMillis(duration);
        repository.limitLockWait(HEARTBEAT_LOCK_WAIT.toMillis());
        return repository.renewRun(lease.userId(), lease.generation(), millis) == 1;
    }

    @Override
    public boolean hold(Lease lease, Duration duration) {
        requireCallerTransaction();
        return repository.lockHeldRun(lease.userId(), lease.generation(), positiveMillis(duration));
    }

    @Override
    public List<StepState> stepsOf(Lease lease) {
        return stepsOf(lease.userId());
    }

    @Override
    public void stepSucceeded(Lease lease, DeletionStep step, long erasedRows) {
        requireCallerTransaction();
        if (erasedRows < 0) {
            throw new IllegalArgumentException("지운 행 수는 음수일 수 없다.");
        }
        if (repository.markStepSucceeded(lease.userId(), step.name(), erasedRows) != 1) {
            throw new IllegalStateException("삭제 단계 행이 없다.");
        }
    }

    @Override
    public void stepsNeedRetry(Lease lease, DeletionStep ran, Map<DeletionStep, String> failures) {
        requireCallerTransaction();
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("재시도 필요로 기록할 단계가 없다.");
        }
        for (var failure : failures.entrySet()) {
            String code = failure.getValue();
            if (!STEP_FAILED.equals(code) && !RESIDUAL_FOUND.equals(code) && !TOMBSTONE_FAILED.equals(code)) {
                throw new IllegalArgumentException("닫힌 실패 code가 아니다.");
            }
            if (repository.markStepRetryNeeded(lease.userId(), failure.getKey().name(), code,
                    failure.getKey() == ran) != 1) {
                throw new IllegalStateException("삭제 단계 행이 없다.");
            }
        }
        if (!failures.containsKey(ran) && repository.countStepAttempt(lease.userId(), ran.name()) != 1) {
            // 이번에 돈 단계(마지막 단계의 재검사)는 자기 행에 시도로 센다 — 상태는 그대로 PENDING이다.
            throw new IllegalStateException("삭제 단계 행이 없다.");
        }
        if (repository.markRunRetryNeeded(lease.userId(), lease.generation()) != 1) {
            throw new IllegalStateException("삭제 실행이 이 세대의 실행 중이 아니다.");
        }
    }

    @Override
    public void operationalDataErased(Lease lease) {
        requireCallerTransaction();
        if (repository.markRunErased(lease.userId(), lease.generation()) != 1) {
            throw new IllegalStateException("삭제 실행이 이 세대의 실행 중이 아니다.");
        }
    }

    @Override
    @Transactional
    public boolean release(Lease lease) {
        return repository.releaseRun(lease.userId(), lease.generation()) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeletionRun> runOf(@Nullable UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return repository.findRun(userId).map(run -> new DeletionRun(RunStatus.valueOf(run.status()),
                run.operationalErasedAt() == null ? null : run.operationalErasedAt().toInstant(), stepsOf(userId)));
    }

    @Override
    @Transactional
    public RetryOutcome acceptRetry(@Nullable UUID userId, String retryKeyDigest) {
        if (userId == null || retryKeyDigest == null || !HEX_DIGEST.matcher(retryKeyDigest).matches()) {
            return new RetryOutcome.NotFound();
        }
        if (repository.findDeletionRequest(userId).isEmpty()) {
            return new RetryOutcome.NotFound();
        }
        var run = repository.lockRun(userId).orElse(null);
        if (run == null) {
            // 아직 Worker가 집기 전이다. 재시도할 실패가 없다.
            return new RetryOutcome.NotRetryable();
        }
        /*
         * 재시도 필요면 키와 상관없이 다시 대기열에 넣는다 — 앞선 다시 시도의 응답을 잃은 사이 실행이 또 실패했으면 같은 키의 재전송이
         * 그 실패를 다시 시도해야 한다. 재생(아무것도 쓰지 않음)은 같은 키이고 실행이 재시도 필요가 아닐 때뿐이다.
         */
        if (!RunStatus.RETRY_NEEDED.name().equals(run.status())) {
            return retryKeyDigest.equals(run.retryKeyDigest()) ? new RetryOutcome.Accepted(true)
                    : new RetryOutcome.NotRetryable();
        }
        if (repository.requeueRun(userId, retryKeyDigest) != 1) {
            // 잠금 아래에서 RETRY_NEEDED를 봤으므로 구조상 오지 않는다.
            throw new IllegalStateException("재시도 필요 실행을 되돌리지 못했다.");
        }
        return new RetryOutcome.Accepted(false);
    }

    /*
     * 두 읽기(실행 행·실패 단계)는 한 스냅숏이다(REPEATABLE READ) — 그 사이 재대기열·재실행이 커밋돼도 재시도 필요 실행이 빈 실패 단계로
     * 보이지 않는다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RetryNeededRuns retryNeededRuns() {
        // 상한보다 하나 더 읽어 잘림을 안다.
        var found = repository.findRetryNeededRuns(MAX_RETRY_NEEDED_RUNS + 1);
        boolean truncated = found.size() > MAX_RETRY_NEEDED_RUNS;
        var runs = truncated ? found.subList(0, MAX_RETRY_NEEDED_RUNS) : found;
        var failed = repository.findFailedStepsByRequest(
                runs.stream().map(IdentityAccountRepository.StoredRequestRun::requestId).toList());
        return new RetryNeededRuns(runs.stream()
                .map(run -> requestRunOf(run, failed.getOrDefault(run.requestId(), List.of()))).toList(), truncated);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<RequestRun> runByRequest(@Nullable String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return Optional.empty();
        }
        return repository.findRunByRequest(requestId).map(run -> requestRunOf(run,
                repository.findFailedStepsByRequest(List.of(requestId)).getOrDefault(requestId, List.of())));
    }

    @Override
    @Transactional
    public OperatorRequeue requeueByOperator(@Nullable String requestId, String retryKeyDigest) {
        if (retryKeyDigest == null || !HEX_DIGEST.matcher(retryKeyDigest).matches()) {
            // 호출자가 만든 digest다. 모양이 틀리면 배선 실수라 쓰지 않는다.
            throw new IllegalArgumentException("재대기열 키 digest의 모양이 아니다.");
        }
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return new OperatorRequeue.NotFound();
        }
        var requeued = repository.requeueRunByRequest(requestId, retryKeyDigest).orElse(null);
        if (requeued != null) {
            return new OperatorRequeue.Requeued(requeued.requeuedAt().toInstant(), requeued.retrySteps());
        }
        /*
         * 0행: 잠그지 않는 읽기로 거절을 가른다. 실행이 없거나, 재시도 필요인데 요청 행이 없으면(Worker가 집지 못한다) 없는 대상이다. 그 밖
         * (다른 상태이거나, 재시도 필요지만 다른 트랜잭션이 그 행을 쥐어 건너뛰었다)은 지금 다시 대기열에 넣을 수 없는 상태다.
         */
        var run = repository.findRunByRequest(requestId).orElse(null);
        if (run == null || (RunStatus.RETRY_NEEDED.name().equals(run.status()) && run.requestedAt() == null)) {
            return new OperatorRequeue.NotFound();
        }
        return new OperatorRequeue.NotRetryable();
    }

    private static RequestRun requestRunOf(IdentityAccountRepository.StoredRequestRun run,
            List<IdentityAccountRepository.StoredStep> failed) {
        return new RequestRun(run.requestId(), run.requestedAt() == null ? null : run.requestedAt().toInstant(),
                RunStatus.valueOf(run.status()), run.stoppedAt() == null ? null : run.stoppedAt().toInstant(),
                failed.stream().map(step -> new StepState(DeletionStep.valueOf(step.step()),
                        StepStatus.valueOf(step.status()), step.erasedRows(), step.failureCode(), step.attempts()))
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Instant> operationalCompletions(Collection<String> requestIds) {
        Map<String, Instant> completions = new LinkedHashMap<>();
        repository.operationalCompletions(requestIds)
                .forEach((requestId, erasedAt) -> completions.put(requestId, erasedAt.toInstant()));
        return completions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> awaitingDeletionCompletion() {
        return List.copyOf(repository.awaitingDeletionCompletion());
    }

    @Override
    @Transactional
    public int recordDeletionCompleted(Collection<String> requestIds) {
        List<String> valid = requestIds.stream().filter(id -> id != null && REQUEST_ID.matcher(id).matches())
                .distinct().toList();
        return repository.markDeletionCompleted(valid);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompletedDeletion> completedDeletionOf(@Nullable String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return Optional.empty();
        }
        return repository.findCompletion(requestId).map(found -> new CompletedDeletion(
                found.operationalErasedAt().toInstant(),
                found.deletionCompletedAt() == null ? null : found.deletionCompletedAt().toInstant()));
    }

    @Override
    @Transactional
    public int expireDeletedIdentities() {
        // 잠금 대기 2초·문장 5초 — 백업 파기 판정 트랜잭션과 같은 상한이다. 일괄 크기는 두지 않는다(같은 판정자의 만료 삭제와 같은 관용).
        repository.limitDeletedIdentitySweepTime();
        return repository.expireDeletedIdentities(DELETED_IDENTITY_RETENTION);
    }

    /** 삭제 요청 ID의 모양. V30의 CHECK와 한 쌍이다. */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private List<StepState> stepsOf(UUID userId) {
        return repository.findSteps(userId).stream()
                .map(step -> new StepState(DeletionStep.valueOf(step.step()), StepStatus.valueOf(step.status()),
                        step.erasedRows(), step.failureCode(), step.attempts()))
                .toList();
    }

    private static long positiveMillis(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("lease 수명은 양수여야 한다.");
        }
        return duration.toMillis();
    }

    private static void requireCallerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("삭제 단계의 기록은 호출자 트랜잭션 안에서만 한다.");
        }
    }
}
