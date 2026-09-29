package com.bokrilog.sync.internal;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The time rules of the incremental sync (ERD-1.0.0 §2.2, SYNC-UX-1.0), as pure functions of instants.
 *
 * <p>No database, no clock, no Spring. The caller brings the times — the database clock at admission and in
 * the dashboard read, the Bybit server time {@code tc} in the worker — and this class only compares them, to
 * the millisecond. The numbers are the approved ones and are not configuration: 30 days (long gap, exactly
 * 30 days included), 7 days (extended verification interval and overlap), 24 hours (normal overlap),
 * 5 minutes (automatic triggers), 30 seconds (manual cooldown).
 */
final class SyncWindow {
    /** A gap longer than this — strictly — is a long gap. Exactly 30 days is still synced. */
    static final Duration LONG_GAP = Duration.ofDays(30);
    /** A sync at least this long after the last extended verification widens its overlap to seven days. */
    static final Duration EXTENDED_INTERVAL = Duration.ofDays(7);
    static final Duration NORMAL_OVERLAP = Duration.ofHours(24);
    static final Duration EXTENDED_OVERLAP = Duration.ofDays(7);
    /** Entry and tab-return triggers wait this long after the later of the last admission and the publication. */
    static final Duration AUTOMATIC_INTERVAL = Duration.ofMinutes(5);
    /** A manual trigger waits this long after the last admission, whatever that attempt's outcome. */
    static final Duration MANUAL_COOLDOWN = Duration.ofSeconds(30);
    /**
     * How far Bybit's time may be from the database clock and still be trusted as {@code tc} — the same future
     * skew the action grant allows ({@code ActionGrant}). Beyond it neither clock can be the reference.
     */
    static final Duration TIME_SKEW = Duration.ofSeconds(60);

    enum Trigger { ENTRY, VISIBILITY, MANUAL }

    enum Mode { NORMAL, EXTENDED }

    /** The dashboard's sync availability, in the order the admission judges it. */
    enum Availability { AVAILABLE, IN_PROGRESS, COOLDOWN, LONG_GAP, UNAVAILABLE }

    private SyncWindow() {
    }

    /** {@code reference - verifiedThrough > 30 days}. Exactly 30 days is not a long gap. */
    static boolean longGap(Instant reference, Instant verifiedThrough) {
        return Duration.between(verifiedThrough, reference).compareTo(LONG_GAP) > 0;
    }

    /** {@code |tc - databaseNow| > 60 seconds}: Bybit's time is not trusted. Exactly 60 seconds is. */
    static boolean untrustedTime(Instant tc, Instant databaseNow) {
        return Duration.between(databaseNow, tc).abs().compareTo(TIME_SKEW) > 0;
    }

    /** {@code tc - lastExtendedVerificationAt >= 7 days}: this sync is the extended one. */
    static Mode mode(Instant tc, Instant lastExtendedVerificationAt) {
        return Duration.between(lastExtendedVerificationAt, tc).compareTo(EXTENDED_INTERVAL) >= 0 ? Mode.EXTENDED
                : Mode.NORMAL;
    }

    /** {@code max(t0, verifiedThrough - overlap)}, the overlap of the mode. */
    static Instant windowStart(Instant t0, Instant verifiedThrough, Mode mode) {
        Instant start = verifiedThrough.minus(mode == Mode.EXTENDED ? EXTENDED_OVERLAP : NORMAL_OVERLAP);
        return start.isBefore(t0) ? t0 : start;
    }

    /** What the worker does with the {@code tc} it fixed, judged before any signed call. */
    sealed interface Scope permits Scope.Collect, Scope.NotAdvanced, Scope.LongGap, Scope.PeriodEnded {
        /** Collect {@code [windowStart, tc]} in the given mode. */
        record Collect(Mode mode, Instant windowStart) implements Scope {}
        /** {@code tc <= verifiedThrough}: the provider's time did not move past the last verification. */
        record NotAdvanced() implements Scope {}
        /** {@code tc - verifiedThrough > 30 days}. */
        record LongGap() implements Scope {}
        /** {@code tc >= T}. */
        record PeriodEnded() implements Scope {}
    }

    /**
     * The worker's second judgment, with the fixed {@code tc}, in the approved order: {@code tc} must move past
     * the verification, then the long gap, then the plan end.
     */
    static Scope scope(Instant tc, Instant t0, Instant endAt, Instant verifiedThrough,
            Instant lastExtendedVerificationAt) {
        if (!tc.isAfter(verifiedThrough)) return new Scope.NotAdvanced();
        if (longGap(tc, verifiedThrough)) return new Scope.LongGap();
        if (!tc.isBefore(endAt)) return new Scope.PeriodEnded();
        Mode mode = mode(tc, lastExtendedVerificationAt);
        return new Scope.Collect(mode, windowStart(t0, verifiedThrough, mode));
    }

    /**
     * How long the trigger still has to wait at {@code now}, or {@code null} when it is due. A manual trigger
     * waits for 30 seconds after the last admission; an automatic one for 5 minutes after the later of the last
     * admission and the current official publication — so entering the dashboard right after the first
     * publication does not start a sync at once.
     */
    static @Nullable Duration wait(Trigger trigger, Instant now, @Nullable Instant lastAdmission, Instant publishedAt) {
        Instant due;
        if (trigger == Trigger.MANUAL) {
            if (lastAdmission == null) return null;
            due = lastAdmission.plus(MANUAL_COOLDOWN);
        } else {
            Instant base = lastAdmission != null && lastAdmission.isAfter(publishedAt) ? lastAdmission : publishedAt;
            due = base.plus(AUTOMATIC_INTERVAL);
        }
        return now.isBefore(due) ? Duration.between(now, due) : null;
    }

    /** A wait in whole seconds for the client, rounded up and never zero: asking again after it is admitted. */
    static long seconds(Duration wait) {
        long millis = wait.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    /**
     * The availability, in the admission's order after the account and the plan: a sync in progress, then an
     * unavailability reason, then the long gap, then the manual cooldown.
     *
     * @param unavailableReason {@code RESTRICTED}, {@code CONNECTION_UNAVAILABLE}, {@code PLAN_PERIOD_ENDED} or
     *     {@code null}
     * @param manualWait {@link #wait} of a manual trigger now
     */
    static Availability availability(boolean inProgress, @Nullable String unavailableReason, boolean longGap,
            @Nullable Duration manualWait) {
        if (inProgress) return Availability.IN_PROGRESS;
        if (unavailableReason != null) return Availability.UNAVAILABLE;
        if (longGap) return Availability.LONG_GAP;
        if (manualWait != null) return Availability.COOLDOWN;
        return Availability.AVAILABLE;
    }
}
