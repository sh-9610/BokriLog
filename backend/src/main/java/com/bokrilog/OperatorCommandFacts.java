package com.bokrilog;

import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.operations.AccountErasureState;
import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperatorCommandApi.LiftExpectation;
import com.bokrilog.operations.OperatorCommandApi.LiftTarget;
import com.bokrilog.operations.OperatorCommandApi.Restriction;
import com.bokrilog.operations.OperatorCommandApi.TargetCheck;
import com.bokrilog.operations.OperatorCommandEnvironment;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;

/**
 * 운영 command가 operations 밖에서 얻는 사실({@link OperatorCommandEnvironment})의 루트 구현(Epic 3 회고 AI-5).
 *
 * <p>각 사실은 이미 있는 루트 부품이 소유한다 — 제한 대상·영향·해제 차원은 {@link RestrictionTargets}(각 모듈 공개 API),
 * 지원 연락처는 {@link SupportContact}(배포 설정), 문의 주인 계정의 상태는 삭제 시작 여부 port {@link AccountErasureState}다(판정식은 루트 구현
 * {@link IdentityAccountErasureState} 한 곳 — identity의 민감 작업 판정이 허용인가). 이
 * 클래스는 그것을 잇기만 하고 command를 가르지 않는다. 삭제 재대기열(Epic 5 회고 AI-1)의 목록·단건·재대기열은 identity
 * {@link AccountDeletionRunApi}, 환경 게이트는 사용자 삭제 요청과 같은 판정({@link AccountDeletionRequestService#available})이다. servlet 웹 애플리케이션에서만 배선한다(운영 command가 API
 * 프로세스에만 있다).
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
class OperatorCommandFacts implements OperatorCommandEnvironment {

    private final RestrictionTargets targets;

    private final SupportContact supportContact;

    private final AccountErasureState accounts;

    /**
     * 계정 삭제 실행의 상태(identity, Epic 5 회고 AI-1). 운영자 목록·재대기열이 읽고 쓰는 유일한 운영 호출 자리다 —
     * {@code ModuleBoundaryTest}가 {@code retryNeededRuns}·{@code runByRequest}·{@code requeueByOperator}의 호출자를 이 클래스로 고정한다.
     */
    private final AccountDeletionRunApi runs;

    /** 사용자 삭제 요청의 환경 게이트(production, G-5 전). 재대기열이 같은 판정을 쓴다. */
    private final AccountDeletionRequestService deletionRequests;

    OperatorCommandFacts(RestrictionTargets targets, SupportContact supportContact, AccountErasureState accounts,
            AccountDeletionRunApi runs, AccountDeletionRequestService deletionRequests) {
        this.targets = targets;
        this.supportContact = supportContact;
        this.accounts = accounts;
        this.runs = runs;
        this.deletionRequests = deletionRequests;
    }

    @Override
    public TargetCheck restrictionTarget(Restriction restriction) {
        return this.targets.check(restriction);
    }

    @Override
    public RestrictionImpact restrictionImpact(Restriction restriction, TargetCheck.Valid valid) {
        return this.targets.impact(restriction, valid);
    }

    @Override
    public LiftImpact liftImpact(UUID liftedRestrictionId, LiftTarget target) {
        return this.targets.liftImpact(liftedRestrictionId, target);
    }

    @Override
    public List<ActiveRestriction> remainingAfterLift(UUID liftedRestrictionId, LiftTarget target) {
        return this.targets.remaining(liftedRestrictionId, target);
    }

    @Override
    public LiftExpectation liftExpectation(LiftTarget target, List<ActiveRestriction> remaining) {
        return RestrictionTargets.expectation(target, remaining);
    }

    @Override
    public RestrictionSubject subjectOf(LiftTarget target) {
        return this.targets.subjectOf(target);
    }

    @Override
    public List<String> stages(RestrictionFeature feature, RestrictionScope scope) {
        return RestrictionTargets.stages(feature, scope);
    }

    @Override
    public @Nullable String supportContact() {
        return this.supportContact.value();
    }

    @Override
    public boolean accountUsable(UUID userId) {
        return !this.accounts.erasureStarted(userId);
    }

    @Override
    public boolean deletionRequeueAvailable() {
        return this.deletionRequests.available();
    }

    @Override
    public DeletionRunList retryNeededDeletions() {
        AccountDeletionRunApi.RetryNeededRuns found = this.runs.retryNeededRuns();
        return new DeletionRunList(found.runs().stream().map(OperatorCommandFacts::factsOf).toList(),
                found.truncated());
    }

    @Override
    public Optional<DeletionRunFacts> deletionRun(String requestId) {
        return this.runs.runByRequest(requestId).map(OperatorCommandFacts::factsOf);
    }

    @Override
    public DeletionRequeue requeueDeletion(String requestId, String retryKeyDigest) {
        return switch (this.runs.requeueByOperator(requestId, retryKeyDigest)) {
            case AccountDeletionRunApi.OperatorRequeue.Requeued requeued ->
                    new DeletionRequeue.Requeued(requeued.requeuedAt(), requeued.retrySteps());
            case AccountDeletionRunApi.OperatorRequeue.NotRetryable ignored -> new DeletionRequeue.NotRetryable();
            case AccountDeletionRunApi.OperatorRequeue.NotFound ignored -> new DeletionRequeue.NotFound();
        };
    }

    /** identity의 실행을 운영 command의 사실로 옮긴다 — 단계·실패 code·시도 수뿐이다(사용자 식별자 없음). */
    private static DeletionRunFacts factsOf(AccountDeletionRunApi.RequestRun run) {
        return new DeletionRunFacts(run.requestId(), run.requestedAt(),
                run.status() == AccountDeletionRunApi.RunStatus.RETRY_NEEDED, run.stoppedAt(),
                run.failedSteps().stream().filter(step -> step.failureCode() != null)
                        .map(step -> new FailedDeletionStep(step.step().name(), step.failureCode(), step.attempts()))
                        .toList());
    }
}
