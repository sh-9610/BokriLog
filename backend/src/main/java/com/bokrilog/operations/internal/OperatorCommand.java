package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.Accepted;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandApi.Finished;
import com.bokrilog.operations.OperatorCommandApi.Inspection;
import com.bokrilog.operations.OperatorCommandApi.Proposal;
import com.bokrilog.operations.OperatorCommandApi.Validation;
import com.bokrilog.operations.OperatorPermission;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 운영 command 하나의 전략(Epic 3 회고 AI-5). command마다 자기 몫만 정의한다 — 본문 해석, 검증, 준비 뒤 거절, 미리보기,
 * 확정 본문, 커밋 뒤 처리, 재전송 재구성, 응답 모양.
 *
 * <p><b>엔진({@link OperatorEnvelopeService})이 순서를 갖는다.</b> 무장·허가·멱등은 루트가, 확정의 트랜잭션 가드 → 원장
 * 추가 잠금 → 최종 권한 → {@link #complete} → {@code COMPLETED} 행은 엔진 템플릿 한 곳이 한다. 전략은 원장 잠금을 쥐거나
 * 트랜잭션을 확인하지 않는다 — {@code OperatorCommandRegistryTest}가 그 우회를 막는다. 새 command는 이 인터페이스의
 * 구현 하나와 {@link OperatorCommandRegistry}의 등록 한 줄이다.
 *
 * @param <A> 검증된 요청. 세션 무장에 실리므로 직렬화할 수 있어야 한다
 * @param <R> 확정 본문의 실행 결과. 커밋 뒤 처리에 그대로 되돌아온다
 */
interface OperatorCommand<A extends Serializable, R> {

    /** 이 전략이 받는 command 이름. 제한 생성 넷은 한 전략이 넷을 받는다. */
    Set<String> names();

    /** 이 command가 요구하는 권한. */
    Set<OperatorPermission> permissions();

    /** 검증된 요청의 타입. 무장에서 꺼낸 값을 확인해 되돌린다. */
    Class<A> payloadType();

    /** 실행 결과의 타입. */
    Class<R> resultType();

    /**
     * 준비 본문의 해석. 엔진이 공통 세 필드를 이미 읽었다({@code common}). 그 command의 필드가 있으면 더하고, 문자열이어야 할
     * 값이 문자열이 아니면 {@link IllegalArgumentException}(400)이다. 기본은 공통 제안 그대로다.
     */
    default Proposal parse(String command, JsonNode body, Proposal common) {
        return common;
    }

    /** envelope 검증. 수행자 주장은 엔진이 먼저 거절했다. */
    Validation validate(Proposal proposal);

    /**
     * 검증에서 거절된 제안의 원장 모양. 형식이 맞는 값만 남긴다 — 기본은 엔진의 일반 규칙({@code common})이다.
     */
    default RefusedLedger refusedLedger(Proposal proposal, RefusedLedger common) {
        return common;
    }

    /**
     * 거절 행에 남는 scope·target과 사유를 남겨도 되는가.
     *
     * @param keepReason 사유를 원문으로 남겨도 되는 규칙을 지났는가
     */
    record RefusedLedger(String scope, String target, boolean keepReason) {}

    /**
     * 준비 시각의 상태 거절과 미리보기(멱등 키·재인증 판정 뒤에 적용한다). 대상의 사실을 한 번 읽어 둘을 함께 정한다. 기본은
     * 거절도 미리보기도 없다.
     */
    default Inspection inspect(Accepted accepted, A payload) {
        return new Inspection.Ready(null);
    }

    /** {@code REQUESTED} 행의 {@code details}에 더할 증거. 기본은 없다. */
    default Map<String, Object> requestedDetails(A payload) {
        return Map.of();
    }

    /**
     * 확정 본문의 결말.
     *
     * @param details {@code COMPLETED} 행의 {@code details}. 엔진이 멱등 키 digest를 앞에 붙인다
     */
    record Done<R>(R result, Map<String, Object> details) {}

    /**
     * 확정 본문. 엔진 템플릿이 열린 트랜잭션·원장 추가 잠금·최종 권한을 마친 <b>뒤</b> 같은 트랜잭션에서 부른다. 확정 순간의
     * 상태를 다시 보고 쓴다. 거절은 {@link OperatorCommandApi.CommandRefusal}로 던진다. {@code COMPLETED} 행은 엔진이
     * 쓴다.
     *
     * @param engine 이 확정을 부른 엔진 빈 — 호출자가 받은 바로 그 빈이다(시험의 spy 포함). 전략은 엔진을 필드로 들지 않고
     *     세 단계(확정·커밋 뒤·재전송) 모두 이 인자로만 닿는다. 해제는 엔진의 해제 본문({@code completeLift})을 부른다
     */
    Done<R> complete(OperatorEnvelopeService engine, Envelope envelope, A payload);

    /** 허가에 저장하는 결과 요약(상한 4KiB). 식별자 원문·사유를 담지 않는다. */
    String grantRecord(R result);

    /** 확정이 거절로 끝났을 때 같은 code의 문장을 가르는 사실. 기본은 없다. */
    default @Nullable String refusalDetail(A payload) {
        return null;
    }

    /** 확정 트랜잭션 밖으로 나온 유일 색인 위반의 거절 code. 기본은 없다(실행 실패). */
    default @Nullable String duplicateKeyRefusal() {
        return null;
    }

    /**
     * 커밋 뒤의 처리와 응답.
     *
     * @param engine 이 command가 커밋 뒤에 부를 수 있는 엔진(되돌림·smoke 기록). 호출자가 받은 바로 그 빈이다
     */
    Finished afterCommit(OperatorEnvelopeService engine, String requestedCommand, Envelope envelope, R result,
            String correlationId);

    /**
     * 원장의 {@code COMPLETED} 행에서 확정 응답을 다시 만든다. 재생은 사실을 지어내지 않는다 — 행이 그 command의 모양이
     * 아니면 {@link IllegalStateException}이다.
     *
     * @param details 그 행의 {@code details}(JSON 객체)
     */
    Map<String, Object> replay(OperatorEnvelopeService engine, String requestedCommand, String operationId,
            String ledgerTarget, JsonNode details);
}
