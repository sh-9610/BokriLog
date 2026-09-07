package com.bokrilog;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 스키마 변경은 별도 역할이 하고 기동은 검증만 한다(AD-12).
 *
 * <p>Spring Boot의 기본값은 기동할 때마다 마이그레이션을 실행하는 것이다. 그 기본값을 두면 배포
 * 중 두 버전이 동시에 뜨는 순간 어느 프로세스가 스키마를 바꿀지 정해지지 않고, rollback 창에서
 * 예전 버전이 새 스키마를 만나는 경로도 검증할 수 없게 된다.
 *
 * <p>그래서 기본 프로필의 기동은 {@link Flyway#validate()}만 한다. 적용되지 않은 마이그레이션이
 * 있으면 기동이 실패하며, 그것이 의도한 fail-closed다. 실제 적용은 {@code migrate} 프로필로
 * 뜨는 일회성 역할만 한다.
 *
 * <p>루트 패키지에 둔다. 기동이 스키마를 바꾸는가는 애플리케이션 전역 정책이고, 지금 마이그레이션을
 * 가진 모듈이 {@code identity} 하나뿐이라는 사실과는 무관하다. 모듈 패키지가 이 정책을 소유하면
 * 두 번째 모듈이 생기는 순간 그 모듈의 기동이 남의 모듈 내부 설정에 의존하게 된다.
 *
 * <p>프로필별로 어느 전략이 선택되는지는 {@code SchemaValidationConfigTest}가 단정한다. 시험
 * 구성({@code PostgresTestcontainersConfig})이 {@code @Primary}로 이 bean을 가리므로, 통합 시험만
 * 있으면 이 클래스를 통째로 지워도 아무 시험이 실패하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class SchemaValidationConfig {

    /** API 기동 경로. 스키마를 바꾸지 않고 기대와 다르면 멈춘다. */
    @Bean
    @Profile("!migrate")
    FlywayMigrationStrategy validateOnlyMigrationStrategy() {
        return Flyway::validate;
    }

    /** 일회성 migration 역할. 이 프로필에서만 스키마가 바뀐다. */
    @Bean
    @Profile("migrate")
    FlywayMigrationStrategy migrateMigrationStrategy() {
        return Flyway::migrate;
    }
}
