package com.bokrilog.operations;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * 운영자 권한(Story 3.1 AC-5).
 *
 * <p>둘뿐이고 서로 포함하지 않는다. 일반 운영 명령({@code command})과 감사 조회·내보내기({@code audit})를 승인 정책대로
 * 가른다 — 한쪽만 가진 운영자는 다른 쪽 표면에서 비운영자와 같은 404를 받는다. 감사 내보내기는 command이면서 감사
 * 데이터를 꺼내므로 둘 다 요구한다({@link OperatorCommandApi#permissionsOf}).
 */
public enum OperatorPermission {
    COMMAND,
    AUDIT;

    /** 설정 값 하나({@code command}·{@code audit}, 대소문자 무시). 모르는 값은 {@code null}이다. */
    static @Nullable OperatorPermission parse(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "command" -> COMMAND;
            case "audit" -> AUDIT;
            default -> null;
        };
    }
}
