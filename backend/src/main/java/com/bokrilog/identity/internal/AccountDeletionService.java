package com.bokrilog.identity.internal;

import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.DeletionOutcome;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 삭제 전환과 payload 삭제.
 *
 * <p>두 연산 모두 생명주기 행을 먼저 잠근다. 그래서 같은 계정에 대한 매핑 연결·중복 전환과
 * 직렬화되고, 단조 전진 술어가 붙은 UPDATE가 되돌아가는 전이를 0행으로 만든다.
 *
 * <p>저장이 실패하면 예외가 그대로 밖으로 나간다. 잡아서 값으로 바꾸면 부분 실패가 완료와 같은
 * 모양이 되고, 상태는 이미 전진했는데 payload는 남은 채로 호출자가 성공을 본다.
 */
@Service
class AccountDeletionService implements AccountDeletionApi {

    private final IdentityAccountRepository repository;

    AccountDeletionService(IdentityAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public DeletionOutcome beginDeletion(@Nullable UUID userId) {
        if (userId == null) {
            return DeletionOutcome.NOT_APPLICABLE;
        }
        AccountState state = repository.lockLifecycleForUpdate(userId).orElse(null);
        if (state == null) {
            return DeletionOutcome.NOT_APPLICABLE;
        }
        return switch (state) {
            case ACTIVE -> {
                repository.markDeletionInProgress(userId);
                yield DeletionOutcome.COMPLETED;
            }
            // 이미 그 지점이거나 그 너머다. 되돌리지 않고 멱등하게 성공으로 답한다.
            case DELETION_IN_PROGRESS, DELETED -> DeletionOutcome.COMPLETED;
        };
    }

    @Override
    @Transactional
    public DeletionOutcome erasePayload(@Nullable UUID userId) {
        if (userId == null) {
            return DeletionOutcome.NOT_APPLICABLE;
        }
        AccountState state = repository.lockLifecycleForUpdate(userId).orElse(null);
        if (state == null) {
            return DeletionOutcome.NOT_APPLICABLE;
        }
        return switch (state) {
            // 전환을 건너뛴 삭제는 하지 않는다. 삭제가 시작된 순간부터 새 세션·쓰기가 막혀야
            // 하는데, 전환 없이 지우면 그 창이 통째로 사라진다.
            case ACTIVE -> DeletionOutcome.NOT_APPLICABLE;
            case DELETION_IN_PROGRESS, DELETED -> {
                // 0행은 실패가 아니다. 이미 payload가 없는 계정에 다시 불러도 같은 결과다.
                repository.deleteSocialLinks(userId);
                /*
                 * 인증 세션도 이 경로가 지운다.
                 *
                 * Epic 1은 "identity가 도입한 사용자·소셜 식별자·세션의 삭제 경로를 모듈 수준에서
                 * 시험한다"를 요구한다. 매핑만 지우고 세션을 남기면 로그인 수단이 사라진 계정의
                 * 세션이 그대로 살아 있고, 그 세션은 여전히 내부 `user_id`를 결정한다. 그것은
                 * "삭제가 시작된 순간부터 새 세션·쓰기가 막힌다"의 반대편 절반이다.
                 *
                 * 같은 트랜잭션이다. 매핑은 지웠는데 세션이 남는 부분 상태를 만들지 않는다.
                 */
                repository.deleteSessionsOf(userId);
                repository.deleteAccountPayload(userId);
                // 술어가 DELETION_IN_PROGRESS를 요구한다. 이미 DELETED면 0행이고 그것이 멱등이다.
                repository.markDeleted(userId);
                // 생명주기 guard 행은 지우지 않는다. 이 행이 남아야 신규 세션·매핑 생성 판정이
                // 계속 거부한다.
                yield DeletionOutcome.COMPLETED;
            }
        };
    }
}
