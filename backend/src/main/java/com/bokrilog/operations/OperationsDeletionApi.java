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
 * <p><b>문의(Story 3.6)는 다르다.</b> 문의 행(제목·본문·회신 이메일·답변)은 "사용자 귀속 데이터"이고 운영 사실이 아니다 —
 * 삭제의 답은 비식별이 아니라 행 삭제다({@link #eraseInquiries}). 멱등 기록은 문의 행 자체이므로 함께 사라진다. 문의를 처리한
 * 운영 command의 감사 행은 reference의 keyed digest만 담으므로 그대로 둔다.
 *
 * <p><b>원장 잠금과 직렬화한다(Epic 3 회고 B-2).</b> {@link #eraseActor}·{@link #eraseRestrictionTargets}는 감사 원장 추가
 * 잠금을 쥔 뒤 귀속 행을 읽고 비식별한다 — 같은 잠금 아래에서 사용자 귀속 제한을 쓰는 경로(제한 생성 command·강한 신호·해제
 * smoke 되돌림)와 한 줄로 선다. 호출자 트랜잭션이 있으면 거기에 참여해 그 커밋까지 쥐고, 없으면 자기 트랜잭션 안에서 쥔다.
 * <b>한계:</b> 이 직렬화가 덮는 것은 삭제보다 먼저 잠금을 쥔 쓰기뿐이다. 삭제가 커밋된 뒤 원장에 붙는 운영자 거절·실패
 * 행(롤백 뒤 별도 트랜잭션에서 append, 예: {@code ACCESS_REVOKED})은 삭제된 수행자의 {@code actor_user_id}를 여전히 실을 수
 * 있다 — 그 경로에 계정 상태 판정이 없고, 열린 결정으로 {@code deferred-work.md}에 남아 있다. 사용자 귀속 제한·신호의 늦은
 * 쓰기는 강한 신호가 계정 상태를 보고 스스로 비식별한다. {@link #eraseInquiries}는 이 잠금이 아니라 문의 접수의 사용자
 * 잠금으로 직렬화한다.
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

    /**
     * 그 사용자의 문의를 지운다(Story 3.6 AC-6) — 문의 행과 그 행이 곧 멱등 기록이다. 다른 사용자의 문의는 바뀌지 않는다.
     * 멱등이다.
     *
     * @return 이번에 지운 행 수. 이미 지웠으면 0
     */
    int eraseInquiries(@Nullable UUID userId);

    /**
     * 그 사용자에게 아직 귀속된 이 모듈의 행 수(Story 3.10 복원 검증). {@code operations_*}·{@code support_*} 표의 사용자 열
     * ({@code user_id}·{@code actor_user_id}·{@code target_user_id})을 카탈로그로 찾아 센다 — 표가 늘어도 빠지지 않는다. 삭제가 끝난
     * 계정이면 0이어야 한다(비식별은 귀속 열을 비우고, 문의는 지운다).
     */
    long remainingRowsOf(UUID userId);
}
