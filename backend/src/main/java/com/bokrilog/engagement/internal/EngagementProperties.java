package com.bokrilog.engagement.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * engagement 모듈의 배포 설정(Story 4.2).
 *
 * <p>{@code ad-provider-enabled}(env {@code BOKRILOG_AD_PROVIDER_ENABLED}, 기본 {@code false})는 광고 공급자가 활성인가다. 거짓이면
 * 자격 판정이 관문 {@code PROVIDER_DISABLED}로 건너뛴다 — 실제 광고 계정·ad unit·개인정보·접근성 승인(외부 출시 게이트) 전에는
 * 켜지 않는다. 운영 환경에서 이 값을 강제하거나 거부하는 기동 검사는 두지 않는다(SPEC Ask First).
 *
 * @param adProviderEnabled 광고 공급자 활성
 */
@ConfigurationProperties("bokrilog.engagement")
record EngagementProperties(boolean adProviderEnabled) {}
