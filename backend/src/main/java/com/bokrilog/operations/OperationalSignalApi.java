package com.bokrilog.operations;

import java.util.List;
import java.util.UUID;

/**
 * 강한 신호의 자동 정지(Story 3.3). 감지 지점이 부르는 {@link #raise} 하나다.
 *
 * <p><b>동기·별도 트랜잭션이다.</b> 백그라운드 detector 없이 감지 순간에 부르고, {@link #raise}는 호출자의 트랜잭션과 무관한
 * 자기 트랜잭션({@code REQUIRES_NEW}) 하나를 커밋한 뒤 돌아온다. 그 한 커밋에 원장 잠금 → 판정표 읽기 → 중복 판정 → 제한
 * INSERT({@code origin='SIGNAL'}) → 신호 행 → 감사 SYSTEM 행이 들어가고, 커밋 직후 경보 WARN 한 줄이 남는다. 그래서 감지
 * 지점의 트랜잭션이 롤백돼도 신호는 사라지지 않고, 돌아온 순간부터 evaluator({@link OperationalRestrictionApi#evaluate})가
 * 차단을 본다.
 *
 * <p><b>판정은 판정표가 한다(AC-1).</b> 판정표 {@code OPS-SIGNAL-1.0}의 행이 scope 규칙·필수 증거·threshold·window를 정한다.
 * 필수 증거가 빠졌거나 창 집계가 threshold에 못 미치는 신호는 승격하지 않고 {@link IllegalArgumentException}으로 거절한다
 * — 아무것도 쓰지 않는다. 판정표를 읽지 못하거나 그 행이 없으면 코드의 최소 필수 증거({@link
 * SignalType#requiredEvidence})만 본 뒤 신규 연결+동기화 전역으로 제한한다({@code POLICY_UNAVAILABLE}은 판정 이유이고 감사
 * 사유는 여전히 신호 종류다, AC-5의 fail-closed).
 *
 * <p><b>중복(AC-3).</b> 같은 기능·scope·target의 활성 제한이 있으면 새 제한 없이 신호 행만 그 제한에 연결한다({@code
 * LINKED}) — 출처(운영자·신호)와 무관하다. 3.2의 부분 유일 색인이 둘째 활성 행을 허용하지 않기 때문이다. 더 넓거나 좁은
 * 신호는 각자 제한을 더하고 차단은 합집합이다. 신호는 제한을 지우거나 줄이지 못하며 해제 경로는 없다(Story 3.4).
 *
 * <p>실패는 예외로 올라간다(fail-closed). 감지 지점은 그 예외로 작업을 정상 완료하지 않는다.
 */
public interface OperationalSignalApi {

    /** 현행 판정표 버전. */
    String POLICY_VERSION = "OPS-SIGNAL-1.0";

    /** 감사 원장의 command. */
    String AUDIT_COMMAND = "auto-restrict";

    /** 판정표를 읽지 못해 전역으로 접었을 때의 판정 이유(신호 행 {@code scope_decision}·감사 {@code details}). */
    String POLICY_UNAVAILABLE = "POLICY_UNAVAILABLE";

    /** 판정 하나의 결과. */
    enum Outcome {
        /** 새 제한을 만들었다. */
        CREATED,
        /** 같은 기능·scope·target의 활성 제한에 연결했다. */
        LINKED
    }

    /**
     * 신호가 판정한 {@code <기능>:<scope>} 하나.
     *
     * @param sliMs 감지 시각부터 제한 효력 시각까지(ms, DB 시각, 0 이상). 연결했으면 그 제한의 효력 시작 기준이다
     */
    record Decision(UUID signalId, RestrictionFeature feature, RestrictionScope scope, UUID restrictionId,
            Outcome outcome, long sliMs) {}

    /** 신호 하나의 기록. */
    record Raised(String operationId, List<Decision> decisions) {}

    /**
     * 신호를 기록하고 판정된 scope를 제한한다. 돌아오면 커밋돼 있다.
     *
     * @throws IllegalArgumentException 필수 증거가 빠졌거나 threshold·window를 채우지 못한 신호 — 아무것도 쓰지 않았다
     */
    Raised raise(Signal signal);

    /**
     * 그 작업을 증거로 가진 신호가 만들었거나 연결한 제한들(증거 {@code jobId} 또는 {@code attemptId}가 그 값인 신호 행).
     * 신호를 낸 작업이 자기 제한에 막혀 검토 기록을 반쪽으로 남기지 않게 판독에서 빼는 데만 쓴다({@link
     * OperationalRestrictionApi#evaluate(java.util.Set, RestrictionSubject, java.util.Set)}). 판독 오류는 예외다.
     */
    java.util.Set<UUID> restrictionsRaisedBy(UUID jobId);
}
