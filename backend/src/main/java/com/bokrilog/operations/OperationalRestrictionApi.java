package com.bokrilog.operations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 지금 유효한 운영 제한의 판독(Story 3.2). 제한 하나는 {@code 기능 × scope × target}이다.
 *
 * <p><b>판독은 evaluator 하나다</b>({@link #evaluate}). 검사 지점은 막을 기능의 집합과 자기가 가진 대상 차원
 * ({@link RestrictionSubject})만 넘긴다. 차단은 일치하는 행의 합집합이고, 돌려주는 effective 제한은 그중 가장 구체적인
 * 행이다({@link RestrictionScope#specificity()}) — 구체 우선은 차단을 줄이지 않고 어느 제한이 효력을 갖는지 보고할 뿐이다.
 *
 * <p><b>생성은 이 인터페이스에 없다.</b> 제한 행은 감사 행과 같은 트랜잭션에서만 생겨야 하므로 운영 command({@link
 * OperatorCommandApi#completeRestriction})와 강한 신호({@link OperationalSignalApi#raise}, Story 3.3)가 만든다. 해제는
 * Story 3.4다.
 *
 * <p>시각의 권위는 데이터베이스다. 유효 구간 판정을 애플리케이션 시계로 하지 않는 이유는 {@code V3__action_grant.sql}
 * 머리말과 같다 — 인스턴스마다 값이 다르고 뒤로 갈 수 있다. 판독 오류는 값이 아니라 예외로 올라가 접수·발행 전체를
 * 실패시킨다(fail-closed) — 조회 실패를 "제한 없음"으로 접으면 그 순간 제한이 무의미해진다.
 */
public interface OperationalRestrictionApi {

    /**
     * 효력 중인 제한 하나.
     *
     * @param target 원 target. 계정 삭제로 비식별됐으면 {@code null}이고 {@code targetErased}가 참이다
     * @param effectiveFrom 효력 시작(DB 시각)
     * @param reason 그 제한을 만든 운영 command의 사유. 운영 command가 만들지 않은 옛 행은 {@code null}
     * @param actorDigest 그 command 수행자의 keyed digest. 옛 행은 {@code null}. 신호가 만든 행은 SYSTEM digest다
     * @param origin 그 제한을 만든 출처(Story 3.3). 신호가 만든 행은 사유가 신호 종류 code다
     * @param linkedSignals 이 제한을 만들었거나 이 제한에 연결된 강한 신호 수(Story 3.3). 하나라도 있으면 출처와 무관하게
     *     수동 검증이 필요하다({@link #requiresVerification})
     */
    record ActiveRestriction(UUID restrictionId, RestrictionFeature feature, RestrictionScope scope,
            @Nullable String target, boolean targetErased, Instant effectiveFrom, @Nullable String reason,
            @Nullable String actorDigest, RestrictionOrigin origin, int linkedSignals) {

        /** 강한 신호가 만들었거나 연결된 제한인가 — 해제(Story 3.4) 전에 수동 검증이 필요하다. */
        public boolean requiresVerification() {
            return this.origin == RestrictionOrigin.SIGNAL || this.linkedSignals > 0;
        }
    }

    /**
     * 그 기능들 중 하나라도 그 대상에 지금 막혀 있으면 효력을 갖는 가장 구체적인 제한. 없으면 빈 값이다.
     *
     * @param features 비어 있으면 안 된다 — 아무것도 묻지 않는 판독은 배선 실수다
     */
    default Optional<ActiveRestriction> evaluate(Set<RestrictionFeature> features, RestrictionSubject subject) {
        return evaluate(features, subject, Set.of());
    }

    /**
     * {@link #evaluate}에서 그 제한들만 빼고 본다(Story 3.3). 강한 신호를 낸 작업이 <b>자기 신호가 만들거나 연결한 제한</b>
     * ({@link OperationalSignalApi#restrictionsRaisedBy})에 막혀 검토 기록을 반쪽으로 남기지 않게 하는 데만 쓴다 — 운영자
     * 제한·다른 신호의 제한은 그대로 막는다.
     */
    Optional<ActiveRestriction> evaluate(Set<RestrictionFeature> features, RestrictionSubject subject,
            Set<UUID> excluding);

    /** {@link #evaluate}가 무언가를 돌려주는가. 검사 지점은 대개 이것만 본다. */
    default boolean blocks(Set<RestrictionFeature> features, RestrictionSubject subject) {
        return evaluate(features, subject).isPresent();
    }

    /** {@link #evaluate(Set, RestrictionSubject, Set)}의 {@link #blocks}. */
    default boolean blocks(Set<RestrictionFeature> features, RestrictionSubject subject, Set<UUID> excluding) {
        return evaluate(features, subject, excluding).isPresent();
    }

    /** 지금 효력 중인 모든 제한. 구체 우선, 같은 구체성 안에서는 먼저 시작한 순이다({@link #evaluate}와 같은 규칙). */
    List<ActiveRestriction> active();

    /** 같은 기능·scope·target의 효력 중인 제한이 이미 있는가. 생성 command의 중복 판정이다. */
    boolean isActive(RestrictionFeature feature, RestrictionScope scope, String target);
}
