package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

    /**
     * Story 5.2: 계정 삭제 Worker는 tombstone 키가 없는 프로세스에서 삭제를 집지 않는다 — 그 판정인 실제 {@link DeletionTombstoneService#keyAvailable}은
     * 키가 없는 컨텍스트(그 bean이 생기지 않는다)에서 거짓, 키가 주어진 컨텍스트에서 참이다.
     */
    @Test
    void tombstone_키가_없는_컨텍스트에서_keyAvailable은_거짓이다() {
        ApplicationContextRunner tombstones = this.migrate
                .withBean(DeletionTombstoneRepository.class, () -> org.mockito.Mockito.mock(DeletionTombstoneRepository.class))
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                        () -> org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class))
                .withBean(OperatorAuditRepository.class, () -> Mockito.mock(OperatorAuditRepository.class))
                .withBean(TombstoneAudit.class)
                .withBean(DeletionTombstoneService.class);
        tombstones.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(TombstoneDigest.class);
            assertThat(context.getBean(DeletionTombstoneService.class).keyAvailable()).isFalse();
        });
        tombstones.withPropertyValues("bokrilog.tombstone.digest-key=" + KEY).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TombstoneDigest.class);
            assertThat(context.getBean(DeletionTombstoneService.class).keyAvailable()).isTrue();
        });
    }

    /**
     * Story 5.3: tombstone 키는 Worker·{@code all}·tombstone 역할(삭제 표본·내보내기·복원 적용)에만 생긴다. API 슬롯({@code bokrilog.role=api})은
     * 키가 주어져도 그 bean을 만들지 않는다. 내보내기 역할은 감사 SYSTEM 행을 쓰므로 감사 digest 키도 있어야 뜬다.
     */
    @Test
    void API_역할은_키가_있어도_tombstone_digest를_만들지_않는다() {
        String tombstoneKey = key(32);
        this.api.withPropertyValues("bokrilog.operations.audit-digest-key=" + KEY, "bokrilog.role=api",
                "bokrilog.tombstone.digest-key=" + tombstoneKey)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TombstoneDigest.class));
        for (String role : new String[] {"worker", "all"}) {
            this.api.withPropertyValues("bokrilog.operations.audit-digest-key=" + KEY, "bokrilog.role=" + role,
                    "bokrilog.tombstone.digest-key=" + tombstoneKey)
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TombstoneDigest.class));
        }
        this.api.withPropertyValues("bokrilog.operations.audit-digest-key=" + KEY, "bokrilog.role=worker")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TombstoneDigest.class));
    }

    @Test
    void tombstone_export_역할은_두_키가_있어야_뜬다() {
        String tombstoneKey = key(32);
        this.migrate.withPropertyValues("spring.profiles.active=tombstone-export",
                "bokrilog.operations.audit-digest-key=" + KEY, "bokrilog.tombstone.digest-key=" + tombstoneKey)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ActorDigest.class)
                        .hasSingleBean(TombstoneDigest.class));
        this.migrate.withPropertyValues("spring.profiles.active=tombstone-export",
                "bokrilog.tombstone.digest-key=" + tombstoneKey)
                .run(context -> assertThat(context).hasFailed());
        this.migrate.withPropertyValues("spring.profiles.active=tombstone-export",
                "bokrilog.operations.audit-digest-key=" + KEY)
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
