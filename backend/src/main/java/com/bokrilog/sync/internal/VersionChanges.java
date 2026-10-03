package com.bokrilog.sync.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a version changed, in one place (Story 2.12 AC-7): which change rows the screen calls late, and the kind of
 * the version's change marker. The dashboard's marker, the calculation evidence and the adjustment history all read
 * it from here, so the three never disagree.
 *
 * <p>A change row is <b>late</b> when it is an addition not after the previous version's verification, a removal or a
 * changed value. The marker is {@link #CORRECTED} with at least one late row, {@link #VERIFIED_NO_CHANGE} when a
 * precision verification or a continuation made the version without one, and {@link #NONE} otherwise — a first
 * publication, or a sync whose additions all came after the previous verification.
 *
 * <p>The attempt's ending line counts the same rows in SQL ({@link #LATE_SQL}); a test compares that count with the
 * dashboard's.
 */
final class VersionChanges {
    static final String CORRECTED = "CORRECTED";
    static final String VERIFIED_NO_CHANGE = "VERIFIED_NO_CHANGE";
    static final String NONE = "NONE";

    /** {@link #late} over {@code sync_plan_calculation_change ch}, for SQL that counts late rows. */
    static final String LATE_SQL = "((ch.change_kind='ADDED' AND ch.late) OR ch.change_kind IN ('REMOVED','VALUE_CHANGED'))";

    private VersionChanges() {
    }

    /** Whether a change row of this kind and late flag is one the screen calls late. */
    static boolean late(String changeKind, boolean late) {
        return ("ADDED".equals(changeKind) && late) || "REMOVED".equals(changeKind) || "VALUE_CHANGED".equals(changeKind);
    }

    /** The late rows of a version's change rows. */
    static List<PlanPublicationRepository.ChangeRow> late(List<PlanPublicationRepository.ChangeRow> changes) {
        return changes.stream().filter(change -> late(change.kind(), change.late())).toList();
    }

    /**
     * The late rows of a version, by what they are: additions found late, changed values and removals. They sum to
     * the late rows' count; the screen names each kind for what it is — a value another user's price correction moved
     * under an ordinary sync is not a "late event".
     */
    record Counts(int lateAdded, int valueChanged, int removed) {
        int total() {
            return this.lateAdded + this.valueChanged + this.removed;
        }
    }

    static Counts counts(List<PlanPublicationRepository.ChangeRow> changes) {
        int added = 0;
        int changed = 0;
        int removed = 0;
        for (var change : changes) {
            if ("ADDED".equals(change.kind()) && change.late()) added++;
            else if ("VALUE_CHANGED".equals(change.kind())) changed++;
            else if ("REMOVED".equals(change.kind())) removed++;
        }
        return new Counts(added, changed, removed);
    }

    /**
     * The marker of a version.
     *
     * @param lateCount how many of its change rows are late
     * @param creatingPurpose the purpose of the attempt that made it ({@code SYNC}, {@code VERIFY}, {@code CATCH_UP}),
     *     {@code null} for a first publication
     */
    static String kind(int lateCount, @Nullable String creatingPurpose) {
        if (lateCount > 0) return CORRECTED;
        return creatingPurpose != null && !"SYNC".equals(creatingPurpose) ? VERIFIED_NO_CHANGE : NONE;
    }
}
