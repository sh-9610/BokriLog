package com.bokrilog.identity.internal;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 계정 생명주기 상태.
 *
 * <p>{@code ACTIVE -> DELETION_IN_PROGRESS -> DELETED} 단조 전진뿐이다. 되돌아가는 전이는 없고,
 * 반복 요청·실패·재시도로도 이전 상태가 되지 않는다. 그 규칙은 이 enum이 아니라 UPDATE 술어와
 * 상태 행 잠금이 강제한다({@link IdentityAccountRepository}).
 *
 * <p>모듈 내부 타입이다. Java 가시성이 아니라 모듈 경계가 이를 막는다({@code ModuleBoundaryTest}).
 * 밖으로 나가는 값은 {@code AccessDecision}과 {@code DeletionOutcome}뿐이다. 상태를 그대로
 * 돌려주면 호출자가 "존재하지 않음"과 "삭제됨"을 구분할 수 있게 되기 때문이다.
 */
public enum AccountState {
    ACTIVE,
    DELETION_IN_PROGRESS,
    DELETED;

    /**
     * 저장된 상태 문자열을 읽는다. 아는 값이 아니면 빈 값이다.
     *
     * <p>{@link #valueOf(String)}을 그대로 쓰지 않는 이유는 판정 경로의 계약 때문이다.
     * {@code AccountAccessApi.decide}는 "어떤 입력에도 예외를 던지지 않는다"를 약속하는데,
     * {@code valueOf}는 아는 값이 아니면 {@code IllegalArgumentException}을 던진다. 그 예외는
     * 판정 경로 밖으로 나가 거부를 500으로 바꾼다. 즉 데이터 하나가 응답 계약을 뒤집는다.
     *
     * <p>스키마의 CHECK 제약이 세 값만 허용하므로 이 경로는 지금 도달하지 않는다. 그래도 접는다.
     * 마이그레이션이 값 집합을 넓히는 순간, 손으로 고친 행 하나가 들어오는 순간, 두 버전이 함께
     * 뜬 배포 창에서 예전 버전이 새 값을 만나는 순간 모두 여기로 온다. 셋 다 "모르는 상태"이고
     * 모르는 상태의 안전한 답은 거부다.
     *
     * <p>빈 값은 호출자에게 "행이 없다"와 같은 모양이며, 그 경로는 이미 모두 거부·해당 없음으로
     * 끝난다. 모르는 상태를 전진시키지 않는 것도 같은 이유다.
     */
    static Optional<AccountState> parse(@Nullable String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        for (AccountState state : values()) {
            if (state.name().equals(stored)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
