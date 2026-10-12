package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Story 3.8 AC-5: 배포 감사 역할이 존재하는 이유. 배포의 첫 감사 행({@code GATE REQUESTED})은 새 이미지로 migration <b>전에</b>
 * 쓴다 — 그 시점의 데이터베이스에는 새 이미지가 담은 migration이 아직 적용되지 않았다. 기본 역할의 기동(Flyway validate)이라면
 * 반드시 실패할 그 상태에서 {@code deploy-audit} 역할은 뜨고 행을 쓴다.
 *
 * <p>실제 진입과 같은 모양으로 띄운다({@link SpringApplicationBuilder}, 프로필 {@code deploy-audit}). 데이터베이스는 같은 시험
 * 컨테이너 안의 새 database이고 마지막 migration 하나를 남겨 둔다.
 */
class DeployAuditPendingMigrationTest {

    @Test
    void 미적용_migration이_있는_데이터베이스에도_감사_행을_쓴다() throws Exception {
        PostgreSQLContainer container = PostgresTestcontainersConfig.shared();
        String database = "deploy_audit_pending_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword()); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = container.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");

        // 마지막 migration 하나만 남기고 적용한다.
        Flyway all = Flyway.configure().dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").load();
        MigrationVersion[] versions = Arrays.stream(all.info().all()).map(MigrationInfo::getVersion).sorted()
                .toArray(MigrationVersion[]::new);
        assertThat(versions.length).isGreaterThan(1);
        MigrationVersion older = versions[versions.length - 2];
        Flyway.configure().dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").target(older).load().migrate();
        assertThat(all.info().pending()).as("미적용 migration이 남아 있다").isNotEmpty();

        String operation = "deploy_pending_" + UUID.randomUUID().toString().replace("-", "");
        String target = "registry.test/bokrilog/backend@sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(operation.getBytes(StandardCharsets.UTF_8)));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BokriLogApplication.class)
                .profiles("deploy-audit")
                // 명령줄 인자는 application.yml보다 우선한다(기본 속성은 그 아래다).
                .run(
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + container.getUsername(),
                        "--spring.datasource.password=" + container.getPassword(),
                        "--bokrilog.operations.audit-digest-key=" + OperatorTestProperties.AUDIT_DIGEST_KEY,
                        "--bokrilog.deploy.audit.operation=" + operation,
                        "--bokrilog.deploy.audit.step=GATE",
                        "--bokrilog.deploy.audit.outcome=REQUESTED",
                        "--bokrilog.deploy.audit.target=" + target,
                        "--bokrilog.deploy.audit.actor=pending-migration-test",
                        "--bokrilog.deploy.audit.rehearsal=true")) {
            assertThat(context.isActive()).isTrue();
        }

        try (Connection connection = DriverManager.getConnection(url, container.getUsername(), container.getPassword());
                PreparedStatement query = connection.prepareStatement("SELECT reason, outcome, target FROM "
                        + "operations_audit_event WHERE operation_id = ?")) {
            query.setString(1, operation);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).as("감사 행이 있다").isTrue();
                assertThat(rows.getString("reason")).isEqualTo("GATE");
                assertThat(rows.getString("outcome")).isEqualTo("REQUESTED");
                assertThat(rows.getString("target")).isEqualTo(target);
                assertThat(rows.next()).isFalse();
            }
        }
        // 감사 역할은 schema를 바꾸지 않았다 — 남긴 migration은 여전히 미적용이다.
        assertThat(all.info().pending()).isNotEmpty();
    }
}
