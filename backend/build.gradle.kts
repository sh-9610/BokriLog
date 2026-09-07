import org.springframework.boot.gradle.plugin.SpringBootPlugin

/*
 * BokriLog 백엔드 빌드.
 *
 * 허용 버전 계열은 `ARCHITECTURE-SPINE.md`의 Stack 표가 소유한다(Java 25 LTS, Spring Boot 4.1.x).
 * 이 파일은 그 표가 확인한 기준선 4.1.1을 고정한다. Gradle 버전은 wrapper가 소유한다.
 *
 * 의존은 `spec-1-3-identity-ownership.md`가 승인한 범위다. 운영 classpath는 JDBC·Flyway·PostgreSQL
 * 드라이버(`identity` 저장소와 마이그레이션)까지이고, Spring Modulith(경계 검증)와
 * Testcontainers(실제 PostgreSQL 통합 시험)는 시험 classpath에만 있다. Actuator·Spring Security·
 * Spring Session JDBC·JPA는 아직 없으며, 각각을 필요로 하는 스토리의 SPEC이 목적과 함께 승인할 때
 * 들어온다.
 *
 * JPA를 쓰지 않는 것은 누락이 아니라 선택이다. `identity`가 하는 일은 상태 행을 잠그고, 읽고,
 * 술어가 붙은 UPDATE를 던지는 것뿐이라 `JdbcClient`면 SQL이 그대로 보인다. Hibernate의 dirty
 * checking과 flush 시점은 "커밋 직전 재검사"라는 이 모듈의 계약을 오히려 흐린다.
 *
 * 이제 백엔드 시험은 Docker를 전제한다. Testcontainers가 실제 PostgreSQL을 띄우며, 그 컨테이너
 * 없이 통과하는 `@SpringBootTest`는 이 모듈에 없다.
 */
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "com.bokrilog"
version = "0.0.1"

java {
    toolchain {
        // 자동 프로비저닝은 `gradle.properties`가 끈다. 로컬에 설치된 JDK 25만 쓴다.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    /*
     * Spring Boot Gradle 플러그인은 BOM을 자동으로 적용하지 않는다. `io.spring.dependency-management`
     * 플러그인을 하나 더 얹는 대신 플러그인이 노출하는 BOM 좌표를 platform으로 직접 인용한다.
     * 필수 경로에서 실행되는 외부 플러그인이 하나 줄고, 관리 버전의 출처는 그대로 Boot BOM이다.
     */
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    /*
     * Spring Modulith는 Boot BOM이 관리하지 않는다. 같은 platform 관용으로 자기 BOM을 인용한다.
     * 버전은 `ARCHITECTURE-SPINE.md` Stack 표의 검증 기준선 2.1.1이다.
     *
     * 시험 classpath에만 있다. 아래 `spring-modulith-starter-test` 설명을 참고한다.
     */
    testImplementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.1"))

    /*
     * Testcontainers는 Boot BOM이 이미 `testcontainers-bom`(2.0.5)을 import해 버전을 관리한다.
     * 여기서 BOM 좌표를 한 번 더 적으면 관리 버전이 두 곳에서 갈라질 자리가 생긴다.
     * 아래 Testcontainers 좌표에 버전을 적지 않는 것이 "Boot BOM이 소유한다"는 뜻이다.
     * 좌표는 2.x의 `testcontainers-<모듈>` 형태다. 1.x의 `org.testcontainers:postgresql`은
     * 이 BOM에 없어 버전이 해결되지 않는다.
     */

    implementation("org.springframework.boot:spring-boot-starter-web")
    // JdbcClient·DataSource·트랜잭션 관리자. JPA starter가 아니다.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Flyway 자동 구성(`FlywayMigrationStrategy` 포함)은 Boot 4의 별도 모듈이 소유한다.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    // PostgreSQL 18 방언 지원. `flyway-core`만으로는 PostgreSQL에 마이그레이션하지 못한다.
    implementation("org.flywaydb:flyway-database-postgresql")

    // 드라이버는 컴파일 시점에 참조하지 않는다. 코드가 JDBC 표준 밖으로 나가지 못하게 한다.
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    /*
     * 모듈 경계 검증은 시험만의 관심사다.
     *
     * `spring-modulith-starter-core`를 런타임 의존으로 두면 그 전이 의존인 ArchUnit(4.5MB)과
     * `spring-modulith-apt`가 `bokrilog-backend.jar`에 그대로 실려 나간다. 경계를 확인하는 것은
     * `ModuleBoundaryTest` 하나이고 운영 코드는 Modulith 타입을 참조하지 않는다. `package-info.java`도
     * 애노테이션 없이 javadoc만 담고 있어 컴파일에도 필요 없다. 그래서 시험 classpath에만 둔다.
     * 이 starter가 `spring-modulith-core`를 함께 가져오므로 `ApplicationModules`는 그대로 쓸 수 있다.
     *
     * 이 배치가 `spring-modulith-moments`의 문제도 함께 없앤다. 그 모듈의 `@Scheduled` 작업은
     * 전용 TaskScheduler가 없으면 non-daemon 스레드를 만들고, 그 스레드 하나가 `migrate` 프로필의
     * 일회성 프로세스를 영원히 붙든다(마이그레이션은 끝났는데 컨테이너가 종료하지 않는다).
     * 운영 classpath에 Modulith가 없으므로 그 스레드가 생길 자리 자체가 없다. AD-12의
     * "migration은 정상 종료하는 별도 단일 실행"은 이제 배치로 성립한다.
     */
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 경고를 남긴 채 통과시키지 않는다. 프런트엔드 ESLint의 `--max-warnings 0`과 같은 기준이다.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/*
 * 산출물 이름을 고정한다. `deploy/compose`와 `backend/Dockerfile`, CI artifact가 모두 이 한 경로를
 * 참조한다. 버전이 이름에 들어가면 그 세 곳이 버전 문자열을 따라다녀야 한다.
 */
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "bokrilog-backend.jar"
}

/*
 * 라이브러리용 plain jar는 만들지 않는다. `build/libs/`에 실행 가능한 jar 하나만 남겨
 * "어느 jar를 이미지에 넣는가"라는 질문 자체를 없앤다.
 */
tasks.named<Jar>("jar") {
    enabled = false
}
