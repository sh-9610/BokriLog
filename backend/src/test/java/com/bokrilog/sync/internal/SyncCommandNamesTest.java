package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanEndApi;
import org.junit.jupiter.api.Test;

/**
 * sync must not depend on plan (plan depends on sync), so sync keeps its own copies of the plan module's names.
 *
 * <p>Story 3.2가 command 이름 사본을 거뒀다: 제한은 이제 command가 아니라 기능·scope·target으로 이름 붙는다
 * ({@code OperationalRestrictionApi}). 그래서 plan의 command 이름이 sync의 것과 같아야 할 이유가 더는 없다.
 */
class SyncCommandNamesTest {

    /**
     * Story 2.13: sync reads the plan statuses, the end reasons and completion values of an end record, and reports the
     * end's unavailability reasons on the ready dashboard, each through its own copy. A rename on either side must fail
     * here — otherwise every ended plan's record turns unavailable, or the dashboard names a reason the end never gives.
     */
    @Test
    void theSyncCopiesOfThePlanEndNamesMatchThePlanModule() {
        assertThat(PlanPublicationService.ACTIVE).isEqualTo(PlanEndApi.STATUS_ACTIVE);
        assertThat(PlanPublicationService.ENDED_EARLY).isEqualTo(PlanEndApi.STATUS_ENDED_EARLY);
        assertThat(PlanPublicationService.END_USER_REQUEST).isEqualTo(PlanEndApi.Reason.USER_REQUEST.name());
        assertThat(PlanPublicationService.END_LONG_GAP_NEW_PLAN).isEqualTo(PlanEndApi.Reason.LONG_GAP_NEW_PLAN.name());
        assertThat(java.util.Arrays.stream(PlanEndApi.Reason.values()).map(Enum::name))
                .containsExactlyInAnyOrder(PlanPublicationService.END_USER_REQUEST,
                        PlanPublicationService.END_LONG_GAP_NEW_PLAN);
        assertThat(PlanPublicationService.COMPLETION_NOT_APPLICABLE)
                .isEqualTo(PlanEndApi.Completion.NOT_APPLICABLE.name());
        assertThat(PlanPublicationService.COMPLETION_DATA_GAP)
                .isEqualTo(PlanEndApi.Completion.UNAVAILABLE_DATA_GAP.name());
        assertThat(java.util.Arrays.stream(PlanEndApi.Completion.values()).map(Enum::name))
                .containsExactlyInAnyOrder(PlanPublicationService.COMPLETION_NOT_APPLICABLE,
                        PlanPublicationService.COMPLETION_DATA_GAP);
        assertThat(DashboardTrust.END_RESTRICTED).isEqualTo(PlanEndApi.RESTRICTED);
        assertThat(DashboardTrust.END_PERIOD_ENDED).isEqualTo(PlanEndApi.PLAN_PERIOD_ENDED);
        assertThat(DashboardTrust.END_RESULT_TOO_RECENT).isEqualTo(PlanEndApi.RESULT_TOO_RECENT);
    }

    /**
     * Story 2.14: sync reads the completed status, the completion comparison and its reasons, and writes the comparison
     * through the port — each through its own copy. A rename on either side must fail here, or every completed plan's
     * bundle turns unavailable and the comparison write is refused.
     */
    @Test
    void theSyncCopiesOfTheCompletionNamesMatchThePlanModule() {
        assertThat(PlanPublicationService.COMPLETED).isEqualTo(PlanEndApi.STATUS_COMPLETED).isEqualTo("COMPLETED");
        assertThat(java.util.Arrays.stream(PlanEndApi.Comparison.values()).map(Enum::name))
                .containsExactly(PlanPublicationService.COMPARISON_PENDING, PlanPublicationService.COMPARISON_CONFIRMED,
                        PlanPublicationService.COMPARISON_UNAVAILABLE, PlanPublicationService.COMPARISON_HELD);
        assertThat(java.util.Arrays.stream(PlanEndApi.CompletionReason.values()).map(Enum::name))
                .containsExactly(PlanPublicationService.COMPLETION_DELAY_EXCEEDED,
                        PlanPublicationService.COMPLETION_POST_END_CASHFLOW,
                        PlanPublicationService.COMPLETION_HISTORY_OUT_OF_RANGE,
                        PlanPublicationService.COMPLETION_REASON_DATA_GAP, PlanPublicationService.COMPLETION_INPUT_REVIEW);
    }
}
