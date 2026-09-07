package com.bokrilog.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * identity가 소유한 데이터의 삭제 경로.
 *
 * <p>두 단계다. {@link #beginDeletion(UUID)}가 상태를 {@code DELETION_IN_PROGRESS}로 전진시켜
 * 새 세션·쓰기·민감 접근을 즉시 막고, {@link #erasePayload(UUID)}가 payload와 인증 매핑을 지운 뒤
 * 상태를 {@code DELETED}로 전진시킨다. 생명주기 guard 행은 두 단계 어디서도 지워지지 않는다.
 *
 * <p>사용자에게 노출되는 계정 삭제 화면과 여러 모듈에 걸친 삭제 조율은 Epic 5가 소유한다.
 * 이 API는 HTTP로 노출되지 않으며, 다른 모듈의 데이터를 지우지도 않는다.
 */
public interface AccountDeletionApi {

    /**
     * 삭제 진행 상태로 전환한다.
     *
     * <p>멱등하다. 이미 {@code DELETION_IN_PROGRESS}거나 {@code DELETED}인 계정에 다시 요청해도
     * 상태는 그대로이고 {@link DeletionOutcome#COMPLETED}를 돌려준다. 어떤 반복·실패·재시도도
     * 상태를 {@code ACTIVE}로 되돌리지 않는다.
     *
     * @param userId 삭제를 시작할 계정
     * @return 전진했거나 이미 그 너머면 {@link DeletionOutcome#COMPLETED},
     *     대상이 없으면 {@link DeletionOutcome#NOT_APPLICABLE}
     */
    DeletionOutcome beginDeletion(@Nullable UUID userId);

    /**
     * identity payload와 인증 매핑을 지우고 삭제를 완료한다.
     *
     * <p>{@link #beginDeletion(UUID)}를 거치지 않은 계정에는 아무것도 하지 않는다. 삭제는 전환을
     * 먼저 거쳐야 하며, 그래야 삭제가 시작된 순간부터 새 세션·쓰기가 막힌다.
     *
     * <p>멱등하다. payload가 이미 없는 계정에 다시 요청해도 실패가 아니다.
     *
     * <p>저장이 실패하면 이 호출은 예외로 끝난다. 트랜잭션이 통째로 롤백되어 상태는
     * {@code DELETION_IN_PROGRESS}로 남고, 부분 실패를 완료로 보고하지 않는다.
     *
     * @param userId 삭제할 계정
     * @return 삭제가 끝났으면 {@link DeletionOutcome#COMPLETED}, 대상이 없거나 아직 삭제 전환을
     *     거치지 않았으면 {@link DeletionOutcome#NOT_APPLICABLE}
     */
    DeletionOutcome erasePayload(@Nullable UUID userId);
}
