package com.bokrilog.sync.internal;

import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import com.bokrilog.operations.SignalType;
import com.bokrilog.portfolio.PortfolioPointerInvariantViolation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * sync의 네 감지 지점이 강한 신호를 올리는 자리(Story 3.3). 대응표({@code spec-3-3} Boundaries)의 증거와 대상 차원을
 * 여기서 한 번만 정한다 — 어느 scope로 막을지는 판정표가 정하고, 이 클래스는 감지 지점이 가진 차원만 넘긴다.
 *
 * <table>
 *   <tr><th>신호</th><th>증거</th><th>대상 차원</th></tr>
 *   <tr><td>4 다른 result hash</td><td>앞서 기록된 계산</td><td>계산 정책 버전</td></tr>
 *   <tr><td>5 CAS 불변식</td><td>작업</td><td>portfolio</td></tr>
 *   <tr><td>6 source ID 의미 충돌</td><td>수집 작업·관측 행</td><td>계획(계획 작업일 때)·연결</td></tr>
 *   <tr><td>7 미인식 enum</td><td>수집 작업·관측 행</td><td>연결</td></tr>
 * </table>
 *
 * <p><b>실패의 두 갈래.</b> 판정표가 거절한 신호(필수 증거 누락)는 {@link IllegalArgumentException} 그대로 올라가 그 작업을
 * 실패로 끝낸다. 그 밖의 기록 실패는 {@link Unraised}로 바꿔 올린다 — 감지 지점은 그것을 받으면 작업을 끝내지 않고 lease
 * 규칙에 맡긴다(fail-closed): 실패로 끝내 버리면 신호가 기록되지 않은 채 사건이 닫힌다. 다시 잡힌 작업이 같은 실패를
 * {@link #UNRECORDED_ATTEMPTS}번째 세대까지 되풀이하면({@link #exhausted}) 감지 지점이 fail-closed 종단 code로 맺는다 —
 * 무한히 다시 잡히지 않게.
 *
 * <p><b>자기 제한.</b> 신호를 낸 작업은 자기 신호가 만들거나 연결한 제한({@link #ownRestrictions})에만 막히지 않는다 — 그
 * 작업은 검토 필요로만 맺고 아무것도 발행하지 않는다. 운영자 제한·다른 신호의 제한과 계정·연결·계획 재검사는 그대로다.
 */
@Component
class StrongSignals {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StrongSignals.class);

    /** 신호를 기록하지 못한 작업이 몇 번째 세대에서 fail-closed로 맺는가. */
    static final int UNRECORDED_ATTEMPTS = 3;

    /** 그 상한에서 작업을 맺는 code. */
    static final String SIGNAL_UNRECORDED = "SIGNAL_UNRECORDED";

    private final OperationalSignalApi signals;

    StrongSignals(OperationalSignalApi signals) {
        this.signals = signals;
    }

    /**
     * 신호를 기록하지 못했다. 메시지·원인·stack trace가 없다 — 감지 지점은 작업을 끝내지 않고 돌아간다. 원인 종류는 여기서
     * 한 번 WARN으로 남긴다.
     */
    static final class Unraised extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unraised() {
            super(null, null, false, false);
        }
    }

    /** 같은 입력·정책의 다른 result hash. 대상은 그 계산 정책 버전이다. */
    void resultHashMismatch(PlanPublicationRepository.ResultHashMismatch mismatch, UUID userId, UUID jobId) {
        raise(new Signal(SignalType.RESULT_HASH_MISMATCH,
                RestrictionSubject.none().withCalculationPolicy(mismatch.calculationPolicy()), userId,
                evidence("calculationId", mismatch.calculationId(), "jobId", jobId), mismatch.detectedAt()));
    }

    /** 행 잠금 아래의 portfolio 포인터 CAS 실패. 대상은 그 portfolio다. */
    void portfolioInvariant(PortfolioPointerInvariantViolation violation, UUID jobId) {
        raise(new Signal(SignalType.PORTFOLIO_INVARIANT_VIOLATION,
                RestrictionSubject.none().withPortfolio(violation.portfolioId()), violation.userId(),
                evidence("jobId", jobId), null));
    }

    /**
     * 커밋된 수집 페이지의 강한 관측들(신호 6·7). 신호 종류마다 {@code raise} 한 번으로 묶는다 — 증거는 첫 관측 행과 관측 수다.
     * 계획 작업이면 계획과 그 시도를 함께 넘긴다: 의미 충돌은 그 계획의 발행을, 계획 없는 초기 수집이면 그 연결을 막는다
     * (판정표가 고른다). 페이지 트랜잭션이 커밋된 <b>뒤</b> 부른다 — 증거가 커밋된 관측 행을 가리킨다.
     *
     * @param planId 증분 동기화·검증의 계획. 초기 수집이면 {@code null}
     * @param attemptId 그 계획 동기화 시도. 초기 수집이면 {@code null}
     */
    void observations(List<InitialCollectionRepository.StrongObservation> observed, UUID userId,
            UUID collectionJobId, UUID connectionId, @Nullable UUID planId, @Nullable UUID attemptId) {
        Map<SignalType, List<InitialCollectionRepository.StrongObservation>> byType = new LinkedHashMap<>();
        for (var observation : observed) {
            byType.computeIfAbsent(observation.type(), type -> new java.util.ArrayList<>()).add(observation);
        }
        for (var entry : byType.entrySet()) {
            RestrictionSubject subject = RestrictionSubject.none().withConnection(connectionId);
            if (entry.getKey() == SignalType.SOURCE_ID_CONFLICT) {
                subject = subject.withPlan(planId);
            }
            var first = entry.getValue().getFirst();
            Map<String, String> evidence = evidence("jobId", collectionJobId, "observationId", first.observationId(),
                    "observationCount", entry.getValue().size());
            if (attemptId != null) {
                evidence.put("attemptId", attemptId.toString());
            }
            raise(new Signal(entry.getKey(), subject, userId, evidence, first.observedAt()));
        }
    }

    /**
     * 그 작업(초기 수집 작업이나 계획 동기화 시도)을 증거로 가진 신호가 만들거나 연결한 제한. 그 작업의 제한 판독에서만 뺀다.
     * 판독 오류는 예외다(fail-closed).
     */
    java.util.Set<UUID> ownRestrictions(UUID jobId) {
        return this.signals.restrictionsRaisedBy(jobId);
    }

    /** 신호를 기록하지 못한 작업의 이 세대가 상한에 닿았는가. */
    static boolean exhausted(long generation) {
        return generation >= UNRECORDED_ATTEMPTS;
    }

    /** 상한에서 작업을 맺을 때의 한 줄. 식별자 없이 작업 종류만. */
    static void logUnrecordedEnding(String jobKind) {
        LOG.error("strong signal unrecorded {} times: job={} ended fail-closed", UNRECORDED_ATTEMPTS, jobKind);
    }

    /** {@link #portfolioInvariant}·{@link #resultHashMismatch}의 대상이 되는 실패인가. */
    static boolean isStrong(Throwable failure) {
        return failure instanceof PortfolioPointerInvariantViolation
                || failure instanceof PlanPublicationRepository.ResultHashMismatch;
    }

    /**
     * 발행 트랜잭션이 {@link #isStrong} 실패로 롤백된 <b>뒤</b> 그 신호를 올린다(순서: 롤백 → 신호 커밋). 다른 실패는 아무것도
     * 하지 않는다.
     */
    void raiseFor(RuntimeException failure, UUID userId, UUID jobId) {
        if (failure instanceof PortfolioPointerInvariantViolation violation) {
            portfolioInvariant(violation, jobId);
        } else if (failure instanceof PlanPublicationRepository.ResultHashMismatch mismatch) {
            resultHashMismatch(mismatch, userId, jobId);
        }
    }

    private void raise(Signal signal) {
        try {
            this.signals.raise(signal);
        } catch (IllegalArgumentException refused) {
            // 판정표가 거절했다(필수 증거 누락 등). 감지 지점의 배선 결함이라 작업은 실패로 끝난다.
            throw refused;
        } catch (RuntimeException failure) {
            // 메시지·원인은 남기지 않는다 — SQL이 실린다. 종류만.
            LOG.atWarn().addKeyValue("exception", failure.getClass().getSimpleName())
                    .log("strong signal not recorded: signal={}", signal.type().name());
            throw new Unraised();
        }
    }

    private static Map<String, String> evidence(Object... pairs) {
        Map<String, String> evidence = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            evidence.put((String) pairs[index], pairs[index + 1].toString());
        }
        return evidence;
    }
}
