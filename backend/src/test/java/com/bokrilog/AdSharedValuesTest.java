package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.internal.AdProviderTestHook;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 광고 표면에서 화면·서버·스키마가 따로 든 값의 대조(Epic 4 회고 AI-3, D-3).
 *
 * <p>transition 참조의 모양은 세 곳이 든다 — 서버가 소비 요청을 거르는 {@link AdTransitionController#TRANSITION}, 저장 행의 V27 CHECK,
 * 화면이 주소의 참조를 거르는 {@code frontend/src/content/adTransition.ts}의 {@code TRANSITION_PATTERN}. 하나만 바뀌면 화면이 보낸
 * 참조를 서버가 400으로 접거나, 서버가 받는 참조를 화면이 버린다. 발급 쪽({@code AdEligibilityService.TRANSITION_BYTES})이 만드는
 * 길이도 그 모양에 맞아야 한다 — 어긋나면 발급한 참조가 V27 CHECK에 걸려 자격 판정이 500이 된다. 광고 API 경로 넷(동의·자격·소비와
 * Story 4.5 계측 beacon)도 화면 상수와 컨트롤러 상수가 따로 있다 — 갈라지면 화면의 요청이 없는 경로의 404가 되고 광고 없이 결과로
 * 접히거나 계측이 조용히 사라져 아무도 모른다. 한쪽을 바꾸면 다른 쪽도 바꾸도록 이 시험이 묶는다.
 *
 * <p>Spring·DB 없이 파일만 읽는다({@code AdConsentPolicyVersionTest}·{@code AdClaimWindowTest} 선례). 읽는 프런트엔드 파일과 V27 SQL
 * 파일 각각에서 그 선언은 하나뿐이어야 한다 — 두 값 중 하나만 맞춰 놓고 통과하지 않는다. 서버 쪽은 Java 상수 하나라 컴파일러가 단일
 * 선언을 보장한다.
 */
class AdSharedValuesTest {

    private static final Path TRANSITION_CONTENT = Path.of("../frontend/src/content/adTransition.ts");

    private static final Path CONSENT_CONTENT = Path.of("../frontend/src/content/adConsent.ts");

    private static final Path TELEMETRY_BEACON = Path.of("../frontend/src/telemetry/beacon.ts");

    private static final Path ATTEMPT_MIGRATION =
            Path.of("src/main/resources/db/migration/V27__engagement_ad_attempt.sql");

    @Test
    void transition_참조_모양은_서버_스키마_화면이_같다() throws IOException {
        String screen = declaredOnce(TRANSITION_CONTENT,
                Pattern.compile("export const TRANSITION_PATTERN = /\\^(.+?)\\$/\\s*$", Pattern.MULTILINE),
                "adTransition.ts의 TRANSITION_PATTERN 선언");
        String schema = declaredOnce(ATTEMPT_MIGRATION,
                Pattern.compile("transition_ref ~ '\\^([^']+)\\$'"),
                "V27의 transition_ref 모양 CHECK");

        // 서버는 전체 일치(matches)로 보므로 앵커 없는 본문이 화면·스키마의 앵커 안쪽과 같아야 한다.
        assertThat(AdTransitionController.TRANSITION.pattern()).isEqualTo(schema).isEqualTo(screen);
    }

    @Test
    void 발급하는_참조는_그_모양에_맞는_43자다() {
        // 256비트 = 32바이트이고, base64url 무패딩으로 정확히 43자다.
        assertThat(AdProviderTestHook.TRANSITION_BYTES).isEqualTo(32);
        String issued = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[AdProviderTestHook.TRANSITION_BYTES]);
        assertThat(issued).hasSize(43);
        assertThat(AdTransitionController.TRANSITION.matcher(issued).matches()).isTrue();
    }

    @Test
    void 광고_API_경로는_화면_상수와_컨트롤러_상수가_같다() throws IOException {
        assertThat(declaredOnce(CONSENT_CONTENT, pathDeclaration("AD_CONSENT_PATH"),
                "adConsent.ts의 AD_CONSENT_PATH 선언"))
                .isEqualTo(AdConsentController.AD_CONSENT_PATH);
        assertThat(declaredOnce(TRANSITION_CONTENT, pathDeclaration("AD_ELIGIBILITY_PATH"),
                "adTransition.ts의 AD_ELIGIBILITY_PATH 선언"))
                .isEqualTo(AdEligibilityController.AD_ELIGIBILITY_PATH);
        assertThat(declaredOnce(TRANSITION_CONTENT, pathDeclaration("AD_TRANSITION_CLAIM_PATH"),
                "adTransition.ts의 AD_TRANSITION_CLAIM_PATH 선언"))
                .isEqualTo(AdTransitionController.AD_TRANSITION_PATH);
        assertThat(declaredOnce(TELEMETRY_BEACON, pathDeclaration("TELEMETRY_PATH"),
                "beacon.ts의 TELEMETRY_PATH 선언"))
                .isEqualTo(ClientTelemetryController.TELEMETRY_PATH);
    }

    private static Pattern pathDeclaration(String name) {
        return Pattern.compile("export const " + name + " = '([^']+)'");
    }

    /** 파일에서 선언 하나의 값을 읽는다. 없거나 둘 이상이면 실패한다. */
    private static String declaredOnce(Path file, Pattern declaration, String what) throws IOException {
        Matcher declared = declaration.matcher(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(declared.find()).as(what).isTrue();
        String value = declared.group(1);
        assertThat(declared.find()).as(what + "은 하나뿐이다").isFalse();
        return value;
    }
}
