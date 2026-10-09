package com.bokrilog.portfolio;

import java.util.UUID;

/**
 * portfolio 포인터 행을 잠그고 기대값과 대조한 <b>뒤</b>의 CAS가 실패했다(Story 3.3, 신호 5).
 *
 * <p>잠금 아래에서는 포인터가 움직일 수 없으므로 이것은 정상 경합이 아니라 불변식 위반이다. 정상 경합 — 잠금 전에 포인터가
 * 이미 다른 값이었던 것 — 은 {@link PortfolioMembershipApi.NextPublication.PointerMoved}이고 신호가 아니다. 둘을 같은
 * 예외로 접으면 경합이 자동 정지로 번진다.
 *
 * <p>portfolio는 operations를 모르고 부르지도 않는다. 이 예외를 던질 뿐이고, 잡아서 신호를 올리는 것은 호출자(sync)다.
 * 호출자 트랜잭션은 이 예외로 롤백된다. 메시지에 식별자를 담지 않는다 — 대상은 필드로만 읽는다.
 */
public final class PortfolioPointerInvariantViolation extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final UUID portfolioId;

    private final UUID userId;

    public PortfolioPointerInvariantViolation(UUID portfolioId, UUID userId) {
        super("portfolio pointer moved under its row lock");
        this.portfolioId = portfolioId;
        this.userId = userId;
    }

    /** 위반이 난 portfolio. */
    public UUID portfolioId() {
        return this.portfolioId;
    }

    /** 그 portfolio의 소유자. 신호 행의 사용자 귀속이다. */
    public UUID userId() {
        return this.userId;
    }
}
