package com.bokrilog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 모듈 경계.
 *
 * <p>{@code identity}는 이 저장소의 첫 모듈이고, 이 시험은 그 경계가 문서가 아니라 검사로
 * 존재하게 한다. Spring 컨텍스트를 띄우지 않는다. 확인하는 것이 실행 시점의 동작이 아니라
 * 컴파일된 바이트코드의 구조이기 때문이며, 그래서 이 시험은 데이터베이스도 Docker도 쓰지 않는다.
 *
 * <p>시험 클래스는 검사 대상에서 뺀다. 시험은 자기가 검증하는 모듈의 내부를 들여다볼 수 있어야
 * 한다. 경계가 막는 것은 운영 코드의 의존이다.
 */
class ModuleBoundaryTest {

    private static final String IDENTITY = "com.bokrilog.identity";

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.bokrilog");

    @Test
    void 모듈_구조가_Spring_Modulith_검증을_통과한다() {
        ApplicationModules modules = ApplicationModules.of(BokriLogApplication.class);

        // 모듈이 실제로 인식되는지부터 본다. `identity`가 루트 패키지로 옮겨지거나 이름이 바뀌면
        // `verify()`는 검사할 것이 없어 조용히 통과한다.
        assertThat(modules.getModuleByName("identity")).isPresent();

        // 순환 의존, 다른 모듈의 내부 패키지 참조, 선언하지 않은 모듈 간 의존을 모두 여기서 막는다.
        modules.verify();
    }

    @Test
    void identity_밖에서는_internal_패키지에_닿지_못한다() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideOutsideOfPackage(IDENTITY + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage(IDENTITY + ".internal..")
                        .because(
                                "다른 모듈은 identity의 공개 application API로만 협력한다. "
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
        // 애노테이션 없이도 identity 상태가 HTTP로 나간다. 두 starter가 아직 classpath에 없어
        // 이 두 줄은 지금 통과하지만, 계약을 문장이 아니라 검사로 두는 것이 이 시험의 이유다.
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
}
