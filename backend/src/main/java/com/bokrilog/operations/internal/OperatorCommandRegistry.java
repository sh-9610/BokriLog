package com.bokrilog.operations.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 운영 command의 등록표(Epic 3 회고 AI-5). command 이름 하나에 전략 하나다 — 엔진의 모든 분기가 이 조회 하나다.
 *
 * <p>같은 이름을 두 전략이 받으면 기동을 거부한다. 모르는 이름은 경로가 없는 것과 같다(호출자가
 * {@link #find}로 먼저 본다). 무엇이 등록됐는지는 {@code OperatorCommandRegistryTest}가 고정한다.
 */
final class OperatorCommandRegistry {

    private final Map<String, OperatorCommand<?, ?>> byName;

    OperatorCommandRegistry(List<OperatorCommand<?, ?>> commands) {
        Map<String, OperatorCommand<?, ?>> names = new LinkedHashMap<>();
        for (OperatorCommand<?, ?> command : commands) {
            for (String name : command.names()) {
                if (names.putIfAbsent(name, command) != null) {
                    throw new IllegalStateException("같은 운영 command 이름을 두 전략이 받는다.");
                }
            }
        }
        this.byName = Map.copyOf(names);
    }

    /** 그 이름의 전략. 모르면 빈 값이다. */
    Optional<OperatorCommand<?, ?>> find(String name) {
        return Optional.ofNullable(this.byName.get(name));
    }

    /** 그 이름의 전략. 모르는 이름은 배선 실수다(호출자가 {@link #find}·{@code isSupported}를 먼저 본다). */
    OperatorCommand<?, ?> of(String name) {
        OperatorCommand<?, ?> command = this.byName.get(name);
        if (command == null) {
            throw new IllegalArgumentException("모르는 운영 command다.");
        }
        return command;
    }

    /** 등록된 이름 전부. */
    Set<String> names() {
        return this.byName.keySet();
    }
}
