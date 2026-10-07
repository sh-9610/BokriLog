package com.bokrilog.operations;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * operations 모듈의 deletion port(Story 3.1 AC-8).
 *
 * <p><b>분류.</b> 감사 원장의 OPERATOR 행은 "사용자 귀속 운영 데이터"이고 SYSTEM 행은 "순수 운영 데이터"다. 법정 분리
 * 보존 후보 필드는 외부 승인(G-5) 전까지 없다(개발·CI 기본값 "없음"). 그래서 삭제의 답은 하나다 — 그 사용자의
 * actor 귀속만 비식별하고(행의 {@code actor_user_id}를 비우고 {@code actor_erased_at}을 남긴다) keyed digest와 사실은
 * 둔다. 다른 사용자의 행과 SYSTEM 행은 바뀌지 않는다. 행을 지우지 않는다 — 원장은 추가 전용이다.
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
}
