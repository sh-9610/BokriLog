package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.plan.PlanEndApi;
import org.junit.jupiter.api.Test;

/**
 * sync must not depend on plan (plan depends on sync), so sync keeps its own copies of the plan
 * module's command names. A restriction on the plan condition command stops sync admissions and
 * publications, and one on the plan end command (Story 2.13) is what the dashboard's plan-end part
 * reports as {@code RESTRICTED} — both only work while the names are the same strings.
 */
class SyncCommandNamesTest {

    @Test
    void theSyncCopyOfThePlanConditionCommandMatchesThePlanModule() {
        assertThat(SyncJobService.PLAN_CONDITION_COMMAND).isEqualTo(PlanConditionApi.COMMAND);
    }

    @Test
    void theSyncCopyOfThePlanEndCommandMatchesThePlanModule() {
        assertThat(SyncJobService.PLAN_END_COMMAND).isEqualTo(PlanEndApi.COMMAND).isEqualTo("plan-end");
    }

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
}
