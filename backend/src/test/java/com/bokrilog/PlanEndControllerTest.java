package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bokrilog.plan.PlanEndApi;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * The plan end surface's closed refusal map and strict body (Story 2.13), without a Spring context or a database: a
 * refusal code the map does not know is the common 500, never passed through; a body that is not exactly the shape
 * names nothing; the reason travels only with {@code PLAN_END_UNAVAILABLE}.
 */
class PlanEndControllerTest {
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest signedInRequest() {
        UUID user = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new PreAuthenticatedAuthenticationToken(user.toString(),
                null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        return request;
    }

    private static String body(String reason) {
        return "{\"reason\":\"" + reason + "\",\"planId\":\"" + PLAN + "\",\"pointerVersion\":3,\"calculationId\":\""
                + CALCULATION + "\"}";
    }

    @Test
    void anUnknownRefusalIsTheCommonInternalError() {
        PlanEndCommandService ends = mock(PlanEndCommandService.class);
        when(ends.prepare(any(), any(), any()))
                .thenReturn(new PlanEndCommandService.Preparation.Refused("A_CODE_NO_ONE_MAPPED", null));
        var answer = new PlanEndController(ends).prepare(body("USER_REQUEST"), signedInRequest());
        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        var error = (PlanEndController.PlanEndError) answer.getBody();
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo(ApiErrorHandler.CODE_INTERNAL_ERROR);
        assertThat(error.reason()).isNull();
        assertThat(error.toString()).doesNotContain("A_CODE_NO_ONE_MAPPED");
    }

    @Test
    void theReasonTravelsOnlyWithTheUnavailableCode() {
        PlanEndCommandService ends = mock(PlanEndCommandService.class);
        when(ends.prepare(any(), any(), any())).thenReturn(
                new PlanEndCommandService.Preparation.Refused(PlanEndApi.PLAN_END_UNAVAILABLE, PlanEndApi.RESTRICTED),
                new PlanEndCommandService.Preparation.Refused(PlanEndApi.LONG_GAP, PlanEndApi.RESTRICTED),
                new PlanEndCommandService.Preparation.Refused(PlanEndApi.NOT_PERMITTED, null),
                new PlanEndCommandService.Preparation.Refused(PlanEndCommandService.REAUTHENTICATION_REQUIRED, null),
                new PlanEndCommandService.Preparation.Refused(PlanEndApi.PLAN_NOT_FOUND, null));
        var controller = new PlanEndController(ends);
        var unavailable = controller.prepare(body("USER_REQUEST"), signedInRequest());
        assertThat(unavailable.getStatusCode().value()).isEqualTo(409);
        assertThat(((PlanEndController.PlanEndError) unavailable.getBody()).reason()).isEqualTo("RESTRICTED");
        var longGap = controller.prepare(body("USER_REQUEST"), signedInRequest());
        assertThat(longGap.getStatusCode().value()).isEqualTo(409);
        assertThat(((PlanEndController.PlanEndError) longGap.getBody()).reason()).isNull();
        var forbidden = controller.prepare(body("USER_REQUEST"), signedInRequest());
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(((PlanEndController.PlanEndError) forbidden.getBody()).code()).isEqualTo("FORBIDDEN");
        var reauthenticate = controller.prepare(body("USER_REQUEST"), signedInRequest());
        assertThat(reauthenticate.getStatusCode().value()).isEqualTo(403);
        assertThat(((PlanEndController.PlanEndError) reauthenticate.getBody()).code())
                .isEqualTo("REAUTHENTICATION_REQUIRED");
        var notFound = controller.prepare(body("USER_REQUEST"), signedInRequest());
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void onlyTheExactShapeNamesARequest() {
        var parsed = PlanEndController.bodyOf(body("LONG_GAP_NEW_PLAN"), false);
        assertThat(parsed).isNotNull();
        assertThat(parsed.request()).isEqualTo(new PlanEndApi.Request(PlanEndApi.Reason.LONG_GAP_NEW_PLAN, PLAN, 3,
                CALCULATION));
        assertThat(parsed.confirmation()).isNull();
        String confirm = body("USER_REQUEST").replace("}", ",\"confirmation\":\"plan-end|x\"}");
        assertThat(PlanEndController.bodyOf(confirm, true).confirmation()).isEqualTo("plan-end|x");
        for (String malformed : List.of("", "null", "[]", "{}", body("ended"), body("COMPLETED"),
                body("USER_REQUEST").replace("3", "0"), body("USER_REQUEST").replace("3", "\"3\""),
                body("USER_REQUEST").replace("3", "3.5"), body("USER_REQUEST").replace(PLAN.toString(), "1-1-1-1-1"),
                body("USER_REQUEST").replace("\"reason\":\"USER_REQUEST\"", "\"reason\":1"),
                "{\"reason\":\"USER_REQUEST\"," + "x".repeat(1100) + "}"))
            assertThat(PlanEndController.bodyOf(malformed, false)).as(malformed).isNull();
        assertThat(PlanEndController.bodyOf(body("USER_REQUEST"), true)).as("a confirm needs its phrase").isNull();
        assertThat(PlanEndController.bodyOf(body("USER_REQUEST").replace("}", ",\"confirmation\":\" \"}"), true))
                .isNull();
        assertThat(PlanEndController.validKey("plan-end-key-00000001")).isTrue();
        for (String key : List.of("short", "-starts-with-dash-0001", "has..dots-0000000001", "has space 0000000001",
                "k".repeat(129)))
            assertThat(PlanEndController.validKey(key)).as(key).isFalse();
    }
}
