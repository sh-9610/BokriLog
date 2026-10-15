package com.bokrilog.engagement.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * engagement 모듈의 배포 설정(Story 4.2·4.3).
 *
 * <p>{@code ad-provider-enabled}(env {@code BOKRILOG_AD_PROVIDER_ENABLED}, 기본 {@code false})는 광고 공급자 플래그다.
 * {@code ad-unit-path}(env {@code BOKRILOG_AD_UNIT_PATH}, 기본 빈 값)는 GPT web interstitial의 ad unit 경로다(Story 4.3,
 * {@code AD-GPT-1.0}). 공급자는 둘이 함께 성립할 때만 활성이다 — 플래그가 켜졌고 단위 경로가 형식에 맞아야 한다
 * ({@link AdProviderState}). 아니면 자격 판정과 transition 소비가 관문 {@code PROVIDER_DISABLED}로 건너뛴다 — 실제 광고 계정·ad
 * unit·개인정보·접근성 승인(외부 출시 게이트) 전에는 켜지 않는다. 운영 환경에서 이 값들을 강제하거나 거부하는 기동 검사는 두지
 * 않는다(SPEC Ask First) — 형식이 틀린 단위 경로도 기동을 막지 않고 공급자를 끈다.
 *
 * @param adProviderEnabled 광고 공급자 플래그
 * @param adUnitPath GPT ad unit 경로. 비었으면 {@code null}이거나 빈 문자열이다
 */
@ConfigurationProperties("bokrilog.engagement")
record EngagementProperties(boolean adProviderEnabled, @Nullable String adUnitPath) {}
