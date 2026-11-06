package com.bokrilog;

import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.identity.AccountDeletionRunApi.Lease;
import com.bokrilog.identity.AccountDeletionRunApi.StepStatus;
import com.bokrilog.identity.DeletionStep;
import com.bokrilog.sync.WorkerLease;
import java.io.Serial;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 계정 삭제의 단계 모드 실행자(Story 5.2). Worker({@link AccountErasureWorker})가 집은 실행 하나를 {@link DeletionStep} 순서대로
 * <b>단계마다 한 트랜잭션</b>으로 돈다. 각 단계가 지우는 것과 잔여 판독은 {@link AccountErasure}가 소유하고, 단계 상태는 identity
 * ({@link AccountDeletionRunApi})가 소유한다.
 *
 * <p><b>단계 트랜잭션 하나.</b> 실행 행 잠금 아래 fencing 재검사({@link AccountDeletionRunApi#hold}) → 이미 성공한 단계면 건너뜀 → 지우기
 * 전 잔여 수(기록할 건수) → 그 단계의 삭제 → 같은 트랜잭션의 잔여 0 확인 → 성공 기록. 마지막 단계({@link DeletionStep#IDENTITY})는
 * 지우기 전에 앞 단계 전부의 잔여를 다시 세고, 잔여가 있는 단계를 재시도 필요({@link AccountDeletionRunApi#RESIDUAL_FOUND})로 되돌린다 —
 * 늦은 쓰기를 완료로 숨기지 않는다. 잔여가 없으면 tombstone 집합 닫기·보존 연장 → 요청 행·세션·payload 삭제 → {@code DELETED} → 운영 데이터 삭제
 * 완료 시각이 한 커밋이다.
 *
 * <p><b>tombstone은 첫 단계 앞이다(Story 5.3).</b> 단계를 돌기 전에 같은 fencing 아래 자기 트랜잭션 하나로 요청 ID·요청 시각의 tombstone을
 * 기록한다(멱등) — 운영 완료 전에 복원해도 그 계정을 다시 지운다. 마지막 단계는 tombstone 기록이 아니라 영향 generation 집합 닫기·보존
 * 연장이다.
 *
 * <p><b>실패.</b> 단계가 예외로 끝나거나 잔여가 남거나 결과 없이 끝나면 그 트랜잭션을 통째로 되돌리고, 새 트랜잭션에서(다시 fencing을 본 뒤)
 * 그 단계를 재시도 필요로 기록하고 실행을 멈춘다. 기록은 {@link #RECORD_ATTEMPTS}번까지 새 트랜잭션으로 다시 해 본다. 자동 재시도는 없다 —
 * 사용자의 다시 시도가 실행을 되돌릴 때만 Worker가 다시 집는다. fencing을 잃은 늦은 Worker는 아무것도 기록하지 않는다. 기록이 끝내
 * 불가능하면(DB 장애) 실행은 {@code RUNNING}으로 남고 lease가 끝난 뒤의 claim이 그것을 집는 장애 복구에 맡기며, 그 사실을 error
 * code {@link #FAILURE_UNRECORDED} 한 줄로 남긴다 — 결과 없는 실행을 기록됐다고 보고하지 않는다.
 *
 * <p><b>로그</b>에는 단계 code·결과 code·예외의 단순 클래스 이름만 남는다 — 사용자·요청 ID·예외 메시지·건수의 원값이 없다.
 */
@Component
class AccountErasureRun {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AccountErasureRun.class);

    /** 실패 기록을 새 트랜잭션으로 해 보는 상한. */
    static final int RECORD_ATTEMPTS = 3;

    /** 실패를 끝내 기록하지 못했을 때의 error code. 실행은 lease 만료 뒤 장애 복구로 다시 집힌다. */
    static final String FAILURE_UNRECORDED = "ACCOUNT_ERASURE_FAILURE_UNRECORDED";

    /** drain에서 lease를 놓지 못했을 때의 warn code. 실행은 lease 만료 뒤 다시 집힌다. */
    static final String RELEASE_FAILED = "ACCOUNT_ERASURE_RELEASE_FAILED";

    /** 실행 하나의 결말. */
    enum Result {
        /** 모든 단계가 성공했다 — 운영 데이터 삭제 완료. */
        OPERATIONAL_DATA_ERASED,
        /** 한 단계가 실패해 재시도 필요로 멈췄다(기록됐다). */
        RETRY_NEEDED,
        /** 이 Worker의 lease·fencing이 더는 유효하지 않다. 아무것도 기록하지 않았다. */
        LEASE_LOST,
        /** 종료 drain으로 단계 사이에서 lease를 놓았다({@code QUEUED}). */
        RELEASED,
        /** 실패를 기록하지 못했다. 실행은 {@code RUNNING}으로 남아 lease 만료 뒤 장애 복구로 다시 집힌다. */
        UNRECORDED
    }

    /** 단계 하나의 결말. */
    private enum StepResult {
        SUCCEEDED,
        SKIPPED,
        FAILED,
        LOST,
        UNRECORDED
    }

    /** fencing을 잃었다. 단계 트랜잭션을 되돌리기 위한 사설 신호이며 스택도 메시지도 없다. */
    private static final class LeaseLost extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        LeaseLost() {
            super(null, null, false, false);
        }
    }

    /** 잔여가 있다. 단계 트랜잭션을 되돌리고 그 단계들을 재시도 필요로 기록한다. */
    private static final class Residual extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        private final transient Map<DeletionStep, String> failures;

        Residual(Map<DeletionStep, String> failures) {
            super(null, null, false, false);
            this.failures = failures;
        }
    }

    private final AccountErasure erasure;

    private final AccountDeletionRunApi runs;

    private final TransactionTemplate transaction;

    AccountErasureRun(AccountErasure erasure, AccountDeletionRunApi runs, PlatformTransactionManager transactionManager) {
        this.erasure = erasure;
        this.runs = runs;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 집은 실행 하나를 끝까지(또는 첫 실패·drain까지) 돈다. 이미 성공한 단계는 건너뛴다.
     *
     * @param stopping 참이면 다음 단계를 시작하지 않고 lease를 놓는다(Worker 종료 drain). 단계 사이에서만 본다
     */
    Result execute(Lease lease, BooleanSupplier stopping) {
        // tombstone 기록은 종료 drain 중에도 한다 — 짧은 멱등 트랜잭션 하나이고, 놓기 전에 남겨 두면 다음 Worker가 이어 받기 전의 복원도 그
        // 계정을 다시 지운다. drain 판정은 아래 단계 앞에서 그대로 한다.
        switch (recordTombstone(lease)) {
            case LOST -> {
                log(Result.LEASE_LOST, null);
                return Result.LEASE_LOST;
            }
            case FAILED -> {
                return Result.RETRY_NEEDED;
            }
            case UNRECORDED -> {
                return Result.UNRECORDED;
            }
            case SUCCEEDED, SKIPPED -> {
                // 첫 단계로.
            }
        }
        for (DeletionStep step : DeletionStep.values()) {
            if (stopping.getAsBoolean()) {
                Result released;
                try {
                    released = this.runs.release(lease) ? Result.RELEASED : Result.LEASE_LOST;
                } catch (RuntimeException unreleased) {
                    // 놓지 못했다. 단계 실패가 아니므로 재시도 필요로 적지 않는다 — lease 만료 뒤 다음 claim이 이어 받는다.
                    LOG.warn("account erasure release failed: code={} failure={}", RELEASE_FAILED,
                            unreleased.getClass().getSimpleName());
                    return Result.UNRECORDED;
                }
                log(released, null);
                return released;
            }
            StepResult result = runStep(lease, step);
            switch (result) {
                case LOST -> {
                    log(Result.LEASE_LOST, step);
                    return Result.LEASE_LOST;
                }
                case FAILED -> {
                    return Result.RETRY_NEEDED;
                }
                case UNRECORDED -> {
                    return Result.UNRECORDED;
                }
                case SUCCEEDED, SKIPPED -> {
                    // 다음 단계.
                }
            }
        }
        log(Result.OPERATIONAL_DATA_ERASED, null);
        return Result.OPERATIONAL_DATA_ERASED;
    }

    /**
     * 단계 처리 밖에서 실행이 예상하지 못한 예외로 끝났을 때(Worker가 잡는다) 지금 아직 성공하지 않은 첫 단계를 재시도 필요
     * ({@link AccountDeletionRunApi#STEP_FAILED})로 기록한다 — 조용한 자동 재시도로 남기지 않는다. fencing을 다시 보고, 기록할 수 없으면
     * {@link Result#UNRECORDED}다.
     */
    Result failUnexpected(Lease lease, RuntimeException failure) {
        return switch (recordFailure(lease, null, null, failure)) {
            case FAILED -> Result.RETRY_NEEDED;
            case LOST -> Result.LEASE_LOST;
            default -> Result.UNRECORDED;
        };
    }

    /**
     * 첫 단계 앞의 tombstone 기록(Story 5.3): 실행 행 잠금 아래 fencing 재검사({@link AccountDeletionRunApi#hold}) → 요청 ID·요청 시각으로
     * 기록(멱등)을 자기 트랜잭션 하나로 커밋한다 — 뒤 단계가 실패해도 tombstone은 남는다. 실패하면 아직 성공하지 않은 첫 단계를 재시도 필요로
     * 기록하되 code는 {@link AccountDeletionRunApi#TOMBSTONE_FAILED}다(그 단계 자체의 실패와 구분한다).
     */
    private StepResult recordTombstone(Lease lease) {
        Boolean recorded;
        try {
            recorded = this.transaction.execute(status -> {
                if (!this.runs.hold(lease, WorkerLease.DURATION)) {
                    throw new LeaseLost();
                }
                this.erasure.recordRequestedTombstone(lease.userId());
                return Boolean.TRUE;
            });
        } catch (LeaseLost lost) {
            return StepResult.LOST;
        } catch (RuntimeException failure) {
            return recordFailure(lease, null, null, failure, AccountDeletionRunApi.TOMBSTONE_FAILED);
        }
        if (recorded == null) {
            return recordFailure(lease, null, null, null, AccountDeletionRunApi.TOMBSTONE_FAILED);
        }
        return StepResult.SUCCEEDED;
    }

    private StepResult runStep(Lease lease, DeletionStep step) {
        UUID userId = lease.userId();
        StepResult result;
        try {
            result = this.transaction.execute(status -> {
                if (!this.runs.hold(lease, WorkerLease.DURATION)) {
                    throw new LeaseLost();
                }
                boolean done = this.runs.stepsOf(lease).stream()
                        .anyMatch(state -> state.step() == step && state.status() == StepStatus.SUCCEEDED);
                if (done) {
                    return StepResult.SKIPPED;
                }
                if (step == DeletionStep.IDENTITY) {
                    Map<DeletionStep, String> residual = new EnumMap<>(DeletionStep.class);
                    for (DeletionStep earlier : DeletionStep.values()) {
                        if (earlier != step && this.erasure.residueOf(earlier, userId) > 0) {
                            residual.put(earlier, AccountDeletionRunApi.RESIDUAL_FOUND);
                        }
                    }
                    if (!residual.isEmpty()) {
                        throw new Residual(residual);
                    }
                }
                long before = this.erasure.residueOf(step, userId);
                this.erasure.apply(step, userId, () -> this.erasure.closeRequestedTombstone(userId));
                if (this.erasure.residueOf(step, userId) > 0) {
                    throw new Residual(Map.of(step, AccountDeletionRunApi.RESIDUAL_FOUND));
                }
                this.runs.stepSucceeded(lease, step, before);
                if (step == DeletionStep.IDENTITY) {
                    this.runs.operationalDataErased(lease);
                }
                return StepResult.SUCCEEDED;
            });
        } catch (LeaseLost lost) {
            return StepResult.LOST;
        } catch (Residual residual) {
            return recordFailure(lease, step, residual.failures, null);
        } catch (RuntimeException failure) {
            return recordFailure(lease, step, Map.of(step, AccountDeletionRunApi.STEP_FAILED), failure);
        }
        if (result == null) {
            // 결과 없이 끝난 트랜잭션은 성공이 아니다. 단계 실패로 기록한다.
            return recordFailure(lease, step, Map.of(step, AccountDeletionRunApi.STEP_FAILED), null);
        }
        return result;
    }

    /** 실패 기록 한 번의 결말. */
    private enum Recording {
        RECORDED,
        LOST
    }

    /**
     * 새 트랜잭션에서 fencing을 다시 본 뒤 실패를 기록한다. 기록 트랜잭션이 예외로 끝나거나 결과 없이 끝나면 {@link #RECORD_ATTEMPTS}번까지
     * 새 트랜잭션으로 다시 해 본다. fencing을 잃었으면 아무것도 쓰지 않는다.
     *
     * @param step 실패한 단계. {@code null}이면 그 트랜잭션에서 아직 성공하지 않은 첫 단계를 고른다(단계 밖 예외)
     * @param failures 기록할 단계 → code. {@code null}이면 위 단계 하나의 {@link AccountDeletionRunApi#STEP_FAILED}다
     */
    private StepResult recordFailure(Lease lease, @Nullable DeletionStep step,
            @Nullable Map<DeletionStep, String> failures, @Nullable RuntimeException failure) {
        return recordFailure(lease, step, failures, failure, AccountDeletionRunApi.STEP_FAILED);
    }

    /**
     * {@link #recordFailure(Lease, DeletionStep, Map, RuntimeException)}과 같지만 {@code failures}가 없을 때의 code를 준다 — tombstone 기록
     * 실패는 {@link AccountDeletionRunApi#TOMBSTONE_FAILED}로 남겨 그 단계 자체의 실패와 구분한다.
     */
    private StepResult recordFailure(Lease lease, @Nullable DeletionStep step,
            @Nullable Map<DeletionStep, String> failures, @Nullable RuntimeException failure, String defaultCode) {
        String unrecordedBy = "-";
        for (int attempt = 1; attempt <= RECORD_ATTEMPTS; attempt++) {
            Recording recorded;
            try {
                recorded = this.transaction.execute(status -> {
                    if (!this.runs.hold(lease, WorkerLease.DURATION)) {
                        return Recording.LOST;
                    }
                    DeletionStep failed = step != null ? step : firstUnfinished(lease);
                    if (failed == null) {
                        // 모든 단계가 이미 성공했다면 실행이 실행 중일 수 없다 — 기록할 실패가 없다.
                        return Recording.LOST;
                    }
                    this.runs.stepsNeedRetry(lease, failed,
                            failures != null ? failures : Map.of(failed, defaultCode));
                    return Recording.RECORDED;
                });
            } catch (RuntimeException unrecorded) {
                unrecordedBy = unrecorded.getClass().getSimpleName();
                continue;
            }
            if (recorded == null) {
                unrecordedBy = "NO_RESULT";
                continue;
            }
            if (recorded == Recording.LOST) {
                return StepResult.LOST;
            }
            if (step != null && failures != null && !failures.containsKey(step)) {
                // 이 단계(마지막 단계)가 앞 단계들의 잔여를 보고 멈췄다 — 잔여가 있는 것은 그 앞 단계들이다.
                LOG.warn("account erasure residue found: step={} code={} residualSteps={}", step.name(),
                        AccountDeletionRunApi.RESIDUAL_FOUND, failures.keySet().stream().map(Enum::name).toList());
            } else {
                LOG.warn("account erasure step failed: step={} code={} failure={}", step == null ? "-" : step.name(),
                        failures == null || step == null ? defaultCode
                                : failures.getOrDefault(step, AccountDeletionRunApi.STEP_FAILED),
                        failure == null ? "-" : failure.getClass().getSimpleName());
            }
            return StepResult.FAILED;
        }
        // 기록할 수 없었다. 실행은 RUNNING으로 남고 lease 만료 뒤 장애 복구로 다시 집힌다 — 기록됐다고 보고하지 않는다.
        LOG.error("account erasure failure unrecorded: code={} step={} failure={}", FAILURE_UNRECORDED,
                step == null ? "-" : step.name(), unrecordedBy);
        return StepResult.UNRECORDED;
    }

    private @Nullable DeletionStep firstUnfinished(Lease lease) {
        return this.runs.stepsOf(lease).stream()
                .filter(state -> state.status() != StepStatus.SUCCEEDED)
                .map(AccountDeletionRunApi.StepState::step)
                .findFirst()
                .orElse(null);
    }

    private static void log(Result result, @Nullable DeletionStep step) {
        LOG.info("account erasure run: result={} step={}", result.name(), step == null ? "-" : step.name());
    }
}
