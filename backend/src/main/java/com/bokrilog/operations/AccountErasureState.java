package com.bokrilog.operations;

import java.util.UUID;

/**
 * 계정의 삭제가 시작됐는가(Epic 3 회고 B-2). <b>operations가 소유하고 루트가 구현한다.</b>
 *
 * <p>강한 신호({@link OperationalSignalApi#raise})는 원장 잠금을 기다린 뒤 사용자 귀속 제한·신호 행을 쓴다. 그 사이에 같은
 * 사용자의 계정 삭제가 커밋됐으면 삭제의 비식별은 이미 지나갔으므로, 신호가 같은 트랜잭션에서 자기가 쓴 귀속을 즉시
 * 비식별해야 지워진 사용자의 원문 target·귀속이 되살아나지 않는다. 그 판정에 이 port를 쓴다.
 *
 * <p>operations는 identity를 모른다(모듈 경계). 그래서 그 사실은 identity 공개 API를 아는 루트가 답한다. 판독은 잠금 없는
 * 일반 조회다 — 원장 잠금 아래에서 identity 생명주기 행을 잡지 않는다. 신호가 모든 역할에서 돌므로 웹 조건 없이 배선한다.
 */
public interface AccountErasureState {

    /**
     * 그 계정의 삭제가 시작됐는가.
     *
     * @return 삭제가 시작됐거나(진행 중·완료) 그 계정이 없으면 {@code true}. 활성 계정이면 {@code false}
     */
    boolean erasureStarted(UUID userId);
}
