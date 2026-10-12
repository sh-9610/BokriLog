package com.bokrilog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 캡처한 출력의 로그 줄 읽기(Story 3.7). 로그는 JSON 한 줄이고({@link RedactingJsonEncoder}) 메시지 본문은 {@code event}
 * 필드다. 시험은 본문을 단정하므로 줄을 풀어 그 값을 꺼낸다. JSON이 아닌 줄(JVM 경고 등)은 건너뛴다.
 */
final class LogLines {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private LogLines() {}

    /** JSON 로그 줄 전부(필드 이름 → 값). */
    static List<Map<String, Object>> records(String captured) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : captured.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("{\"ts\":")) {
                continue;
            }
            try {
                records.add(JSON.readValue(trimmed, new TypeReference<LinkedHashMap<String, Object>>() {}));
            } catch (JacksonException notJson) {
                // 다른 출력과 섞여 잘린 줄이다. 건너뛴다.
            }
        }
        return records;
    }

    /** 로그 본문({@code event})들. */
    static List<String> events(String captured) {
        List<String> events = new ArrayList<>();
        for (Map<String, Object> record : records(captured)) {
            Object event = record.get("event");
            if (event != null) {
                events.add(event.toString());
            }
        }
        return events;
    }

    /** 본문에 {@code marker}를 담은 줄들의 본문. */
    static List<String> events(String captured, String marker) {
        return events(captured).stream().filter(event -> event.contains(marker)).toList();
    }
}
