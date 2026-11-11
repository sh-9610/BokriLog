package com.bokrilog;

import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
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
 * 하나만 뜬다. 이미 시작된 컨테이너에 대한 {@code start()}는 아무 일도 하지 않는다. 컨텍스트는 접속 정보만 받고 컨테이너를
 * 소유하지 않으므로 컨텍스트가 닫혀도 컨테이너는 같은 id·같은 포트로 남는다(아래 {@link #sharedPostgresConnectionDetails()}).
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

    /** 이 JVM에서 처음 시작한 공유 컨테이너의 id. 시작 전에는 비어 있다({@link #started()}만 쓴다). */
    private static @Nullable String firstContainerId;

    /**
     * Spring 컨텍스트의 데이터베이스 접속 정보. 컨텍스트가 연결을 만들기 전에 여기서 컨테이너를 시작해 시계 차이를 재고(아래
     * {@link #started()}), 컨테이너가 준 JDBC URL·사용자·비밀번호·드라이버를 그대로 돌려준다 — 예전 {@code @ServiceConnection}이
     * 맞추던 datasource 연결과 같은 값이다. Boot의 datasource 자동 구성은 이 bean이 있으면 설정값 기반 접속 정보를 만들지 않는다.
     * {@code spring.datasource.*} 설정값을 들이지 않으므로 비밀번호가 시험 컨텍스트의 Environment에 들어가지 않는다
     * ({@code application.yml} 머리 주석이 말하는 그 경로다).
     *
     * <p><b>컨테이너를 bean으로 노출하지 않는다.</b> 컨테이너 수명은 JVM({@code static})과 Testcontainers의 ryuk이 갖고, 어떤 Spring
     * 컨텍스트도 갖지 않는다. 예전처럼 {@code @Bean @ServiceConnection}으로 노출하면 Spring Boot의
     * {@code TestcontainersLifecycleBeanPostProcessor}가 그 {@code Startable} bean을 가진 컨텍스트가 닫힐 때 {@code close()}를
     * 부른다({@code destroyMethod = ""}로도 막히지 않는다). 그러면 컨텍스트 하나의 종료 — 로드 실패, 컨텍스트 캐시 축출 — 가 JVM
     * 전체의 공유 컨테이너를 멈추고, 같은 JVM의 다른 시험이 {@code Connection refused}로 연쇄 실패하거나, 다시 뜬 컨테이너가 새 포트를
     * 받아 캐시에 남은 컨텍스트가 죽은 포트를 계속 쓴다({@code spec-test-container-context-lifecycle.md}).
     * {@link JdbcConnectionDetails}는 {@code Startable}이 아니라 그 post-processor가 닫지 않는다. 고정은
     * {@code SharedPostgresLifecycleTest}다.
     */
    @Bean
    JdbcConnectionDetails sharedPostgresConnectionDetails() {
        PostgreSQLContainer container = started();
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return container.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return container.getUsername();
            }

            @Override
            public String getPassword() {
                return container.getPassword();
            }

            @Override
            public String getDriverClassName() {
                return container.getDriverClassName();
            }
        };
    }

    /**
     * 컨테이너 자체에 닿아야 하는 시험이 쓴다 — Spring 컨텍스트 없이 닿는 시험(Story 3.8: 단일 Worker 잠금, 배포 감사의 schema
     * 준비)과, 컨텍스트 안에서 별도 데이터베이스를 만들어 옛 마이그레이션을 올리는 시험(컨테이너는 bean이 아니므로 주입받지 않는다). 이미
     * 시작됐으면 그대로 돌려준다 — JVM 하나에 컨테이너 하나라는 위 계약이 그대로다.
     */
    public static PostgreSQLContainer shared() {
        return started();
    }

    /**
     * 컨테이너를 시작하고(이미 시작됐으면 아무 일도 하지 않는다) JVM↔DB 시계 차이를 확인한다. 차이가 상한을 넘으면 두 시각·방향과
     * 대처를 말하며 실패한다({@link DatabaseClockSkew}, Epic 4 회고 AI-6).
     *
     * <p>처음 시작한 컨테이너의 id를 기억하고, 그 뒤의 진입에서 지금 id가 그것과 다르거나 비었으면 이 JVM 안에서 누군가 공유 컨테이너를
     * 닫은 것으로 보고 실패한다({@link #requireSameContainer}). 조용히 새로 띄우지 않는다 — 새로 띄우면 새 포트를 받아, 앞서 접속 정보를
     * 받은 컨텍스트들이 죽은 포트로 엉뚱한 단정에서 실패한다. Docker를 조회하지 않으므로 바깥에서 죽인 컨테이너(id는 그대로)는 이 검사가
     * 잡지 않는다 — 그때는 시험의 {@code Connection refused}가 원인 문장이다.
     */
    private static synchronized PostgreSQLContainer started() {
        if (firstContainerId == null) {
            CONTAINER.start();
            firstContainerId = CONTAINER.getContainerId();
        } else {
            requireSameContainer(firstContainerId, CONTAINER.getContainerId());
        }
        DatabaseClockSkew.verify(CONTAINER);
        return CONTAINER;
    }

    /**
     * 공유 컨테이너가 이 JVM에서 닫히지 않았는지의 판정. 처음 id가 없으면(아직 시작 전) 통과, 지금 id가 처음 id와 같으면 통과, 지금
     * id가 비었거나 다르면 — Testcontainers의 {@code stop()}·{@code close()}는 id를 비우고, 그 뒤의 {@code start()}는 새 id를
     * 받는다 — 처음·지금 id와 대처를 말하며 실패한다. 컨테이너 없이 부를 수 있게 값만 받는다({@code SharedPostgresLifecycleTest}).
     *
     * @throws IllegalStateException 공유 컨테이너가 이 JVM에서 닫혔을 때
     */
    static void requireSameContainer(@Nullable String firstId, @Nullable String currentId) {
        if (firstId == null || firstId.equals(currentId)) {
            return;
        }
        throw new IllegalStateException("공유 시험 PostgreSQL 컨테이너가 이 JVM에서 닫혔습니다(처음 id " + firstId + ", 지금 id "
                + currentId + "). 이 JVM의 남은 DB 시험은 이어 갈 수 없습니다. 공유 컨테이너를 Spring bean·@Container 필드로 "
                + "노출하거나 stop()·close()를 부른 곳을 찾으십시오 — 컨테이너는 PostgresTestcontainersConfig.shared()로, "
                + "컨텍스트의 접속 정보는 그 설정의 JdbcConnectionDetails bean으로만 받습니다.");
    }

    /**
     * 이미 뜬 컨테이너의 시계 차이를 다시 확인한다 — 붙들어 둔 결과가 새로우면 비교 하나뿐이고, 오래됐으면 다시 잰다. 시험 메서드마다
     * 도는 저장소 초기화({@code AuthFlowTestBase.resetStore})가 불러 긴 빌드 도중의 시계 밀림을 잡는다.
     */
    static void verifyClock() {
        DatabaseClockSkew.verify(CONTAINER);
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
