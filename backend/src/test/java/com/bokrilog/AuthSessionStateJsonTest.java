package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/**
 * 세션 확인 응답의 JSON 모양.
 *
 * <p>Docker를 쓰지 않는다. 여기서 확인하는 것은 직렬화 규칙 하나이며 그것은 저장소도 서블릿도
 * 필요하지 않다. {@code AuthSessionContractTest}가 실제 HTTP 응답에서 같은 계약을 다시 보지만,
 * 그 시험은 PostgreSQL 컨테이너를 전제하므로 이 규칙이 깨졌을 때 원인을 가리키지 못한다.
 *
 * <p>고정하려는 것은 <b>미인증 응답 본문이 Story 1.5 전후로 글자까지 같다</b>는 것이다. Story 1.4가
 * 그 문자열을 전문 동등성으로 단정해 두었고({@code AuthSessionContractTest},
 * {@code verify-same-origin.sh}), 새 필드가 {@code null}일 때도 직렬화되면 그 두 단정이 함께
 * 깨진다. 그것을 막는 것은 {@code @JsonInclude(NON_NULL)} 한 줄이고, 이 시험이 그 줄이 실제로
 * 효력을 갖는지 본다 — 애노테이션은 붙어 있어도 동작하지 않을 수 있다.
 *
 * <p>애플리케이션이 쓰는 {@link ObjectMapper}로 검사한다. 새 매퍼를 만들면 Boot의 구성(모듈 등록,
 * 직렬화 기능)과 다른 규칙을 보게 되고, 그때 이 시험은 아무것도 확인하지 않는다.
 */
class AuthSessionStateJsonTest {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withConfiguration(
                    org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            JacksonAutoConfiguration.class));

    @Test
    void 미인증_응답은_상태와_목적지_두_필드뿐이다() {
        this.contexts.run(
                context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);

                    String json =
                            mapper.writeValueAsString(
                                    new AuthSessionController.SessionState(
                                            false, "entry", null, null));

                    // Story 1.4가 고정한 문자열 그대로다. 필드 하나가 늘면 여기서 먼저 깨진다.
                    assertThat(json).isEqualTo("{\"authenticated\":false,\"destination\":\"entry\"}");
                });
    }

    @Test
    void 인증된_응답은_토큰을_세_번째_필드로_싣는다() {
        this.contexts.run(
                context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);

                    String json =
                            mapper.writeValueAsString(
                                    new AuthSessionController.SessionState(
                                            true, "onboarding", null, "csrf-token-value"));

                    /*
                     * 필드 순서는 record 컴포넌트 순서다. 클라이언트가 순서에 의존하지는 않지만,
                     * 이 동등성이 "무엇이 나가는가"를 한 줄로 고정한다.
                     *
                     * Story 1.8이 `reauthRequired`를 세 번째 컴포넌트로 더했어도 이 문자열은 그대로다.
                     * 표시되지 않은 세션에서 그 값은 `null`이고 `NON_NULL`이 그것을 걸러 낸다 —
                     * 인증된 응답의 본문도 이 스토리 전후로 글자까지 같아야 하기 때문이다.
                     */
                    assertThat(json)
                            .isEqualTo(
                                    "{\"authenticated\":true,\"destination\":\"onboarding\","
                                            + "\"csrfToken\":\"csrf-token-value\"}");
                });
    }

    @Test
    void 표시된_세션의_응답만_재인증_요구를_싣는다() {
        this.contexts.run(
                context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);

                    /*
                     * 표시된 세션은 미인증으로 답하면서 필드 둘을 더 싣는다. 회복에 필요한 것이
                     * 그 둘이다 — 화면이 패널을 그릴 근거와 재인증 시작에 실을 토큰.
                     *
                     * 값이 `Boolean.FALSE`인 상태를 만들지 않는 것이 컨트롤러의 계약이다. 그 값도
                     * `NON_NULL`을 지나가므로 `"reauthRequired":false`가 미인증 응답에 실리고,
                     * 그러면 위 전문 동등성이 깨진다. 아래 단정이 그 두 값이 다르게 직렬화된다는
                     * 사실을 남긴다.
                     */
                    String marked =
                            mapper.writeValueAsString(
                                    new AuthSessionController.SessionState(
                                            false, "entry", Boolean.TRUE, "csrf-token-value"));
                    assertThat(marked)
                            .isEqualTo(
                                    "{\"authenticated\":false,\"destination\":\"entry\","
                                            + "\"reauthRequired\":true,"
                                            + "\"csrfToken\":\"csrf-token-value\"}");

                    String notMarked =
                            mapper.writeValueAsString(
                                    new AuthSessionController.SessionState(
                                            false, "entry", Boolean.FALSE, null));
                    assertThat(notMarked).contains("\"reauthRequired\":false");
                });
    }

    @Test
    void 빈_토큰도_실린다() {
        this.contexts.run(
                context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);

                    /*
                     * `NON_NULL`이지 `NON_EMPTY`가 아니다. 빈 문자열이 나가는 것은 컨트롤러가
                     * 토큰을 얻지 못한 상태이며, 그 값을 조용히 감추면 클라이언트는 토큰을 받았다고
                     * 착각한다. 컨트롤러가 그 경우를 `null`로 접는 것이 계약이고
                     * (`AuthSessionController#csrfToken`), 이 단정은 두 값이 다르게 다뤄진다는
                     * 사실을 남긴다.
                     */
                    String json =
                            mapper.writeValueAsString(
                                    new AuthSessionController.SessionState(
                                            true, "dashboard", null, ""));

                    assertThat(json).contains("\"csrfToken\":\"\"");
                });
    }
}
