package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bokrilog.sync.SyncJobApi;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The verification surface's closed refusal map (Story 2.12): a refusal code the map does not know is a broken
 * contract on the server's side, answered as the common 500 — never passed through as a 409 with the unknown code.
 * No Spring context and no database: the port is a mock.
 */
class DashboardControllerTest {

    @Test
    void anUnknownVerificationRefusalIsTheCommonInternalError() throws Exception {
        SyncJobApi jobs = mock(SyncJobApi.class);
        when(jobs.admitPlanVerification(any(), any(), any(), any()))
                .thenReturn(new SyncJobApi.VerificationAdmission.Refused("A_CODE_NO_ONE_MAPPED"));
        var controller = new DashboardController(jobs);
        String body = "{\"kind\":\"PRECISION\",\"planId\":\"" + UUID.randomUUID() + "\",\"pointerVersion\":1,"
                + "\"calculationId\":\"" + UUID.randomUUID() + "\"}";
        var answer = controller.verification(body, "controller-unit-key-0001", new MockHttpServletRequest(),
                new MockHttpServletResponse());
        assertThat(answer).isNotNull();
        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        assertThat(answer.getBody()).isInstanceOf(ApiErrorHandler.ApiError.class);
        var error = (ApiErrorHandler.ApiError) answer.getBody();
        assertThat(error.code()).isEqualTo(ApiErrorHandler.CODE_INTERNAL_ERROR);
        assertThat(error.toString()).doesNotContain("A_CODE_NO_ONE_MAPPED");
        // Every code the service can refuse with is mapped.
        assertThat(DashboardController.VERIFICATION_REFUSALS).containsOnlyKeys("NO_ACTIVE_PLAN", "REFERENCE_STALE",
                "JOB_IN_PROGRESS", "VERIFICATION_UNAVAILABLE", "LONG_GAP", "NOT_LONG_GAP", "VERIFICATION_NOT_USEFUL",
                "IDEMPOTENCY_CONFLICT");
    }
}
