package com.bokrilog;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 백엔드 시험이 쓰는 실제 PostgreSQL.
 *
 * <p>이 시험 묶음은 Docker를 전제한다. 인메모리 데이터베이스로 바꿔치기하지 않는 이유는 이
 * 스토리가 고정하려는 것이 SQL 문법이 아니라 {@code SELECT … FOR UPDATE}의 직렬화, 단조 전진
 * UPDATE 술어, 유일 인덱스 위반 같은 실제 엔진의 동작이기 때문이다. 다른 엔진에서 통과하는
 * 시험은 이 계약의 증거가 되지 못한다.
 *
 * <p>컨테이너 인스턴스는 {@code static}이다. 시험 클래스마다 Spring 컨텍스트가 갈라져도
 * (예: 웹 환경이 다르거나 bean을 덮어쓰는 시험) 같은 인스턴스를 공유해 JVM 하나에 컨테이너
 * 하나만 뜬다. 이미 시작된 컨테이너에 대한 {@code start()}는 아무 일도 하지 않는다.
 *
 * <p>자격정보 리터럴이 없다. 사용자명·비밀번호는 Testcontainers가 컨테이너마다 정하고 이 파일도,
 * compose도, 시험 fixture도 그 값을 적지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

    /**
     * `ARCHITECTURE-SPINE.md` Stack 표의 검증 기준선 PostgreSQL 18.6이다. compose의
     * {@code deploy/compose/compose.yaml}도 같은 태그를 쓴다. 두 곳이 갈라지면 시험이 통과한
     * 엔진과 스택이 띄우는 엔진이 달라진다.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:18.6-alpine");

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(IMAGE);

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return CONTAINER;
    }

    /**
     * 시험에서만 스키마를 실제로 적용한다.
     *
     * <p>기본 프로필의 기동 전략은 {@code Flyway::validate}다(AD-12). 갓 뜬 빈 컨테이너에는 적용된
     * 마이그레이션이 없으므로 그대로 두면 모든 시험이 검증 실패로 끝난다. {@code migrate} 프로필을
     * 켜는 대신 여기서 전략만 바꾸는 이유는 그 프로필이 웹 서버까지 끄기 때문이다. Story 1.2의
     * HTTP 회귀는 실제 서블릿 컨테이너를 필요로 한다.
     *
     * <p>{@code @Primary}가 기본 프로필의 검증 전략보다 이 bean을 먼저 고르게 한다.
     */
    @Bean
    @Primary
    FlywayMigrationStrategy testMigrationStrategy() {
        return Flyway::migrate;
    }
}
