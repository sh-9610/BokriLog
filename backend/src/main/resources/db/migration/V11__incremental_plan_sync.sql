-- Story 2.11: the user-triggered incremental sync of an active plan and the next official versions
-- (ERD-1.0.0 §2.2, SYNC-UX-1.0, AD-7, AD-8, AD-16, AD-23).
--
-- Expand only. V1~V10 are not modified: this file adds one table for the sync attempt, one append-only
-- table for the history of the official pointer, and nullable lineage columns to the stage tables the
-- attempt reuses. Every existing row keeps its meaning: a stage row without a sync attempt is a Story
-- 2.4~2.6 attempt, a candidate without a base is an initial one, a calculation without a predecessor is a
-- first publication.
--
-- Ownership stays with `sync`. The attempt carries the plan, portfolio, intent, membership and connection
-- as logical references the code re-verifies at every stage; the only foreign key that leaves the module
-- is the owner's, and erasure removes the attempt explicitly after its stage rows.

-- sync: the incremental sync attempt ---------------------------------------------------------------

-- One attempt runs the four stages in order under one lease. Its base is the official version it read at
-- admission; its tc, mode and window start are fixed once, from Bybit's time, before any signed call.
CREATE TABLE sync_plan_sync_job (
    job_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    plan_id uuid NOT NULL,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    trigger text NOT NULL CHECK (trigger IN ('ENTRY','VISIBILITY','MANUAL')),
    idempotency_key text NOT NULL,
    base_calculation_id uuid NOT NULL,
    base_pointer_version bigint NOT NULL CHECK (base_pointer_version>0),
    base_candidate_id uuid NOT NULL,
    base_verified_through timestamptz NOT NULL,
    base_extended_verification_at timestamptz NOT NULL,
    t0 timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    tc timestamptz,
    mode text CHECK (mode IN ('NORMAL','EXTENDED')),
    window_start timestamptz,
    stage text CHECK (stage IN ('TIME','COLLECTION','CLASSIFICATION','CONVERSION','CALCULATION','PUBLICATION')),
    status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','NEEDS_REVIEW','BLOCKED')),
    failure_code text,
    collection_job_id uuid,
    classification_job_id uuid,
    conversion_job_id uuid,
    calculation_id uuid,
    published_pointer_version bigint,
    fencing_generation bigint NOT NULL DEFAULT 0 CHECK (fencing_generation>=0),
    lease_until timestamptz,
    heartbeat_at timestamptz,
    admitted_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    finished_at timestamptz,
    -- The key is per user: the same key with another trigger is a conflict (409).
    UNIQUE(user_id,idempotency_key),
    CHECK (base_extended_verification_at<=base_verified_through),
    CHECK (t0<end_at),
    -- tc is fixed first; mode and window start follow together only when the attempt goes on to collect,
    -- and the window then starts inside [t0, base verification] with tc beyond it.
    CHECK ((mode IS NULL) = (window_start IS NULL)),
    CHECK (mode IS NULL OR tc IS NOT NULL),
    CHECK (window_start IS NULL OR (t0<=window_start AND window_start<=base_verified_through
        AND base_verified_through<tc)),
    CHECK (failure_code IS NULL OR failure_code IN ('PROVIDER_UNAVAILABLE','PROVIDER_TIME_UNTRUSTED',
        'SOURCE_REJECTED','CONVERSION_REQUIRED','CALCULATION_RANGE_EXCEEDED','CALCULATION_INPUT_INVALID',
        'CALCULATION_POLICY_UNVERIFIED','SYNC_INTERRUPTED','SYNC_FAILED','SOURCE_REQUIRES_REVIEW',
        'CLASSIFICATION_REQUIRES_REVIEW','CONVERSION_REQUIRES_REVIEW','NOT_PERMITTED','PLAN_CHANGED',
        'MEMBERSHIP_CHANGED','POINTER_CONFLICT','INPUTS_CHANGED','LONG_GAP','PLAN_PERIOD_ENDED')),
    -- Lifecycle consistency. In progress: no result, no failure, no finish time, and a running attempt
    -- holds a lease. Finished: a finish time and no lease. Only a success has a result, and it has one
    -- exactly; every other ending has a closed code of its own kind.
    CHECK (status NOT IN ('QUEUED','RUNNING') OR (finished_at IS NULL AND failure_code IS NULL
        AND calculation_id IS NULL AND published_pointer_version IS NULL)),
    CHECK (status<>'QUEUED' OR (lease_until IS NULL AND tc IS NULL AND stage IS NULL)),
    CHECK (status<>'RUNNING' OR lease_until IS NOT NULL),
    CHECK (status IN ('QUEUED','RUNNING') OR (finished_at IS NOT NULL AND lease_until IS NULL)),
    CHECK (status<>'SUCCEEDED' OR (failure_code IS NULL AND calculation_id IS NOT NULL
        AND published_pointer_version=base_pointer_version+1 AND mode IS NOT NULL
        AND collection_job_id IS NOT NULL AND classification_job_id IS NOT NULL
        AND conversion_job_id IS NOT NULL)),
    CHECK (status NOT IN ('FAILED','NEEDS_REVIEW','BLOCKED')
        OR (failure_code IS NOT NULL AND calculation_id IS NULL AND published_pointer_version IS NULL)),
    -- PROVIDER_TIME_UNTRUSTED: Bybit's time is more than 60 seconds from the database clock, or did not move past
    -- the last verification. The attempt stops before any signed call.
    CHECK (status<>'FAILED' OR failure_code IN ('PROVIDER_UNAVAILABLE','PROVIDER_TIME_UNTRUSTED','SOURCE_REJECTED',
        'CONVERSION_REQUIRED','CALCULATION_RANGE_EXCEEDED','CALCULATION_INPUT_INVALID',
        'CALCULATION_POLICY_UNVERIFIED','SYNC_INTERRUPTED','SYNC_FAILED')),
    CHECK (status<>'NEEDS_REVIEW' OR failure_code IN ('SOURCE_REQUIRES_REVIEW','CLASSIFICATION_REQUIRES_REVIEW',
        'CONVERSION_REQUIRES_REVIEW')),
    -- INPUTS_CHANGED: at publication, the inputs are not the ones this attempt's own stages produced.
    CHECK (status<>'BLOCKED' OR failure_code IN ('NOT_PERMITTED','PLAN_CHANGED','MEMBERSHIP_CHANGED',
        'POINTER_CONFLICT','INPUTS_CHANGED','LONG_GAP','PLAN_PERIOD_ENDED'))
);
-- The authority for "one sync in progress per user and plan": other tabs, repeated clicks and racing
-- admissions join the one attempt this index lets stand.
CREATE UNIQUE INDEX sync_plan_sync_one_active_idx
    ON sync_plan_sync_job(user_id,plan_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX sync_plan_sync_queue_idx
    ON sync_plan_sync_job(admitted_at) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX sync_plan_sync_user_idx ON sync_plan_sync_job(user_id,plan_id,admitted_at DESC);

-- The stage rows an attempt runs are the Story 2.4~2.6 rows themselves, so their foreign keys and the
-- lineage checks of Stories 2.9 and 2.10 hold unchanged. The attempt owns their lease: the independent
-- claims never pick a row that names an attempt.
ALTER TABLE sync_initial_collection_job ADD COLUMN plan_sync_job_id uuid
    REFERENCES sync_plan_sync_job(job_id) ON DELETE RESTRICT;
ALTER TABLE sync_initial_collection_job ADD COLUMN window_start timestamptz;
ALTER TABLE sync_initial_collection_job ADD COLUMN base_candidate_id uuid;
ALTER TABLE sync_initial_collection_job ADD CONSTRAINT sync_initial_collection_incremental_check
    CHECK ((plan_sync_job_id IS NULL) = (window_start IS NULL)
        AND (window_start IS NULL) = (base_candidate_id IS NULL)
        AND (window_start IS NULL OR (t0<=window_start AND (tc IS NULL OR window_start<tc))));
CREATE UNIQUE INDEX sync_initial_collection_plan_sync_key
    ON sync_initial_collection_job(plan_sync_job_id) WHERE plan_sync_job_id IS NOT NULL;

ALTER TABLE sync_cashflow_classification_job ADD COLUMN plan_sync_job_id uuid
    REFERENCES sync_plan_sync_job(job_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX sync_cashflow_classification_plan_sync_key
    ON sync_cashflow_classification_job(plan_sync_job_id) WHERE plan_sync_job_id IS NOT NULL;

ALTER TABLE sync_cashflow_conversion_job ADD COLUMN plan_sync_job_id uuid
    REFERENCES sync_plan_sync_job(job_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX sync_cashflow_conversion_plan_sync_key
    ON sync_cashflow_conversion_job(plan_sync_job_id) WHERE plan_sync_job_id IS NOT NULL;

-- An incremental candidate covers (t0, tc] as a whole: the events of its base candidate (the previous
-- official version's) carried over with a provenance marker, and the events it observed in its own
-- windows, which start at window_start. A candidate has both or neither.
ALTER TABLE sync_initial_candidate_bundle ADD COLUMN base_candidate_id uuid
    REFERENCES sync_initial_candidate_bundle(candidate_id);
ALTER TABLE sync_initial_candidate_bundle ADD COLUMN window_start timestamptz;
ALTER TABLE sync_initial_candidate_bundle ADD CONSTRAINT sync_initial_candidate_base_check
    CHECK ((base_candidate_id IS NULL) = (window_start IS NULL)
        AND (window_start IS NULL OR (t0<=window_start AND window_start<tc)));
CREATE INDEX sync_initial_candidate_base_idx ON sync_initial_candidate_bundle(base_candidate_id);

-- The marker of a carried event: this collection did not observe it again; the base candidate proved it.
ALTER TABLE sync_initial_event_provenance ADD COLUMN carried_from_candidate_id uuid
    REFERENCES sync_initial_candidate_bundle(candidate_id) ON DELETE CASCADE;
CREATE INDEX sync_initial_event_provenance_carried_idx
    ON sync_initial_event_provenance(carried_from_candidate_id);

-- sync: the calculation version's watermarks and predecessor ----------------------------------------

-- The two verification times are immutable columns of a version: cash flows are verified through tc
-- (V10's CHECK), and the last extended verification is the tc of the last version whose window reached
-- seven days back. A first publication verified all of (t0, tc], which counts as an extended one.
ALTER TABLE sync_plan_calculation ADD COLUMN last_extended_verification_at timestamptz;
UPDATE sync_plan_calculation SET last_extended_verification_at=cashflow_verified_through;
ALTER TABLE sync_plan_calculation ALTER COLUMN last_extended_verification_at SET NOT NULL;
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_extended_check
    CHECK (t0<=last_extended_verification_at AND last_extended_verification_at<=cashflow_verified_through);

-- A version continues the one it replaced. Never cascades: an official lineage is erased as a whole.
ALTER TABLE sync_plan_calculation ADD COLUMN previous_calculation_id uuid
    REFERENCES sync_plan_calculation(calculation_id);
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_previous_check
    CHECK (previous_calculation_id IS NULL OR previous_calculation_id<>calculation_id);
CREATE UNIQUE INDEX sync_plan_calculation_previous_key
    ON sync_plan_calculation(previous_calculation_id) WHERE previous_calculation_id IS NOT NULL;

-- sync: the history of the official pointer ----------------------------------------------------------

-- Append-only: one row per pointer version, written in the transaction that moved the pointer. A version
-- stops being current when the next one is published; its end is that row's publication time, so no row
-- is ever updated.
CREATE TABLE sync_official_plan_result_history (
    plan_id uuid NOT NULL,
    pointer_version bigint NOT NULL CHECK (pointer_version>0),
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    calculation_id uuid NOT NULL UNIQUE,
    published_at timestamptz NOT NULL,
    PRIMARY KEY(plan_id,pointer_version),
    FOREIGN KEY(calculation_id,plan_id) REFERENCES sync_plan_calculation(calculation_id,plan_id)
        ON DELETE RESTRICT,
    FOREIGN KEY(calculation_id,user_id) REFERENCES sync_plan_calculation(calculation_id,user_id)
        ON DELETE RESTRICT
);
CREATE INDEX sync_official_plan_result_history_user_idx ON sync_official_plan_result_history(user_id);
INSERT INTO sync_official_plan_result_history(plan_id,pointer_version,user_id,portfolio_id,calculation_id,
    published_at)
SELECT plan_id,pointer_version,user_id,portfolio_id,calculation_id,published_at FROM sync_official_plan_result;
