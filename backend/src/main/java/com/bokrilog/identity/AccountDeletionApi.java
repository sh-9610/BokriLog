package com.bokrilog.identity;

import java.util.List;
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

    // ------------------------------------------------------------------ Story 3.10 복원 적용

    /**
     * 생명주기 행이 있는 모든 계정(상태 무관). 복원 적용이 외부 tombstone과 맞춰 볼 후보다. HTTP로 노출되지 않는다.
     */
    List<UUID> accounts();

    /** 삭제가 끝난({@code DELETED}) 계정. 복원 검증이 그 계정의 잔여 행을 센다. */
    List<UUID> deletedAccounts();

    /**
     * 삭제가 시작됐지만 끝나지 않은({@code DELETION_IN_PROGRESS}) 계정. 삭제는 한 트랜잭션이라 정상이면 없다 — 복원 검증은 외부 tombstone이
     * 다시 지우지 않은 이 상태의 계정을 불변식 위반으로 센다.
     */
    List<UUID> deletionInProgressAccounts();

    /**
     * 인증 세션을 <b>모두</b> 지운다(속성 행은 cascade). <b>복원 적용 전용이다</b> — 복원된 DB에는 백업 시점의 세션이 남아 있고, 서비스를
     * 열기 전에 그 전부를 폐기해야 한다(AC-3). 세션 저장소 빈이 없는 일회성 역할에서도 돌도록 SQL은 이 모듈의 저장소에 있다.
     *
     * @return 지운 세션 수
     */
    int revokeAllSessions();

    /**
     * 그 계정에 아직 남은 identity 행 수 — payload·인증 매핑·세션. 생명주기 guard 행은 세지 않는다(삭제 뒤에도 남는 것이 계약이다).
     * 삭제가 끝난 계정이면 0이어야 한다.
     */
    long remainingRowsOf(UUID userId);
}
