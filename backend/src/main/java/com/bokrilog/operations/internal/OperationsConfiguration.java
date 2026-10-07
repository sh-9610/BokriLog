package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * operations 모듈의 설정 배선(Story 3.1).
 *
 * <p>allowlist는 모든 역할에서 바인딩한다 — 형식 오류는 어느 프로세스에서든 기동 거부다. digest 키는 API(servlet)
 * 역할에서만 요구한다. 스키마만 바꾸는 migrate 역할은 감사 행을 쓰지 않으므로 그 키를 받을 이유가 없고, 받게 하면 운영
 * 비밀이 필요 없는 프로세스로 퍼진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorProperties.class)
class OperationsConfiguration {

    /** 키가 비거나 짧으면 생성이 실패하고 API 기동이 거부된다({@link ActorDigest}). */
    @Bean
    @ConditionalOnWebApplication(type = Type.SERVLET)
    ActorDigest operationsActorDigest(OperatorProperties properties) {
        return new ActorDigest(properties.auditDigestKey());
    }
}
