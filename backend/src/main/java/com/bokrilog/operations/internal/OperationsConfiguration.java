package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorProperties;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * operations 모듈의 설정 배선(Story 3.1).
 *
 * <p>allowlist는 모든 역할에서 바인딩한다 — 형식 오류는 어느 프로세스에서든 기동 거부다. digest 키는 감사 행을 쓰는 역할에서만
 * 요구한다: API·Worker(servlet)와 일회성 배포 감사 역할({@code deploy-audit}, Story 3.8)·백업 기록 역할({@code backup-record}, Story
 * 3.9 — 파기 실패·기한 초과를 감사 원장에 남긴다). 스키마만 바꾸는 migrate 역할은 감사 행을
 * 쓰지 않으므로 그 키를 받을 이유가 없고, 받게 하면 운영 비밀이 필요 없는 프로세스로 퍼진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorProperties.class)
class OperationsConfiguration {

    /** 키가 비거나 짧으면 생성이 실패하고 그 역할의 기동이 거부된다({@link ActorDigest}). */
    @Bean
    @Conditional(AuditWritingRole.class)
    ActorDigest operationsActorDigest(OperatorProperties properties) {
        return new ActorDigest(properties.auditDigestKey());
    }

    /** 감사 행을 쓰는 역할: servlet 프로세스이거나 {@code deploy-audit}·{@code backup-record} 프로필이다. */
    static final class AuditWritingRole extends AnyNestedCondition {

        AuditWritingRole() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnWebApplication(type = Type.SERVLET)
        static final class Api {}

        @Profile("deploy-audit")
        static final class DeployAudit {}

        @Profile("backup-record")
        static final class BackupRecord {}
    }
}
