-- Story 2.7: first plan calculation and the atomic publication of the active plan with its official
-- result (CALC-1.0, CALENDAR-SEOUL-1.0, AD-23).
--
-- Ownership follows the modules. `plan_definition` and the intent consumption columns belong to
-- `plan`. The publication attempt, the calculation version with its metrics and cash-flow history,
-- and the per-plan official pointer belong to `sync`. The portfolio snapshot and its pointer stay in
-- `portfolio` (V5). No foreign key crosses module ownership; inside a module, composite keys prove in
-- SQL that owner and provenance agree. V1~V9 are not modified — this file only expands.
--
-- The name `active_plan` is deliberately unused: earlier stories assert that no such table exists,
-- and the active state is a status of an immutable plan row, not a table of its own.
--
-- Erasure is explicit and ordered (portfolio -> sync -> plan -> source -> identity). User rows point
-- at `identity_account` with ON DELETE RESTRICT; inside `sync` the official pointer and the
-- calculation version point at their evidence with ON DELETE RESTRICT, so evidence that an official
-- result cites cannot silently disappear, and `eraseSyncData` removes the publication rows first.

-- plan ----------------------------------------------------------------------------------------------

CREATE TABLE plan_definition (
    plan_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL UNIQUE,
    basis_portfolio_snapshot_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    basis_source_snapshot_id uuid NOT NULL,
    p0_decimal text NOT NULL CHECK (p0_decimal ~ '^[0-9]+(\.[0-9]+)?$' AND p0_decimal ~ '[1-9]'),
    t0 timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    period text NOT NULL CHECK (period IN ('DAILY','WEEKLY','MONTHLY')),
    target_rate text NOT NULL CHECK (target_rate ~ '^(0|[1-9][0-9]*)(\.[0-9]+)?$'),
    source_policy_version text NOT NULL,
    portfolio_policy_version text NOT NULL,
    calendar_policy text NOT NULL CHECK (calendar_policy='CALENDAR-SEOUL-1.0'),
    calculation_policy text NOT NULL CHECK (calculation_policy='CALC-1.0'),
    -- Only ACTIVE exists until plan ending (Story 2.13) and completion (Story 2.14) widen the set.
    status text NOT NULL CHECK (status IN ('ACTIVE')),
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    UNIQUE(plan_id,user_id),
    CHECK (end_at>t0),
    FOREIGN KEY(intent_id,user_id) REFERENCES plan_condition_intent(intent_id,user_id) ON DELETE RESTRICT
);
-- The authority for "one active plan per portfolio". The application checks first for a friendly
-- answer, but a racing second insert fails here and rolls its whole publication back.
CREATE UNIQUE INDEX plan_definition_one_active_per_portfolio
    ON plan_definition(portfolio_id) WHERE status='ACTIVE';
CREATE INDEX plan_definition_user_idx ON plan_definition(user_id,created_at DESC);

-- An intent is consumed exactly once, by the plan it became. The reference back to the plan is
-- deferred because the plan row itself references the intent (the two are written in one
-- transaction, and erasure removes both in one transaction).
ALTER TABLE plan_condition_intent ADD COLUMN consumed_at timestamptz;
ALTER TABLE plan_condition_intent ADD COLUMN consumed_by_plan_id uuid;
ALTER TABLE plan_condition_intent ADD CONSTRAINT plan_condition_intent_consumption_check
    CHECK ((status='CONSUMED') = (consumed_at IS NOT NULL)
        AND (consumed_at IS NULL) = (consumed_by_plan_id IS NULL));
ALTER TABLE plan_condition_intent ADD CONSTRAINT plan_condition_intent_consumed_by_fk
    FOREIGN KEY(consumed_by_plan_id,user_id) REFERENCES plan_definition(plan_id,user_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX plan_condition_intent_consumed_by_key
    ON plan_condition_intent(consumed_by_plan_id) WHERE consumed_by_plan_id IS NOT NULL;

-- sync: the tc source snapshot ---------------------------------------------------------------------

-- A source snapshot now has one of two origins: the first-snapshot job (Story 2.2) or the complete
-- initial candidate whose totalEquity is E(tc) (Story 2.4). The composite key lets the new reference
-- prove the candidate's owner, connection and provider account in SQL.
ALTER TABLE sync_initial_candidate_bundle ADD CONSTRAINT sync_initial_candidate_owner_key
    UNIQUE(candidate_id,user_id,connection_id,provider_account_id);
ALTER TABLE sync_source_snapshot ALTER COLUMN job_id DROP NOT NULL;
ALTER TABLE sync_source_snapshot ADD COLUMN initial_candidate_id uuid UNIQUE;
ALTER TABLE sync_source_snapshot ADD CONSTRAINT sync_source_snapshot_candidate_fk
    FOREIGN KEY(initial_candidate_id,user_id,connection_id,provider_account_id)
    REFERENCES sync_initial_candidate_bundle(candidate_id,user_id,connection_id,provider_account_id)
    ON DELETE RESTRICT;
ALTER TABLE sync_source_snapshot ADD CONSTRAINT sync_source_snapshot_one_origin
    CHECK ((job_id IS NULL) <> (initial_candidate_id IS NULL));

-- sync: the publication attempt --------------------------------------------------------------------

-- One attempt of the first publication. Its inputs are pinned at admission (the intent's terms, the
-- candidate, classification and calculable conversion of one lineage, E(tc) and the portfolio pointer
-- the publication expects to advance) and re-verified against `input_scope_hash` at claim and inside
-- the publishing transaction.
CREATE TABLE sync_plan_publication_job (
    job_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    basis_portfolio_snapshot_id uuid NOT NULL,
    basis_source_snapshot_id uuid NOT NULL,
    expected_portfolio_snapshot_id uuid NOT NULL,
    expected_portfolio_sequence bigint NOT NULL CHECK (expected_portfolio_sequence>0),
    candidate_id uuid NOT NULL REFERENCES sync_initial_candidate_bundle(candidate_id) ON DELETE CASCADE,
    classification_id uuid NOT NULL
        REFERENCES sync_cashflow_classification_bundle(classification_id) ON DELETE CASCADE,
    conversion_id uuid NOT NULL REFERENCES sync_cashflow_conversion_bundle(conversion_id) ON DELETE CASCADE,
    p0_decimal text NOT NULL,
    t0 timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    period text NOT NULL CHECK (period IN ('DAILY','WEEKLY','MONTHLY')),
    target_rate text NOT NULL,
    tc timestamptz NOT NULL,
    current_value_decimal text NOT NULL,
    current_value_measured_at timestamptz NOT NULL,
    flow_count integer NOT NULL CHECK (flow_count>=0),
    input_scope_hash text NOT NULL CHECK (input_scope_hash ~ '^[0-9a-f]{64}$'),
    calculation_policy text NOT NULL,
    calendar_policy text NOT NULL,
    idempotency_key text NOT NULL,
    status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','PUBLISHED','BLOCKED','FAILED')),
    failure_code text,
    plan_id uuid,
    calculation_id uuid,
    fencing_generation bigint NOT NULL DEFAULT 0 CHECK (fencing_generation>=0),
    lease_until timestamptz,
    heartbeat_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    last_checked_at timestamptz,
    finished_at timestamptz,
    -- The key is per user, not per intent: the same key with another intent is a conflict (409).
    UNIQUE(user_id,idempotency_key),
    CHECK (t0<tc AND tc<end_at),
    CHECK (failure_code IS NULL OR failure_code IN ('NOT_PERMITTED','ACTIVE_PLAN_EXISTS',
        'PLAN_CONDITIONS_CHANGED','INPUTS_CHANGED','PLAN_PERIOD_ENDED','MEMBERSHIP_CHANGED',
        'POINTER_CONFLICT','CALCULATION_POLICY_UNVERIFIED','CALCULATION_RANGE_EXCEEDED',
        'CALCULATION_INPUT_INVALID','PUBLICATION_INTERRUPTED')),
    -- Lifecycle consistency. In progress: no result, no failure, no finish time, and a running
    -- attempt holds a lease. Finished: a finish time and no lease. Only a publication has a plan and
    -- a calculation; every other ending has a closed reason and neither.
    CHECK (status NOT IN ('QUEUED','RUNNING')
        OR (finished_at IS NULL AND failure_code IS NULL AND plan_id IS NULL AND calculation_id IS NULL)),
    CHECK (status<>'QUEUED' OR lease_until IS NULL),
    CHECK (status<>'RUNNING' OR lease_until IS NOT NULL),
    CHECK (status IN ('QUEUED','RUNNING') OR (finished_at IS NOT NULL AND lease_until IS NULL)),
    CHECK (status<>'PUBLISHED' OR (plan_id IS NOT NULL AND calculation_id IS NOT NULL AND failure_code IS NULL)),
    CHECK (status NOT IN ('BLOCKED','FAILED')
        OR (failure_code IS NOT NULL AND plan_id IS NULL AND calculation_id IS NULL)),
    CHECK (status<>'FAILED' OR failure_code IN ('CALCULATION_POLICY_UNVERIFIED','CALCULATION_RANGE_EXCEEDED',
        'CALCULATION_INPUT_INVALID','PUBLICATION_INTERRUPTED')),
    CHECK (status<>'BLOCKED' OR failure_code IN ('NOT_PERMITTED','ACTIVE_PLAN_EXISTS',
        'PLAN_CONDITIONS_CHANGED','INPUTS_CHANGED','PLAN_PERIOD_ENDED','MEMBERSHIP_CHANGED','POINTER_CONFLICT'))
);
-- Two tabs converge: an intent has at most one attempt in progress.
CREATE UNIQUE INDEX sync_plan_publication_one_active_idx
    ON sync_plan_publication_job(user_id,intent_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX sync_plan_publication_queue_idx
    ON sync_plan_publication_job(created_at) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX sync_plan_publication_user_idx
    ON sync_plan_publication_job(user_id,intent_id,created_at DESC);
CREATE INDEX sync_plan_publication_candidate_idx ON sync_plan_publication_job(candidate_id);
CREATE INDEX sync_plan_publication_classification_idx ON sync_plan_publication_job(classification_id);
CREATE INDEX sync_plan_publication_conversion_idx ON sync_plan_publication_job(conversion_id);

-- sync: the calculation version --------------------------------------------------------------------

-- An immutable calculation version: every input and provenance the result needs to be reproduced
-- (both portfolio snapshots, the tc source snapshot, the provider namespace and account, the
-- candidate, classification and conversion of one lineage, the plan terms), the policies and the
-- implementation version, and the canonical input and result hashes.
CREATE TABLE sync_plan_calculation (
    calculation_id uuid PRIMARY KEY,
    created_by_job_id uuid NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    plan_id uuid NOT NULL,
    portfolio_id uuid NOT NULL,
    provider text NOT NULL CHECK (provider='BYBIT'),
    provider_account_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    basis_portfolio_snapshot_id uuid NOT NULL,
    basis_source_snapshot_id uuid NOT NULL,
    portfolio_snapshot_id uuid NOT NULL,
    portfolio_publication_sequence bigint NOT NULL CHECK (portfolio_publication_sequence>0),
    source_snapshot_id uuid NOT NULL REFERENCES sync_source_snapshot(source_snapshot_id) ON DELETE RESTRICT,
    candidate_id uuid NOT NULL REFERENCES sync_initial_candidate_bundle(candidate_id) ON DELETE RESTRICT,
    classification_id uuid NOT NULL
        REFERENCES sync_cashflow_classification_bundle(classification_id) ON DELETE RESTRICT,
    conversion_id uuid NOT NULL REFERENCES sync_cashflow_conversion_bundle(conversion_id) ON DELETE RESTRICT,
    classification_policy_version text NOT NULL,
    price_policy_version text NOT NULL,
    source_policy_version text NOT NULL,
    calculation_policy text NOT NULL CHECK (calculation_policy='CALC-1.0'),
    calendar_policy text NOT NULL CHECK (calendar_policy='CALENDAR-SEOUL-1.0'),
    display_policy text NOT NULL CHECK (display_policy='DISPLAY-1.0'),
    precision_policy text NOT NULL CHECK (precision_policy='DECIMAL128-HALF_EVEN'),
    time_zone text NOT NULL CHECK (time_zone='Asia/Seoul'),
    implementation_version text NOT NULL,
    p0_decimal text NOT NULL,
    t0 timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    period text NOT NULL CHECK (period IN ('DAILY','WEEKLY','MONTHLY')),
    target_rate text NOT NULL,
    basis_at timestamptz NOT NULL,
    current_value_measured_at timestamptz NOT NULL,
    cashflow_verified_through timestamptz NOT NULL,
    adjusted_principal text NOT NULL CHECK (adjusted_principal ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    periods_elapsed text NOT NULL CHECK (periods_elapsed ~ '^(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    periods_total text NOT NULL CHECK (periods_total ~ '^(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    periods_remaining text NOT NULL CHECK (periods_remaining ~ '^(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    flow_count integer NOT NULL CHECK (flow_count>=0),
    estimated_flow_count integer NOT NULL CHECK (estimated_flow_count>=0),
    estimated boolean NOT NULL,
    input_reliability text NOT NULL CHECK (input_reliability IN ('CONFIRMED','REVIEW_REQUIRED')),
    input_hash text NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    result_hash text NOT NULL CHECK (result_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    UNIQUE(calculation_id,user_id),
    UNIQUE(calculation_id,plan_id),
    -- The initial calculation's cash flows are verified through tc itself.
    CHECK (cashflow_verified_through=basis_at),
    CHECK (t0<basis_at AND basis_at<end_at),
    CHECK (estimated_flow_count<=flow_count),
    CHECK (estimated=(estimated_flow_count>0))
);
CREATE INDEX sync_plan_calculation_user_idx ON sync_plan_calculation(user_id,created_at DESC);
CREATE INDEX sync_plan_calculation_source_idx ON sync_plan_calculation(source_snapshot_id);
CREATE INDEX sync_plan_calculation_candidate_idx ON sync_plan_calculation(candidate_id);
CREATE INDEX sync_plan_calculation_classification_idx ON sync_plan_calculation(classification_id);
CREATE INDEX sync_plan_calculation_conversion_idx ON sync_plan_calculation(conversion_id);

ALTER TABLE sync_plan_publication_job
    ADD CONSTRAINT sync_plan_publication_job_result_fk
    FOREIGN KEY(calculation_id) REFERENCES sync_plan_calculation(calculation_id);
CREATE INDEX sync_plan_publication_result_idx ON sync_plan_publication_job(calculation_id);

-- The six metrics of one calculation. An undefined metric has no value and a closed reason, never a
-- zero; the nature (official or estimated) is kept either way — "not computable" does not overwrite it.
CREATE TABLE sync_plan_calculation_metric (
    calculation_id uuid NOT NULL REFERENCES sync_plan_calculation(calculation_id) ON DELETE CASCADE,
    metric text NOT NULL CHECK (metric IN ('CURRENT_VALUE','PLAN_AMOUNT','PLAN_DIFFERENCE',
        'ADJUSTED_TARGET','REQUIRED_RATE','GOAL_PROGRESS')),
    value_decimal text CHECK (value_decimal IS NULL
        OR value_decimal ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    nature text NOT NULL CHECK (nature IN ('OFFICIAL','ESTIMATED')),
    unavailable_reason text CHECK (unavailable_reason IS NULL OR unavailable_reason IN
        ('CURRENT_VALUE_NOT_POSITIVE','TARGET_NOT_POSITIVE','GOAL_PROFIT_NOT_POSITIVE',
         'REMAINING_PERIOD_NOT_POSITIVE')),
    PRIMARY KEY(calculation_id,metric),
    CHECK ((value_decimal IS NULL) = (unavailable_reason IS NOT NULL)),
    CHECK (metric<>'CURRENT_VALUE' OR (nature='OFFICIAL' AND value_decimal IS NOT NULL)),
    CHECK (unavailable_reason IS NULL OR metric IN ('REQUIRED_RATE','GOAL_PROGRESS'))
);

-- The external cash flows the calculation applied, in the order it applied them. Each row cites the
-- calculable row it came from (V9), so the history is linked to verified inputs, not copied freehand.
CREATE TABLE sync_plan_calculation_flow (
    calculation_id uuid NOT NULL REFERENCES sync_plan_calculation(calculation_id) ON DELETE CASCADE,
    sequence integer NOT NULL CHECK (sequence>0),
    conversion_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    source_id text NOT NULL,
    occurred_at timestamptz NOT NULL,
    usd_value text NOT NULL CHECK (usd_value ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    estimated boolean NOT NULL,
    evidence_type text NOT NULL CHECK (evidence_type IN ('DIRECT_NORMALIZATION','SPOT_KLINE_CLOSE')),
    periods_to_basis text NOT NULL CHECK (periods_to_basis ~ '^(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    periods_to_end text NOT NULL CHECK (periods_to_end ~ '^(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    PRIMARY KEY(calculation_id,source_event_id),
    UNIQUE(calculation_id,sequence),
    FOREIGN KEY(conversion_id,source_event_id)
        REFERENCES sync_calculable_cashflow(conversion_id,source_event_id) ON DELETE RESTRICT,
    CHECK (estimated=(evidence_type='SPOT_KLINE_CLOSE'))
);
CREATE INDEX sync_plan_calculation_flow_source_idx ON sync_plan_calculation_flow(conversion_id,source_event_id);

-- sync: the per-plan official pointer --------------------------------------------------------------

-- The only way to the official result of a plan. It moves by an expected-version CAS: the first
-- publication inserts version 1 against an absent row, and a later one (Story 2.11) updates only when
-- the version it read is still current. The current value is never inferred from max(created_at).
CREATE TABLE sync_official_plan_result (
    plan_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    calculation_id uuid NOT NULL UNIQUE,
    pointer_version bigint NOT NULL CHECK (pointer_version>0),
    published_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    FOREIGN KEY(calculation_id,plan_id) REFERENCES sync_plan_calculation(calculation_id,plan_id)
        ON DELETE RESTRICT,
    FOREIGN KEY(calculation_id,user_id) REFERENCES sync_plan_calculation(calculation_id,user_id)
        ON DELETE RESTRICT
);
CREATE INDEX sync_official_plan_result_user_idx ON sync_official_plan_result(user_id);
