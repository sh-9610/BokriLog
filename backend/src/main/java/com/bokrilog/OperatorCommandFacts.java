package com.bokrilog;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;

/**
 * 운영 command가 operations 밖에서 얻는 사실({@link OperatorCommandEnvironment})의 루트 구현(Epic 3 회고 AI-5).
 *
 * <p>각 사실은 이미 있는 루트 부품이 소유한다 — 제한 대상·영향·해제 차원은 {@link RestrictionTargets}(각 모듈 공개 API),
 * 지원 연락처는 {@link SupportContact}(배포 설정), 문의 주인 계정의 상태는 identity의 {@link AccountAccessApi}다. 이
 * 클래스는 그것을 잇기만 하고 command를 가르지 않는다. servlet 웹 애플리케이션에서만 배선한다(운영 command가 API
 * 프로세스에만 있다).
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
class OperatorCommandFacts implements OperatorCommandEnvironment {

    private final RestrictionTargets targets;

    private final SupportContact supportContact;

    private final AccountAccessApi accounts;

    OperatorCommandFacts(RestrictionTargets targets, SupportContact supportContact, AccountAccessApi accounts) {
        this.targets = targets;
        this.supportContact = supportContact;
        this.accounts = accounts;
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
        return this.accounts.decide(userId, userId, AccountAccessKind.SENSITIVE) == AccessDecision.ALLOWED;
    }
}
