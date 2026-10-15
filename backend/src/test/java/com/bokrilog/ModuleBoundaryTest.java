package com.bokrilog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 모듈 경계.
 *
 * <p>Story 1.3이 {@code identity}로 이 경계를 세웠고 Story 2.1이 {@code source}·{@code portfolio}·
 * {@code operations} 셋을 더했다. 이 시험은 그 경계가 문서가 아니라 검사로 존재하게 한다. Spring
 * 컨텍스트를 띄우지 않는다 — 확인하는 것이 실행 시점의 동작이 아니라 컴파일된 바이트코드의
 * 구조이기 때문이며, 그래서 이 시험은 데이터베이스도 Docker도 쓰지 않는다.
 *
 * <p>시험 클래스는 검사 대상에서 뺀다. 시험은 자기가 검증하는 모듈의 내부를 들여다볼 수 있어야
 * 한다. 경계가 막는 것은 운영 코드의 의존이다.
 */
class ModuleBoundaryTest {

    private static final String IDENTITY = "com.bokrilog.identity";

    /**
     * 이 저장소의 모듈 전부.
     *
     * <p><b>목록이 하나다.</b> 존재 단정과 {@code internal} 미노출 규칙이 같은 목록을 돌므로 모듈을
     * 더할 때 한쪽만 늘어나는 자리가 없다 — 존재 단정을 빠뜨리면 {@code verify()}는 검사할 것이
     * 없어 조용히 통과하고, 미노출 규칙을 빠뜨리면 그 모듈의 내부가 밖에서 보인다.
     */
    private static final List<String> MODULES =
            List.of("identity", "source", "portfolio", "operations", "sync", "plan", "calculation", "engagement");

    private static final String CALCULATION = "com.bokrilog.calculation";

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.bokrilog");

    static List<String> modules() {
        return MODULES;
    }

    @Test
    void 모듈_구조가_Spring_Modulith_검증을_통과한다() {
        ApplicationModules modules = ApplicationModules.of(BokriLogApplication.class);

        // 모듈이 실제로 인식되는지부터 본다. 패키지가 루트로 옮겨지거나 이름이 바뀌면
        // `verify()`는 검사할 것이 없어 조용히 통과한다.
        for (String module : MODULES) {
            assertThat(modules.getModuleByName(module))
                    .withFailMessage("모듈 %s가 이름으로 발견되지 않는다.", module)
                    .isPresent();
        }

        // 순환 의존, 다른 모듈의 내부 패키지 참조, 선언하지 않은 모듈 간 의존을 모두 여기서 막는다.
        modules.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modules")
    void 모듈_밖에서는_internal_패키지에_닿지_못한다(String module) {
        String basePackage = "com.bokrilog." + module;
        ArchRule rule =
                noClasses()
                        .that()
                        .resideOutsideOfPackage(basePackage + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage(basePackage + ".internal..")
                        .because(
                                "다른 모듈은 이 모듈의 공개 application API로만 협력한다. "
                                        + "내부 구현과 소유 테이블에 직접 닿는 경로를 만들지 않는다.");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void identity는_어떤_HTTP_표면도_갖지_않는다() {
        // 애노테이션도 ArchUnit의 의존으로 잡힌다. `@RestController`·`@RequestMapping`을 붙이는
        // 순간 이 규칙이 깨지고, 서블릿·MVC 타입을 참조하는 경로도 함께 막힌다.
        //
        // Actuator와 Boot의 웹 인프라도 함께 막는다. `package-info.java`와 `DEVELOPMENT.md`는
        // "컨트롤러도 엔드포인트도 Actuator도 없다"를 계약으로 적는데, `@Endpoint` 하나면 MVC
        // 애노테이션 없이도 identity 상태가 HTTP로 나간다. Story 3.7부터 Actuator가 운영 classpath에 있으므로
        // (관리 포트의 Prometheus 하나, 루트 `TelemetryConfig`만 닿는다) 이 줄은 이제 실제로 막는 검사다.
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage(IDENTITY + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "org.springframework.web..",
                                "org.springframework.http..",
                                "org.springframework.boot.webmvc..",
                                "org.springframework.boot.actuate..",
                                "org.springframework.boot.web..",
                                "jakarta.servlet..")
                        .because(
                                "계정 생성·상태 변경·삭제에 HTTP로 닿는 경로를 만들지 않는다. "
                                        + "identity 기능은 모듈 공개 API로만 호출한다.");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * Actuator에 닿는 코드는 루트 관측 설정 한 곳이다(Story 3.7). 모듈과 다른 루트 클래스는 Micrometer {@code MeterRegistry}만
     * 쓴다 — Actuator 엔드포인트·자동 구성 타입에 닿는 순간 노출 범위가 설정 한 곳({@code TelemetryConfig}·{@code
     * application.yml})이 아니라 코드 여러 곳에서 정해진다.
     */
    @Test
    void actuator_의존은_루트_관측_설정에만_있다() {
        ArchRule rule =
                noClasses()
                        .that()
                        .haveNameNotMatching("com\\.bokrilog\\.TelemetryConfig(\\$.*)?")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("org.springframework.boot.actuate..")
                        .because("Actuator 노출은 루트 TelemetryConfig와 application.yml 한 곳이 정한다.");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * 모듈은 HTTP 표면을 갖지 않는다.
     *
     * <p>{@code identity} 전용 규칙보다 한 겹 느슨하다 — {@code org.springframework.http}와
     * {@code boot.web}을 목록에서 뺐다. {@code source}는 제공자 API를 <b>부르고</b> 그 호출은
     * {@code java.net.http}를 지나며, {@code SourceProperties}는 Boot의 설정 바인딩을 쓴다. 막는
     * 것은 "밖에서 이 모듈에 닿는 경로"이지 "이 모듈이 밖으로 나가는 경로"가 아니다 — 컨트롤러·
     * 엔드포인트·서블릿 의존이 그 판정의 대상이고, 연결 표면·재인증·허가·세션 회전은 전부 루트
     * 패키지가 소유한다({@code BybitConnectionController}).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("modules")
    void 모듈은_컨트롤러도_서블릿_의존도_갖지_않는다(String module) {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("com.bokrilog." + module + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "org.springframework.web..",
                                "org.springframework.boot.webmvc..",
                                "org.springframework.boot.actuate..",
                                "jakarta.servlet..")
                        .because(
                                "모듈에는 컨트롤러도 엔드포인트도 없다. HTTP 표면과 세션 정책은 "
                                        + "루트 패키지가 소유한다.");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * 계산 모듈은 순수하다(Story 2.7 AC-3).
     *
     * <p>다른 모듈·Spring·JDBC·HTTP·직렬화 라이브러리에 닿지 않는다. 그래서 Bybit DTO도, 저장소도,
     * 트랜잭션도 이 모듈에서 보이지 않는다 — 입력은 provider 중립 값 객체 하나다.
     */
    @Test
    void calculation은_다른_모듈과_프레임워크에_의존하지_않는다() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage(CALCULATION + "..")
                        .should()
                        .dependOnClassesThat(
                                JavaClass.Predicates.resideInAPackage("com.bokrilog..")
                                        .and(DescribedPredicate.not(
                                                JavaClass.Predicates.resideInAPackage(CALCULATION + ".."))))
                        .orShould()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "org.springframework..",
                                "java.sql..",
                                "javax.sql..",
                                "java.net..",
                                "jakarta..",
                                "com.fasterxml..",
                                "tools.jackson..")
                        .because("계산기는 provider 중립 값 객체만 받는 순수 함수다.");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * 계산 모듈은 시계를 읽지 않고 이진 부동소수점을 쓰지 않는다.
     *
     * <p>기준시각은 호출자가 명시적으로 넘긴다. {@code now()}라는 이름의 호출은 어느 타입의 것이든
     * 막는다 — {@code Instant.now()}만 막으면 {@code LocalDateTime.now()}가 같은 구멍이 된다.
     * {@code double}·{@code float}로 가는 {@code BigDecimal} 변환도 함께 막는다.
     */
    @Test
    void calculation은_시계도_부동소수점_변환도_쓰지_않는다() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage(CALCULATION + "..")
                        .should()
                        .callMethodWhere(JavaCall.Predicates.target(HasName.Predicates.name("now")))
                        .orShould()
                        .callMethod(System.class, "currentTimeMillis")
                        .orShould()
                        .callMethod(System.class, "nanoTime")
                        .orShould()
                        .callMethod(java.time.ZoneId.class, "systemDefault")
                        .orShould()
                        .dependOnClassesThat()
                        .areAssignableTo(java.time.Clock.class)
                        .orShould()
                        .callMethod(java.math.BigDecimal.class, "doubleValue")
                        .orShould()
                        .callMethod(java.math.BigDecimal.class, "floatValue")
                        .orShould()
                        .callMethod(java.math.BigDecimal.class, "valueOf", double.class)
                        .orShould()
                        .callConstructor(java.math.BigDecimal.class, double.class)
                        .because("같은 입력은 실행 시각과 관계없이 같은 결과여야 하고, 금융 계산은 "
                                + "BigDecimal과 MathContext(34, HALF_EVEN)만 쓴다.");

        rule.check(PRODUCTION_CLASSES);
    }

    /** {@code double}·{@code float}와 그 wrapper. 형 자체가 금융 계산에 들어오는 통로다. */
    private static final Set<String> BINARY_FLOATING =
            Set.of("double", "float", Double.class.getName(), Float.class.getName());

    /**
     * 계산 모듈은 이진 부동소수점 값을 선언하지도 주고받지도 않는다.
     *
     * <p>변환 호출만 막으면 {@code double} 필드나 인자로 들어온 값이 {@code BigDecimal}을 거치지 않고
     * 산술에 쓰일 수 있다. 필드·인자·반환형을 함께 막고, {@code Math}·{@code StrictMath}의 부동소수점
     * 함수와 문자열 → 부동소수점 파싱도 막는다.
     */
    @Test
    void calculation은_부동소수점_형과_Math_함수를_쓰지_않는다() {
        ArchRule fields = noFields()
                .that().areDeclaredInClassesThat().resideInAPackage(CALCULATION + "..")
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .because("금융 계산은 BigDecimal만 쓴다.");
        fields.check(PRODUCTION_CLASSES);

        ArchRule signatures = codeUnits()
                .that().areDeclaredInClassesThat().resideInAPackage(CALCULATION + "..")
                .should(new ArchCondition<JavaCodeUnit>("not take or return double or float") {
                    @Override
                    public void check(JavaCodeUnit unit, ConditionEvents events) {
                        boolean floating = BINARY_FLOATING.contains(unit.getRawReturnType().getName())
                                || unit.getRawParameterTypes().stream()
                                        .anyMatch(type -> BINARY_FLOATING.contains(type.getName()));
                        if (floating)
                            events.add(SimpleConditionEvent.violated(unit,
                                    unit.getFullName() + " takes or returns a binary floating-point type"));
                    }
                })
                .because("금융 계산은 BigDecimal만 쓴다.");
        signatures.check(PRODUCTION_CLASSES);

        ArchRule calls = noClasses()
                .that().resideInAPackage(CALCULATION + "..")
                .should().callMethod(Math.class, "pow", double.class, double.class)
                .orShould().callMethod(Math.class, "log", double.class)
                .orShould().callMethod(Math.class, "log10", double.class)
                .orShould().callMethod(Math.class, "exp", double.class)
                .orShould().callMethod(StrictMath.class, "pow", double.class, double.class)
                .orShould().callMethod(StrictMath.class, "log", double.class)
                .orShould().callMethod(StrictMath.class, "exp", double.class)
                .orShould().callMethod(Double.class, "parseDouble", String.class)
                .orShould().callMethod(Double.class, "valueOf", String.class)
                .orShould().callMethod(Float.class, "parseFloat", String.class)
                .because("거듭제곱은 버전 고정 big-math pow이고 입력은 BigDecimal로만 읽는다.");
        calls.check(PRODUCTION_CLASSES);
    }

    /**
     * 버전 고정 big-math는 계산 모듈만 쓴다. 다른 모듈이 직접 부르면 같은 정책 버전 아래에서 두 곳의
     * 거듭제곱이 따로 진화한다 — 구현 버전 문자열({@code PlanCalculator.IMPLEMENTATION_VERSION})이
     * 책임지는 범위를 벗어난다.
     */
    @Test
    void big_math는_calculation만_의존한다() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(CALCULATION + "..")
                .should().dependOnClassesThat().resideInAPackage("ch.obermuhlner..")
                .because("CALC-1.0의 거듭제곱 구현은 calculation 모듈 하나가 소유한다.");
        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * 계정 삭제는 루트 orchestrator 한 곳이다(Story 3.10 AC-4). 삭제 전환·payload 삭제와 tombstone 기록을 부르는 운영 클래스는 {@code
     * AccountErasure} 하나다 — 다른 자리가 삭제 전환을 직접 부르면 tombstone 없는 탈퇴, 모듈 데이터가 남은 탈퇴가 생긴다. 세션 전체
     * 폐기는 복원 적용({@code RestoreApplication}) 한 곳이다. 두 API를 구현하는 모듈 안쪽은 이 규칙 밖이다.
     */
    @Test
    void 계정_삭제와_세션_전체_폐기는_루트의_정해진_한_곳만_부른다() {
        ArchRule erasure = noClasses()
                .that().resideOutsideOfPackages(IDENTITY + "..", "com.bokrilog.operations..")
                .and().doNotHaveFullyQualifiedName("com.bokrilog.AccountErasure")
                .should().callMethod(com.bokrilog.identity.AccountDeletionApi.class, "beginDeletion", java.util.UUID.class)
                .orShould().callMethod(com.bokrilog.identity.AccountDeletionApi.class, "erasePayload", java.util.UUID.class)
                .orShould().callMethod(com.bokrilog.operations.DeletionTombstoneApi.class, "record", java.util.UUID.class,
                        String.class)
                .because("계정 삭제는 한 트랜잭션의 orchestrator(AccountErasure) 하나가 tombstone과 함께 한다.");
        erasure.check(PRODUCTION_CLASSES);

        // 외부 사본을 outbox로 옮기는 일은 복원 적용의 것이다(재삭제와 함께 옮기는 AccountErasure, 남은 사본을 옮기는 RestoreApplication).
        ArchRule carry = noClasses()
                .that().resideOutsideOfPackage("com.bokrilog.operations..")
                .and().doNotHaveFullyQualifiedName("com.bokrilog.AccountErasure")
                .and().doNotHaveFullyQualifiedName("com.bokrilog.RestoreApplication")
                .should().callMethod(com.bokrilog.operations.DeletionTombstoneApi.class, "carry",
                        com.bokrilog.operations.DeletionTombstoneApi.Tombstone.class)
                .because("외부 tombstone을 outbox로 옮기는 것은 복원 적용뿐이다.");
        carry.check(PRODUCTION_CLASSES);

        ArchRule sessions = noClasses()
                .that().resideOutsideOfPackage(IDENTITY + "..")
                .and().doNotHaveFullyQualifiedName("com.bokrilog.RestoreApplication")
                .should().callMethod(com.bokrilog.identity.AccountDeletionApi.class, "revokeAllSessions")
                .because("세션 전체 폐기는 서비스를 열기 전의 복원 적용에서만 한다.");
        sessions.check(PRODUCTION_CLASSES);

        // orchestrator를 부르는 자리도 정해져 있다: 새 삭제는 검증용 표본 역할, 다시 지우기는 복원 적용. 사용자 삭제 경로(Epic 5)가 생기면
        // 이 목록에 그 자리를 더한다.
        ArchRule erase = noClasses()
                .that().doNotHaveFullyQualifiedName("com.bokrilog.DeletionSampleRunner")
                .should().callMethod(AccountErasure.class, "erase", java.util.UUID.class, String.class)
                .because("지금 새 계정 삭제를 시작하는 곳은 검증용 삭제 표본 역할뿐이다.");
        erase.check(PRODUCTION_CLASSES);
        ArchRule reapply = noClasses()
                .that().doNotHaveFullyQualifiedName("com.bokrilog.RestoreApplication")
                .should().callMethod(AccountErasure.class, "reapply", java.util.UUID.class,
                        com.bokrilog.operations.DeletionTombstoneApi.Tombstone.class)
                .because("외부 tombstone으로 다시 지우는 곳은 복원 적용뿐이다.");
        reapply.check(PRODUCTION_CLASSES);
    }

    /**
     * 광고 동의는 결과 조회의 조건이 아니다(Story 4.1). 동의 API에 닿는 운영 클래스는 engagement 모듈 안과 설정 화면이 부르는 루트
     * 컨트롤러 하나다 — 연결·동기화·계획·대시보드 경로가 동의 여부로 동작을 바꿀 길이 없다. 광고 자격 판정(Story 4.2)이 생기면 그
     * 판정은 engagement 안에서 이 API를 읽는다.
     */
    @Test
    void 광고_동의_API는_engagement와_동의_컨트롤러만_닿는다() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.bokrilog.engagement..")
                .and().haveNameNotMatching("com\\.bokrilog\\.AdConsentController(\\$.*)?")
                .should().dependOnClassesThat().belongToAnyOf(com.bokrilog.engagement.AdConsentApi.class)
                .because("동의 여부로 연결·동기화·대시보드 동작을 바꾸지 않는다.");
        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * 광고 동의 행의 삭제는 계정 삭제 orchestrator 한 곳이다(Story 4.1). 다른 자리가 동의를 지우면 삭제 전환·tombstone 없이 사용자의
     * 결정이 사라진다. 구현하는 모듈 안쪽은 이 규칙 밖이다.
     */
    @Test
    void 광고_동의_삭제는_계정_삭제_orchestrator만_부른다() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.bokrilog.engagement..")
                .and().doNotHaveFullyQualifiedName("com.bokrilog.AccountErasure")
                .should().callMethod(com.bokrilog.engagement.EngagementDeletionApi.class, "eraseConsents",
                        java.util.UUID.class)
                .because("광고 동의 삭제는 한 트랜잭션의 계정 삭제(AccountErasure) 안에서만 한다.");
        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * engagement 소유 표({@code engagement_*})의 이름은 운영 소스에서 {@code engagement/internal} 안에만 나온다(Story 4.1). 다른
     * 모듈·루트가 표 이름으로 SQL을 쓰면 공개 API를 우회해 동의를 읽거나 바꾼다. 마이그레이션은 Java 소스가 아니라 대상이 아니다.
     */
    @Test
    void engagement_표_이름은_engagement_internal_밖의_운영_소스에_없다() throws java.io.IOException {
        java.util.regex.Pattern tableName = java.util.regex.Pattern.compile("engagement_[a-z]");
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java");
        java.nio.file.Path owner = root.resolve("com/bokrilog/engagement/internal");
        List<String> naming = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.startsWith(owner)) {
                    continue;
                }
                String source = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                // 표 이름(`engagement_` 뒤 글자)만 본다. 문서의 접두사 표기 `engagement_*`는 표 이름이 아니다.
                if (tableName.matcher(source).find()) {
                    naming.add(root.relativize(file).toString());
                }
            }
        }
        assertThat(naming).as("engagement 표 이름을 아는 internal 밖 운영 소스").isEmpty();
        // 검사가 빈 디렉터리를 훑어 조용히 통과하지 않는다: 소유 자리에는 그 이름이 있다.
        assertThat(java.nio.file.Files.readString(owner.resolve("AdConsentRepository.java"),
                java.nio.charset.StandardCharsets.UTF_8)).contains("engagement_ad_consent_decision");
    }
}
