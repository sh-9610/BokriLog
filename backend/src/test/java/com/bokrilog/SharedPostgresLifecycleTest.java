package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextManager;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 공유 PostgreSQL 컨테이너의 수명은 Spring 컨텍스트가 갖지 않는다({@code spec-test-container-context-lifecycle.md}).
 *
 * <p>예전에는 컨테이너가 {@code @Bean @ServiceConnection}이라, 그 bean을 가진 컨텍스트 하나가 닫히면(로드 실패·캐시 축출) Spring
 * Boot의 {@code TestcontainersLifecycleBeanPostProcessor}가 JVM의 공유 컨테이너를 멈췄다. 같은 JVM의 다른 시험은
 * {@code Connection refused}로 연쇄 실패했다.
 *
 * <p>이 시험은 실제 시험과 같은 경로 — TestContext 프레임워크가 {@code @SpringBootTest}로 띄우는 컨텍스트, 공유 설정 import — 로
 * 프로브 컨텍스트를 띄운다. 정상 종료와 로드 실패(이번 사고의 실제 경로) 두 경우 모두 그 컨텍스트에 {@code Startable} bean이 없고
 * datasource 비밀번호가 설정값으로 들어오지 않았는지, 그리고 그 뒤 붙든 컨테이너가 같은 id로 살아 있고 SQL이 되는지 단정한다. 프로브
 * 구성은 이 클래스만의 캐시 키를 갖고, 닫는 것은 그 컨텍스트 하나다. 공유 컨테이너가 이 JVM에서 닫혔는지의 판정은 Docker 없이 따로 보고,
 * 끝으로 시험 소스를 훑어 컨테이너를 bean으로 노출하거나 주입받는 꼴이 다시 생기지 않게 막는다.
 */
class SharedPostgresLifecycleTest {

    /**
     * 프로브 컨텍스트의 구성. 컴포넌트 스캔 없이 datasource와 service connection 자동 구성만 든다. 평범한
     * {@code @Configuration}이다 — {@code @SpringBootConfiguration}이면 같은 패키지의 다른 시험이 찾는 부트 구성이 둘이 되고,
     * {@code @TestConfiguration}이면 Spring Boot가 운영 구성({@code BokriLogApplication})을 함께 올린다. 시험 클래스 안의 구성이라
     * {@code @SpringBootTest} 컨텍스트의 컴포넌트 스캔에는 걸리지 않는다({@code TestTypeExcludeFilter}). 그 필터가 없는 스캔(아래
     * {@link FailingProbeConfiguration})에 걸려도 이미 있는 자동 구성 둘을 import할 뿐이다.
     *
     * <p>{@code ServiceConnectionAutoConfiguration}은 지금 설정에는 필요 없지만 지우지 않는다. 옛 설정(컨테이너를 노출하는
     * {@code @Bean @ServiceConnection})은 이 자동 구성이 있어야 컨테이너를 접속 정보로 잇는다 — 실제 시험 컨텍스트(전체 자동 구성)와 같은
     * 조건이어야 이 시험이 옛 설정에서 회귀를 재현해 실패한다.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, ServiceConnectionAutoConfiguration.class})
    static class ProbeConfiguration {
    }

    /**
     * 기동 중 예외를 던지는 프로브 구성. datasource를 받아 접속 정보(옛 설정이면 컨테이너 bean)가 먼저 만들어진 뒤 실패하게 한다. 실패한
     * 컨텍스트는 시험이 받을 수 없으므로, 던지기 직전에 그 컨텍스트의 {@code Startable} bean 이름과 datasource 비밀번호 설정값을 남긴다.
     *
     * <p>stereotype({@code @Configuration})을 달지 않는다 — {@code @Bean} 메서드만 가진 lite 구성으로도 {@code classes}에 주면
     * 처리된다. 시험 프레임워크 밖에서 {@code BokriLogApplication}을 띄우는 시험({@code DeployAuditPendingMigrationTest})의 컴포넌트
     * 스캔에는 {@code TestTypeExcludeFilter}가 없어서, stereotype이 있으면 이 실패 bean이 그 컨텍스트에 들어간다.
     */
    @Import(ProbeConfiguration.class)
    static class FailingProbeConfiguration {

        static final String FAILURE = "프로브: 기동 중 실패";

        static volatile @Nullable List<String> startables;

        static volatile @Nullable String password;

        @Bean
        Object failsOnStartup(DataSource dataSource, ListableBeanFactory beans, Environment environment) {
            startables = startableBeans(beans);
            password = datasourcePassword(environment);
            throw new IllegalStateException(FAILURE);
        }
    }

    /** TestContext 프레임워크가 읽는 프로브 시험 클래스. 실제 시험처럼 공유 설정을 import한다. 연결은 하나면 된다. */
    @SpringBootTest(classes = ProbeConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {"spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=0"})
    @Import(PostgresTestcontainersConfig.class)
    static class ProbeTest {
    }

    /** 로드에 실패하는 프로브 시험 클래스. */
    @SpringBootTest(classes = FailingProbeConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {"spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=0"})
    @Import(PostgresTestcontainersConfig.class)
    static class FailingProbeTest {
    }

    /**
     * 시험 소스에서 거부하는 꼴. 주석·문자열을 지운 코드에 맞춘다({@link #code}). 이름 → 패턴이고, 이름은 실패 문장에 실린다.
     */
    private static final Map<String, Pattern> FORBIDDEN = forbidden();

    private static Map<String, Pattern> forbidden() {
        Map<String, Pattern> forbidden = new LinkedHashMap<>();
        // 컨테이너를 접속 정보 bean으로 잇는 표지(한정 이름 포함, 줄 어디든)와 그 import·패키지 와일드카드 import.
        forbidden.put("@ServiceConnection", Pattern.compile("@(?:[\\w.]+\\.)?ServiceConnection\\b"
                + "|import\\s+(?:static\\s+)?[\\w.]+\\.ServiceConnection\\s*;"
                + "|import\\s+[\\w.]*\\bservice\\.connection\\.\\*\\s*;"));
        // Testcontainers JUnit 확장·Boot의 컨테이너 import — 컨테이너 수명을 시험 클래스나 컨텍스트에 묶는다.
        forbidden.put("@Container·@Testcontainers·@ImportTestcontainers",
                Pattern.compile("@(?:[\\w.]+\\.)?(?:Container|Testcontainers|ImportTestcontainers)\\b"));
        // 컨테이너를 돌려주는 @Bean 메서드(사이의 다른 annotation·수식어 허용).
        forbidden.put("컨테이너를 돌려주는 @Bean", Pattern.compile("@(?:[\\w.]+\\.)?Bean\\b(?:\\s*\\([^)]*\\))?"
                + "(?:\\s*@[\\w.]+(?:\\s*\\([^)]*\\))?)*\\s*(?:(?:public|protected|private|static|final)\\s+)*"
                + "(?:[\\w.]+\\.)?(?:PostgreSQLContainer|GenericContainer|Startable)\\b"));
        // 주입 annotation에서 다음 세미콜론·여는 중괄호 전까지(필드·생성자·메서드 매개변수)에 컨테이너 타입이 있음.
        forbidden.put("컨테이너 주입(@Autowired·@Inject·@Resource)",
                Pattern.compile("@(?:[\\w.]+\\.)?(?:Autowired|Inject|Resource)\\b[^;{]*?\\bPostgreSQLContainer\\b"));
        return forbidden;
    }

    @Test
    void 컨텍스트가_닫혀도_공유_컨테이너는_같은_id로_살아_있고_SQL이_된다() throws Exception {
        PostgreSQLContainer container = PostgresTestcontainersConfig.shared();
        String containerId = container.getContainerId();

        TestContext testContext = new TestContextManager(ProbeTest.class).getTestContext();
        ApplicationContext context = testContext.getApplicationContext();
        try {
            // 컨텍스트는 공유 컨테이너의 접속 정보를 받았고(어느 설정이 줬든 datasource가 읽는 그 값), 그 datasource로 실제로 닿는다.
            assertThat(context.getBean(JdbcConnectionDetails.class).getJdbcUrl()).isEqualTo(container.getJdbcUrl());
            try (Connection connection = context.getBean(DataSource.class).getConnection()) {
                assertThat(currentDatabase(connection)).isEqualTo(container.getDatabaseName());
            }
            assertNothingStartableAndNoPassword(startableBeans(context), datasourcePassword(context.getEnvironment()));
        } finally {
            // 단정이 실패해도 그 컨텍스트를 캐시에서 빼고 닫는다 — 캐시 축출과 같은 경로다.
            testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        }
        assertThat(((ConfigurableApplicationContext) context).isActive()).isFalse();

        assertStillTheSameContainer(container, containerId);
    }

    @Test
    void 컨텍스트_로드가_실패해도_공유_컨테이너는_같은_id로_살아_있고_SQL이_된다() throws Exception {
        PostgreSQLContainer container = PostgresTestcontainersConfig.shared();
        String containerId = container.getContainerId();
        FailingProbeConfiguration.startables = null;
        FailingProbeConfiguration.password = null;

        TestContext testContext = new TestContextManager(FailingProbeTest.class).getTestContext();
        // 실패 원인은 그대로 보고된다.
        assertThatThrownBy(testContext::getApplicationContext).isInstanceOf(IllegalStateException.class)
                .rootCause().hasMessage(FailingProbeConfiguration.FAILURE);
        assertThat(FailingProbeConfiguration.startables).as("실패 bean이 그 컨텍스트를 기록했다").isNotNull();
        assertNothingStartableAndNoPassword(FailingProbeConfiguration.startables, FailingProbeConfiguration.password);

        assertStillTheSameContainer(container, containerId);
    }

    @Test
    void 처음_시작한_id와_다르거나_빈_id는_공유_컨테이너가_이_JVM에서_닫힌_것으로_실패한다() {
        // 공유 컨테이너를 멈추지 않는다 — 판정은 값만 받는다.
        assertThatNoException().isThrownBy(() -> PostgresTestcontainersConfig.requireSameContainer(null, null));
        assertThatNoException().isThrownBy(() -> PostgresTestcontainersConfig.requireSameContainer(null, "first-container"));
        assertThatNoException().isThrownBy(
                () -> PostgresTestcontainersConfig.requireSameContainer("first-container", "first-container"));

        assertThatThrownBy(() -> PostgresTestcontainersConfig.requireSameContainer("first-container", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이 JVM에서 닫혔습니다")
                .hasMessageContaining("처음 id first-container, 지금 id null")
                .hasMessageContaining("PostgresTestcontainersConfig.shared()")
                .hasMessageContaining("JdbcConnectionDetails");
        assertThatThrownBy(() -> PostgresTestcontainersConfig.requireSameContainer("first-container", "second-container"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("처음 id first-container, 지금 id second-container");
    }

    @Test
    void 시험_소스는_공유_컨테이너를_bean으로_노출하거나_주입받지_않는다() throws IOException {
        // 패턴마다 겨냥한 꼴은 잡고, 설명·이름만 같은 꼴은 놓아 두는지 먼저 본다.
        rejects("@ServiceConnection", "    @Bean\n    @ServiceConnection\n    PostgreSQLContainer c() {");
        rejects("@ServiceConnection", "    @Bean @ServiceConnection PostgreSQLContainer c() {");
        rejects("@ServiceConnection",
                "    @org.springframework.boot.testcontainers.service.connection.ServiceConnection\n");
        rejects("@ServiceConnection", "import org.springframework.boot.testcontainers.service.connection.ServiceConnection;");
        rejects("@ServiceConnection", "import org.springframework.boot.testcontainers.service.connection.*;");
        allows("@ServiceConnection", "    /** 예전에는 {@code @Bean @ServiceConnection}이라 */\n");
        allows("@ServiceConnection", "    // @ServiceConnection은 쓰지 않는다\n");
        allows("@ServiceConnection", "    String why = \"@ServiceConnection으로 노출하면\";\n");
        allows("@ServiceConnection",
                "import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration;");

        rejects("@Container·@Testcontainers·@ImportTestcontainers", "    @Container\n    static PostgreSQLContainer c;");
        rejects("@Container·@Testcontainers·@ImportTestcontainers", "@Testcontainers\nclass FooTest {");
        rejects("@Container·@Testcontainers·@ImportTestcontainers",
                "@org.springframework.boot.testcontainers.context.ImportTestcontainers(Foo.class)");
        allows("@Container·@Testcontainers·@ImportTestcontainers", "@ContainerTag\nclass FooTest {");
        allows("@Container·@Testcontainers·@ImportTestcontainers", "    /** {@code @Container} 필드로 노출하면 */\n");

        rejects("컨테이너를 돌려주는 @Bean", "    @Bean\n    PostgreSQLContainer postgres() {");
        rejects("컨테이너를 돌려주는 @Bean", "    @Bean(destroyMethod = \"\")\n    static GenericContainer<?> c() {");
        rejects("컨테이너를 돌려주는 @Bean", "    @Bean @Primary org.testcontainers.lifecycle.Startable s() {");
        allows("컨테이너를 돌려주는 @Bean", "    @Bean\n    JdbcConnectionDetails details() {");

        rejects("컨테이너 주입(@Autowired·@Inject·@Resource)", "    @Autowired\n    PostgreSQLContainer postgres;");
        rejects("컨테이너 주입(@Autowired·@Inject·@Resource)", "@org.springframework.beans.factory.annotation.Autowired "
                + "org.testcontainers.postgresql.PostgreSQLContainer postgres;");
        rejects("컨테이너 주입(@Autowired·@Inject·@Resource)", "    @Inject PostgreSQLContainer postgres;");
        rejects("컨테이너 주입(@Autowired·@Inject·@Resource)", "    void set(@Resource PostgreSQLContainer postgres) {");
        allows("컨테이너 주입(@Autowired·@Inject·@Resource)", "@Autowired PlanEndApi planEnd;\n    PostgreSQLContainer postgres;");

        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Pattern> rule : FORBIDDEN.entrySet()) {
            for (String file : holders(rule.getValue())) {
                found.add(rule.getKey() + " — " + file);
            }
        }
        assertThat(found).withFailMessage("공유 PostgreSQL 컨테이너를 Spring bean으로 노출하거나 주입받으면(또는 Testcontainers 확장에 "
                + "맡기면), 그 컨텍스트·시험 클래스가 끝날 때 JVM의 공유 컨테이너가 멈추고 같은 JVM의 뒤 시험이 연쇄 실패한다"
                + "(spec-test-container-context-lifecycle.md). 컨테이너가 필요하면 PostgresTestcontainersConfig.shared()를, "
                + "컨텍스트의 접속 정보는 그 설정의 JdbcConnectionDetails bean을 쓴다: %s", found).isEmpty();
    }

    private static void rejects(String rule, String sample) {
        assertThat(FORBIDDEN.get(rule).matcher(code(sample)).find()).as("%s가 거부해야 한다: %s", rule, sample).isTrue();
    }

    private static void allows(String rule, String sample) {
        assertThat(FORBIDDEN.get(rule).matcher(code(sample)).find()).as("%s가 놓아 두어야 한다: %s", rule, sample).isFalse();
    }

    private static void assertNothingStartableAndNoPassword(@Nullable List<String> startables, @Nullable String password) {
        // 컨테이너를 닫을 수 있는 Startable bean이 컨텍스트에 없다(Boot의 lifecycle post-processor가 닫는 대상).
        assertThat(startables).as("프로브 컨텍스트의 Startable bean").isEmpty();
        // 비밀번호는 JdbcConnectionDetails로만 오고 설정값으로 들어오지 않는다 — application.yml의 기본값(빈 값)만 남는다.
        assertThat(password).as("프로브 컨텍스트의 spring.datasource.password").isNullOrEmpty();
    }

    private static List<String> startableBeans(ListableBeanFactory beans) {
        // 아직 만들지 않은 bean도 선언 타입으로 본다(초기화를 일으키지 않는다).
        return List.of(beans.getBeanNamesForType(Startable.class, true, false));
    }

    private static @Nullable String datasourcePassword(Environment environment) {
        return environment.getProperty("spring.datasource.password");
    }

    /** 다시 시작하는 {@code shared()}를 부르기 전에 붙든 인스턴스가 멈추지 않았음을 먼저 보고, 그다음 같은 컨테이너로 SQL을 낸다. */
    private static void assertStillTheSameContainer(PostgreSQLContainer container, String containerId)
            throws Exception {
        assertThat(container.isRunning()).isTrue();
        assertThat(container.getContainerId()).isEqualTo(containerId);

        PostgreSQLContainer after = PostgresTestcontainersConfig.shared();
        assertThat(after.getContainerId()).isEqualTo(containerId);
        try (Connection connection = after.createConnection("")) {
            assertThat(currentDatabase(connection)).isEqualTo(after.getDatabaseName());
        }
    }

    private static String currentDatabase(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT current_database()")) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** 그 꼴이 있는 시험 소스({@code src/test/java} 기준 경로). 패턴 표본을 문자열로 든 이 파일 자신은 뺀다. */
    private static List<String> holders(Pattern pattern) throws IOException {
        Path root = Path.of("src/test/java");
        assertThat(root)
                .withFailMessage("시험 소스 %s를 찾지 못했다 — 이 시험은 backend 디렉터리를 working dir로 돈다(Gradle 기본).",
                        root.toAbsolutePath())
                .isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("SharedPostgresLifecycleTest.java"))
                    .filter(path -> pattern.matcher(code(read(path))).find())
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 주석과 문자열·문자 리터럴(text block 포함)을 공백으로 바꾼 코드. 설명 속의 {@code @ServiceConnection}이나 실패 문장 속의
     * 이름은 거부 대상이 아니다. 줄바꿈은 남긴다.
     */
    static String code(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                blank(out, source, i, stop);
                i = stop;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                blank(out, source, i, stop);
                i = stop;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int stop = Math.min(n, j + 1);
                out.append(' ');
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 지운 구간의 줄바꿈만 남긴다. */
    private static void blank(StringBuilder out, String source, int from, int to) {
        out.append(' ');
        for (int k = from; k < to; k++) {
            if (source.charAt(k) == '\n') {
                out.append('\n');
            }
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(path.toString(), failure);
        }
    }
}
