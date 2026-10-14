package com.bokrilog.operations;

import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.OperatorCommandApi.LiftExpectation;
import com.bokrilog.operations.OperatorCommandApi.LiftTarget;
import com.bokrilog.operations.OperatorCommandApi.Restriction;
import com.bokrilog.operations.OperatorCommandApi.TargetCheck;
import java.time.Instant;
import java.util.List;
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
}
