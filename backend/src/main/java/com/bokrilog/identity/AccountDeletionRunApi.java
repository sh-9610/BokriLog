package com.bokrilog.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 계정 삭제 실행과 단계 상태(Story 5.2). <b>identity가 그 상태의 권위다</b> — 표는 V31 {@code identity_deletion_run}·
 * {@code identity_deletion_step}이다. 실제 모듈 삭제는 하지 않는다(루트 orchestrator가 모듈 port를 부른다). HTTP로 노출되지 않는다.
 *
 * <p><b>집기.</b> Worker가 {@link #claim}으로 "요청 행이 있고 실행이 없거나 {@code QUEUED}이거나 lease가 끝난 {@code RUNNING}인
 * {@code DELETION_IN_PROGRESS} 계정" 하나를 집는다 — 실행 행이 없으면 이 호출이 만든다(단계 행 여덟 {@code PENDING}과 함께). claim마다
 * fencing 세대가 오른다. {@code RETRY_NEEDED} 실행은 집지 않는다(자동 재시도 없음).
 *
 * <p><b>단계 커밋.</b> 단계 트랜잭션은 먼저 {@link #hold}로 실행 행을 잠그고 자기 세대·살아 있는 lease를 다시 본다 — 거짓이면 다른
 * Worker가 집었거나 lease가 끝났으므로 아무것도 기록하지 않고 되돌린다. 성공·실패 기록({@link #stepSucceeded}·{@link #stepsNeedRetry}·
 * {@link #operationalDataErased})은 같은 트랜잭션의 {@link #hold} 뒤에만 부른다.
 *
 * <p><b>다시 시도.</b> {@link #acceptRetry}가 {@code RETRY_NEEDED} 실행을 {@code QUEUED}로 되돌린다. 실패·미실행 단계만 다시 돌고 성공한
 * 단계는 건너뛴다(모듈 port는 멱등이다). 요청 세션을 잃은 실행은 운영 command {@code requeue-deletion}이 {@link #requeueByOperator}로
 * 같은 의미로 되돌린다(Epic 5 회고 AI-1) — 단계 상태는 바꾸지 않는다.
 */
public interface AccountDeletionRunApi {

    /** 실행 상태. */
    enum RunStatus {
        /** 집기를 기다린다. */
        QUEUED,
        /** lease 아래 실행 중이다. */
        RUNNING,
        /** 한 단계가 실패해 멈췄다. 다시 시도를 받기 전까지 집지 않는다. */
        RETRY_NEEDED,
        /** 모든 단계가 성공했다 — 운영 데이터 삭제 완료. 백업 파기 완료가 아니다(Story 5.3). */
        OPERATIONAL_DATA_ERASED,
        /**
         * 전체 삭제 완료(Story 5.4) — 운영 데이터 삭제 완료에 그 요청의 백업 파기 완료(tombstone {@code DESTROYED})와 법정 분리 보존 처리가
         * 더해졌다. 루트 판정자({@code BackupDestructionJudge})만 기록한다({@link #recordDeletionCompleted}).
         */
        DELETION_COMPLETED
    }

    /** 단계 상태. */
    enum StepStatus {
        PENDING,
        SUCCEEDED,
        RETRY_NEEDED
    }

    /** 단계가 예외로 끝났다. 예외의 종류·메시지는 남기지 않는다. */
    String STEP_FAILED = "STEP_FAILED";

    /** 단계 뒤 또는 마지막 단계의 재검사에서 그 모듈의 잔여 행이 있었다. */
    String RESIDUAL_FOUND = "RESIDUAL_FOUND";

    /**
     * 첫 단계 앞의 tombstone 기록(Story 5.3)이 실패했다. 아직 성공하지 않은 첫 단계에 붙는다 — 그 단계 자체가 실패한 것이 아님을 구분한다.
     */
    String TOMBSTONE_FAILED = "TOMBSTONE_FAILED";

    /**
     * 단계 하나의 상태.
     *
     * @param erasedRows 성공한 단계가 지운(비식별한) 행 수. 성공이 아니면 {@code null}
     * @param failureCode {@link #STEP_FAILED}·{@link #RESIDUAL_FOUND}·{@link #TOMBSTONE_FAILED}. 재시도 필요가 아니면 {@code null}
     */
    record StepState(DeletionStep step, StepStatus status, @Nullable Long erasedRows, @Nullable String failureCode,
            int attempts) {}

    /**
     * 실행 하나. 사용자 식별자를 담지 않는다.
     *
     * @param operationalErasedAt 운영 데이터 삭제 완료 시각. 그 상태가 아니면 {@code null}
     * @param steps 단계 순서대로
     */
    record DeletionRun(RunStatus status, @Nullable Instant operationalErasedAt, List<StepState> steps) {

        public DeletionRun {
            steps = List.copyOf(steps);
        }

        /** 재시도 필요인 단계(순서대로). */
        public List<DeletionStep> failedSteps() {
            return this.steps.stream().filter(step -> step.status() == StepStatus.RETRY_NEEDED)
                    .map(StepState::step).toList();
        }
    }

    /**
     * 집은 실행. 단계 커밋이 이 값의 세대로 fencing을 재검사한다.
     *
     * @param generation 이 claim의 fencing 세대
     */
    record Lease(UUID userId, long generation) {}

    /** 다시 시도 접수의 결과. */
    sealed interface RetryOutcome {

        /** 실행을 {@code QUEUED}로 되돌렸다({@code replayed}면 같은 키의 앞선 접수 — 아무것도 쓰지 않았다). */
        record Accepted(boolean replayed) implements RetryOutcome {}

        /** 실행이 재시도 필요가 아니다(아직 집기 전·실행 중·완료). 아무것도 쓰지 않았다. */
        record NotRetryable() implements RetryOutcome {}

        /** 삭제 요청이 없다. 아무것도 쓰지 않았다. */
        record NotFound() implements RetryOutcome {}
    }

    /**
     * 실행 하나를 집는다(트랜잭션 하나). 기존 실행({@code QUEUED}·lease 끝난 {@code RUNNING})을 먼저, 없으면 실행이 없는 요청 계정을
     * 요청 시각 순으로 본다. 다른 Worker가 잡은 행은 건너뛴다.
     *
     * @param lease lease 수명. 작업 lease와 같은 값을 넘긴다
     * @return 집은 실행, 없으면 빈 값
     */
    Optional<Lease> claim(Duration lease);

    /**
     * 그 lease를 민다. 세대가 다르거나 실행 중이 아니면 거짓(lease를 잃었다). 단계 트랜잭션이 실행 행을 쥐고 있으면 짧은 잠금 대기 상한에서
     * 예외로 끝난다 — 호출자는 건너뛰고 다음 tick에 다시 민다.
     */
    boolean heartbeat(Lease lease, Duration duration);

    /**
     * <b>호출자 트랜잭션 안에서</b> 실행 행을 그 세대·{@code RUNNING}일 때만 잠그고 lease를 {@code duration}만큼 새로 민다(한 문장). 단계
     * 커밋과 결과 기록의 앞이다. lease 만료는 보지 않는다 — 긴 단계 동안 heartbeat가 이 잠금에 막혀 lease가 지났어도 세대가 그대로면 이
     * Worker가 주인이다(fencing이 정확성을 지킨다).
     *
     * @return 이 lease가 아직 실행을 쥐고 있는가
     * @throws IllegalStateException 트랜잭션 밖에서 불렀다
     */
    boolean hold(Lease lease, Duration duration);

    /** 그 실행의 단계 상태(순서대로). {@link #hold} 뒤 같은 트랜잭션에서 읽는다. */
    List<StepState> stepsOf(Lease lease);

    /** {@link #hold} 뒤: 그 단계를 성공으로 기록한다. */
    void stepSucceeded(Lease lease, DeletionStep step, long erasedRows);

    /**
     * {@link #hold} 뒤: 그 단계들을 재시도 필요로 기록하고 실행을 {@code RETRY_NEEDED}로 멈춘다(lease를 놓는다).
     *
     * @param ran 이번에 돌던 단계. 그 단계가 {@code failures}에 있을 때만 시도 횟수가 오른다 — 마지막 단계의 재검사가 되돌린 앞
     *     단계는 이번에 돌지 않았다. {@code failures}에 없으면(마지막 단계가 앞 단계의 잔여를 본 경우) 그 단계는 상태를
     *     그대로 두고 시도만 센다
     * @param failures 단계 → {@link #STEP_FAILED}·{@link #RESIDUAL_FOUND}·{@link #TOMBSTONE_FAILED}. 비어 있으면 안 된다
     */
    void stepsNeedRetry(Lease lease, DeletionStep ran, Map<DeletionStep, String> failures);

    /** {@link #hold} 뒤: 실행을 운영 데이터 삭제 완료로 끝낸다(시각은 DB 문장 시각, lease를 놓는다). */
    void operationalDataErased(Lease lease);

    /** lease를 놓고 실행을 {@code QUEUED}로 되돌린다(Worker 종료 drain). 세대가 다르면 아무것도 하지 않는다. */
    boolean release(Lease lease);

    /** 그 계정의 실행. 없으면 빈 값(아직 집기 전). 읽기만 한다. */
    Optional<DeletionRun> runOf(@Nullable UUID userId);

    /**
     * 다시 시도를 접수한다(트랜잭션 하나, 실행 행 잠금 아래). 실행이 {@code RETRY_NEEDED}면 키와 상관없이 {@code QUEUED}로 되돌리고
     * 그 키를 남긴다({@link RetryOutcome.Accepted}{@code (false)} — 같은 키의 재전송이라도 그 사이 다시 실패한 실행은 다시 돈다).
     * 재시도 필요가 아니면 같은 키는 재생({@code Accepted(true)}, 아무것도 쓰지 않음), 다른 키는 {@link RetryOutcome.NotRetryable}이다.
     *
     * @param retryKeyDigest 멱등 키의 SHA-256(소문자 16진 64자)
     */
    RetryOutcome acceptRetry(@Nullable UUID userId, String retryKeyDigest);

    // ------------------------------------------------------------------ Epic 5 회고 AI-1 운영자 재대기열

    /** 운영자 화면이 한 번에 받는 재시도 필요 실행의 상한. */
    int MAX_RETRY_NEEDED_RUNS = 200;

    /**
     * 요청 ID로 본 실행 하나(운영자 목록·미리보기). 사용자 식별자·소셜 식별자를 담지 않는다.
     *
     * @param requestedAt 삭제 요청 시각. 요청 행이 이미 지워졌으면(운영 완료 뒤 — {@link #runByRequest}의 끝난 실행) {@code null}.
     *     재시도 필요 목록({@link #retryNeededRuns})의 실행은 요청 행을 언제나 가져 비지 않는다
     * @param stoppedAt 멈춘 시각 — 재시도 필요 단계의 마지막 결과 시각의 최댓값. 재시도 필요 단계가 없으면 {@code null}
     * @param failedSteps 재시도 필요인 단계(순서대로). 각 단계의 실패 code와 시도 횟수를 든다
     */
    record RequestRun(String requestId, @Nullable Instant requestedAt, RunStatus status, @Nullable Instant stoppedAt,
            List<StepState> failedSteps) {

        public RequestRun {
            failedSteps = List.copyOf(failedSteps);
        }
    }

    /** 운영자 재대기열의 결과. */
    sealed interface OperatorRequeue {

        /**
         * 실행을 {@code QUEUED}로 되돌렸다.
         *
         * @param requeuedAt 접수 시각(DB 문장 시각)
         * @param retrySteps 다시 돌 재시도 필요 단계 수(단계 상태는 그대로다)
         */
        record Requeued(Instant requeuedAt, int retrySteps) implements OperatorRequeue {}

        /** 실행이 재시도 필요가 아니다(대기·실행 중·완료이거나 다른 트랜잭션이 그 행을 쥐었다). 아무것도 쓰지 않았다. */
        record NotRetryable() implements OperatorRequeue {}

        /** 그 요청 ID의 실행이 없다(요청이 없거나 아직 집기 전). 아무것도 쓰지 않았다. */
        record NotFound() implements OperatorRequeue {}
    }

    /**
     * 재시도 필요 실행 목록 한 번.
     *
     * @param runs 요청 시각 순, 많아야 {@link #MAX_RETRY_NEEDED_RUNS}
     * @param truncated 상한을 넘는 실행이 더 있어 앞쪽(오래된 순)만 담았다
     */
    record RetryNeededRuns(List<RequestRun> runs, boolean truncated) {

        public RetryNeededRuns {
            runs = List.copyOf(runs);
        }
    }

    /**
     * 재시도 필요로 멈춘 실행(요청 시각 순, 상한 {@link #MAX_RETRY_NEEDED_RUNS}과 잘림 여부). 읽기만 한다 — 실행 행과 실패 단계를 한
     * 스냅숏({@code REPEATABLE READ} 읽기 전용 트랜잭션)에서 읽어, 재시도 필요 실행이 빈 실패 단계로 보이는 일이 없다. 운영 command 화면이
     * 쓴다.
     */
    RetryNeededRuns retryNeededRuns();

    /**
     * 그 요청 ID의 실행 — <b>상태 무관</b>이다(재시도 필요가 아니면 {@link RequestRun#status}가 가른다). 없으면 빈 값. 읽기만 한다(목록과
     * 같은 한 스냅숏).
     */
    Optional<RequestRun> runByRequest(@Nullable String requestId);

    /**
     * 운영자 재대기열(호출자 트랜잭션에 참여한다). 그 요청 ID의 실행이 {@code RETRY_NEEDED}면 {@code QUEUED}로 되돌리고 운영자 키
     * digest를 남긴다 — 앞서 잠그지 않는 조건부 UPDATE 한 문장이고 다른 트랜잭션이 쥔 행은 건너뛴다({@code SKIP LOCKED}). 그래서
     * 단계 트랜잭션(실행 행 → 원장 순서)과 교착하지 않는다. 0행이면 잠그지 않는 읽기로 {@link OperatorRequeue.NotFound}(실행이 없거나, 끝나지
     * 않았는데 요청 행이 없다 — Worker가 집지 못한다)와 {@link OperatorRequeue.NotRetryable}(다른 상태이거나 다른 트랜잭션이 그 행을 쥐었다)을
     * 가른다. 단계 상태는 바꾸지 않는다. 키 digest를 덮어쓰므로 그 전 사용자 재시도 키의 재전송은 재시도 필요가 아님(409)이 된다.
     *
     * @param retryKeyDigest 운영자 재대기열 키의 SHA-256(소문자 16진 64자)
     */
    OperatorRequeue requeueByOperator(@Nullable String requestId, String retryKeyDigest);

    /**
     * 그 삭제 요청 ID들 가운데 운영 데이터 삭제가 끝난 실행의 완료 시각(Story 5.3). 읽기만 한다. 실행 행은 claim이 요청 ID를 채운다 —
     * 요청 행(V30)은 운영 완료 때 지워지므로 백업 파기 판정이 요청 ID ↔ 운영 완료 시각을 이 값으로 잇는다. 끝나지 않은 실행·없는 요청 ID는
     * 빠진다. 사용자 식별자를 돌려주지 않는다.
     */
    Map<String, Instant> operationalCompletions(Collection<String> requestIds);

    // ------------------------------------------------------------------ Story 5.4 전체 삭제 완료

    /**
     * 운영 데이터 삭제가 끝났고 아직 전체 삭제 완료가 아닌 실행의 요청 ID. 읽기만 한다 — 판정자가 그 가운데 백업 파기가 완료된 요청을 고른다
     * (복원으로 옮긴 tombstone의 요청도 여기서 다시 잡힌다).
     */
    List<String> awaitingDeletionCompletion();

    /**
     * 그 요청 ID들의 실행을 전체 삭제 완료로 기록한다(트랜잭션 하나, 시각은 DB 문장 시각). <b>호출자가 완료 조건을 확인한 요청만 넘긴다</b> —
     * 그 요청의 백업 파기 완료와 법정 분리 보존 처리. 운영 데이터 삭제 완료인 실행만 바뀐다(단조, 이미 완료면 아무것도 하지 않는다).
     *
     * @return 이번에 바꾼 실행 수
     */
    int recordDeletionCompleted(Collection<String> requestIds);

    /**
     * 끝난 삭제 실행의 시각들.
     *
     * @param deletionCompletedAt 전체 삭제 완료 시각. 아직이면 {@code null}
     */
    record CompletedDeletion(Instant operationalErasedAt, @Nullable Instant deletionCompletedAt) {}

    /** 그 요청 ID의 실행이 운영 데이터 삭제를 끝냈으면 그 시각들(완료 조회, Story 5.4). 아니면 빈 값. 사용자 식별자를 돌려주지 않는다. */
    Optional<CompletedDeletion> completedDeletionOf(@Nullable String requestId);
}
