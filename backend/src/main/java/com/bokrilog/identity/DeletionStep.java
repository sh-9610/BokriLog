package com.bokrilog.identity;

/**
 * 계정 삭제의 단계(Story 5.2). <b>선언 순서가 실행 순서다</b> — 이 enum이 단계 순서의 유일한 목록이고, 단계 모드(Worker, 단계마다 한
 * 트랜잭션)와 단일 모드(리허설 삭제 표본·복원 적용, 한 트랜잭션)가 같은 목록을 돈다. 각 단계가 무엇을 지우는지는 루트 orchestrator
 * ({@code AccountErasure})가 소유한다 — identity는 단계 code와 그 상태만 기록한다(V31 {@code identity_deletion_step}의 CHECK와 한 쌍).
 *
 * <ul>
 *   <li>{@link #CREDENTIALS} — Bybit 자격정보와 소셜 로그인 연결. 가장 먼저 지운다 — 그 뒤로는 어떤 Worker도 거래소에 서명 호출을 할 수
 *       없고 같은 소셜 식별자로 이 계정에 닿을 수 없다.
 *   <li>{@link #PORTFOLIO}·{@link #SYNC}·{@link #PLAN}·{@link #SOURCE} — 외래키가 {@code ON DELETE RESTRICT}라 이 순서다.
 *   <li>{@link #OPERATIONS} — 감사 수행자·제한 target 비식별과 문의 삭제. 감사 원장 추가 잠금을 쥐므로 sync 단계 뒤다.
 *   <li>{@link #ENGAGEMENT} — 광고 동의·광고 자격 결정.
 *   <li>{@link #IDENTITY} — 요청 행·세션·계정 payload 삭제, tombstone 기록, {@code DELETED} 전환. 모든 모듈이 {@code identity_account}를
 *       가리키므로 마지막이다.
 * </ul>
 */
public enum DeletionStep {
    CREDENTIALS,
    PORTFOLIO,
    SYNC,
    PLAN,
    SOURCE,
    OPERATIONS,
    ENGAGEMENT,
    IDENTITY;

    /** 저장되는 순서 값(1부터). V31의 {@code position}이다. */
    public int position() {
        return ordinal() + 1;
    }
}
