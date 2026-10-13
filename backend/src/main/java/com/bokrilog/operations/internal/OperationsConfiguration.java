package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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

    /**
     * 삭제 tombstone의 digest 키(Story 3.10). 삭제 표본·내보내기·복원 적용 역할에서는 필수다 — 비거나 짧으면 생성이 실패하고 그 역할의 기동이
     * 거부된다. 그 밖의 역할은 값이 있을 때만 만든다(지금 사용자 삭제 경로가 없어 API·Worker는 이 키를 받지 않는다). <b>감사 digest 키와
     * 같은 값이면 거부한다</b> — 외부에 35일 남는 tombstone 사본 하나로 원장의 digest와 대조할 길이 생긴다. 메시지에 값을 담지 않는다.
     */
    @Bean
    @Conditional(TombstoneRole.class)
    TombstoneDigest operationsTombstoneDigest(@Value("${bokrilog.tombstone.digest-key:}") String key,
            OperatorProperties properties) {
        TombstoneDigest digest = new TombstoneDigest(key);
        String audit = properties.auditDigestKey();
        if (audit != null && !audit.isBlank()) {
            // 표기가 아니라 바이트로 비교한다 — 같은 키의 다른 base64 표기(줄바꿈·패딩)로 판정을 피하지 못하게.
            byte[] auditBytes;
            try {
                auditBytes = java.util.Base64.getMimeDecoder().decode(audit.strip());
            } catch (IllegalArgumentException unreadable) {
                auditBytes = audit.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            if (digest.sameKeyAs(auditBytes)) {
                throw new IllegalStateException("bokrilog.tombstone.digest-key가 감사 digest 키와 같다. 두 키는 달라야 한다.");
            }
        }
        return digest;
    }

    /**
     * 감사 행을 쓰는 역할: servlet 프로세스이거나 {@code deploy-audit}·{@code backup-record} 프로필, 그리고 계정을 지우는 두 일회성
     * 역할({@code deletion-sample}·{@code restore-apply}, Story 3.10 — 제한 target 비식별에 digest가 필요하다)이다. 내보내기 역할
     * ({@code tombstone-export})은 감사 행도 digest도 쓰지 않으므로 이 키를 받지 않는다.
     */
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

        @Profile("deletion-sample")
        static final class DeletionSample {}

        @Profile("restore-apply")
        static final class RestoreApply {}
    }

    /** tombstone digest를 만드는 역할: 삭제 표본·내보내기(파일 머리의 키 ID)·복원 적용 프로필이거나, 키가 주어졌다. */
    static final class TombstoneRole extends AnyNestedCondition {

        TombstoneRole() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @Profile("tombstone-export")
        static final class TombstoneExport {}

        @Profile("deletion-sample")
        static final class DeletionSample {}

        @Profile("restore-apply")
        static final class RestoreApply {}

        @ConditionalOnExpression("!'${bokrilog.tombstone.digest-key:}'.isBlank()")
        static final class Configured {}
    }
}
