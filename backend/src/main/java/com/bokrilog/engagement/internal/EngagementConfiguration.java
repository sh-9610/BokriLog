package com.bokrilog.engagement.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** engagement 모듈의 설정 배선(Story 4.2). 공급자 활성 값은 모든 역할에서 바인딩한다 — 형식 오류는 어느 프로세스에서든 기동 거부다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EngagementProperties.class)
class EngagementConfiguration {}
