package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Story 3.2: V18의 되채우기.
 *
 * <p>V17까지 올린 별도 데이터베이스에 옛 command 행(신규 연결·증분 동기화·모르는 이름)을 넣고 V18을 적용한다. 신규 연결
 * command는 {@code NEW_CONNECTION:GLOBAL}, 나머지는 모르는 이름까지 {@code SYNC:GLOBAL}이 되고(fail-closed), 판독기가
 * 그 행들로 실제로 막는다. 판독기는 운영 클래스를 그 데이터베이스 위에 그대로 세워 부른다 — SQL을 시험에 옮겨 적지 않는다.
 */
class RestrictionMigrationTest extends PlanSyncTestBase {

    @Autowired
    org.testcontainers.postgresql.PostgreSQLContainer postgres;

    @Test
    void v18은_옛_command_행을_기능_전역_제한으로_되채우고_판독기가_막는다() throws Exception {
        String probe = "v17_probe_" + UUID.randomUUID().toString().replace("-", "");
        jdbc().sql("CREATE DATABASE " + probe).update();
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + probe;
            var dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("17").load().migrate();
            JdbcClient probeJdbc = JdbcClient.create(dataSource);
            for (String command : List.of("bybit-connection-create", "plan-sync", "made-up-command")) {
                probeJdbc.sql("INSERT INTO operations_restriction (restriction_id, command) VALUES (:id, :command)")
                        .param("id", UUID.randomUUID()).param("command", command).update();
            }
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("18").load().migrate();

            assertThat(probeJdbc.sql("SELECT command || '|' || feature || '|' || scope || '|' || target || '|' "
                            + "|| (target_user_id IS NULL) || '|' || (operation_id IS NULL) FROM operations_restriction "
                            + "ORDER BY command").query(String.class).list())
                    .containsExactly("bybit-connection-create|NEW_CONNECTION|GLOBAL|ALL|true|true",
                            "made-up-command|SYNC|GLOBAL|ALL|true|true", "plan-sync|SYNC|GLOBAL|ALL|true|true");

            // V19(Story 3.3)는 출처 열을 더한다. 되채운 옛 행은 운영자 출처다. 판독기는 현행 스키마 위에서 부른다.
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load()
                    .migrate();
            assertThat(probeJdbc.sql("SELECT DISTINCT origin FROM operations_restriction").query(String.class).list())
                    .containsExactly("OPERATOR");

            OperationalRestrictionApi evaluator = evaluatorOn(probeJdbc);
            var subject = RestrictionSubject.none().withPlan(UUID.randomUUID()).withPortfolio(UUID.randomUUID());
            assertThat(evaluator.blocks(EnumSet.of(RestrictionFeature.NEW_CONNECTION), RestrictionSubject.none()))
                    .isTrue();
            assertThat(evaluator.blocks(EnumSet.of(RestrictionFeature.SYNC), subject)).isTrue();
            assertThat(evaluator.blocks(EnumSet.of(RestrictionFeature.CALCULATION_DISPLAY), subject)).isFalse();
            assertThat(evaluator.blocks(EnumSet.of(RestrictionFeature.ADVERTISING), subject)).isFalse();
            assertThat(evaluator.active()).extracting(OperationalRestrictionApi.ActiveRestriction::scope)
                    .containsOnly(RestrictionScope.GLOBAL).hasSize(3);
            // 되채운 옛 행끼리는 겹쳐도 된다(부분 유일 색인은 운영 command가 만든 행만 센다). 같은 제한의 활성 판정은 본다.
            assertThat(evaluator.isActive(RestrictionFeature.SYNC, RestrictionScope.GLOBAL, "ALL")).isTrue();
        } finally {
            jdbc().sql("DROP DATABASE IF EXISTS " + probe + " WITH (FORCE)").update();
        }
    }

    /** 운영 판독기를 그 데이터베이스 위에 세운다. 구현이 {@code internal}의 package-private이라 리플렉션으로 만든다. */
    private static OperationalRestrictionApi evaluatorOn(JdbcClient jdbc) throws ReflectiveOperationException {
        Class<?> repositoryType = Class.forName("com.bokrilog.operations.internal.OperationalRestrictionRepository");
        var repositoryConstructor = repositoryType.getDeclaredConstructor(JdbcClient.class,
                org.springframework.beans.factory.ObjectProvider.class);
        repositoryConstructor.setAccessible(true);
        // 연결 범위 해석기는 없다 — 판독기는 연결 제한을 그 연결 자신에만 맞춘다(이 시험은 전역 행만 본다).
        Object repository = repositoryConstructor.newInstance(jdbc,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.bokrilog.operations.RestrictionConnectionScope.class));
        Class<?> auditType = Class.forName("com.bokrilog.operations.internal.OperatorAuditRepository");
        var auditConstructor = auditType.getDeclaredConstructor(JdbcClient.class);
        auditConstructor.setAccessible(true);
        Object audit = auditConstructor.newInstance(jdbc);
        Class<?> serviceType = Class.forName("com.bokrilog.operations.internal.OperationalRestrictionService");
        var serviceConstructor = serviceType.getDeclaredConstructor(repositoryType, auditType);
        serviceConstructor.setAccessible(true);
        return (OperationalRestrictionApi) serviceConstructor.newInstance(repository, audit);
    }
}
