package com.bokrilog.identity;

import java.time.Duration;
import java.time.Instant;
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
 * 단계는 건너뛴다(모듈 port는 멱등이다).
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
        OPERATIONAL_DATA_ERASED
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
     * 단계 하나의 상태.
     *
     * @param erasedRows 성공한 단계가 지운(비식별한) 행 수. 성공이 아니면 {@code null}
     * @param failureCode {@link #STEP_FAILED}·{@link #RESIDUAL_FOUND}. 재시도 필요가 아니면 {@code null}
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
     * @param failures 단계 → {@link #STEP_FAILED}·{@link #RESIDUAL_FOUND}. 비어 있으면 안 된다
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
}
