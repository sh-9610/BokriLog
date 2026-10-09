package com.bokrilog.operations;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * operations 모듈의 deletion port(Story 3.1 AC-8, Story 3.2 AC-6).
 *
 * <p><b>분류.</b> 감사 원장의 OPERATOR 행은 "사용자 귀속 운영 데이터"이고 SYSTEM 행은 "순수 운영 데이터"다. 운영 제한의
 * 계획·portfolio·연결 target도 "사용자 귀속 운영 데이터"이며 전역·계산 정책 버전 제한은 "순수 운영 데이터"다. 법정 분리
 * 보존 후보 필드는 외부 승인(G-5) 전까지 없다(개발·CI 기본값 "없음"). 그래서 삭제의 답은 비식별이다 — 귀속만 비우고 keyed
 * digest와 사실은 둔다. 다른 사용자의 행과 SYSTEM·전역 행은 바뀌지 않는다. 행을 지우지 않는다 — 원장은 추가 전용이고,
 * 제한 행을 지우면 그 제한이 풀린다.
 *
 * <p>실제 계정 삭제 요청과의 통합(orchestration)은 Epic 5가 한다. 이 port는 그때 부를 모듈 경계다.
 */
public interface OperationsDeletionApi {

    /**
     * 그 사용자의 actor 귀속을 비식별한다. 멱등이다.
     *
     * @return 이번에 비식별한 행 수. 이미 비식별했으면 0
     */
    int eraseActor(@Nullable UUID userId);

    /**
     * 그 사용자에게 귀속된 제한 target을 비식별한다 — target을 keyed digest로 바꾸고 {@code target_user_id}를 비우며 비식별
     * 시각을 남긴다. 제한 자체(기능·scope·효력 구간)는 그대로다. 강한 신호 행(Story 3.3 AC-6)의 {@code target_user_id}도
     * 비운다 — 신호 행의 증거는 내부 참조뿐이고 fingerprint는 keyed digest의 hash라 다른 원식별자가 없다. 멱등이다.
     *
     * @return 이번에 비식별한 행 수(제한 행과 신호 행의 합). 이미 비식별했으면 0
     * @throws IllegalStateException 비식별할 행이 있는데 digest 키를 쓸 수 없는 역할에서 불렀을 때
     */
    int eraseRestrictionTargets(@Nullable UUID userId);
}
