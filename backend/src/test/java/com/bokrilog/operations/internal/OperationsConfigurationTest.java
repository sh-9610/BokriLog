package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Story 3.1: 운영자 설정의 기동 판정.
 *
 * <p>API(servlet) 역할은 감사 digest 키가 비거나 짧으면 기동을 거부한다. allowlist 형식 오류는 어느 역할이든 기동
 * 거부다. 스키마만 바꾸는 migrate 역할(웹 아님)은 키를 받지 않아도 뜬다. 키는 실행 시점에 만든다(gitleaks).
 */
class OperationsConfigurationTest {

    private static final String KEY = key(32);

    private final WebApplicationContextRunner api =
            new WebApplicationContextRunner().withUserConfiguration(OperationsConfiguration.class);

    private final ApplicationContextRunner migrate =
            new ApplicationContextRunner().withUserConfiguration(OperationsConfiguration.class);

    @Test
    void API는_digest_키가_있어야_뜬다() {
        this.api.withPropertyValues("bokrilog.operations.audit-digest-key=" + KEY)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ActorDigest.class));
        this.api.run(context -> assertThat(context).hasFailed());
        this.api.withPropertyValues("bokrilog.operations.audit-digest-key=" + key(16))
                .run(context -> assertThat(context).hasFailed());
        this.api.withPropertyValues("bokrilog.operations.audit-digest-key=not*base64")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void allowlist_형식_오류는_어느_역할이든_기동_거부다() {
        this.api.withPropertyValues("bokrilog.operations.audit-digest-key=" + KEY,
                "bokrilog.operations.operators=https://issuer|subject")
                .run(context -> assertThat(context).hasFailed());
        this.migrate.withPropertyValues("bokrilog.operations.operators=https://issuer|subject|root")
                .run(context -> assertThat(context).hasFailed());
        this.api.withPropertyValues("bokrilog.operations.audit-digest-key=" + KEY,
                "bokrilog.operations.operators=https://issuer|subject|command,audit")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void migrate_역할은_키_없이_뜬다() {
        this.migrate.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ActorDigest.class));
    }

    /** Story 3.8: 배포 감사 역할(웹 아님)은 감사 행을 쓰므로 API와 같이 키가 있어야 뜬다. */
    @Test
    void deploy_audit_역할은_키가_있어야_뜬다() {
        this.migrate.withPropertyValues("spring.profiles.active=deploy-audit",
                "bokrilog.operations.audit-digest-key=" + KEY)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ActorDigest.class));
        this.migrate.withPropertyValues("spring.profiles.active=deploy-audit")
                .run(context -> assertThat(context).hasFailed());
    }

    /** Story 3.9: 백업 기록 역할(웹 아님)도 파기 실패·기한 초과를 감사 원장에 쓰므로 키가 있어야 뜬다. */
    @Test
    void backup_record_역할은_키가_있어야_뜬다() {
        this.migrate.withPropertyValues("spring.profiles.active=backup-record",
                "bokrilog.operations.audit-digest-key=" + KEY)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ActorDigest.class));
        this.migrate.withPropertyValues("spring.profiles.active=backup-record")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 백업_수행자_digest는_다른_digest와_겹치지_않는다() {
        ActorDigest digest = new ActorDigest(KEY);
        assertThat(digest.ofBackup("backup-timer")).matches("[0-9a-f]{64}")
                .isNotEqualTo(digest.ofDeployer("backup-timer")).isNotEqualTo(digest.ofSystem("backup-timer"))
                .isEqualTo(new ActorDigest(KEY).ofBackup("backup-timer"));
    }

    @Test
    void 배포_수행자_digest는_다른_digest와_겹치지_않는다() {
        ActorDigest digest = new ActorDigest(KEY);
        assertThat(digest.ofDeployer("deployer")).matches("[0-9a-f]{64}").isNotEqualTo(digest.ofSystem("deployer"))
                .isEqualTo(new ActorDigest(KEY).ofDeployer("deployer"))
                .isNotEqualTo(digest.ofDeployer("someone-else"));
    }

    @Test
    void digest는_키에_매여_있고_사용자마다_다르다() {
        UUID user = UUID.randomUUID();
        ActorDigest one = new ActorDigest(KEY);
        assertThat(one.of(user)).matches("[0-9a-f]{64}").isEqualTo(new ActorDigest(KEY).of(user))
                .isNotEqualTo(new ActorDigest(key(32)).of(user)).isNotEqualTo(one.of(UUID.randomUUID()))
                .doesNotContain(user.toString());
    }

    private static String key(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }
}
