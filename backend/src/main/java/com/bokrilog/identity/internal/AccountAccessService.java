package com.bokrilog.identity.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소유권과 삭제 상태에 따른 접근 판정.
 *
 * <p>소유권 불일치는 저장소를 보지 않고 거부한다. 대상 계정을 조회하지 않으니 타 사용자에 대한
 * 존재 확인 자체가 성립하지 않는다.
 */
@Service
class AccountAccessService implements AccountAccessApi {

    private final IdentityAccountRepository repository;

    AccountAccessService(IdentityAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public AccessDecision decide(
            @Nullable UUID callerUserId,
            @Nullable UUID targetUserId,
            @Nullable AccountAccessKind kind) {
        if (callerUserId == null || targetUserId == null || kind == null) {
            return AccessDecision.DENIED;
        }
        if (!callerUserId.equals(targetUserId)) {
            // 저장소를 보지 않는다. 타 사용자 대상과 미존재 대상이 같은 값으로 끝나는 이유다.
            return AccessDecision.DENIED;
        }
        AccountState state = repository.findLifecycle(targetUserId).orElse(null);
        if (state == null) {
            return AccessDecision.DENIED;
        }
        return switch (state) {
            case ACTIVE -> AccessDecision.ALLOWED;
            // 삭제가 시작되면 조회는 남기고 새 쓰기와 민감 작업은 즉시 막는다.
            case DELETION_IN_PROGRESS ->
                    kind == AccountAccessKind.READ ? AccessDecision.ALLOWED : AccessDecision.DENIED;
            // payload가 없다는 사실을 오류로 보고하지 않는다. 다른 거부와 같은 값이다.
            case DELETED -> AccessDecision.DENIED;
        };
    }
}
