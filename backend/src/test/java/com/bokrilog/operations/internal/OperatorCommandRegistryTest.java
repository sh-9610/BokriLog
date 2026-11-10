package com.bokrilog.operations.internal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandEnvironment;
import com.bokrilog.operations.OperatorPermission;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영 command 등록표와 확정 템플릿(Epic 3 회고 AI-5). 데이터베이스 없이 구조만 본다 — 동작은 운영자 시험들
 * ({@code OperatorCommandTest}·{@code OperatorRestrictionTest}·{@code OperatorRecoveryTest}·{@code ServiceNoticeTest}·
 * {@code SupportInquiryTest})이 본다.
 *
 * <ul>
 *   <li>등록표가 공개 command 이름 상수 전부를 정확히 덮는다 — 새 command는 상수·전략·등록이 함께여야 한다.
 *   <li>전략은 확정 prologue(트랜잭션 확인·원장 추가 잠금)를 스스로 하지 않고, 전략의 확정 본문은 엔진 템플릿만 부른다.
 *   <li>{@link OperatorCommand} 구현은 모두 등록돼 있다 — 구현만 하고 등록을 빠뜨리지 않는다.
 * </ul>
 */
class OperatorCommandRegistryTest {

    private static final JavaClasses OPERATIONS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bokrilog.operations");

    /** {@link OperatorCommandApi#COMMANDS}가 선언한 command 이름 전부. */
    private static Set<String> declaredCommandNames() {
        Set<String> names = new TreeSet<>(OperatorCommandApi.COMMANDS);
        assertThat(names).as("이름이 겹치지 않는다").hasSameSizeAs(OperatorCommandApi.COMMANDS);
        return names;
    }

    private static OperatorEnvelopeService engine() {
        return new OperatorEnvelopeService(mock(OperatorAuditRepository.class),
                mock(OperationalRestrictionRepository.class), mock(ServiceNoticeRepository.class),
                mock(SupportInquiryRepository.class), mock(ActorDigest.class), mock(SupportMetrics.class),
                mock(OperationalRestrictionApi.class), mock(OperatorCommandEnvironment.class),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void 등록표는_공개_command_이름_전부를_정확히_덮는다() {
        assertThat(declaredCommandNames()).containsExactlyInAnyOrder(OperatorCommandApi.AUDIT_EXPORT,
                OperatorCommandApi.RESTRICT_NEW_CONNECTION, OperatorCommandApi.RESTRICT_SYNC,
                OperatorCommandApi.RESTRICT_CALCULATION_DISPLAY, OperatorCommandApi.RESTRICT_ADVERTISING,
                OperatorCommandApi.LIFT_RESTRICTION, OperatorCommandApi.PUBLISH_NOTICE,
                OperatorCommandApi.UPDATE_INQUIRY, OperatorCommandApi.REQUEUE_DELETION);
        OperatorEnvelopeService engine = engine();
        assertThat(engine.registry().names()).containsExactlyInAnyOrderElementsOf(declaredCommandNames());
        for (String name : declaredCommandNames()) {
            assertThat(engine.isSupported(name)).as(name).isTrue();
            assertThat(engine.permissionsOf(name)).as(name).contains(OperatorPermission.COMMAND);
        }
        // command이면서 감사 데이터를 꺼내는 것은 내보내기 하나다.
        assertThat(engine.permissionsOf(OperatorCommandApi.AUDIT_EXPORT))
                .containsExactlyInAnyOrder(OperatorPermission.COMMAND, OperatorPermission.AUDIT);
        assertThat(engine.isSupported("unknown-command")).isFalse();
    }

    @Test
    void 같은_이름을_두_전략이_받으면_등록표가_서지_않는다() {
        var restrict = new RestrictCommand(mock(OperationalRestrictionRepository.class), mock(ActorDigest.class),
                mock(OperatorCommandEnvironment.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new OperatorCommandRegistry(java.util.List.of(restrict, restrict)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void OperatorCommand_구현은_모두_등록돼_있다() {
        Set<String> implementations = OPERATIONS.stream()
                .filter(type -> !type.isInterface() && type.isAssignableTo(OperatorCommand.class))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        OperatorEnvelopeService engine = engine();
        Set<String> registered = engine.registry().names().stream()
                .map(name -> engine.registry().of(name).getClass().getName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(implementations).isNotEmpty().isEqualTo(registered);
    }

    /** 전략의 확정 본문 호출: 대상이 {@link OperatorCommand}에 대입되는 타입의 {@code complete}다. bridge 위임은 뺀다. */
    private static final DescribedPredicate<JavaMethodCall> STRATEGY_COMPLETE =
            new DescribedPredicate<>("OperatorCommand 구현의 complete 호출") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return "complete".equals(call.getName())
                            && call.getTargetOwner().isAssignableTo(OperatorCommand.class)
                            && !call.getOrigin().getModifiers().contains(JavaModifier.BRIDGE)
                            && !call.getOrigin().getModifiers().contains(JavaModifier.SYNTHETIC);
                }
            };

    @Test
    void 전략은_확정_prologue를_우회하지_않는다() {
        // 원장 추가 잠금·열린 트랜잭션 확인·자기 트랜잭션은 엔진 템플릿 한 곳의 몫이다.
        noClasses().that().areAssignableTo(OperatorCommand.class)
                .should().callMethod(OperatorAuditRepository.class, "lockAppends")
                .orShould().callMethod(TransactionSynchronizationManager.class, "isActualTransactionActive")
                .orShould().dependOnClassesThat().areAssignableTo(TransactionTemplate.class)
                .orShould().dependOnClassesThat().areAssignableTo(PlatformTransactionManager.class)
                .because("확정의 prologue(트랜잭션 가드 → 원장 잠금 → 최종 권한)는 엔진 템플릿 한 곳에만 있다.")
                .check(OPERATIONS);
        // 전략의 확정 본문은 엔진 템플릿만 부른다 — prologue 없이 본문에 닿는 길이 없다. 인터페이스로 부르든 구현 클래스로
        // 직접 부르든 같다(컴파일러가 만든 bridge 메서드의 위임은 호출 자리가 아니다).
        noClasses().that().doNotHaveFullyQualifiedName(OperatorEnvelopeService.class.getName())
                .should().callMethodWhere(STRATEGY_COMPLETE)
                .because("확정 본문은 엔진 템플릿(OperatorEnvelopeService.complete)만 부른다.")
                .check(OPERATIONS);
        // 해제 본문(해제 UPDATE의 유일한 호출 자리)은 해제 전략의 확정 본문만 부른다.
        noClasses().that().doNotHaveFullyQualifiedName(LiftRestrictionCommand.class.getName())
                .should().callMethod(OperatorEnvelopeService.class, "completeLift", Envelope.class,
                        LiftRestrictionCommand.Lift.class)
                .because("해제 본문은 해제 전략이 템플릿 안에서만 부른다.")
                .check(OPERATIONS);
    }
}
