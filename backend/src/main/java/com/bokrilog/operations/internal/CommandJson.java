package com.bokrilog.operations.internal;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 운영 command의 JSON 읽기 도우미 — 준비 본문의 필드와 원장 {@code details}의 저장된 사실. 전략들이 같은 규칙으로 읽는다.
 *
 * <p>본문 읽기는 모양이 틀리면 {@link IllegalArgumentException}(400)이고, {@code details} 읽기는 없으면 {@code null}이다 —
 * 재생은 그 command의 전략이 없는 사실을 지어내지 않고 거절한다.
 */
final class CommandJson {

    private CommandJson() {}

    /** 본문의 문자열 필드. 없거나 {@code null}이면 {@code null}, 문자열이 아니면 예외(400)다. */
    static @Nullable String stringOrNull(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(name);
        }
        return value.asString();
    }

    /**
     * 본문의 문자열 배열 필드. 없거나 {@code null}이면 {@code null}, 배열이 아니거나 {@code max}보다 길거나 문자열이 아닌
     * 원소가 있으면 예외(400)다.
     */
    static @Nullable List<String> stringsOrNull(JsonNode node, String name, int max) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray() || value.size() > max) {
            throw new IllegalArgumentException(name);
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isString()) {
                throw new IllegalArgumentException(name);
            }
            items.add(item.asString());
        }
        return List.copyOf(items);
    }

    /**
     * 제안의 command별 입력. 없으면 {@code null}(검증이 빈 입력으로 거절한다)이고, 다른 command의 입력 타입이면 배선 실수라
     * {@link IllegalArgumentException}이다 — 조용히 빈 입력(422)으로 접지 않는다.
     */
    static <T> @Nullable T inputOf(com.bokrilog.operations.OperatorCommandApi.Proposal proposal, Class<T> type) {
        Object input = proposal.input();
        if (input == null) {
            return null;
        }
        if (!type.isInstance(input)) {
            throw new IllegalArgumentException("다른 command의 입력이다.");
        }
        return type.cast(input);
    }

    /** 저장된 문자열. 없거나 문자열이 아니면 {@code null}. */
    static @Nullable String text(JsonNode details, String name) {
        JsonNode value = details.get(name);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** 저장된 시각(ISO-8601). 없거나 읽을 수 없으면 {@code null}. */
    static @Nullable Instant instant(JsonNode details, String name) {
        String text = text(details, name);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /** 저장된 UUID. 없거나 읽을 수 없으면 {@code null}. */
    static @Nullable UUID uuid(JsonNode details, String name) {
        String text = text(details, name);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /** 저장된 정수. 없거나 정수가 아니면 {@code null}. */
    static @Nullable Long number(JsonNode details, String name) {
        JsonNode value = details.get(name);
        return value != null && value.isIntegralNumber() ? value.asLong() : null;
    }

    /** 저장된 참·거짓. 없거나 참·거짓이 아니면 {@code null}. */
    static @Nullable Boolean bool(JsonNode details, String name) {
        JsonNode value = details.get(name);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    /** 저장된 문자열 배열. 없거나 배열이 아니면 빈 목록이다 — 재생은 사실을 지어내지 않는다. 문자열이 아닌 원소는 건너뛴다. */
    static List<String> strings(JsonNode details, String name) {
        JsonNode value = details.get(name);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isString()) {
                items.add(item.asString());
            }
        }
        return List.copyOf(items);
    }
}
