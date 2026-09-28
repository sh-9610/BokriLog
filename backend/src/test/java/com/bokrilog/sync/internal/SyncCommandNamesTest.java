package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanConditionApi;
import org.junit.jupiter.api.Test;

/**
 * sync must not depend on plan (plan depends on sync), so sync keeps its own copy of the plan
 * condition command name. A restriction on that command stops sync admissions and publications,
 * which only works while the two names are the same string.
 */
class SyncCommandNamesTest {

    @Test
    void theSyncCopyOfThePlanConditionCommandMatchesThePlanModule() {
        assertThat(SyncJobService.PLAN_CONDITION_COMMAND).isEqualTo(PlanConditionApi.COMMAND);
    }
}
