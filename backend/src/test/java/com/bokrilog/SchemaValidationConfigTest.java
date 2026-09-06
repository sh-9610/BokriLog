package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 기동 시점의 Flyway 전략(AD-12).
 *
 * <p>이 시험이 없으면 {@link SchemaValidationConfig}를 통째로 지워도 백엔드 시험이 전부 통과한다.
 * 시험 구성({@link PostgresTestcontainersConfig})이 {@code @Primary}로 migrate 전략을 얹어 운영
 * bean을 가리기 때문이다. 그래서 여기서는 그 구성을 import하지 않는다. import하는 순간 다시
 * 가려지고 이 시험은 아무것도 확인하지 않게 된다.
 *
 * <p>Spring 컨텍스트를 통째로 띄우지도 않는다. 확인하려는 것은 데이터베이스 동작이 아니라
 * "어느 프로필에서 어느 bean이 선택되는가"라는 배선이다. {@link ApplicationContextRunner}면
 * 그 배선만 정확히 볼 수 있고 Docker도 필요 없다.
 *
 * <p>전략은 메서드 참조라 동일성으로 비교할 수 없다. 대신 {@link Flyway}를 대역으로 주고 어느
 * 메서드가 불렸는지 본다. 확인하려는 것이 곧 "기동이 스키마를 바꾸는가"이므로 이 단정이 그
 * 질문의 직접적인 답이다.
 */
class SchemaValidationConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SchemaValidationConfig.class);

    @Test
    void 기본_프로필의_기동은_스키마를_검증만_한다() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(FlywayMigrationStrategy.class);

                    Flyway flyway = mock(Flyway.class);
                    context.getBean(FlywayMigrationStrategy.class).migrate(flyway);

                    verify(flyway).validate();
                    // 배포 중 두 버전이 함께 뜨는 순간 어느 프로세스가 스키마를 바꿀지 정해지지
                    // 않는다. API 기동 경로에서 마이그레이션이 실행되면 안 되는 이유다.
                    verify(flyway, never()).migrate();
                });
    }

    @Test
    void migrate_프로필에서만_실제로_마이그레이션한다() {
        runner.withPropertyValues("spring.profiles.active=migrate")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(FlywayMigrationStrategy.class);

                            Flyway flyway = mock(Flyway.class);
                            context.getBean(FlywayMigrationStrategy.class).migrate(flyway);

                            verify(flyway).migrate();
                            verify(flyway, never()).validate();
                        });
    }
}
