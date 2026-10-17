package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.ClientTelemetryApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 4.5: 계측 어휘의 두 벌 대조. 화면(`frontend/src/telemetry/beacon.ts`)이 보내는 어휘와 서버가 받는 어휘가 갈라지면 묶음 전체가
 * 400으로 거절돼 계측이 조용히 사라진다. 공유 표 하나({@code frontend/src/telemetry/telemetryVocabulary.fixture.json})를 이 시험과
 * 프런트 시험({@code vocabulary.test.ts})이 함께 읽는다. Spring·DB 없이 파일만 읽는다.
 */
class ClientTelemetryVocabularyTest {

    private static final Path SHARED_FIXTURE = Path.of("../frontend/src/telemetry/telemetryVocabulary.fixture.json");

    @Test
    void 경로_묶음_상한_어휘가_공유_표와_같다() throws Exception {
        JsonNode table = JsonMapper.builder().build()
                .readTree(Files.readString(SHARED_FIXTURE, StandardCharsets.UTF_8));
        assertThat(table.get("path").asString()).isEqualTo(ClientTelemetryController.TELEMETRY_PATH);
        assertThat(table.get("maxEvents").asInt()).isEqualTo(ClientTelemetryApi.MAX_EVENTS);
        assertThat(table.get("maxBodyBytes").asInt()).isEqualTo(ClientTelemetryApi.MAX_BODY_BYTES);
        assertThat(strings(table, "metrics")).containsExactlyElementsOf(
                Arrays.stream(ClientTelemetryApi.VitalMetric.values()).map(Enum::name).toList());
        assertThat(strings(table, "routes")).containsExactlyElementsOf(
                Arrays.stream(ClientTelemetryApi.Route.values()).map(ClientTelemetryApi.Route::path).toList());
        assertThat(strings(table, "adEvents")).containsExactlyElementsOf(
                Arrays.stream(ClientTelemetryApi.AdEvent.values()).map(Enum::name).toList());
        assertThat(strings(table, "failureReasons")).containsExactlyElementsOf(
                Arrays.stream(ClientTelemetryApi.AdFailure.values()).map(Enum::name).toList());
    }

    private static List<String> strings(JsonNode table, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : table.get(field)) {
            values.add(value.asString());
        }
        return values;
    }
}
