package com.bokrilog;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.AccountErasureState;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 계정 삭제 시작 여부({@link AccountErasureState})의 루트 구현(Epic 3 회고 B-2).
 *
 * <p>identity의 {@link AccountAccessApi} 민감 작업 판정이 허용이 아니면 삭제가 시작됐거나 계정이 없는 것이다. 그 판정은
 * 생명주기 행을 잠금 없이 읽는다. 판정식은 여기 한 곳이다 — 운영 command의 계정 사용 가능 판정({@code
 * OperatorCommandFacts#accountUsable})도 이 port의 부정이다. 신호가 모든 역할에서 돌므로 웹 조건 없이 배선한다.
 */
@Component
class IdentityAccountErasureState implements AccountErasureState {

    private final AccountAccessApi accounts;

    IdentityAccountErasureState(AccountAccessApi accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean erasureStarted(UUID userId) {
        return this.accounts.decide(userId, userId, AccountAccessKind.SENSITIVE) != AccessDecision.ALLOWED;
    }
}
