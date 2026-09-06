package com.bokrilog.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 소유권과 삭제 상태에 따른 접근 판정.
 *
 * <p>다른 모듈이 "이 요청 주체가 이 계정의 데이터에 접근해도 되는가"를 묻는 유일한 곳이다.
 * 각 모듈이 자기 소유권 검사를 새로 만들거나 identity 테이블을 직접 읽어 삭제 상태를 재구성하지
 * 않는다. 클라이언트가 보낸 소유자 ID는 권위가 아니며, 여기서 판정하는 {@code callerUserId}는
 * 인증 세션이 결정한 값이어야 한다.
 */
public interface AccountAccessApi {

    /**
     * 접근을 판정한다.
     *
     * <p>어떤 입력에도 예외를 던지지 않는다. 존재하지 않는 대상, 타 사용자 대상, 삭제된 대상,
     * 그리고 {@code null} 입력까지 모두 {@link AccessDecision#DENIED} 하나로 답한다. 이 값에서
     * 대상 계정의 존재 여부를 복원할 수 없다는 것이 이 API의 계약이다.
     *
     * @param callerUserId 인증 세션이 결정한 요청 주체
     * @param targetUserId 접근하려는 계정
     * @param kind 접근의 종류
     * @return 허용 또는 거부
     */
    AccessDecision decide(
            @Nullable UUID callerUserId, @Nullable UUID targetUserId, @Nullable AccountAccessKind kind);
}
