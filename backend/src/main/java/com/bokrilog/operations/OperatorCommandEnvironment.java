package com.bokrilog.operations;

import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperatorCommandApi.LiftExpectation;
import com.bokrilog.operations.OperatorCommandApi.LiftTarget;
import com.bokrilog.operations.OperatorCommandApi.Restriction;
import com.bokrilog.operations.OperatorCommandApi.TargetCheck;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 운영 command가 이 모듈 밖에서 얻는 사실(Epic 3 회고 AI-5). <b>루트가 구현한다</b> — {@code PlanPublicationPort}와 같은
 * 관용이다.
 *
 * <p>operations는 다른 모듈을 모른다. 제한 대상(계획·portfolio·연결)이 있는지·누구에게 귀속되는지, 해제가 막던 대상
 * 차원, 지원 연락처 설정, 문의 주인 계정의 상태는 각 모듈·배포 설정의 사실이라 루트가 각 공개 API로 본다. command 전략은
 * 이 인터페이스로만 그 사실에 닿는다 — 루트의 흐름은 command를 가르지 않는다.
 */
public interface OperatorCommandEnvironment {

    /**
     * 제한 생성의 영향 미리보기(Story 3.2). 수만 담는다 — 식별자도 금융값도 없다.
     *
     * @param blockedStages 막히는 단계 code
     * @param affectedPlans 영향을 받는 계획 수
     * @param affectedConnections 영향을 받는 연결 수
     * @param remainingFeatures 이 제한 뒤에도 전역으로 막히지 않은 기능
     */
    record RestrictionImpact(List<String> blockedStages, long affectedPlans, long affectedConnections,
            List<String> remainingFeatures) {}

    /**
     * 해제의 영향 미리보기(Story 3.4). 식별자를 담지 않는다 — 남는 제한은 기능·scope·출처뿐이다.
     *
     * @param reopenedStages 해제로 다시 열리는 단계 code
     * @param remainingRestrictions 해제 뒤에도 그 대상의 같은 단계를 막는 다른 활성 제한
     * @param linkedSignals 그 제한을 만들었거나 그것에 연결된 강한 신호 수
     * @param latestSignalAt 그 신호들의 최신 감지 시각(DB). 신호가 없으면 {@code null}
     */
    record LiftImpact(List<String> reopenedStages, List<Remaining> remainingRestrictions, int linkedSignals,
            @Nullable Instant latestSignalAt) {}

    /** 남는 제한 하나. */
    record Remaining(String feature, String scope, String origin) {}

    /** 그 제한의 대상이 지금 있고 상태가 맞는가, 그리고 누구에게 귀속되는가(Story 3.2). */
    TargetCheck restrictionTarget(Restriction restriction);

    /** 제한 생성의 영향 미리보기. 대상 확인을 지난 제한에만 부른다. */
    RestrictionImpact restrictionImpact(Restriction restriction, TargetCheck.Valid valid);

    /** 해제의 영향 미리보기(Story 3.4). 준비가 열린 제한에만 부른다. */
    LiftImpact liftImpact(UUID liftedRestrictionId, LiftTarget target);

    /** 해제 뒤에도 그 대상의 단계를 막는 다른 활성 제한(Story 3.4). evaluator와 같은 술어·순서다. */
    List<ActiveRestriction> remainingAfterLift(UUID liftedRestrictionId, LiftTarget target);

    /** 해제 뒤 기대하는 단계: 해제한 제한의 단계 가운데 남는 제한이 막지 않는 것과 여전히 막는 것. */
    LiftExpectation liftExpectation(LiftTarget target, List<ActiveRestriction> remaining);

    /** 해제 대상이 막던 대상 차원(Story 3.4). smoke가 이 차원으로 evaluator를 다시 읽는다. */
    RestrictionSubject subjectOf(LiftTarget target);

    /** 그 기능·scope의 제한이 막는 단계 code. */
    List<String> stages(RestrictionFeature feature, RestrictionScope scope);

    /** 공지 발행의 지원 연락처(Story 3.5). 비어 있으면 {@code null}이고 어떤 공지도 발행하지 않는다. */
    @Nullable String supportContact();

    /** 그 계정을 지금 쓸 수 있는가(Story 3.6) — 삭제가 시작된 계정의 문의에는 답변을 쓰지 않는다. */
    boolean accountUsable(UUID userId);

    // ------------------------------------------------------------------ Epic 5 회고 AI-1 삭제 재대기열

    /** 재시도 필요 단계 하나. 단계 code·실패 code·시도 수뿐이다. */
    record FailedDeletionStep(String step, String failureCode, int attempts) {}

    /**
     * 요청 ID로 본 계정 삭제 실행. 사용자 식별자·소셜 식별자를 담지 않는다.
     *
     * @param requestedAt 삭제 요청 시각. 요청 행이 이미 지워졌으면(끝난 실행) {@code null}. 목록의 실행은 언제나 있다
     * @param retryNeeded 실행이 지금 재시도 필요로 멈춰 있는가
     * @param stoppedAt 재시도 필요 단계의 마지막 결과 시각. 없으면 {@code null}
     */
    record DeletionRunFacts(String requestId, @Nullable Instant requestedAt, boolean retryNeeded,
            @Nullable Instant stoppedAt, List<FailedDeletionStep> failedSteps) {

        public DeletionRunFacts {
            failedSteps = List.copyOf(failedSteps);
        }
    }

    /**
     * 재시도 필요 실행 목록 한 번.
     *
     * @param truncated 상한을 넘는 실행이 더 있어 앞쪽(오래된 순)만 담았다
     */
    record DeletionRunList(List<DeletionRunFacts> runs, boolean truncated) {

        public DeletionRunList {
            runs = List.copyOf(runs);
        }
    }

    /** 재대기열의 결과. */
    sealed interface DeletionRequeue {

        /** {@code QUEUED}로 되돌렸다. {@code retrySteps}는 다시 돌 재시도 필요 단계 수다. */
        record Requeued(Instant requeuedAt, int retrySteps) implements DeletionRequeue {}

        /** 재시도 필요가 아니다. 아무것도 쓰지 않았다. */
        record NotRetryable() implements DeletionRequeue {}

        /** 그 요청 ID의 실행이 없다. 아무것도 쓰지 않았다. */
        record NotFound() implements DeletionRequeue {}
    }

    /** 삭제 재대기열을 열 수 있는 환경인가 — production(G-5 전)이 아니다. 사용자 삭제 요청의 게이트와 같은 판정이다. */
    boolean deletionRequeueAvailable();

    /** 재시도 필요로 멈춘 실행(요청 시각 순, 상한까지)과 잘림 여부. 읽기만 한다. */
    DeletionRunList retryNeededDeletions();

    /** 그 요청 ID의 실행(상태 무관). 없으면 빈 값. 읽기만 한다. */
    Optional<DeletionRunFacts> deletionRun(String requestId);

    /**
     * 그 요청 ID의 재시도 필요 실행을 다시 대기열에 넣는다. <b>호출자 트랜잭션 안에서</b> — 엔진의 원장 잠금·최종 권한 뒤 — 앞서
     * 잠그지 않는 조건부 UPDATE 한 문장으로 쓴다.
     *
     * @param retryKeyDigest 운영자 전용 접두사의 재대기열 키 SHA-256
     */
    DeletionRequeue requeueDeletion(String requestId, String retryKeyDigest);
}
