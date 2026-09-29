-- Story 2.11: the active plan's incremental sync at the user's moment and the next official version
-- (ERD-1.0.0 §2.2, SYNC-UX-1.0, AD-23).
--
-- Everything here belongs to `sync`. No foreign key crosses module ownership: the plan, portfolio,
-- membership, connection and provider account are provenance columns, as in V10. V1~V10 are not
-- modified — this file only expands them and backfills the one official version each plan has so far.
--
-- Erasure stays explicit and ordered: the publication rows go first (pointer, history, calculation
-- versions, which cite the stage evidence with ON DELETE RESTRICT), then the sync jobs (their stage
-- rows follow by ON DELETE CASCADE), then the stage erasures of Stories 2.4~2.6.

-- the sync job ---------------------------------------------------------------------------------------

-- One incremental sync of one active plan, admitted by one of three user actions. Its basis is the
-- official version current at admission (the calculation, the pointer version and the two verification
-- times that version owns). The worker records the stage it reached, the fixed Bybit reference time,
-- the collection mode and range, and the stage rows and results it produced. The ids of those rows are
-- provenance only: the stage rows point back here, never the other way round.
CREATE TABLE sync_plan_sync_job (
    job_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    plan_id uuid NOT NULL,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    trigger text NOT NULL CHECK (trigger IN ('DASHBOARD_ENTRY','TAB_REACTIVATION','MANUAL')),
    status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','PUBLISHED','REVIEW_REQUIRED','FAILED','BLOCKED')),
    stage text NOT NULL CHECK (stage IN ('ADMITTED','REFERENCE_TIME','COLLECTION','CLASSIFICATION','CONVERSION',
        'CALCULATION','PUBLICATION')),
    failure_code text,
    retryable boolean NOT NULL DEFAULT false,
    basis_calculation_id uuid NOT NULL,
    basis_pointer_version bigint NOT NULL CHECK (basis_pointer_version>0),
    basis_verified_through timestamptz NOT NULL,
    basis_extended_at timestamptz NOT NULL,
    mode text CHECK (mode IN ('NORMAL','EXTENDED')),
    tc timestamptz,
    range_start timestamptz,
    collection_job_id uuid,
    candidate_id uuid,
    classification_job_id uuid,
    classification_id uuid,
    conversion_job_id uuid,
    conversion_id uuid,
    calculation_id uuid,
    published_pointer_version bigint,
    window_count integer CHECK (window_count IS NULL OR window_count>=0),
    page_count integer CHECK (page_count IS NULL OR page_count>=0),
    flow_count integer CHECK (flow_count IS NULL OR flow_count>=0),
    source_policy_version text,
    classification_policy_version text,
    price_policy_version text,
    calculation_policy text,
    fencing_generation bigint NOT NULL DEFAULT 0 CHECK (fencing_generation>=0),
    lease_until timestamptz,
    heartbeat_at timestamptz,
    -- When the running lease was claimed. A heartbeat stops renewing a lease held longer than the
    -- worker's run limit, so a stalled attempt loses it and ends as interrupted.
    claimed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',statement_timestamp()),
    last_checked_at timestamptz,
    finished_at timestamptz,
    CHECK (failure_code IS NULL OR failure_code IN (
        'NOT_PERMITTED','PLAN_CHANGED','MEMBERSHIP_CHANGED','SOURCE_CHANGED','POINTER_CONFLICT',
        'LONG_GAP_CHOICE_REQUIRED','PLAN_PERIOD_ENDED',
        'COLLECTION_REVIEW_REQUIRED','CLASSIFICATION_REVIEW_REQUIRED','CONVERSION_REQUIRED',
        'CONVERSION_REVIEW_REQUIRED','APPLIED_FLOW_CHANGED',
        'REFERENCE_TIME_UNAVAILABLE','REFERENCE_TIME_INVALID','SOURCE_UNAVAILABLE','CONVERSION_PENDING',
        'SYNC_INTERRUPTED','SOURCE_REJECTED','CALCULATION_RANGE_EXCEEDED','CALCULATION_INPUT_INVALID',
        'CALCULATION_POLICY_UNVERIFIED')),
    -- The mode and the range are fixed together with tc, once.
    CHECK ((tc IS NULL) = (mode IS NULL) AND (tc IS NULL) = (range_start IS NULL)),
    CHECK (range_start IS NULL OR range_start<tc),
    -- Lifecycle consistency. Queued: admitted only, no lease, nothing fixed. Running: a lease. Finished:
    -- a finish time and no lease. Only a publication has a calculation and a pointer version; every other
    -- ending has a closed reason from its own set and neither.
    CHECK (status<>'QUEUED' OR (lease_until IS NULL AND claimed_at IS NULL AND stage='ADMITTED' AND tc IS NULL)),
    CHECK (status<>'RUNNING' OR (lease_until IS NOT NULL AND claimed_at IS NOT NULL)),
    CHECK (status NOT IN ('QUEUED','RUNNING')
        OR (finished_at IS NULL AND failure_code IS NULL AND NOT retryable AND calculation_id IS NULL
            AND published_pointer_version IS NULL)),
    CHECK (status IN ('QUEUED','RUNNING') OR (finished_at IS NOT NULL AND lease_until IS NULL)),
    CHECK (status<>'PUBLISHED' OR (failure_code IS NULL AND NOT retryable AND calculation_id IS NOT NULL
        AND published_pointer_version=basis_pointer_version+1 AND tc IS NOT NULL)),
    CHECK (status NOT IN ('REVIEW_REQUIRED','FAILED','BLOCKED')
        OR (failure_code IS NOT NULL AND calculation_id IS NULL AND published_pointer_version IS NULL)),
    CHECK (status<>'BLOCKED' OR failure_code IN ('NOT_PERMITTED','PLAN_CHANGED','MEMBERSHIP_CHANGED',
        'SOURCE_CHANGED','POINTER_CONFLICT','LONG_GAP_CHOICE_REQUIRED','PLAN_PERIOD_ENDED')),
    CHECK (status<>'REVIEW_REQUIRED' OR failure_code IN ('COLLECTION_REVIEW_REQUIRED',
        'CLASSIFICATION_REVIEW_REQUIRED','CONVERSION_REQUIRED','CONVERSION_REVIEW_REQUIRED','APPLIED_FLOW_CHANGED')),
    CHECK (status<>'FAILED' OR failure_code IN ('REFERENCE_TIME_UNAVAILABLE','REFERENCE_TIME_INVALID',
        'SOURCE_UNAVAILABLE','CONVERSION_PENDING','SYNC_INTERRUPTED','SOURCE_REJECTED',
        'CALCULATION_RANGE_EXCEEDED','CALCULATION_INPUT_INVALID','CALCULATION_POLICY_UNVERIFIED')),
    -- Whether a new attempt can help is decided by the closed table, never per attempt.
    CHECK (retryable = (failure_code IS NOT NULL AND failure_code IN ('POINTER_CONFLICT',
        'REFERENCE_TIME_UNAVAILABLE','REFERENCE_TIME_INVALID','SOURCE_UNAVAILABLE','CONVERSION_PENDING',
        'SYNC_INTERRUPTED')))
);
-- The authority for "one sync in progress per active plan": a racing second admission inserts nothing
-- and answers with the winner's reference.
CREATE UNIQUE INDEX sync_plan_sync_one_active_plan_idx
    ON sync_plan_sync_job(plan_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX sync_plan_sync_queue_idx ON sync_plan_sync_job(created_at) WHERE status='QUEUED';
CREATE INDEX sync_plan_sync_lease_idx ON sync_plan_sync_job(lease_until) WHERE status='RUNNING';
CREATE INDEX sync_plan_sync_user_idx ON sync_plan_sync_job(user_id,plan_id,created_at DESC);

-- the stage rows -------------------------------------------------------------------------------------

-- The sync job writes its stage rows through the Story 2.4~2.6 repositories unchanged. They carry the
-- plan's intent as their lineage key and belong to the job: the stage dispatchers never claim them, the
-- job's heartbeat renews their lease, and an erased job takes them with it.
ALTER TABLE sync_initial_collection_job ADD COLUMN plan_sync_job_id uuid
    REFERENCES sync_plan_sync_job(job_id) ON DELETE CASCADE;
CREATE INDEX sync_initial_collection_plan_sync_idx
    ON sync_initial_collection_job(plan_sync_job_id) WHERE plan_sync_job_id IS NOT NULL;
ALTER TABLE sync_cashflow_classification_job ADD COLUMN plan_sync_job_id uuid
    REFERENCES sync_plan_sync_job(job_id) ON DELETE CASCADE;
CREATE INDEX sync_cashflow_classification_plan_sync_idx
    ON sync_cashflow_classification_job(plan_sync_job_id) WHERE plan_sync_job_id IS NOT NULL;
ALTER TABLE sync_cashflow_conversion_job ADD COLUMN plan_sync_job_id uuid
    REFERENCES sync_plan_sync_job(job_id) ON DELETE CASCADE;
CREATE INDEX sync_cashflow_conversion_plan_sync_idx
    ON sync_cashflow_conversion_job(plan_sync_job_id) WHERE plan_sync_job_id IS NOT NULL;

-- the calculation version ----------------------------------------------------------------------------

-- A version now records how its cash flows were verified: the first calculation collected (t0, tc]
-- whole (INITIAL); an incremental one collected (collection_range_start, tc] and carried the earlier
-- version's flows at or before that start. The two verification times of the plan are this version's
-- own columns — cashflow_verified_through (= basis_at, V10) and last_extended_verification_at — so one
-- pointer CAS guards both. The first version's extended verification is its tc: it verified from t0.
ALTER TABLE sync_plan_calculation ADD COLUMN collection_mode text;
ALTER TABLE sync_plan_calculation ADD COLUMN collection_range_start timestamptz;
ALTER TABLE sync_plan_calculation ADD COLUMN last_extended_verification_at timestamptz;
ALTER TABLE sync_plan_calculation ADD COLUMN previous_calculation_id uuid;
UPDATE sync_plan_calculation
    SET collection_mode='INITIAL', collection_range_start=t0, last_extended_verification_at=basis_at;
ALTER TABLE sync_plan_calculation ALTER COLUMN collection_mode SET NOT NULL;
ALTER TABLE sync_plan_calculation ALTER COLUMN collection_range_start SET NOT NULL;
ALTER TABLE sync_plan_calculation ALTER COLUMN last_extended_verification_at SET NOT NULL;
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_mode_check
    CHECK (collection_mode IN ('INITIAL','NORMAL','EXTENDED'));
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_previous_check
    CHECK ((collection_mode='INITIAL') = (previous_calculation_id IS NULL));
-- The first calculation collected from t0, and that collection was its extended verification.
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_initial_check
    CHECK (collection_mode<>'INITIAL' OR (collection_range_start=t0 AND last_extended_verification_at=basis_at));
-- A version never continues itself.
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_not_self_check
    CHECK (previous_calculation_id IS NULL OR previous_calculation_id<>calculation_id);
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_range_check
    CHECK (collection_mode='INITIAL' OR (collection_range_start>=t0 AND collection_range_start<basis_at
        AND last_extended_verification_at<=basis_at));
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_extended_check
    CHECK (collection_mode<>'EXTENDED' OR last_extended_verification_at=basis_at);
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_normal_check
    CHECK (collection_mode<>'NORMAL' OR last_extended_verification_at<basis_at);
-- The earlier version of the same plan. NO ACTION (not RESTRICT): the constraint is checked at the end of
-- the statement, so the erasure's one DELETE of a user's versions removes a whole chain. The versions of a
-- plan are one line: no version is continued twice.
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_previous_fk
    FOREIGN KEY(previous_calculation_id,plan_id) REFERENCES sync_plan_calculation(calculation_id,plan_id);
CREATE UNIQUE INDEX sync_plan_calculation_previous_idx ON sync_plan_calculation(previous_calculation_id);

-- The source events a version excluded over its whole verified range, like the applied flows (V10): an
-- exclusion is not a row of the history, so each version keeps its own list. A carried exclusion cites
-- the classification that made it.
CREATE TABLE sync_plan_calculation_exclusion (
    calculation_id uuid NOT NULL REFERENCES sync_plan_calculation(calculation_id) ON DELETE CASCADE,
    classification_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY(calculation_id,source_event_id),
    FOREIGN KEY(classification_id,source_event_id)
        REFERENCES sync_cashflow_classification_event(classification_id,source_event_id) ON DELETE RESTRICT
);
CREATE INDEX sync_plan_calculation_exclusion_event_idx
    ON sync_plan_calculation_exclusion(classification_id,source_event_id);
INSERT INTO sync_plan_calculation_exclusion(calculation_id,classification_id,source_event_id,occurred_at)
    SELECT c.calculation_id,e.classification_id,e.source_event_id,e.occurred_at
    FROM sync_plan_calculation c
    JOIN sync_cashflow_classification_event e ON e.classification_id=c.classification_id AND e.verdict='EXCLUDED';

-- the official pointer's history ---------------------------------------------------------------------

-- Every official version of a plan: its pointer version, its calculation, when it became official and
-- when the next one replaced it. The pointer (V10) stays the only way to the current result; this is
-- its record, written in the same transaction as every pointer move — the first one included.
CREATE TABLE sync_official_plan_result_history (
    plan_id uuid NOT NULL,
    pointer_version bigint NOT NULL CHECK (pointer_version>0),
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    calculation_id uuid NOT NULL UNIQUE,
    published_at timestamptz NOT NULL,
    superseded_at timestamptz,
    PRIMARY KEY(plan_id,pointer_version),
    FOREIGN KEY(calculation_id,plan_id) REFERENCES sync_plan_calculation(calculation_id,plan_id) ON DELETE RESTRICT,
    FOREIGN KEY(calculation_id,user_id) REFERENCES sync_plan_calculation(calculation_id,user_id) ON DELETE RESTRICT,
    CHECK (superseded_at IS NULL OR superseded_at>=published_at)
);
CREATE UNIQUE INDEX sync_official_plan_result_history_current_idx
    ON sync_official_plan_result_history(plan_id) WHERE superseded_at IS NULL;
CREATE INDEX sync_official_plan_result_history_user_idx ON sync_official_plan_result_history(user_id);
INSERT INTO sync_official_plan_result_history(plan_id,pointer_version,user_id,calculation_id,published_at)
    SELECT plan_id,pointer_version,user_id,calculation_id,published_at FROM sync_official_plan_result;
