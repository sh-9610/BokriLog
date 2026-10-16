package com.bokrilog.engagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 광고 소비 창의 두 벌 대조(Epic 4 회고 AI-1).
 *
 * <p>서버는 자격 결정 뒤 {@link AdTransitionApi#CLAIM_WINDOW_SECONDS}초가 지난 transition을 광고 없이 건너뛰고, 중립 화면은 준비된
 * 광고를 문서 시작 뒤 같은 시간({@code frontend/src/content/adTransition.ts}의 {@code AD_CLAIM_WINDOW_SECONDS})까지만 띄울 수 있게
 * 두고 지나면 광고를 취소하고 결과로 간다. 둘이 갈라지면 소비 창 밖에서 광고가 뜨거나(화면 값이 더 크다) 소비된 광고가 일찍
 * 끊긴다 — 한쪽을 바꾸면 다른 쪽도 바꾸도록 이 시험이 묶는다. Spring·DB 없이 파일만 읽는다({@code AdConsentPolicyVersionTest} 선례).
 *
 * <p>같은 값이어도 두 창은 정확히 겹치지 않는다 — 화면 창은 결정 시각이 아니라 문서 시작부터 세므로, 결정 뒤 응답 전송·문서 이동
 * 시간만큼 서버 창보다 늦게 끝난다(SPEC {@code spec-ad-slot-lifetime.md} Design Notes가 받아들인 근사).
 */
class AdClaimWindowTest {

    private static final Path FRONTEND_CONTENT = Path.of("../frontend/src/content/adTransition.ts");

    private static final Pattern DECLARATION = Pattern.compile("export const AD_CLAIM_WINDOW_SECONDS = (\\d+)\\b");

    @Test
    void 화면의_소비_창은_서버의_소비_창과_같다() throws Exception {
        String content = Files.readString(FRONTEND_CONTENT, StandardCharsets.UTF_8);
        Matcher declared = DECLARATION.matcher(content);
        assertThat(declared.find()).as("adTransition.ts의 AD_CLAIM_WINDOW_SECONDS 선언").isTrue();
        assertThat(Long.parseLong(declared.group(1))).isEqualTo(AdTransitionApi.CLAIM_WINDOW_SECONDS);
        // 선언은 하나뿐이다 — 두 값 중 하나만 맞춰 놓고 통과하지 않는다.
        assertThat(declared.find()).isFalse();
    }
}
