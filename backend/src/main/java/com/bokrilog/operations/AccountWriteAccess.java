package com.bokrilog.operations;

import java.util.UUID;

/**
 * 계정의 쓰기 접근을 생명주기 잠금 아래에서 다시 본다(Story 5.2). <b>operations가 소유하고 루트가 구현한다</b> — operations는 identity를
 * 모르므로({@link AccountErasureState}와 같은 근거) identity 공개 API({@code AccountAccessApi.lockWriteAccess})를 아는 루트가 답한다.
 *
 * <p>문의 접수가 자기 트랜잭션의 맨 앞에서 부른다. 컨트롤러의 잠금 없는 판정과 접수 커밋 사이에 삭제가 시작되면 접수는 이 잠금에서
 * 삭제 요청과 줄을 서고 {@code DELETION_IN_PROGRESS}를 본다 — 삭제가 이미 지운 문의를 늦게 다시 만들지 않는다. 잠금은 호출자
 * 트랜잭션이 끝날 때까지 간다. 잠금 순서는 생명주기 행 → 문의 사용자 잠금이다. 계정 삭제의 문의 삭제는 생명주기 행을 잡지 않고 문의 사용자
 * 잠금만 쥐므로 순환이 없다.
 */
@FunctionalInterface
public interface AccountWriteAccess {

    /**
     * <b>호출자 트랜잭션 안에서</b> 그 계정의 생명주기 행을 잠그고 쓰기가 허용되는지 본다.
     *
     * @return 활성 계정이면 {@code true}. 삭제가 시작됐거나 끝났거나 계정이 없으면 {@code false}
     */
    boolean lockWriteAccess(UUID userId);
}
