package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The time rules of the incremental sync (ERD-1.0.0 §2.2, SYNC-UX-1.0), fixed to the millisecond: exactly 30
 * days is still synced and one millisecond more is a long gap; exactly 7 days since the last extended
 * verification is the extended sync; the overlap is 24 hours or 7 days and never reaches before {@code t0};
 * automatic triggers wait 5 minutes after the later of the last admission and the publication, and a manual one
 * 30 seconds after the last admission.
 */
class SyncWindowTest {
    private static final Instant T0 = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant VERIFIED = Instant.parse("2027-03-01T00:00:00Z");
    private static final Instant END = Instant.parse("2028-01-01T00:00:00Z");
    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration MS = Duration.ofMillis(1);

    @ParameterizedTest(name = "gap {0} ms beyond 30 days → {1}")
    @CsvSource({"-1,false", "0,false", "1,true", "86400000,true"})
    void exactlyThirtyDaysIsNotALongGap(long beyond, boolean longGap) {
        Instant reference = VERIFIED.plus(Duration.ofDays(30)).plusMillis(beyond);
        assertThat(SyncWindow.longGap(reference, VERIFIED)).isEqualTo(longGap);
    }

    @ParameterizedTest(name = "{0} ms beyond 7 days since the extended verification → {1}")
    @CsvSource({"-1,NORMAL", "0,EXTENDED", "1,EXTENDED"})
    void theExtendedSyncStartsAtExactlySevenDays(long beyond, SyncWindow.Mode mode) {
        Instant extended = VERIFIED.minus(Duration.ofDays(3));
        Instant tc = extended.plus(Duration.ofDays(7)).plusMillis(beyond);
        assertThat(SyncWindow.mode(tc, extended)).isEqualTo(mode);
    }

    @Test
    void theWindowStartIsTheOverlapBeforeTheVerificationAndNeverBeforeT0() {
        assertThat(SyncWindow.windowStart(T0, VERIFIED, SyncWindow.Mode.NORMAL)).isEqualTo(VERIFIED.minus(DAY));
        assertThat(SyncWindow.windowStart(T0, VERIFIED, SyncWindow.Mode.EXTENDED))
                .isEqualTo(VERIFIED.minus(Duration.ofDays(7)));
        // A plan younger than the overlap starts its windows at t0 (clamped), to the millisecond.
        Instant young = T0.plus(DAY).minus(MS);
        assertThat(SyncWindow.windowStart(T0, young, SyncWindow.Mode.NORMAL)).isEqualTo(T0);
        assertThat(SyncWindow.windowStart(T0, T0.plus(DAY), SyncWindow.Mode.NORMAL)).isEqualTo(T0);
        assertThat(SyncWindow.windowStart(T0, T0.plus(DAY).plus(MS), SyncWindow.Mode.NORMAL)).isEqualTo(T0.plus(MS));
        assertThat(SyncWindow.windowStart(T0, T0.plus(Duration.ofDays(6)), SyncWindow.Mode.EXTENDED)).isEqualTo(T0);
    }

    @Test
    void theWorkerJudgesTcInTheApprovedOrder() {
        Instant extended = VERIFIED;
        // tc must move past the verification first.
        assertThat(SyncWindow.scope(VERIFIED, T0, END, VERIFIED, extended))
                .isInstanceOf(SyncWindow.Scope.NotAdvanced.class);
        assertThat(SyncWindow.scope(VERIFIED.minus(MS), T0, END, VERIFIED, extended))
                .isInstanceOf(SyncWindow.Scope.NotAdvanced.class);
        // Then the long gap — even past the plan's end, the gap is what the user must decide first.
        assertThat(SyncWindow.scope(VERIFIED.plus(Duration.ofDays(30)).plus(MS), T0, VERIFIED.plus(DAY), VERIFIED,
                extended)).isInstanceOf(SyncWindow.Scope.LongGap.class);
        // Then the plan's end: tc >= T.
        assertThat(SyncWindow.scope(END, T0, END, END.minus(DAY), END.minus(DAY)))
                .isInstanceOf(SyncWindow.Scope.PeriodEnded.class);
        // Exactly 30 days still collects, from the extended overlap (30 >= 7 days since the extended one).
        var thirty = SyncWindow.scope(VERIFIED.plus(Duration.ofDays(30)), T0, END, VERIFIED, extended);
        assertThat(thirty).isEqualTo(new SyncWindow.Scope.Collect(SyncWindow.Mode.EXTENDED,
                VERIFIED.minus(Duration.ofDays(7))));
        // A 29-day gap is collected whole, from the overlap start to tc — never only the overlap.
        var normal = SyncWindow.scope(VERIFIED.plus(Duration.ofHours(1)), T0, END, VERIFIED,
                VERIFIED.minus(Duration.ofDays(1)));
        assertThat(normal).isEqualTo(new SyncWindow.Scope.Collect(SyncWindow.Mode.NORMAL, VERIFIED.minus(DAY)));
    }

    @Test
    void aManualTriggerWaitsThirtySecondsAfterTheLastAdmission() {
        Instant admitted = VERIFIED.plus(Duration.ofHours(2));
        assertThat(SyncWindow.wait(SyncWindow.Trigger.MANUAL, admitted, null, VERIFIED)).as("never admitted").isNull();
        assertThat(SyncWindow.wait(SyncWindow.Trigger.MANUAL, admitted.plusSeconds(30).minus(MS), admitted, VERIFIED))
                .isEqualTo(MS);
        assertThat(SyncWindow.wait(SyncWindow.Trigger.MANUAL, admitted.plusSeconds(30), admitted, VERIFIED)).isNull();
        assertThat(SyncWindow.wait(SyncWindow.Trigger.MANUAL, admitted.plusSeconds(1), admitted, VERIFIED))
                .isEqualTo(Duration.ofSeconds(29));
        // The publication does not delay a manual sync.
        assertThat(SyncWindow.wait(SyncWindow.Trigger.MANUAL, admitted, null, admitted)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ENTRY", "VISIBILITY"})
    void anAutomaticTriggerWaitsFiveMinutesAfterTheLaterOfAdmissionAndPublication(SyncWindow.Trigger trigger) {
        Instant published = VERIFIED.plus(Duration.ofMinutes(10));
        Instant admitted = published.minus(Duration.ofMinutes(1));
        // The publication is later: it is the base.
        assertThat(SyncWindow.wait(trigger, published.plus(Duration.ofMinutes(5)).minus(MS), admitted, published))
                .isEqualTo(MS);
        assertThat(SyncWindow.wait(trigger, published.plus(Duration.ofMinutes(5)), admitted, published)).isNull();
        // Without any admission the publication is the base.
        assertThat(SyncWindow.wait(trigger, published.plus(Duration.ofMinutes(4)), null, published))
                .isEqualTo(Duration.ofMinutes(1));
        // A later admission is the base.
        Instant later = published.plus(Duration.ofMinutes(20));
        assertThat(SyncWindow.wait(trigger, later.plus(Duration.ofMinutes(5)).minus(MS), later, published))
                .isEqualTo(MS);
        assertThat(SyncWindow.wait(trigger, later.plus(Duration.ofMinutes(5)), later, published)).isNull();
    }

    @ParameterizedTest(name = "tc {0} ms from the database clock → untrusted={1}")
    @CsvSource({"0,false", "60000,false", "-60000,false", "60001,true", "-60001,true", "3600000,true"})
    void bybitsTimeIsTrustedWithinSixtySecondsOfTheDatabaseClock(long offset, boolean untrusted) {
        Instant databaseNow = VERIFIED.plus(Duration.ofDays(1));
        assertThat(SyncWindow.untrustedTime(databaseNow.plusMillis(offset), databaseNow)).isEqualTo(untrusted);
    }

    @ParameterizedTest(name = "{0} ms → {1} s")
    @CsvSource({"1,1", "999,1", "1000,1", "1001,2", "29999,30", "30000,30"})
    void aWaitIsRoundedUpToWholeSecondsAndNeverZero(long millis, long seconds) {
        assertThat(SyncWindow.seconds(Duration.ofMillis(millis))).isEqualTo(seconds);
    }

    @Test
    void theAvailabilityFollowsTheAdmissionsOrder() {
        Duration wait = Duration.ofSeconds(3);
        assertThat(SyncWindow.availability(true, "RESTRICTED", true, wait)).isEqualTo(SyncWindow.Availability.IN_PROGRESS);
        assertThat(SyncWindow.availability(false, "RESTRICTED", true, wait)).isEqualTo(SyncWindow.Availability.UNAVAILABLE);
        assertThat(SyncWindow.availability(false, null, true, wait)).isEqualTo(SyncWindow.Availability.LONG_GAP);
        assertThat(SyncWindow.availability(false, null, false, wait)).isEqualTo(SyncWindow.Availability.COOLDOWN);
        assertThat(SyncWindow.availability(false, null, false, null)).isEqualTo(SyncWindow.Availability.AVAILABLE);
    }
}
