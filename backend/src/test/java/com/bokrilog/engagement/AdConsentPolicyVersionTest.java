package com.bokrilog.engagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 광고 동의 정책 버전의 두 벌 대조(Story 4.1).
 *
 * <p>화면은 자기가 보여 준 설명의 정책 버전({@code frontend/src/content/adConsent.ts}의 {@code AD_CONSENT_POLICY_VERSION})을 결정
 * 요청에 싣고, 서버는 지금 버전({@link AdConsentApi#POLICY_VERSION})과 다르면 저장하지 않는다(409). 둘이 갈라지면 모든 결정이
 * 거절된다 — 한쪽을 바꾸면 다른 쪽도 바꾸도록 이 시험이 묶는다. Spring·DB 없이 파일만 읽는다.
 */
class AdConsentPolicyVersionTest {

    private static final Path FRONTEND_COPY = Path.of("../frontend/src/content/adConsent.ts");

    private static final Pattern DECLARATION =
            Pattern.compile("export const AD_CONSENT_POLICY_VERSION = '([^']+)'");

    @Test
    void 화면의_정책_버전은_서버의_지금_버전과_같다() throws Exception {
        String content = Files.readString(FRONTEND_COPY, StandardCharsets.UTF_8);
        Matcher declared = DECLARATION.matcher(content);
        assertThat(declared.find()).as("adConsent.ts의 AD_CONSENT_POLICY_VERSION 선언").isTrue();
        assertThat(declared.group(1)).isEqualTo(AdConsentApi.POLICY_VERSION);
        // 선언은 하나뿐이다 — 두 값 중 하나만 맞춰 놓고 통과하지 않는다.
        assertThat(declared.find()).isFalse();
    }
}
