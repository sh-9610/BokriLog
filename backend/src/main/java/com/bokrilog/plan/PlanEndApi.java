package com.bokrilog.plan;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The explicit early end of an active plan (Story 2.13). The plan module owns the business rule — who may end which
 * plan, when, for which reason — and the one state change; the root package owns the HTTP surface, the session flow
 * and the action grant (SECURITY-ACTION-1.0), and calls {@link #end} inside the grant's consumption transaction.
 *
 * <p>Eligibility is judged in one order everywhere (AC-1): the account's write access ({@code NOT_PERMITTED}), the
 * user's own active plan ({@code PLAN_NOT_FOUND} — another user's, an unknown or an ended plan are one answer), the
 * reference the screen shows still being the official pointer ({@code REFERENCE_STALE}), unavailability
 * ({@code PLAN_END_UNAVAILABLE} with {@code RESTRICTED}, {@code PLAN_PERIOD_ENDED} or {@code RESULT_TOO_RECENT}), then
 * the reason against the long gap ({@code LONG_GAP}, {@code NOT_LONG_GAP}). An official result that does not agree with
 * itself is unavailable too ({@code RESULT_UNAVAILABLE}), judged before the reference it would be compared with.
 * {@link #check} judges it in one read-only repeatable read without locks for the preparation; {@link #end} judges it
 * again under the plan lock, the account lock and the plan row lock.
 */
public interface PlanEndApi {

    /**
     * The sensitive command's name: the action grant's command and the operational restriction's name. It must differ
     * from every other command's, or a grant issued for one would be consumable by another.
     */
    String COMMAND = "plan-end";

    /** The confirmed end's outcome, as the response and the stored grant result carry it. */
    String OUTCOME_ENDED = "ENDED";

    String NOT_PERMITTED = "NOT_PERMITTED";
    String PLAN_NOT_FOUND = "PLAN_NOT_FOUND";
    String REFERENCE_STALE = "REFERENCE_STALE";
    String PLAN_END_UNAVAILABLE = "PLAN_END_UNAVAILABLE";
    String LONG_GAP = "LONG_GAP";
    String NOT_LONG_GAP = "NOT_LONG_GAP";

    /** With {@link #PLAN_END_UNAVAILABLE}: the {@code plan-end} command is restricted. */
    String RESTRICTED = "RESTRICTED";
    /** With {@link #PLAN_END_UNAVAILABLE}: the database clock reached the plan's end {@code T}. */
    String PLAN_PERIOD_ENDED = "PLAN_PERIOD_ENDED";
    /**
     * With {@link #PLAN_END_UNAVAILABLE}: the official result's measurement or cash-flow verification lies ahead of the
     * database clock (Bybit's time is trusted up to 60 seconds from it). The end time would precede the last normal
     * result, which is never moved to it; a moment later the same request is eligible.
     */
    String RESULT_TOO_RECENT = "RESULT_TOO_RECENT";
    /**
     * With {@link #PLAN_END_UNAVAILABLE}: the official result does not agree with itself (the ready dashboard's own
     * checks). An end recorded on it would be a record that could never be shown, so nothing is ended.
     */
    String RESULT_UNAVAILABLE = "RESULT_UNAVAILABLE";

    /** The plan statuses (V10, V13, V14). Sync keeps its own copies; {@code SyncCommandNamesTest} pins them to these. */
    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_ENDED_EARLY = "ENDED_EARLY";
    /**
     * The natural completion of a plan that reached its end {@code T} (Story 2.14). With {@link #STATUS_ENDED_EARLY}
     * one of the two terminal statuses; neither goes back to {@link #STATUS_ACTIVE}.
     */
    String STATUS_COMPLETED = "COMPLETED";

    /**
     * The completion comparison of a completed plan (Story 2.14), apart from the plan's own status: not judged yet,
     * confirmed, not computable (with a {@link CompletionReason}) or held for input review. A comparison that cannot be
     * confirmed never turns the plan back. Sync keeps its own copies; {@code SyncCommandNamesTest} pins them.
     */
    enum Comparison { PENDING, CONFIRMED, UNAVAILABLE, HELD }

    /**
     * Why a completion comparison is not confirmed: the first observation came more than 24 hours after {@code T}, a
     * confirmed external flow lies in {@code (T, measured]}, the history is out of the provider's reach, the gap at the
     * transition was long (all {@link Comparison#UNAVAILABLE}), or the inputs need review ({@link Comparison#HELD}).
     */
    enum CompletionReason { OBSERVATION_DELAY_EXCEEDED, POST_END_CASHFLOW, HISTORY_OUT_OF_RANGE, DATA_GAP,
        INPUT_REVIEW_REQUIRED }

    /** Why the plan ends: the user's early end, or the new-plan choice after a long gap. */
    enum Reason { USER_REQUEST, LONG_GAP_NEW_PLAN }

    /** The completion comparison an end leaves: none for an early end, not computable after a data gap. */
    enum Completion { NOT_APPLICABLE, UNAVAILABLE_DATA_GAP }

    /**
     * What the user asks to end: the reason and the three values of the official result the screen shows. Those three
     * are the user's choice token — what they saw when they chose — and the confirmation phrase carries all four, so
     * the action grant's confirmation hash tells "this reason for the state I saw" apart from any other.
     */
    record Request(Reason reason, UUID planId, long pointerVersion, UUID calculationId) {

        /**
         * The server's canonical confirmation phrase: {@code plan-end|<reason>|<planId>|<pointerVersion>|<calculationId>}.
         * Every field has a fixed shape (an enum name, a canonical UUID, a positive integer), so the separator is
         * unambiguous. The one producer of the phrase.
         */
        public String confirmation() {
            return COMMAND + "|" + this.reason.name() + "|" + this.planId + "|" + this.pointerVersion + "|"
                    + this.calculationId;
        }

        /** The request hash an idempotency key is bound to: the same key with another request is a conflict. */
        public String requestHash() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(("plan-end-request-1|" + confirmation()).getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /** A refusal: a closed code and, with {@link #PLAN_END_UNAVAILABLE}, its reason. */
    record Refusal(String code, @Nullable String reason) {}

    /** The confirmed end, as the response and an idempotent replay carry it. */
    record Ended(UUID planId, Reason reason, Instant endedAt) {}

    /**
     * A refusal inside {@link #end}: thrown, so it rolls the caller's whole transaction back — the action grant's
     * consumption included — and leaves the plan active. No stack, no message: it carries a closed code only.
     */
    final class Refused extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        private final String code;
        private final @Nullable String reason;

        public Refused(String code, @Nullable String reason) {
            super(null, null, false, false);
            this.code = code;
            this.reason = reason;
        }

        public String code() {
            return this.code;
        }

        public @Nullable String reason() {
            return this.reason;
        }
    }

    /** What the same idempotency key found: the stored end, a conflict with another request, or nothing yet. */
    sealed interface Replay permits Replay.Stored, Replay.Conflict, Replay.None {
        record Stored(Ended ended) implements Replay {}
        record Conflict() implements Replay {}
        record None() implements Replay {}
    }

    /**
     * Whether the request may end the plan now: the same judgment as {@link #end}, in one read-only repeatable read
     * without locks, for the preparation that issues the action grant. An end that commits meanwhile is
     * {@code PLAN_NOT_FOUND}, never a failure.
     *
     * @return the refusal, or empty when the request is eligible
     */
    Optional<Refusal> check(@Nullable UUID userId, Request request);

    /**
     * Ends the plan. <b>Joins the caller's transaction only</b> ({@code MANDATORY}): the root package calls it inside
     * the action grant's consumption, so the grant's consumption, the status change and the end record commit
     * together. In order: the plan lock (the publishers' advisory lock), the account lifecycle lock, the plan row,
     * the eligibility again, the official pointer and calculation (sync's public API, in this transaction), then
     * {@code ACTIVE → ENDED_EARLY} (exactly one row) and the end record. Nothing else is written: no input, source
     * fact, cash flow, calculation version, metric or official pointer is updated or deleted, and no provider is called.
     *
     * @throws Refused with the closed code; the caller's transaction rolls back
     */
    Ended end(UUID userId, Request request, String idempotencyKey);

    /** What the user's earlier end under the same key says about this request. */
    Replay replay(@Nullable UUID userId, String idempotencyKey, String requestHash);
}
