package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 광고 설정과 계측의 프런트엔드 경로가 서버 경로와 같은지 본다. */
class AdSharedValuesTest {

    @Test
    void 광고_API_경로는_화면_상수와_컨트롤러_상수가_같다() throws IOException {
        assertThat(pathDeclaredIn("content/planSync.ts", "DASHBOARD_AD_CONFIG_PATH"))
                .isEqualTo(DashboardAdController.DASHBOARD_AD_PATH);
        assertThat(pathDeclaredIn("telemetry/beacon.ts", "TELEMETRY_PATH"))
                .isEqualTo(ClientTelemetryController.TELEMETRY_PATH);
    }

    private static String pathDeclaredIn(String file, String name) throws IOException {
        var declaration = Pattern.compile("export const " + name + " = '([^']+)'")
                .matcher(Files.readString(Path.of("../frontend/src").resolve(file), StandardCharsets.UTF_8));
        assertThat(declaration.find()).as(name).isTrue();
        String value = declaration.group(1);
        assertThat(declaration.find()).as(name + " 선언은 하나뿐이다").isFalse();
        return value;
    }
}
