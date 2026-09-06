import org.springframework.boot.gradle.plugin.SpringBootPlugin

/*
 * BokriLog 백엔드 빌드.
 *
 * 허용 버전 계열은 `ARCHITECTURE-SPINE.md`의 Stack 표가 소유한다(Java 25 LTS, Spring Boot 4.1.x).
 * 이 파일은 그 표가 확인한 기준선 4.1.1을 고정한다. Gradle 버전은 wrapper가 소유한다.
 *
 * 의존은 `web`과 `test` 두 starter뿐이다. Spring Modulith·Actuator·Spring Security·PostgreSQL은
 * 각각 Story 1.3 이후가 도입하며 그때 SPEC이 목적과 함께 승인한다. 데이터베이스·사용자 테이블·
 * 제공자 자격정보 없이 기동하는 것이 이 스토리의 계약이다.
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

    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
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
