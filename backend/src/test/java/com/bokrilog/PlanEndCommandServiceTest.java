package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.plan.PlanEndApi;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * The plan end's flow decisions that the integration test cannot provoke on demand (Story 2.13): a confirmation whose
 * grant is denied after a same-key, same-request end committed elsewhere replays that end; a denial with nothing
 * committed is the one refusal; a cancel invalidates only the grant its own arming holds. Collaborators are mocks.
 */
class PlanEndCommandServiceTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final String KEY = "plan-end-unit-key-0001";
    private static final PlanEndApi.Request REQUEST = new PlanEndApi.Request(PlanEndApi.Reason.USER_REQUEST, PLAN, 2,
            CALCULATION);

    private final PlanEndApi planEnds = mock(PlanEndApi.class);
    private final ActionGrantService grants = mock(ActionGrantService.class);
    private final AccountAccessApi accounts = mock(AccountAccessApi.class);
    private final PlanEndCommandService service = new PlanEndCommandService(planEnds, grants, accounts);

    private MockHttpSession armedSession(String grantReference) {
        MockHttpSession session = new MockHttpSession();
        PlanEndFlow.arm(session, new PlanEndFlow.Armed(USER, PLAN, "USER_REQUEST", 2, CALCULATION, grantReference,
                REQUEST.confirmation(), session.getId(), Instant.now()));
        return session;
    }

    @Test
    void aDeniedGrantReplaysASameKeyEndThatCommittedMeanwhile() {
        when(accounts.decide(USER, USER, AccountAccessKind.WRITE)).thenReturn(AccessDecision.ALLOWED);
        var ended = new PlanEndApi.Ended(PLAN, PlanEndApi.Reason.USER_REQUEST, Instant.parse("2027-01-10T00:00:00Z"));
        // Nothing yet at the first read; the other request's end has committed by the time the grant is denied.
        when(planEnds.replay(USER, KEY, REQUEST.requestHash())).thenReturn(new PlanEndApi.Replay.None(),
                new PlanEndApi.Replay.Stored(ended));
        when(grants.consume(any(), any())).thenReturn(new ActionGrantService.Consumption.Denied());
        MockHttpSession session = armedSession("grant-reference-a".repeat(3));

        var outcome = service.confirm(new MockHttpServletRequest(), session, USER, REQUEST, REQUEST.confirmation(), KEY);
        assertThat(outcome).isEqualTo(new PlanEndCommandService.Outcome.Ended(ended));
        // Not this request's end: the arming is left and the session is not rotated by it.
        assertThat(PlanEndFlow.peek(session)).isNotNull();
    }

    @Test
    void aDeniedGrantWithNothingCommittedIsTheOneRefusal() {
        when(accounts.decide(USER, USER, AccountAccessKind.WRITE)).thenReturn(AccessDecision.ALLOWED);
        when(planEnds.replay(USER, KEY, REQUEST.requestHash())).thenReturn(new PlanEndApi.Replay.None());
        when(grants.consume(any(), any())).thenReturn(new ActionGrantService.Consumption.Denied());

        var outcome = service.confirm(new MockHttpServletRequest(), armedSession("grant-reference-b".repeat(3)), USER,
                REQUEST, REQUEST.confirmation(), KEY);
        assertThat(outcome).isEqualTo(new PlanEndCommandService.Outcome.Refused("PLAN_END_NOT_CONFIRMED", null));
    }

    /** B2: a cancel invalidates the grant its own arming holds — by reference — and nothing of another session's. */
    @Test
    void aCancelInvalidatesOnlyItsOwnArmingsGrant() {
        String reference = "grant-reference-c".repeat(3);
        MockHttpSession session = armedSession(reference);
        service.cancel(session);
        // 참조 단위가 유일한 명시적 무효화다 — `(user, command, target)` 단위 메서드는 더 이상 서비스 표면에 없다.
        verify(grants).invalidateReference(USER, reference);
        assertThat(PlanEndFlow.peekIncludingExpired(session)).isNull();
        service.cancel(session);
        verify(grants).invalidateReference(eq(USER), eq(reference));
    }
}
