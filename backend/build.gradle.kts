import org.springframework.boot.gradle.plugin.SpringBootPlugin

/*
 * BokriLog 백엔드 빌드.
 *
 * 허용 버전 계열은 `ARCHITECTURE-SPINE.md`의 Stack 표가 소유한다(Java 25 LTS, Spring Boot 4.1.x).
 * 이 파일은 그 표가 확인한 기준선 4.1.1을 고정한다. Gradle 버전은 wrapper가 소유한다.
 *
 * 의존은 `spec-1-3-identity-ownership.md`와 `spec-1-4-google-sign-in.md`가 승인한 범위다. 운영
 * classpath는 JDBC·Flyway·PostgreSQL 드라이버(`identity` 저장소와 마이그레이션)와 Spring Security
 * OAuth2 Client·Spring Session JDBC(Google OIDC 로그인과 PostgreSQL 세션)까지이고, Spring
 * Modulith(경계 검증)와 Testcontainers(실제 PostgreSQL 통합 시험)는 시험 classpath에만 있다.
 * Actuator와 Prometheus registry는 `spec-3-7-observability-foundation.md`가 관리 포트의 메트릭 노출로 승인했다.
 * JPA·`spring-security-test`는 아직 없으며, 각각을 필요로 하는 스토리의 SPEC이 목적과 함께 승인할 때 들어온다.
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

    /*
     * Google OIDC 로그인(`spec-1-4-google-sign-in.md`가 승인).
     *
     * AD-10이 요구하는 목록(Authorization Code + PKCE S256, 일회성 state·nonce, JWS 서명·허용
     * 알고리즘·JWKS와 iss·aud·azp·exp·nonce 검증, 세션 고정 방어, CSRF)을 이 starter가 계약으로
     * 제공한다. 직접 구현하면 JWKS 캐시·시계 오차·알고리즘 혼동을 우리가 다시 틀리게 만들 여지가
     * 열린다. 버전은 Boot BOM이 소유한다.
     *
     * 좌표에 `security-`가 붙는다. Boot 4의 `spring-boot-starter-oauth2-client`는 pom의 설명이
     * "deprecated in favor of spring-boot-starter-security-oauth2-client"라고 적은 별칭이며 내용은
     * 같다. 관리 버전이 아니라 이름만 다르므로 조용히 낮추는 것이 아니고, 폐기 예고된 이름을
     * 새로 들이지 않는 선택이다.
     */
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")

    /*
     * 세션은 PostgreSQL에 둔다(업무 데이터·세션 단일 저장소).
     *
     * Boot 4는 `spring.session.store-type`을 없애고 classpath로 store를 고른다. 그래서 이 좌표가
     * 곧 "세션 저장소는 JDBC"라는 선언이며, `spring-boot-session-jdbc` 자동 구성과
     * `spring-session-jdbc`를 함께 가져온다. Redis 세션을 쓰지 않는다.
     *
     * 스키마는 이 starter가 만들지 않는다. `initialize-schema: never`로 끄고
     * `V2__spring_session.sql`이 별도 migration 역할로 적용한다(AD-12).
     */
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")

    /*
     * CALC-1.0의 거듭제곱(`spec-2-7-first-plan-publication.md`가 승인).
     *
     * `(1 + r)^periods`의 지수는 부분 주기라 정수가 아니다. JDK `BigDecimal.pow`는 정수 지수만
     * 받으므로 비정수 지수를 `MathContext(34, HALF_EVEN)`으로 계산하는 버전 고정 구현이 필요하고,
     * ERD-1.0.0 §3.1이 그 구현으로 이 라이브러리를 지정했다. 버전을 올리면 같은 입력의 결과가
     * 마지막 자리에서 달라질 수 있으므로 범위 표기 없이 고정하고, 올릴 때는 계산 구현 버전
     * (`PlanCalculator.IMPLEMENTATION_VERSION`)을 함께 올린다.
     *
     * 쓰는 곳은 `calculation` 모듈 하나다. 전이 의존이 없다.
     */
    implementation("ch.obermuhlner:big-math:2.3.2")

    /*
     * 관측 계측(`spec-3-7-observability-foundation.md`가 승인).
     *
     * Actuator는 외부에 열리지 않는 관리 포트(`management.server.port`)에서 Prometheus 엔드포인트 하나만 연다. 공개 표면의
     * liveness는 그대로 `GET /api/status`다(`StatusController`). 메트릭 이름·tag는 루트 `TelemetryAllowlist`의 닫힌 표가
     * 정하고 그 밖의 meter는 `TelemetryConfig`의 `MeterFilter`가 거부한다. Actuator에 닿는 코드는 루트 설정 한 곳이고,
     * 모듈은 Micrometer `MeterRegistry`만 쓴다(`ModuleBoundaryTest`가 `org.springframework.boot.actuate`를 막는다).
     *
     * Prometheus registry의 버전은 Boot BOM이 관리한다. promtool(경보 규칙 시험)은 CI·개발 전용 도구라 여기 없다.
     */
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

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
    /*
     * 시험 클래스를 JVM(fork) 최대 셋에 나눈다(`spec-ci-test-forks.md`, deferred 163).
     *
     * CI `backend-quality`의 임계 경로는 이 시험 하나다(PR #129 실측 14분 19초, 그중 Gradle 시험 ≈14분).
     * 시험 1,610건(137 클래스, 2026-11-03 실측)이 JVM 하나에서 순차로 돌아 클래스 시간의 합(2026-11-03 로컬 기준
     * 보고서 136 클래스 ≈20분 — 상위 8개 파이프라인 클래스 12.6분 + 나머지 128개 7.7분)이 그대로 wall-clock이었다.
     *
     * 분배는 Gradle이 class 단위로 round-robin한다(Gradle 9.7.1 `MaxNParallelTestDefinitionProcessor`: 스캔 순서로
     * i번째 클래스를 fork i mod N에 보내고, 빈 fork에 다음 클래스를 주지 않는다). 그래서 wall-clock은 가장 무거운
     * fork의 합이고, 위 보고서로 무작위 순서를 모의한 최장 fork의 p50/p90은 2 fork 12.1/14.4분, 3 fork 9.3/11.5분,
     * 4 fork 7.9/9.9분이다. 3은 4코어 러너에서 worker JVM 셋 + PostgreSQL 셋 + Gradle 클라이언트 JVM(`ci.yml`은
     * `--no-daemon`이라 1회성)에 코어 하나를 남긴다는 가정으로 골랐다.
     *
     * 실제 fork 수는 `min(3, --max-workers)`이고 `--max-workers` 기본은 CPU 수다. CI 러너는 private 저장소의 기본
     * `ubuntu-24.04`라 4코어가 아니었다 — PR #133 실측에서 executor가 2개였고(764건·861건) `backend-quality`는
     * 14분 19초 → 10분 14초였다. 그래서 CI는 fork 2, 4코어 이상 로컬 기기는 fork 3으로 돈다. 2 vCPU에서 fork를 더
     * 늘리면 코어를 나눠 쓸 뿐이라 3을 그대로 둔다. 메모리는 worker JVM당 heap 상한 512 MB + metaspace·off-heap 약
     * 0.3 GB ≈ 0.8 GB의 추정이고, CI는 fork 둘이라 여유가 더 크다.
     *
     * JVM(fork)마다 PostgreSQL 컨테이너 하나다 — `PostgresTestcontainersConfig`의 static 컨테이너는 JVM 안에서만
     * 공유되고, `AuthFlowTestBase.resetStore`의 NOWAIT 전체 잠금도 그 컨테이너 안이다. 서버 포트는 전부 임의
     * (`RANDOM_PORT`·루프백 0)라 fork 사이에 DB·포트·파일을 공유하는 시험이 없다. JVM 안 병렬(JUnit 병렬)과
     * 컨테이너 공유(`reuse`)는 여전히 쓰지 않는다. worker heap은 Gradle 기본값(512 MB) 그대로다 — fork 하나가
     * 모든 Spring 컨텍스트를 담고 통과하던 값이라 fork마다 담는 컨텍스트가 줄어드는 쪽은 더 안전하다.
     *
     * `gradle.properties`의 `org.gradle.parallel=false`는 프로젝트 병렬이지 시험 fork가 아니라 그대로다.
     * Gradle은 `maxParallelForks`를 `--max-workers`로 상한하므로 `--max-workers=1`이면 fork 1로 접힌다.
     */
    maxParallelForks = 3
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    /*
     * fork 분배와 시험 수를 CI 로그에 남긴다 — 판정 없이 출력만이다.
     *
     * green 실행에서는 `ci.yml`이 `build/test-results`를 올리지 않고(실패·취소 때만 진단 artifact) 위 `events("failed")`는
     * 성공 때 아무것도 찍지 않으므로, 이 두 줄이 CI 로그에서 "시험 몇 건이 fork 몇 개에 어떻게 나뉘었나"를 보는
     * 유일한 자리다. fork가 `--max-workers`로 1에 접히면 executor 1개로 드러난다. executor 하나가 Gradle worker
     * JVM(fork) 하나다.
     */
    addTestListener(object : TestListener {
        private var rootStartMillis = 0L
        private var executors = 0

        override fun beforeSuite(suite: TestDescriptor) {
            if (suite.parent == null) {
                rootStartMillis = System.currentTimeMillis()
            }
        }

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println(
                    "[test-forks] 전체 ${result.testCount}건(성공 ${result.successfulTestCount}·실패 ${result.failedTestCount}" +
                        "·건너뜀 ${result.skippedTestCount}), executor ${executors}개",
                )
            } else if (suite.name.startsWith("Gradle Test Executor ")) {
                executors += 1
                val seconds = (result.endTime - result.startTime) / 1000.0
                val offset = (result.startTime - rootStartMillis) / 1000.0
                println(
                    "[test-forks] ${suite.name}: 시험 ${result.testCount}건, 길이 ${"%.1f".format(seconds)}초" +
                        "(시작 +${"%.1f".format(offset)}초)",
                )
            }
        }

        override fun beforeTest(testDescriptor: TestDescriptor) {}

        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
    })
}

/*
 * 산출물 이름을 고정한다. `deploy/compose`와 `backend/Dockerfile`, CI artifact가 모두 이 한 경로를
 * 참조한다. 버전이 이름에 들어가면 그 세 곳이 버전 문자열을 따라다녀야 한다.
 */
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "bokrilog-backend.jar"
}

/*
 * archive를 바이트 단위로 결정적이게 만든다(`spec-ci-backend-assemble-job.md`).
 *
 * CI는 같은 커밋에서 jar를 두 번 만든다 — `backend-assemble`(컴파일 + `bootJar`, 리허설·통합 job이 소비)과
 * `backend-quality`(`build`, 시험이 돈 바이트). `ci-required`가 두 jar의 SHA-256 일치를 요구해 "통합이 소비한
 * 바이트 = 시험이 검증한 바이트"를 증거로 유지한다. 기본값에서는 Gradle이 항목마다 파일 시스템의 mtime을 쓰고
 * 디렉터리 순회 순서로 항목을 넣으므로 같은 소스·같은 JDK라도 두 러너의 jar가 다르다. 타임스탬프를 고정값으로,
 * 항목 순서를 정렬로 바꾸면 bootJar를 포함한 모든 archive가 같은 입력에 같은 바이트를 낸다.
 */
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

/*
 * 라이브러리용 plain jar는 만들지 않는다. `build/libs/`에 실행 가능한 jar 하나만 남겨
 * "어느 jar를 이미지에 넣는가"라는 질문 자체를 없앤다.
 */
tasks.named<Jar>("jar") {
    enabled = false
}
