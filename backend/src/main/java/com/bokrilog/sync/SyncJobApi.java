package com.bokrilog.sync;

import com.bokrilog.portfolio.PortfolioMembershipApi;

import com.bokrilog.portfolio.PortfolioMembershipApi.OfficialValue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 첫 공식 snapshot 작업과 화면 조회의 소유 경계. */
public interface SyncJobApi {
    record Job(UUID jobId, String status, @Nullable String failureReason) {}
    /** @param revoked the user revoked the connection (Story 2.16): no new connection is offered, only a notice */
    record State(@Nullable Job job, @Nullable OfficialValue officialValue,
            boolean connected, boolean restricted, boolean revoked) {}
    record SourceSnapshot(UUID sourceSnapshotId, UUID userId, UUID connectionId,
            UUID providerAccountId, String inputStatus, String policyVersion) {}
    record CollectionJob(UUID jobId, String status, @Nullable String failureCode,
            @Nullable Instant lastCheckedAt) {}
    record InitialCollectionState(boolean permitted, @Nullable CollectionJob job) {}
    record ClassificationJob(UUID jobId, String status, @Nullable String failureCode,
            @Nullable Instant processedAt, int depositCount, int withdrawalCount,
            int excludedCount, int reviewCount, @Nullable String reasonCode) {}
    record ClassificationState(boolean permitted, @Nullable ClassificationJob job) {}

    /** 새 후속 기준 준비의 상태. snapshot은 마지막 성공 준비이며 실패한 재시도에도 유지한다. */
    record NextBaseline(UUID jobId, UUID predecessorPlanId, UUID portfolioId, UUID snapshotId,
            UUID sourceSnapshotId, Instant terminalAt, Instant requestedAt, Instant completedAt) {}
    record NextBaselineState(boolean eligible, String reason, @Nullable Job job,
            @Nullable NextBaseline baseline, PortfolioMembershipApi.@Nullable FirstOfficialSnapshot snapshot) {}
    Optional<Job> admitNextBaseline(@Nullable UUID userId, @Nullable String key);
    NextBaselineState nextBaselineState(@Nullable UUID userId);
    Optional<NextBaseline> currentNextBaseline(UUID userId);

    /**
     * One flow that was not converted automatically (Story 2.6 AC-10).
     *
     * <p>Only held flows are itemized — conversion-required and review-required alike — and only with
     * what the user needs to recognize them: the flow status, the direction, the unrounded source
     * quantity (absolute), the event time and a safe reason. No USD value is carried here or anywhere
     * else in this view.
     */
    record ConversionPendingFlow(String direction, String quantity, Instant occurredAt, String status,
            String reasonCode, boolean retryable) {}
    record ConversionPendingAsset(String assetCode, int count, List<ConversionPendingFlow> flows) {}

    /**
     * The latest conversion attempt of the current COMPLETE classification.
     *
     * <p>{@code flowCount} is the attempt's fixed input count. The four outcome counts are those of the
     * result bundle the attempt published or reused, and are {@code null} when the attempt has no
     * bundle (queued, running, cancelled or interrupted) — they are never reported as zeros that do
     * not add up to {@code flowCount}.
     */
    record ConversionJob(UUID jobId, String status, @Nullable String reasonCode, boolean retryable,
            @Nullable Instant processedAt, int flowCount, @Nullable Integer convertedCount,
            @Nullable Integer estimatedCount, @Nullable Integer conversionRequiredCount,
            @Nullable Integer reviewCount, boolean calculable, String pricePolicyVersion,
            String calculationPolicyVersion, List<ConversionPendingAsset> pendingAssets) {}
    record ConversionState(boolean permitted, @Nullable ConversionJob job) {}

    /** 같은 연결의 진행 작업과 같은 멱등 키는 기존 작업을 반환한다. */
    Optional<Job> admit(@Nullable UUID userId, String idempotencyKey);
    Optional<Job> job(@Nullable UUID userId, UUID jobId);
    Optional<State> state(@Nullable UUID userId);
    Optional<CollectionJob> admitInitialInputs(@Nullable UUID userId, UUID intentId, String idempotencyKey);
    Optional<CollectionJob> initialCollectionJob(@Nullable UUID userId, UUID jobId);
    InitialCollectionState initialCollectionState(@Nullable UUID userId, UUID intentId);
    Optional<ClassificationJob> admitCashflowClassification(@Nullable UUID userId, UUID intentId,
            String idempotencyKey);
    ClassificationState cashflowClassificationState(@Nullable UUID userId, UUID intentId);
    /**
     * Accepts an event-time USD conversion attempt for the intent's COMPLETE classification. The
     * same key and an in-progress attempt are returned as they are. Empty when the classification
     * is missing, partial or under review, or the user, intent, connection or restriction state
     * does not permit it. Never calls the provider.
     */
    Optional<ConversionJob> admitCashflowConversion(@Nullable UUID userId, UUID intentId, String idempotencyKey);
    ConversionState cashflowConversionState(@Nullable UUID userId, UUID intentId);
    /** Read-only source provenance validation for an opaque portfolio snapshot reference. */
    Optional<SourceSnapshot> sourceSnapshot(@Nullable UUID userId, UUID sourceSnapshotId);

    // -------------------------------------------------------------------- Story 2.7

    /**
     * One first-publication attempt. {@code planId} is present only when it published.
     *
     * @param retryable whether a new attempt with a new key can succeed without new conditions or data
     */
    record PublicationJob(UUID jobId, String status, @Nullable String failureCode, boolean retryable,
            @Nullable UUID planId, Instant createdAt, @Nullable Instant finishedAt) {}

    /** Admission of a first-publication attempt. */
    sealed interface PublicationAdmission permits PublicationAdmission.Accepted, PublicationAdmission.Refused {
        record Accepted(PublicationJob job) implements PublicationAdmission {}
        /**
         * @param code {@code NOT_PERMITTED} (403), {@code IDEMPOTENCY_KEY_REQUIRED} (422), or one of
         *     {@code IDEMPOTENCY_CONFLICT}, {@code ACTIVE_PLAN_EXISTS}, {@code PLAN_CONDITIONS_REQUIRED},
         *     {@code PLAN_PERIOD_ENDED}, {@code INPUTS_NOT_READY}, {@code CALCULATION_POLICY_UNVERIFIED}, or —
         *     when an attempt already failed deterministically on exactly these inputs —
         *     {@code CALCULATION_RANGE_EXCEEDED} or {@code CALCULATION_INPUT_INVALID} (409)
         */
        record Refused(String code) implements PublicationAdmission {}
    }

    /**
     * The intent's publication state for the user's screen.
     *
     * @param permitted false only when the account itself is not readable here (no user, or its
     *     deletion is in progress); another user's or an unknown intent is permitted and not startable
     * @param job the latest attempt for this user and intent, if any
     * @param startable whether a new attempt would be admitted now, judged by the same readiness the
     *     admission uses; false while an attempt is in progress
     * @param reason when not startable and no attempt is in progress, the admission's refusal code
     */
    record PublicationState(boolean permitted, @Nullable PublicationJob job, boolean startable,
            @Nullable String reason) {}

    // -------------------------------------------------------------------- Story 2.8

    /** One of the six metrics. {@code value} is a canonical decimal string or absent with a reason. */
    record DashboardMetric(String metric, @Nullable String value, String nature,
            @Nullable String unavailableReason) {}

    /**
     * One trust axis as the server judged it (Story 2.8 AC-5). Every field is a code or a time; the
     * screen maps the codes to copy through closed maps and never judges time itself.
     *
     * @param lastNormalAt the time of the official bundle this axis vouches for: the measurement
     *     (freshness), the cash-flow verification (input reliability) or the calculation basis (nature)
     * @param affectedMetrics the metrics this axis's state reaches, in the screen's order
     * @param nextAction what the axis leaves the user to do: {@code WAIT_FOR_SYNC} (freshness, while a
     *     job runs), {@code SYNC} (freshness, when a new sync can refresh the result — Story 2.11), {@code VERIFY}
     *     (freshness and input reliability, after a sync that ended in review — Story 2.12),
     *     {@code CHOOSE_CONTINUATION} (freshness, after a long gap) or {@code NONE}. An axis never produces
     *     the screen-level {@code CREATE_PLAN} or {@code WAIT_FOR_RESULT} — those belong to
     *     {@link Dashboard.NoPlan} and {@link Dashboard.Preparing}
     */
    record TrustAxis(String state, String cause, Instant lastNormalAt, List<String> affectedMetrics,
            String nextAction) {}

    /**
     * The calculation nature axis and the character it never overwrites: "not computable" is its own
     * state, and an undefined ratio of an estimated plan is still estimated.
     */
    record NatureAxis(TrustAxis axis, String character, List<String> unavailableMetrics,
            int estimatedFlowCount) {}

    /**
     * The three independent axes. There is no combined score. Freshness is judged by SYNC-UX-1.0 against
     * the database clock; its two times travel under their own names in the result.
     */
    record TrustAxes(TrustAxis freshness, TrustAxis inputReliability, NatureAxis calculationNature) {}

    /**
     * The active plan's official bundle, read through its pointer in one consistent read: the plan terms,
     * the pointer, the calculation version it cites, that version's six metrics and the three axes.
     * Nothing is assembled from another version or from candidate, held or review data.
     *
     * <p>{@code calculationKind} is {@code PROGRESS}, or {@code COMPLETION} for the completion version of a completed
     * plan (Story 2.14): the screen tells the two apart by it, never by comparing times.
     */
    record DashboardResult(UUID planId, String planStatus, String principal, Instant startAt, Instant endAt,
            String period, String targetRate, long pointerVersion, Instant publishedAt, UUID calculationId,
            UUID portfolioSnapshotId, UUID basisPortfolioSnapshotId, Instant basisAt, Instant measuredAt,
            Instant cashflowVerifiedThrough, String calculationPolicy, String calendarPolicy,
            String displayPolicy, String pricePolicy, String implementationVersion, String inputHash,
            String resultHash, int flowCount, int estimatedFlowCount, TrustAxes trust,
            List<DashboardMetric> metrics, String calculationKind) {

        /** A progress version's result (every version before Story 2.14). */
        public DashboardResult(UUID planId, String planStatus, String principal, Instant startAt, Instant endAt,
                String period, String targetRate, long pointerVersion, Instant publishedAt, UUID calculationId,
                UUID portfolioSnapshotId, UUID basisPortfolioSnapshotId, Instant basisAt, Instant measuredAt,
                Instant cashflowVerifiedThrough, String calculationPolicy, String calendarPolicy,
                String displayPolicy, String pricePolicy, String implementationVersion, String inputHash,
                String resultHash, int flowCount, int estimatedFlowCount, TrustAxes trust,
                List<DashboardMetric> metrics) {
            this(planId, planStatus, principal, startAt, endAt, period, targetRate, pointerVersion, publishedAt,
                    calculationId, portfolioSnapshotId, basisPortfolioSnapshotId, basisAt, measuredAt,
                    cashflowVerifiedThrough, calculationPolicy, calendarPolicy, displayPolicy, pricePolicy,
                    implementationVersion, inputHash, resultHash, flowCount, estimatedFlowCount, trust, metrics,
                    "PROGRESS");
        }
    }

    // -------------------------------------------------------------------- Story 2.11

    /** Where a sync request came from: entering the dashboard, the tab becoming visible again, or the button. */
    enum SyncTrigger { ENTRY, VISIBILITY, MANUAL }

    /**
     * One incremental sync attempt as the screen may see it: its purpose and reason, its trigger, its status, the
     * stage it reached, its closed code and its two times. No plan, pointer, amount, time of an input, user or
     * provider identifier.
     *
     * @param purpose {@code SYNC}, {@code VERIFY} (a precision verification, Story 2.12), {@code CATCH_UP} (the
     *     continuation of a plan after a long gap), {@code COMPLETION} (the first post-end observation and the
     *     completion result of a plan that reached its end, Story 2.14) or {@code POST_END_VALUE} (the wallet only,
     *     after the completion was judged)
     * @param reason {@code null} for a sync, a completion and a post-end value; for a verification and a continuation
     *     one of {@link VerificationReason}
     * @param trigger {@code ENTRY}, {@code VISIBILITY}, {@code MANUAL} or {@code SYSTEM} (the public verification
     *     method's)
     * @param status {@code QUEUED}, {@code RUNNING}, {@code SUCCEEDED}, {@code FAILED}, {@code NEEDS_REVIEW}
     *     or {@code BLOCKED}
     * @param stage {@code TIME}, {@code OBSERVATION} (a completion's or post-end value's wallet read),
     *     {@code COLLECTION}, {@code FINAL_SWEEP} (a continuation's last recent sweep),
     *     {@code CLASSIFICATION}, {@code CONVERSION}, {@code CALCULATION} or {@code PUBLICATION}; {@code null}
     *     before the first
     */
    record SyncJob(UUID jobId, String purpose, @Nullable String reason, String trigger, String status,
            @Nullable String stage, @Nullable String failureCode, Instant admittedAt, @Nullable Instant finishedAt) {}

    /**
     * The sync part of a ready dashboard: whether a sync can be asked for now, why not, when a manual one can be
     * asked for again, and the plan's latest attempt. Judged by the same rules as the admission, against the
     * database clock, in the dashboard's one consistent read.
     *
     * @param availability {@code AVAILABLE}, {@code IN_PROGRESS}, {@code COOLDOWN}, {@code LONG_GAP} or
     *     {@code UNAVAILABLE}
     * @param reason with {@code UNAVAILABLE} only: {@code RESTRICTED}, {@code CONNECTION_UNAVAILABLE} or
     *     {@code PLAN_PERIOD_ENDED}
     * @param retryAfterSeconds with {@code COOLDOWN} only: the seconds until a manual sync is admitted again
     */
    record SyncState(String availability, @Nullable String reason, @Nullable Long retryAfterSeconds,
            @Nullable SyncJob latest) {}

    /** The admission of one sync request (SYNC-UX-1.0). Nothing here calls the provider. */
    sealed interface SyncAdmission permits SyncAdmission.Accepted, SyncAdmission.Joined, SyncAdmission.Deferred,
            SyncAdmission.NotPermitted, SyncAdmission.Refused {
        /** A new attempt was queued. */
        record Accepted(SyncJob job) implements SyncAdmission {}
        /**
         * The attempt in progress — of any purpose: a sync joins a verification in progress (Story 2.12) — or the one
         * the same key already made as a sync with the same trigger.
         */
        record Joined(SyncJob job) implements SyncAdmission {}
        /**
         * No attempt was made: {@code COOLDOWN} (a manual request within 30 seconds of the last admission),
         * {@code NOT_DUE} (an automatic one within 5 minutes of the later of the last admission and the current
         * publication) or {@code LONG_GAP} (more than 30 days since the cash flows were last verified).
         *
         * @param latest the plan's latest attempt, if any
         * @param retryAfterSeconds for {@code COOLDOWN} and {@code NOT_DUE}: the seconds until it would be due
         */
        record Deferred(String outcome, @Nullable SyncJob latest, @Nullable Long retryAfterSeconds)
                implements SyncAdmission {}
        /** The account's own write access refused it (its deletion began or ended). */
        record NotPermitted() implements SyncAdmission {}
        /**
         * @param code {@code BAD_REQUEST} (400, no trigger), {@code IDEMPOTENCY_KEY_REQUIRED} (422), or one of
         *     {@code IDEMPOTENCY_CONFLICT}, {@code NO_ACTIVE_PLAN} and {@code SYNC_UNAVAILABLE} (409). The HTTP
         *     surface answers any other code as an internal error.
         */
        record Refused(String code) implements SyncAdmission {}
    }

    /**
     * Admits one sync request for the user's active plan (Story 2.11): the account's write access, the same key
     * replayed, the active plan and its pointer, an attempt in progress, unavailability, the long gap, the
     * cooldown and automatic interval, in that order, in one short transaction without any provider call. When the
     * database clock reached the active plan's end {@code T} and nothing is in progress, the same transaction completes
     * the plan (Story 2.14) and admits its completion attempt; a completed plan's request admits the completion attempt
     * while its comparison is not judged, else a post-end value attempt. A
     * {@code null} trigger is not admitted; the HTTP surface answers it as a malformed request.
     */
    SyncAdmission admitPlanSync(@Nullable UUID userId, @Nullable SyncTrigger trigger, @Nullable String idempotencyKey);

    /**
     * The dashboard read model (Story 2.8). One of seven outcomes; only {@link Dashboard.Ready},
     * {@link Dashboard.Ended} (Story 2.13) and {@link Dashboard.Completed} (Story 2.14) carry financial fields. The screen-level next action is a closed set per
     * outcome: {@code CREATE_PLAN} ({@link Dashboard.NoPlan}), {@code WAIT_FOR_RESULT} ({@link Dashboard.Preparing}),
     * {@code WAIT_FOR_SYNC}, {@code SYNC}, {@code VERIFY}, {@code CHOOSE_CONTINUATION} or {@code NONE}
     * ({@link Dashboard.Ready}, the freshness axis's own), {@code PREPARE_NEXT_PLAN} ({@link Dashboard.Ended}), and
     * {@code SYNC_FOR_OBSERVATION} or {@code PREPARE_NEXT_PLAN} ({@link Dashboard.Completed}).
     *
     * <p>The plan shown is one choice (Story 2.13): the active plan; without one, "being prepared" while a first
     * publication is in progress; otherwise the most recent plan when it ended early or completed (Story 2.14). The
     * evidence and the adjustment history judge their reference against the same plan's current pointer.
     */
    sealed interface Dashboard permits Dashboard.NotPermitted, Dashboard.NotFound, Dashboard.Unavailable, Dashboard.NoPlan,
            Dashboard.Preparing, Dashboard.Ready, Dashboard.Ended, Dashboard.Completed {
        /** The account's own access refused it before or after the read (its deletion began or ended). */
        record NotPermitted() implements Dashboard {}
        record NotFound() implements Dashboard {}
        /** The pointer's bundle did not agree with itself; nothing is returned in part. */
        record Unavailable() implements Dashboard {}
        /** No active plan, no publication in progress and no ended plan. */
        record NoPlan(String nextAction) implements Dashboard {}
        /** No active plan yet, and one of the user's publication attempts is queued or running. */
        record Preparing(String nextAction) implements Dashboard {}
        /**
         * The active plan's official bundle, its sync state (Story 2.11), its verification state and what its
         * version changed (Story 2.12), and whether it can be ended now (Story 2.13).
         */
        record Ready(DashboardResult result, String nextAction, SyncState sync, VerificationState verification,
                VersionChange change, PlanEndAvailability planEnd) implements Dashboard {}
        /**
         * The read-only record of the user's plan that ended early (Story 2.13): its last official bundle — the
         * result's shape is the ready one's, its plan status {@code ENDED_EARLY} and its freshness axis
         * {@code ENDED} — and the end facts. No sync, verification or change part: an ended plan is not synced.
         * The next action is {@code PREPARE_NEXT_PLAN}.
         */
        record Ended(DashboardResult result, String nextAction, PlanEndRecord end) implements Dashboard {}
        /**
         * A plan that reached its end {@code T} (Story 2.14): its current official bundle — the completion version once
         * the comparison is judged on complete inputs, else the last progress version — with the plan status
         * {@code COMPLETED} and the freshness axis {@code COMPLETED}, the completion record, the value published after
         * the first observation (if any) and the sync part of the post-end syncs. An active plan whose {@code T} the
         * database clock reached is shown so before its transition, with a comparison not judged yet: never as an active
         * plan that keeps accruing. The next action is {@code SYNC_FOR_OBSERVATION} while the first observation is
         * missing or the comparison is pending or held, else {@code PREPARE_NEXT_PLAN}.
         */
        record Completed(DashboardResult result, String nextAction, PlanCompletionRecord completion,
                @Nullable PostEndValue postEnd, @Nullable SyncState sync, VersionChange change) implements Dashboard {}
    }

    // -------------------------------------------------------------------- Story 2.14

    /**
     * The completion facts of a completed plan: the planned end {@code T}, the comparison and its reason, the first
     * post-end observation (value, measurement time, delay {@code measured − T} in milliseconds — all three or none;
     * never the valuation at {@code T}), the completion target of the completion version and how many confirmed
     * external flows lie in {@code (T, measured]} (both only with a completion version).
     *
     * @param comparison {@code PENDING}, {@code CONFIRMED}, {@code UNAVAILABLE} or {@code HELD}
     * @param reason {@code OBSERVATION_DELAY_EXCEEDED}, {@code POST_END_CASHFLOW}, {@code HISTORY_OUT_OF_RANGE},
     *     {@code DATA_GAP} ({@code UNAVAILABLE}) or {@code INPUT_REVIEW_REQUIRED} ({@code HELD}); {@code null} otherwise
     * @param retryable whether a sync request runs the completion again: no first observation yet, a pending
     *     comparison, or one held by a review that is not persistent. A comparison held by a persistent review of the
     *     same base stays held (Story 2.12's rule) and a sync then only refreshes the post-end value
     */
    record PlanCompletionRecord(Instant scheduledEndAt, String comparison, @Nullable String reason,
            @Nullable String observedValue, @Nullable Instant observedMeasuredAt, @Nullable Long observationDelayMillis,
            @Nullable String completionTarget, @Nullable Integer postEndCashflowCount, boolean retryable) {}

    /** The portfolio's official value published after the first observation: the "post-end current value". */
    record PostEndValue(String value, Instant measuredAt) {}

    // -------------------------------------------------------------------- Story 2.13

    /**
     * Whether the ready dashboard's plan can be ended now, judged by the end's own rules in the dashboard's one read.
     *
     * @param availability {@code AVAILABLE}, {@code NEW_PLAN_CHOICE} (the gap is long: only the long-gap choice's
     *     "start a new plan" ends it) or {@code UNAVAILABLE}
     * @param reason with {@code UNAVAILABLE} only: {@code RESTRICTED}, {@code PLAN_PERIOD_ENDED} or
     *     {@code RESULT_TOO_RECENT} (the official result's measurement or cash-flow verification lies ahead of the
     *     database clock — Bybit's time is trusted up to 60 seconds from it — so it cannot be recorded as the last
     *     normal result before an end now; a moment later it can)
     */
    record PlanEndAvailability(String availability, @Nullable String reason) {}

    /**
     * The end facts of an ended plan's record, as the end stored them: the reason, the server's end time, the planned
     * end {@code T}, the last normal measurement and its delay to the end ({@code endedAt − lastNormalMeasuredAt}), the
     * completion comparison state and — for a long-gap end — the uncollected range {@code (verified, endedAt]}.
     */
    record PlanEndRecord(String reason, Instant endedAt, Instant scheduledEndAt, Instant lastNormalMeasuredAt,
            long lastNormalDelayMillis, String completion, @Nullable Instant uncollectedFrom,
            @Nullable Instant uncollectedUntil) {}

    /**
     * What an early end reads of the plan's official result (Story 2.13): its basis, or why there is none — the plan is
     * not the user's active plan any more ({@link NotActive}), or its official result does not agree with itself by
     * the ready dashboard's own checks ({@link Unavailable}), so an end recorded on it would be a record nobody could
     * read.
     */
    sealed interface PlanEndBasisRead permits PlanEndBasisRead.NotActive, PlanEndBasisRead.Unavailable, PlanEndBasis {
        record NotActive() implements PlanEndBasisRead {}

        record Unavailable() implements PlanEndBasisRead {}
    }

    /**
     * The official basis an early end judges and records (Story 2.13): the active plan's pointer, the calculation it
     * cites (agreeing with the plan, its terms, the portfolio pointer and its six metrics — the ready dashboard's own
     * checks), that calculation's two times, the plan's end {@code T}, the database clock, the long gap judged at that
     * clock by the sync admission's own function, and whether the result is too recent to end on now — the end time
     * (that clock to the millisecond) would precede its measurement or verification. The dashboard's plan-end part
     * judges the last two the same way.
     */
    record PlanEndBasis(UUID planId, long pointerVersion, UUID calculationId, UUID portfolioSnapshotId,
            Instant measuredAt, Instant cashflowVerifiedThrough, Instant endAt, Instant now, boolean longGap,
            boolean resultTooRecent) implements PlanEndBasisRead {}

    /**
     * The basis of the user's active plan {@code planId}. <b>Joins the caller's transaction only</b>
     * ({@code MANDATORY}): the plan module reads it under its own locks, inside the end's transaction, and in the
     * preparation's one read-only repeatable read.
     */
    PlanEndBasisRead planEndBasis(@Nullable UUID userId, UUID planId);

    /**
     * Accepts the first publication of the intent's active plan and official result. Its inputs are
     * pinned now: the intent's terms and one lineage of complete candidate, COMPLETE classification and
     * calculable conversion. The same key with the same intent returns the same attempt; an attempt in
     * progress for the intent is returned instead of a second one. The key is required: this command
     * creates an immutable plan, so a missing key is refused rather than replaced by a random one.
     */
    PublicationAdmission admitPlanPublication(@Nullable UUID userId, UUID intentId,
            @Nullable String idempotencyKey);

    /** The intent's latest publication attempt and whether a new one could start now. */
    PublicationState planPublicationState(@Nullable UUID userId, UUID intentId);

    /** Whether a dashboard read found its bundle agreeing with itself, or read no bundle at all. */
    enum BundleConsistency { CONSISTENT, INCONSISTENT, NOT_READ }

    /**
     * One dashboard read and what it learned about the bundle. {@code consistency} is
     * {@link BundleConsistency#NOT_READ} when the account was refused before the read; a bundle read and
     * then dropped by the second deletion check keeps the consistency it was read with.
     *
     * <p>{@code displayRestricted}(Story 3.2): 계산 표시 제한이 보이는 결과를 막는다 — 그 계획, portfolio, 계산 정책, 또는
     * 전역. 묶음은 그대로 읽어 온전한지 판정하고, HTTP 표면이 상태·계획 정체성·다음 행동을 두고 계산 값을 뺀다. 공식
     * 기록은 아무것도 바뀌지 않는다.
     */
    record DashboardRead(Dashboard dashboard, BundleConsistency consistency, boolean displayRestricted) {

        /** 계산 표시가 제한되지 않은 읽기. */
        public DashboardRead(Dashboard dashboard, BundleConsistency consistency) {
            this(dashboard, consistency, false);
        }
    }

    /**
     * The user's dashboard (Story 2.8): the account's own access is checked, the plan, pointer,
     * calculation version, metrics and freshness are read in one read-only {@code REPEATABLE READ}
     * transaction, and the access is checked again. A deletion that began or finished on either side
     * wins. A restriction on a write command does not hide an existing result. A failing read throws;
     * nothing is returned in part. The HTTP surface writes the one operational line per read, because it
     * alone knows the status it answered with.
     */
    DashboardRead dashboard(@Nullable UUID userId);
    DashboardRead historicalDashboard(@Nullable UUID userId, UUID planId);
    Optional<java.util.List<PlanPublicationPort.LatestPlan>> planHistory(@Nullable UUID userId);

    // -------------------------------------------------------------------- Story 2.9

    /**
     * The dashboard result a screen shows, named by the three values of its official pointer. The
     * evidence read only judges whether they are the user's current pointer; nothing is looked up by them.
     */
    record EvidenceReference(UUID planId, long pointerVersion, UUID calculationId) {}

    /**
     * The read-only lineage of the current official result (Story 2.9): which snapshots, times, inputs,
     * policies and calculation version it came from, how each metric's character was reached, and the
     * attempts of the same intent that never published. Internal user ids, provider account ids, the
     * Bybit UID, credentials, raw provider responses and individual cash-flow events are not here.
     */
    record CalculationEvidence(UUID planId, long pointerVersion, UUID calculationId, Snapshots snapshots,
            Times times, Connection connection, SourceRange sourceRange, Classification classification,
            Conversion conversion, Cashflows cashflows, Plan plan, Calculation calculation,
            List<MetricImpact> metrics, List<OfficialVersion> officialVersions,
            List<UnpublishedAttempt> unpublishedAttempts, EvidenceChange change) {

        /**
         * What this version changed from the one it replaced (Story 2.12): why the attempt that made it ran, the
         * ranges it verified and the evidence it reused, the previous version and its basis, when the impact starts,
         * the six metrics of both versions (both are immutable rows; the basis time moved too) and every changed
         * event. A first publication has kind {@code NONE}, no previous version and no rows.
         *
         * @param kind {@code CORRECTED} (a late addition, a removal or a changed value), {@code VERIFIED_NO_CHANGE}
         *     (a verification or continuation without one) or {@code NONE}
         * @param lateAddedCount additions found late; {@code valueChangedCount} values changed (another user's
         *     verification can correct a shared price under an ordinary sync); {@code removedCount} flows removed — the
         *     three the dashboard's {@code lateChangeCount} sums
         */
        public record EvidenceChange(String kind, @Nullable String purpose, @Nullable String reason,
                int lateAddedCount, int valueChangedCount, int removedCount, List<VerificationRange> targets,
                List<VerificationRange> reused, @Nullable PreviousVersion previous, @Nullable Instant firstAffectedAt,
                List<MetricDelta> metrics, List<ChangedEvent> events) {}

        public record PreviousVersion(long pointerVersion, UUID calculationId, Instant basisAt) {}

        /** One metric, as the previous version and this one hold it (a value or a reason each). */
        public record MetricDelta(String metric, @Nullable String previousValue,
                @Nullable String previousUnavailableReason, @Nullable String value, @Nullable String unavailableReason) {}

        /**
         * One changed event: its Bybit id, time and type, how it changed, both USD values and estimated flags, the
         * price evidence kind, when BokriLog first observed it and when this version applied it.
         *
         * @param late whether it was not after the previous version's verification
         */
        public record ChangedEvent(String sourceId, Instant occurredAt, String changeKind, boolean late,
                String transactionType, @Nullable String previousUsdValue, @Nullable Boolean previousEstimated,
                @Nullable String usdValue, @Nullable Boolean estimated, String evidenceType, Instant discoveredAt,
                Instant appliedAt) {}

        /**
         * @param portfolioSnapshotId the official portfolio snapshot the version was calculated on
         * @param portfolioSequence that snapshot's publication sequence
         * @param basisPortfolioSnapshotId the plan's basis snapshot (its start value)
         * @param basisSourceSnapshotId the source snapshot the basis snapshot was composed from (measured at t0)
         * @param sourceSnapshotId the tc source snapshot the portfolio snapshot was composed from
         */
        public record Snapshots(UUID portfolioSnapshotId, long portfolioSequence, UUID basisPortfolioSnapshotId,
                UUID basisSourceSnapshotId, UUID sourceSnapshotId) {}

        /**
         * The plan start and the three times of the version, each under its own name. Bybit gives no token
         * that ties a balance and the cash-flow log to one moment, so the measurement and the cash-flow
         * verification are two facts, never one "latest" time.
         */
        public record Times(Instant t0, Instant tc, Instant measuredAt, Instant cashflowVerifiedThrough) {}

        /**
         * Where the inputs were collected: the provider, the connection (provenance only) and the connection's
         * policy version, as the first snapshot recorded it after checking it against the connection's own.
         */
        public record Connection(String provider, UUID connectionId, String policyVersion) {}

        /**
         * The collected range {@code (t0, tc]} with the collection policy. {@code windowStart} is where the
         * version's own collection windows started: {@code t0} for a first publication, and for an incremental
         * sync (Story 2.11) its overlap start — the events before it were carried from the previous version.
         */
        public record SourceRange(Instant rangeStart, Instant rangeEnd, Instant windowStart, String collectionPolicy,
                String completenessHash) {}

        public record Classification(UUID classificationId, String policyVersion, String inputScopeHash) {}

        public record Conversion(UUID conversionId, String pricePolicy, String resultFingerprint) {}

        /** How many external cash flows the version applied, and how many of them were estimated. */
        public record Cashflows(int appliedCount, int estimatedCount) {}

        /** The plan version: its id and its immutable terms (canonical decimal strings). */
        public record Plan(UUID planId, String principal, Instant startAt, Instant endAt, String period,
                String targetRate) {}

        /**
         * @param calculationKind {@code PROGRESS}, or {@code COMPLETION} for the completion version at {@code u = T}
         *     (Story 2.14)
         */
        public record Calculation(UUID calculationId, String calculationPolicy, String calendarPolicy,
                String displayPolicy, String precisionPolicy, String timeZone, String implementationVersion,
                String inputHash, String resultHash, Instant createdAt, String calculationKind) {

            /** A progress version's calculation. */
            public Calculation(UUID calculationId, String calculationPolicy, String calendarPolicy,
                    String displayPolicy, String precisionPolicy, String timeZone, String implementationVersion,
                    String inputHash, String resultHash, Instant createdAt) {
                this(calculationId, calculationPolicy, calendarPolicy, displayPolicy, precisionPolicy, timeZone,
                        implementationVersion, inputHash, resultHash, createdAt, "PROGRESS");
            }
        }

        /**
         * One metric's character and why: an undefined metric's reason code, and — for a defined derived
         * metric of an estimated version — the estimated cause, derived from the applied flows (not a stored
         * column). An undefined metric carries its reason and no cause.
         */
        public record MetricImpact(String metric, String nature, @Nullable String unavailableReason,
                @Nullable EstimatedCause estimatedCause) {}

        /** The flows converted with an estimated price and the kind of that price evidence. */
        public record EstimatedCause(String evidenceType, int flowCount) {}

        /**
         * One official version: its publication order (the pointer version), the time it became official, the
         * time it stopped being official (the next version's publication; {@code null} for the current one of an
         * active plan, and the end time for the current one of a plan that ended early — Story 2.13) and whether it
         * is the current one.
         */
        public record OfficialVersion(long pointerVersion, UUID calculationId, Instant publishedAt,
                @Nullable Instant appliedUntil, boolean current) {}

        /** An attempt of the same intent that published nothing. Read-only; never an official value. */
        public record UnpublishedAttempt(String status, String reasonCode, Instant finishedAt) {}
    }

    /** The outcome of one evidence read. Only {@link Found} carries lineage. */
    sealed interface EvidenceOutcome permits EvidenceOutcome.NotPermitted, EvidenceOutcome.NotFound,
            EvidenceOutcome.Unavailable, EvidenceOutcome.Restricted, EvidenceOutcome.Found {
        /**
         * 참조는 현재 포인터지만 계산 표시 제한이 그 결과를 막는다(Story 3.2). 계보는 아무것도 돌려주지 않고 공식 기록은
         * 바뀌지 않는다.
         */
        record Restricted() implements EvidenceOutcome {}
        /** The account's own access refused it before or after the read. */
        record NotPermitted() implements EvidenceOutcome {}
        /**
         * The reference is not the user's current official pointer — another user's, an earlier version,
         * an unknown or malformed id, a missing value, or no plan shown at all (the shown plan is the active one, or
         * the most recent one that ended early — Story 2.13). One answer for all of them.
         */
        record NotFound() implements EvidenceOutcome {}
        /** The current pointer's lineage did not agree with itself; nothing is returned in part. */
        record Unavailable() implements EvidenceOutcome {}
        record Found(CalculationEvidence evidence) implements EvidenceOutcome {}
    }

    /**
     * One evidence read and what it learned about the lineage: {@link BundleConsistency#NOT_READ} when no
     * lineage was read (refused first, or the reference names no current pointer).
     */
    record EvidenceRead(EvidenceOutcome outcome, BundleConsistency consistency) {}

    /**
     * The calculation evidence of the user's current official result (Story 2.9). The account's own access
     * is checked, the pointer, the calculation version, its metrics and cash flows, and the collection,
     * classification and conversion records are read in one read-only {@code REPEATABLE READ} transaction,
     * and the access is checked again. A {@code null} reference (missing or malformed) is not found. A
     * failing read throws; nothing is returned in part.
     */
    EvidenceRead calculationEvidence(@Nullable UUID userId, @Nullable EvidenceReference reference);

    // -------------------------------------------------------------------- Story 2.10

    /** Which applied flows a history page lists: all of them, the deposits, or the withdrawals. */
    enum AdjustmentDirection { ALL, IN, OUT }

    /**
     * One page of the adjustment history a screen asks for: the three values of the shown pointer (as the
     * evidence takes them), the direction filter, and the opaque cursor of the page before — {@code null}
     * for the first page. The cursor travels exactly as the client sent it: only sync can judge it, and a
     * cursor that is malformed or was made for another scope names nothing.
     */
    record AdjustmentQuery(UUID planId, long pointerVersion, UUID calculationId, AdjustmentDirection direction,
            @Nullable String cursor) {}

    /**
     * The external cash flows the current official calculation version applied (Story 2.10), one page in the
     * version's own order, each with its lineage: the source event, its classification, its event-time USD
     * conversion (price legs included) and the calculation that applied it. Only the version's applied rows
     * are here — never an excluded, held, review-required or other attempt's event — and the summary covers
     * the whole filtered range, summed exactly from the stored values. Internal user ids, provider account
     * ids, the Bybit UID, credentials, raw provider responses, wallet addresses and transaction hashes are not
     * here.
     *
     * @param appliedAt when the version became official (the pointer's publication time)
     * @param nextCursor the opaque cursor of the next page, or {@code null} on the last one
     */
    record AdjustmentHistory(UUID planId, long pointerVersion, UUID calculationId, AdjustmentDirection direction,
            Instant appliedAt, Coverage coverage, Summary summary, List<Adjustment> rows,
            @Nullable String nextCursor) {

        /** The verified cash-flow range of the version: {@code (t0, cashflowVerifiedThrough]}. */
        public record Coverage(Instant from, Instant through) {}

        /**
         * @param count the applied flows in the filtered range
         * @param netUsdValue their signed USD values added exactly, never rounded (canonical decimal string)
         * @param excludedCount the source events the classification excluded, apart from every row and total
         */
        public record Summary(int count, String netUsdValue, int excludedCount) {}

        /**
         * One applied flow, in the reading order: event → classification → price → discovery and application.
         *
         * @param sequence its place in the version's order, which realizes {@code (event time, provider
         *     account, source id)}
         * @param direction {@code IN} (external deposit) or {@code OUT} (external withdrawal)
         * @param signedQuantity the unrounded source quantity, signed by the direction
         * @param usdValue the signed USD value the version applied, unrounded
         * @param discoveredAt when BokriLog first observed the source event
         * @param changed whether the current conversion continues an earlier one in which this event's value
         *     was different
         * @param revisions the earlier conversion versions of this event, newest first; empty unless changed
         * @param versionChange the marker of a row this version added late or whose value it changed (Story 2.12),
         *     {@code null} otherwise
         */
        public record Adjustment(int sequence, Instant occurredAt, String direction, String asset,
                String signedQuantity, String usdValue, Classification classification, Price price,
                Instant discoveredAt, References references, boolean changed, List<Revision> revisions,
                @Nullable VersionMark versionChange) {}

        /**
         * Why a row carries a version marker: added late ({@code ADDED}, {@code late}) or its value changed
         * ({@code VALUE_CHANGED}), with the value the previous version applied, when the event was first observed and
         * when this version was applied.
         */
        public record VersionMark(String change, boolean late, @Nullable String previousUsdValue, Instant discoveredAt,
                Instant appliedAt) {}

        /** @param transactionType the Bybit transaction log type the event came with */
        public record Classification(String transactionType, String ruleId, String policyVersion) {}

        /** @param unitPrice the USD price of one unit, unrounded */
        public record Price(String policyVersion, String evidenceType, boolean estimated, String unitPrice,
                String routeId, List<PriceLeg> legs) {}

        /** One leg of a spot route: the closed one-minute candle whose close was used, and how it was chosen. */
        public record PriceLeg(String symbol, Instant candleStartAt, Instant candleEndAt, String closePrice,
                String selectionRule, int lagMinutes) {}

        /** @param sourceId the Bybit transaction log id — the key to match the exchange's own record */
        public record References(UUID sourceEventId, String sourceId, UUID classificationId, UUID conversionId,
                UUID calculationId) {}

        /**
         * An earlier conversion version of the event. It was never used by an official calculation, and it is
         * shown as it was stored — never rewritten.
         *
         * <p>"Never used officially" holds because every official version has its own classification (Story
         * 2.11 classifies each sync's candidate anew, and so does every Story 2.12 verification and continuation —
         * a price revision they find becomes a new classification's conversion, never a relink of a cited one) and a
         * conversion chain never leaves its classification: an earlier link of the chain was replaced before any
         * version cited it. Story 2.15, which may reconvert a classification an official version already cited,
         * must revisit it.
         *
         * @param usdValue the value it had, or {@code null} when it had none (not converted then)
         * @param changeReason why the next version replaced it
         * @param discoveredAt when the change was found: the next version's processing time
         */
        public record Revision(UUID conversionId, Instant processedAt, String status, @Nullable String reasonCode,
                @Nullable String usdValue, String changeReason, Instant discoveredAt) {}
    }

    /** The outcome of one adjustment history read. Only {@link Found} carries rows. */
    sealed interface AdjustmentOutcome permits AdjustmentOutcome.NotPermitted, AdjustmentOutcome.NotFound,
            AdjustmentOutcome.Unavailable, AdjustmentOutcome.Restricted, AdjustmentOutcome.Found {
        /** 참조는 현재 포인터지만 계산 표시 제한이 그 결과를 막는다(Story 3.2). */
        record Restricted() implements AdjustmentOutcome {}
        /** The account's own access refused it before or after the read. */
        record NotPermitted() implements AdjustmentOutcome {}
        /**
         * The reference is not the user's current official pointer, or the cursor is not one made for this
         * user, reference and filter — one answer for every such case, like the evidence's.
         */
        record NotFound() implements AdjustmentOutcome {}
        /** The version's applied history did not agree with its lineage; no partial list is returned. */
        record Unavailable() implements AdjustmentOutcome {}
        record Found(AdjustmentHistory history) implements AdjustmentOutcome {}
    }

    /** One adjustment history read and what it learned about the lineage of the whole version. */
    record AdjustmentRead(AdjustmentOutcome outcome, BundleConsistency consistency) {}

    /**
     * One page of the adjustment history of the user's current official result (Story 2.10). The account's own
     * access is checked, the cursor is judged against this user, reference and filter, the version's whole
     * applied history is checked against its lineage and the page is read in one read-only
     * {@code REPEATABLE READ} transaction, and the access is checked again. A {@code null} query (missing,
     * repeated or malformed values) is not found. Nothing is recalculated, reclassified or reconverted. A
     * failing read throws; nothing is returned in part.
     */
    AdjustmentRead adjustmentHistory(@Nullable UUID userId, @Nullable AdjustmentQuery query);

    // -------------------------------------------------------------------- Story 2.12

    /** What the user asks for: a precision verification, or the continuation of the plan after a long gap. */
    enum VerificationKind { PRECISION, CATCH_UP }

    /**
     * Why a verification or continuation runs. The HTTP surface admits the user's two (the server derives which:
     * {@code GAP_SUSPECTED} when unexplained stored events exist, else {@code USER_REQUEST}) and the continuation's
     * own; the other four come only through {@link #admitSystemVerification}.
     */
    enum VerificationReason { USER_REQUEST, GAP_SUSPECTED, UNRECOGNIZED_EVENT, DUPLICATE_SUSPECTED,
        LONG_GAP_CONTINUATION, PLAN_END_PREPARATION, OPERATOR_INVESTIGATION }

    /**
     * One range a verification planned: a target ({@code UNCONFIRMED}, {@code GAP}, {@code ANOMALY},
     * {@code EXPLICIT}, {@code NEW}, {@code FINAL_SWEEP}) or evidence it reused ({@code REUSED}), closed.
     */
    record VerificationRange(String kind, Instant start, Instant end) {}

    /** An explicit investigation range ({@code OPERATOR_INVESTIGATION} only), inside {@code (t0, verified]}. */
    record InvestigationRange(Instant start, Instant end) {}

    /** How a verification's reconciliation came out, by outcome. */
    record VerificationCounts(int added, int same, int missing, int superseded, int conflict) {}

    /**
     * A verification or continuation attempt with its plan and its reconciliation counts.
     *
     * @param holdsResult whether this attempt is the persistent review of the current base — the one that holds the
     *     input reliability axis ({@code VERIFICATION_REVIEW_REQUIRED} or {@code HISTORY_OUT_OF_RANGE}); false for an
     *     attempt in progress, one that succeeded, one of another base, or a review asking again could change
     */
    record VerificationJob(SyncJob job, List<VerificationRange> targets, List<VerificationRange> reused,
            VerificationCounts counts, boolean holdsResult) {}

    /**
     * What a precision verification would read now: from where, the unconfirmed range's start, how many unexplained
     * stored events it would look around, and where the reused evidence ends ({@code null} without any).
     */
    record VerificationPreview(Instant rangeStart, Instant unconfirmedFrom, int anomalyCount,
            @Nullable Instant reusedUntil) {}

    /**
     * The verification part of a ready dashboard, judged by the admission's own rules in the dashboard's one read.
     *
     * @param availability judged in this order: {@code IN_PROGRESS}, {@code UNAVAILABLE}, {@code NOT_USEFUL} (a
     *     verification or continuation of this base already ended in a persistent review: asking again for either
     *     changes nothing, even in a long gap), {@code LONG_GAP} (only the continuation can run), {@code COOLDOWN},
     *     {@code AVAILABLE}
     * @param reason with {@code UNAVAILABLE} only: {@code RESTRICTED}, {@code CONNECTION_UNAVAILABLE} or
     *     {@code PLAN_PERIOD_ENDED}
     * @param retryAfterSeconds with {@code COOLDOWN} (and with {@code LONG_GAP} while the continuation cools down)
     * @param suggestedReason the reason a precision verification would be admitted with now; only with
     *     {@code AVAILABLE} or {@code COOLDOWN}, {@code null} otherwise
     * @param preview what a precision verification would read now; none while an attempt is in progress or the gap
     *     is long
     * @param catchUpFrom whenever the gap is long: where the continuation would start
     */
    record VerificationState(String availability, @Nullable String reason, @Nullable Long retryAfterSeconds,
            @Nullable String suggestedReason, @Nullable VerificationPreview preview, @Nullable Instant catchUpFrom,
            @Nullable VerificationJob latest) {}

    /**
     * What the current version changed (Story 2.12 AC-7): {@code CORRECTED} when it added an event late, removed one
     * or changed a value; {@code VERIFIED_NO_CHANGE} when a verification or continuation made it without any;
     * {@code NONE} otherwise. The count, the impact start and the ranges come from the version's change rows and the
     * attempt that made it. A version whose change rows are not exactly its flow difference shows {@code NONE}: the
     * marker is never drawn from rows the evidence would refuse (the evidence and the history answer 503 for it).
     *
     * @param lateChangeCount the sum of the three counts that follow it
     * @param lateAddedCount additions found late (not after the previous verification)
     * @param valueChangedCount flows whose USD value or estimated flag changed — a price correction, possibly by
     *     another user's verification of a shared candle under this user's ordinary sync: a change, not a late event
     * @param removedCount flows the version no longer applies
     * @param verifiedAt when the verification or continuation that made the version finished (its attempt's
     *     {@code finished_at}, to the millisecond); {@code null} for any other version
     */
    record VersionChange(String kind, @Nullable String purpose, @Nullable String reason, int lateChangeCount,
            int lateAddedCount, int valueChangedCount, int removedCount, @Nullable Instant firstAffectedAt,
            @Nullable Instant verifiedAt, List<VerificationRange> targets, List<VerificationRange> reused) {}

    /** The admission of a verification or continuation. Nothing here calls the provider. */
    sealed interface VerificationAdmission permits VerificationAdmission.Accepted, VerificationAdmission.Joined,
            VerificationAdmission.Cooldown, VerificationAdmission.NotPermitted, VerificationAdmission.Refused {
        record Accepted(SyncJob job) implements VerificationAdmission {}
        /** The same key replayed with the same kind, or the attempt of the same purpose in progress. */
        record Joined(SyncJob job) implements VerificationAdmission {}
        /** Within 30 seconds of the plan's last admission, of any purpose. */
        record Cooldown(@Nullable SyncJob latest, long retryAfterSeconds) implements VerificationAdmission {}
        /** The account's own write access refused it. */
        record NotPermitted() implements VerificationAdmission {}
        /**
         * @param code {@code BAD_REQUEST} (400), {@code IDEMPOTENCY_KEY_REQUIRED} (422), or one of
         *     {@code NO_ACTIVE_PLAN}, {@code REFERENCE_STALE}, {@code JOB_IN_PROGRESS}, {@code VERIFICATION_UNAVAILABLE},
         *     {@code LONG_GAP}, {@code NOT_LONG_GAP}, {@code VERIFICATION_NOT_USEFUL}, {@code IDEMPOTENCY_CONFLICT}
         *     (409). The public method may also refuse with {@code INVALID_RANGE}.
         */
        record Refused(String code) implements VerificationAdmission {}
    }

    /**
     * Admits the user's precision verification or continuation (Story 2.12), in one short transaction without any
     * provider call, judging in order: the account's write access, the same key replayed, the active plan and whether
     * {@code reference} is still its pointer (the user's choice token), an attempt in progress, unavailability, the
     * kind's own condition (a precision verification needs no long gap and must still be useful; a continuation needs
     * the long gap), the 30-second cooldown, then the plan of ranges is fixed with the attempt.
     */
    VerificationAdmission admitPlanVerification(@Nullable UUID userId, @Nullable VerificationKind kind,
            @Nullable EvidenceReference reference, @Nullable String idempotencyKey);

    /**
     * Admits a system verification of the current official version (Stories 2.13, 2.14 and Epic 3): plan-end
     * preparation, an unrecognized event, a suspected duplicate, or an operator's investigation of explicit ranges
     * (1 to 10, inside {@code (t0, verified]}; the other reasons take none). No reference, usefulness or cooldown is
     * judged: an operator must be able to verify the same base again once the cause is fixed.
     */
    VerificationAdmission admitSystemVerification(@Nullable UUID userId, VerificationReason reason,
            List<InvestigationRange> ranges, @Nullable String idempotencyKey);

    // ------------------------------------------------------------------ Story 2.16

    /**
     * The connection revocation's first lock: the user's plan write lock, the one every publisher takes first
     * ({@link PlanPublicationPort#lockPlanWrites}). <b>Caller's transaction only.</b> The caller then takes the account
     * lifecycle lock and the connection row; {@link #endConnectionWork} takes the attempt rows last — the publishers'
     * order, so a publication either committed before the revocation or waits for it and then reads the connection
     * revoked.
     */
    void lockConnectionRevocation(UUID userId);

    /** How many attempts of each table a revocation ended. */
    record EndedConnectionWork(int firstSnapshots, int planSyncs, int collections, int classifications,
            int conversions, int publications) {
        public int total() {
            return firstSnapshots + planSyncs + collections + classifications + conversions + publications;
        }
    }

    /**
     * Ends every attempt of the connection still queued or running, with each table's own refusal ending: a first
     * snapshot, a plan sync and a first publication {@code BLOCKED}/{@code NOT_PERMITTED}, a collection, a
     * classification and a conversion {@code CANCELLED}/{@code NOT_PERMITTED}; the stage rows of an ended plan sync go
     * with it whatever connection they name. Every ending time is {@code revokedAt}, never before the row's own
     * admission. The lease goes with it, so no holder can
     * heartbeat, store a page or publish any more: every holder's next fenced statement finds the row not running.
     * <b>Caller's transaction only</b>, after {@link #lockConnectionRevocation} and the account lifecycle lock. Plan
     * syncs end before their stage rows — a stage's own lock order, attempt before child.
     */
    EndedConnectionWork endConnectionWork(UUID userId, UUID connectionId, Instant revokedAt);

    /** portfolio 삭제 뒤, source 삭제 앞에 호출한다. 발행 결과·시도를 먼저 지운다. */
    void eraseSyncData(@Nullable UUID userId);

    // ------------------------------------------------------------------ Story 3.10 복원 검증

    /**
     * 공식 포인터 불변식을 어긴 사용자 수. 그 사용자들 가운데 활성 계획이 있는 사용자마다, 대시보드 묶음과 같은 술어로 본다 — 계획의 공식
     * 포인터가 있고, 그 포인터의 계산 버전이 그 계획·portfolio·조건에 속하며({@code PlanPublicationService.belongs}), portfolio 현재
     * 포인터가 그 버전의 snapshot을 가리킨다(AD-23). 읽기 전용이다. 복원된 DB에서 서비스를 열기 전에 0이어야 한다.
     */
    long officialBundleViolations(java.util.Collection<UUID> userIds);

    /**
     * 그 사용자에게 아직 귀속된 이 모듈의 행 수. {@code sync_*} 표의 {@code user_id} 열을 카탈로그로 찾아 센다(공유 공개 증거
     * {@code sync_market_*}에는 사용자 열이 없다). 삭제가 끝난 계정이면 0이어야 한다.
     */
    long remainingRowsOf(UUID userId);
}
