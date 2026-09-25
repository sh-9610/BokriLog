package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.servlet.function.RouterFunction;

/**
 * 개발용 Bybit 스텁을 가르는 프로필.
 *
 * <p>{@code DevOidcStubProfileTest}와 같은 근거의 같은 모양이다. 스텁 논리는 {@code main}에 있고
 * 그 대가가 "운영에서 조용히 켜질 수 있다"이며, 이 시험이 그 대가를 <b>두 방향</b>으로 막는다 —
 * 프로필이 켜졌는데 실제 제공자를 가리키는 경우와 프로필이 꺼졌는데 스텁을 가리키는 경우다. 한
 * 방향만 두면 나머지가 조용히 성립하고, 뒤쪽에서는 스텁 경로에 라우트가 없어 제공자가 404를
 * 돌려주며 그 404가 인증 거절로 접혀 사용자가 고칠 수 없는 안내를 받는다.
 *
 * <p>Spring 컨텍스트를 통째로 띄우지 않는다. 확인하려는 것은 데이터베이스 동작이 아니라 "어느
 * 프로필에서 어느 빈이 만들어지는가"라는 배선이며, {@link ApplicationContextRunner}면 그것만 정확히
 * 볼 수 있고 Docker도 필요 없다.
 *
 * <p>{@code /api/dev-bybit/**}의 노출 여부를 빈으로 단정한다. 모든 경로가 {@link RouterFunction}
 * <b>하나</b>에 실려 있으므로 그 빈의 부재가 곧 경로의 부재다.
 */
class DevBybitStubProfileTest {

    /** 스텁을 가리키는 주소. 프로필이 켜진 스택이 반드시 가져야 하는 값이다. */
    private static final String STUB_BASE_URL =
            "http://127.0.0.1:8080" + DevBybitStubConfig.BASE_PATH;

    /** 실제 제공자 자리에 서는 값. 브랜드 주소를 적지 않는다 — 이 시험이 보는 것은 모양뿐이다. */
    private static final String REAL_BASE_URL = "https://provider.invalid/v5-gateway";

    private static final String RESERVED_IP = "203.0.113.10";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(DevBybitStubConfig.class, BybitProviderTargetGuard.class);

    @Test
    void 기본_프로필에는_스텁_빈이_하나도_없다() {
        this.runner
                .withPropertyValues(
                        "bokrilog.source.bybit.base-url=" + REAL_BASE_URL,
                        "bokrilog.source.bybit.reserved-ip=" + RESERVED_IP)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            // 라우팅이 빈 하나에 실려 있다. 그 빈의 부재가 곧 경로의 부재다.
                            assertThat(context.getBeansOfType(RouterFunction.class)).isEmpty();
                            assertThat(context.containsBean("devBybitStubConfig")).isFalse();
                        });
    }

    @Test
    void 스텁_프로필은_라우터_빈_하나를_올린다() {
        this.runner
                .withPropertyValues(
                        "spring.profiles.active=" + DevBybitStubConfig.PROFILE,
                        "bokrilog.source.bybit.base-url=" + STUB_BASE_URL,
                        "bokrilog.source.bybit.reserved-ip=" + RESERVED_IP)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            /*
                             * 하나다. 제공자 경로가 셋이어도 라우터는 하나여야 위 부재 단정이
                             * "빈이 몇 개인가"를 세는 형태로 바뀌지 않는다.
                             */
                            assertThat(context.getBeansOfType(RouterFunction.class)).hasSize(1);
                        });
    }

    @Test
    void 스텁_프로필이_실제_제공자_주소와_함께_활성이면_기동을_거부한다() {
        this.runner
                .withPropertyValues(
                        "spring.profiles.active=" + DevBybitStubConfig.PROFILE,
                        "bokrilog.source.bybit.base-url=" + REAL_BASE_URL,
                        "bokrilog.source.bybit.reserved-ip=" + RESERVED_IP)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(rootMessageOf(context.getStartupFailure()))
                                    .contains("스텁과 실제 제공자 중 하나만 구성한다");
                            assertNoSecretsInMessage(context.getStartupFailure());
                        });
    }

    @Test
    void 스텁_프로필이_주소_없이_활성이어도_기동을_거부한다() {
        this.runner
                .withPropertyValues(
                        "spring.profiles.active=" + DevBybitStubConfig.PROFILE,
                        "bokrilog.source.bybit.reserved-ip=" + RESERVED_IP)
                .run(
                        context -> {
                            // 스텁은 떠 있는데 어댑터가 그것을 가리키지 않는 상태다. 조용히 두면
                            // 개발 스택의 연결이 어디로도 가지 않는다.
                            assertThat(context).hasFailed();
                        });
    }

    @Test
    void 스텁_프로필이_운영_reserved_IP_없이_활성이면_기동을_거부한다() {
        this.runner
                .withPropertyValues(
                        "spring.profiles.active=" + DevBybitStubConfig.PROFILE,
                        "bokrilog.source.bybit.base-url=" + STUB_BASE_URL)
                .run(
                        context -> {
                            /*
                             * IP binding 검증이 그 값 하나에 걸려 있다. 없으면 스텁이 무엇을
                             * 응답해야 하는지 정해지지 않고, 그 상태에서 도는 왕복은 계약을
                             * 검증하지 못한다.
                             */
                            assertThat(context).hasFailed();
                            assertThat(rootMessageOf(context.getStartupFailure()))
                                    .contains("운영 reserved IP");
                        });
    }

    @Test
    void 기본_프로필이_스텁_주소를_가리키면_기동을_거부한다() {
        this.runner
                .withPropertyValues(
                        "bokrilog.source.bybit.base-url=" + STUB_BASE_URL,
                        "bokrilog.source.bybit.reserved-ip=" + RESERVED_IP)
                .run(
                        context -> {
                            /*
                             * 반대 방향의 guard다. 통과시키면 스텁 경로에 라우트가 없는 프로세스가
                             * 그 주소로 서명된 요청을 보내고, 404가 인증 거절로 접혀 사용자는
                             * "키가 유효한지 확인하라"는 고칠 수 없는 안내를 받는다.
                             */
                            assertThat(context).hasFailed();
                            assertThat(rootMessageOf(context.getStartupFailure()))
                                    .contains("스텁과 실제 제공자 중 하나만 구성한다");
                            assertNoSecretsInMessage(context.getStartupFailure());
                        });
    }

    @Test
    void 두_방향_모두_주소가_비면_기동한다() {
        /*
         * 연결 기능을 쓰지 않는 스택이다. 기동을 죽이는 것은 잘못 가리키는 구성뿐이며, 빈 값은
         * 이 스토리의 fail-closed가 이미 덮는다 — 기능만 닫히고 프로세스는 산다.
         */
        this.runner.run(context -> assertThat(context).hasNotFailed());
    }

    private static String rootMessageOf(Throwable failure) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        String message = root.getMessage();
        return message == null ? "" : message;
    }

    /**
     * 기동 실패 메시지에 환경 변수 이름도 주소도 남지 않는가.
     *
     * <p>기동 실패 로그도 로그다({@code DevOidcStubConfig}의 guard와 같은 규율). 답은 어느 쪽이든
     * "스텁과 실제 제공자 중 하나만 구성한다" 하나이므로 어느 값이 문제인지 적을 이유가 없다.
     */
    private static void assertNoSecretsInMessage(Throwable failure) {
        String message = rootMessageOf(failure);
        assertThat(message).doesNotContain("BOKRILOG_");
        assertThat(message).doesNotContain(STUB_BASE_URL);
        assertThat(message).doesNotContain(REAL_BASE_URL);
    }

    @Test
    void 알_수_없는_구성은_예외를_밖으로_내보내지_않는다() {
        // 이 단정이 위 세 실패와 다른 것은 기대다. 여기서는 예외가 **나면 안 된다**.
        assertThatThrownBy(
                        () ->
                                this.runner
                                        .withPropertyValues(
                                                "bokrilog.source.bybit.base-url=not-a-url",
                                                "bokrilog.source.bybit.reserved-ip=" + RESERVED_IP)
                                        .run(context -> assertThat(context).hasFailed()))
                .isInstanceOf(AssertionError.class);
    }
}
