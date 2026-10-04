-- Story 2.14: the natural completion of a plan that reached its end T, its first post-end observation and its
-- completion result (ERD-1.0.0 §3 CALC-1.0 CV-11, §4 SYNC-UX-1.0, PRD §4 u(t)=min(t,T), AD-6, AD-8, AD-16, AD-23).
--
-- Expand only. V1~V13 are not modified. V10 left `plan_definition.status` at ACTIVE and said plan ending (2.13) and
-- completion (this story) widen it; V13 added ENDED_EARLY and said completion would not widen its `plan_end` row. This
-- file adds the second terminal status, COMPLETED, and keeps that promise: the completion is a fact of its own, in a
-- table of its own (`plan_completion`, owned by `plan`), paired with its plan by the same composite key idiom — a
-- completion record can only cite a COMPLETED plan, and a plan cannot leave COMPLETED while its record exists. Only
-- inline or unnamed checks whose vocabulary widens are replaced, by the names PostgreSQL generated for them (a DROP
-- without IF EXISTS fails the migration if a name is not what the earlier file made); every other check stays exactly
-- as it was, and a test compares the schemas' definitions.
--
-- The plan status (the plan's lifecycle) and the completion comparison (whether the completion result can be compared
-- with its target) are separate on purpose: a comparison that cannot be confirmed never turns the plan back.
--
-- The completion calculation is a new kind of the existing calculation version, so the evidence (2.9) and the
-- adjustment history (2.10) read it like any other. A progress version keeps V10's rule (verified through its basis,
-- which lies before T); a completion version is computed at u = T from the first observation after T, with its flows
-- verified through the cutoff U the completion attempt fixed after that observation: basis = T <= measured <= U.
--
-- No foreign key crosses module ownership: the completion record names the portfolio snapshot and the calculation by
-- value. Erasure removes the completion record before the plan (`PlanConditionRepository.erase`), and the observation's
-- source snapshot before the attempts (`PlanPublicationRepository.erase`).

-- plan: the status gains COMPLETED ------------------------------------------------------------------------------

ALTER TABLE plan_definition DROP CONSTRAINT plan_definition_status_check;
ALTER TABLE plan_definition ADD CONSTRAINT plan_definition_status_check
    CHECK (status IN ('ACTIVE','ENDED_EARLY','COMPLETED'));

-- plan: the completion record -----------------------------------------------------------------------------------

CREATE TABLE plan_completion (
    plan_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    -- The plan status this record pairs with. Always COMPLETED; the composite foreign key below requires it.
    status text NOT NULL DEFAULT 'COMPLETED' CHECK (status='COMPLETED'),
    -- The plan's end T: the completion time is the planned end, never the time the transition happened to run.
    completed_at timestamptz NOT NULL,
    -- The database statement time of the transition, to the millisecond (at or after T).
    transitioned_at timestamptz NOT NULL,
    -- PENDING: not judged yet. CONFIRMED: comparable. UNAVAILABLE: not computable, with its reason. HELD: held for
    -- input review; asking again reuses the first observation.
    comparison text NOT NULL CHECK (comparison IN ('PENDING','CONFIRMED','UNAVAILABLE','HELD')),
    reason text CHECK (reason IS NULL OR reason IN ('OBSERVATION_DELAY_EXCEEDED','POST_END_CASHFLOW',
        'HISTORY_OUT_OF_RANGE','DATA_GAP','INPUT_REVIEW_REQUIRED')),
    -- The first normal post-end observation, fixed once: the portfolio snapshot it was published as, its value, its
    -- measurement time, its delay after T in milliseconds and when it was fixed. Never replaced.
    observed_portfolio_snapshot_id uuid,
    observed_value text CHECK (observed_value IS NULL OR observed_value ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    observed_measured_at timestamptz,
    observation_delay_millis bigint CHECK (observation_delay_millis IS NULL OR observation_delay_millis>=0),
    observed_at timestamptz,
    -- The completion calculation version, when the inputs were complete (CONFIRMED, or not comparable for the delay or
    -- a post-end flow), and how many confirmed external flows lie in (T, measured].
    completion_calculation_id uuid,
    post_end_cashflow_count integer CHECK (post_end_cashflow_count IS NULL OR post_end_cashflow_count>=0),
    judged_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    CONSTRAINT plan_completion_plan_fk FOREIGN KEY(plan_id,user_id,status)
        REFERENCES plan_definition(plan_id,user_id,status) ON DELETE RESTRICT,
    CONSTRAINT plan_completion_transition_check CHECK (completed_at<=transitioned_at),
    -- The reason goes with its comparison. Every comparison is null-safe: a CHECK that evaluates to NULL passes.
    CONSTRAINT plan_completion_reason_pair_check CHECK (
        (comparison IN ('PENDING','CONFIRMED') AND reason IS NULL)
        OR (comparison='HELD' AND reason IS NOT DISTINCT FROM 'INPUT_REVIEW_REQUIRED')
        OR (comparison='UNAVAILABLE' AND reason IS NOT NULL AND reason IN ('OBSERVATION_DELAY_EXCEEDED',
            'POST_END_CASHFLOW','HISTORY_OUT_OF_RANGE','DATA_GAP'))),
    -- The observation is whole or absent, measured at or after T, and its delay is exactly the difference.
    CONSTRAINT plan_completion_observation_check CHECK (
        (observed_portfolio_snapshot_id IS NULL) = (observed_value IS NULL)
        AND (observed_value IS NULL) = (observed_measured_at IS NULL)
        AND (observed_measured_at IS NULL) = (observation_delay_millis IS NULL)
        AND (observation_delay_millis IS NULL) = (observed_at IS NULL)
        AND (observed_measured_at IS NULL OR (observed_measured_at>=completed_at
            AND observation_delay_millis=(extract(epoch FROM (observed_measured_at-completed_at))*1000)::bigint))),
    -- A completion calculation exists exactly for a judged comparison on complete inputs, after the observation.
    CONSTRAINT plan_completion_calculation_check CHECK (
        (completion_calculation_id IS NOT NULL)
            = (comparison='CONFIRMED'
                OR COALESCE(reason IN ('OBSERVATION_DELAY_EXCEEDED','POST_END_CASHFLOW'),false))
        AND (completion_calculation_id IS NULL OR observed_value IS NOT NULL)
        AND (completion_calculation_id IS NULL) = (post_end_cashflow_count IS NULL)
        AND (completion_calculation_id IS NULL) = (judged_at IS NULL)),
    -- The comparison's own order (CV-11): the delay first (exactly 24 hours is comparable), then a post-end flow.
    CONSTRAINT plan_completion_order_check CHECK (
        (comparison<>'CONFIRMED'
            OR COALESCE(observation_delay_millis<=86400000 AND post_end_cashflow_count=0,false))
        AND (reason IS DISTINCT FROM 'OBSERVATION_DELAY_EXCEEDED' OR COALESCE(observation_delay_millis>86400000,false))
        AND (reason IS DISTINCT FROM 'POST_END_CASHFLOW'
            OR COALESCE(observation_delay_millis<=86400000 AND post_end_cashflow_count>0,false)))
);
CREATE INDEX plan_completion_user_idx ON plan_completion(user_id);

-- sync: the completion calculation kind ------------------------------------------------------------------------

ALTER TABLE sync_plan_calculation ADD COLUMN calculation_kind text NOT NULL DEFAULT 'PROGRESS';
ALTER TABLE sync_plan_calculation ADD CONSTRAINT sync_plan_calculation_kind_check
    CHECK (calculation_kind IN ('PROGRESS','COMPLETION'));
-- V10's two unnamed basis checks ("verified through its basis", "t0 < basis < T") hold for a progress version only.
ALTER TABLE sync_plan_calculation
    DROP CONSTRAINT sync_plan_calculation_check,
    DROP CONSTRAINT sync_plan_calculation_check1;
ALTER TABLE sync_plan_calculation
    ADD CONSTRAINT sync_plan_calculation_progress_basis_check CHECK (calculation_kind<>'PROGRESS'
        OR (cashflow_verified_through=basis_at AND t0<basis_at AND basis_at<end_at)),
    -- A completion version: u = T, the observation at or after T, the flows verified through U at or after it, no
    -- remaining period, and always the continuation of a progress version.
    ADD CONSTRAINT sync_plan_calculation_completion_basis_check CHECK (calculation_kind<>'COMPLETION'
        OR (basis_at=end_at AND t0<basis_at AND basis_at<=current_value_measured_at
            AND current_value_measured_at<=cashflow_verified_through AND periods_remaining='0'
            AND periods_elapsed=periods_total AND previous_calculation_id IS NOT NULL));
-- One completion version per plan.
CREATE UNIQUE INDEX sync_plan_calculation_one_completion
    ON sync_plan_calculation(plan_id) WHERE calculation_kind='COMPLETION';

-- A completion version that is not comparable has no plan difference or goal progress (never a zero).
ALTER TABLE sync_plan_calculation_metric
    DROP CONSTRAINT sync_plan_calculation_metric_unavailable_reason_check,
    DROP CONSTRAINT sync_plan_calculation_metric_check2;
ALTER TABLE sync_plan_calculation_metric
    ADD CONSTRAINT sync_plan_calculation_metric_unavailable_reason_check CHECK (unavailable_reason IS NULL
        OR unavailable_reason IN ('CURRENT_VALUE_NOT_POSITIVE','TARGET_NOT_POSITIVE','GOAL_PROFIT_NOT_POSITIVE',
            'REMAINING_PERIOD_NOT_POSITIVE','COMPLETION_NOT_COMPARABLE')),
    ADD CONSTRAINT sync_plan_calculation_metric_check2 CHECK (unavailable_reason IS NULL
        OR metric IN ('REQUIRED_RATE','GOAL_PROGRESS')
        OR (metric='PLAN_DIFFERENCE' AND unavailable_reason='COMPLETION_NOT_COMPARABLE'));

-- sync: the completion and post-end attempts ---------------------------------------------------------------------

-- COMPLETION: fixes the first post-end observation once, verifies the needed ranges and publishes the completion
-- version. POST_END_VALUE: after the completion is judged, reads the wallet only and publishes a portfolio snapshot.
-- Both come from the user's own sync request, never from a schedule.
ALTER TABLE sync_plan_sync_job ADD COLUMN observation_source_snapshot_id uuid;
ALTER TABLE sync_plan_sync_job ADD COLUMN observation_portfolio_snapshot_id uuid;
ALTER TABLE sync_plan_sync_job ADD COLUMN observation_portfolio_sequence bigint;

ALTER TABLE sync_plan_sync_job
    DROP CONSTRAINT sync_plan_sync_job_purpose_check,
    DROP CONSTRAINT sync_plan_sync_job_mode_check,
    DROP CONSTRAINT sync_plan_sync_job_stage_check,
    DROP CONSTRAINT sync_plan_sync_job_purpose_shape_check,
    -- V11's "a success has its result" check (its 10th unnamed table check, named ..._check9).
    DROP CONSTRAINT sync_plan_sync_job_check9,
    -- V12's failure vocabulary and V11's "a failure has a retryable code" check (its 12th, named ..._check11): both
    -- gain PROVIDER_END_NOT_REACHED.
    DROP CONSTRAINT sync_plan_sync_job_failure_code_check,
    DROP CONSTRAINT sync_plan_sync_job_check11;

ALTER TABLE sync_plan_sync_job
    ADD CONSTRAINT sync_plan_sync_job_purpose_check CHECK (purpose IN ('SYNC','VERIFY','CATCH_UP','COMPLETION',
        'POST_END_VALUE')),
    ADD CONSTRAINT sync_plan_sync_job_mode_check CHECK (mode IS NULL OR mode IN ('NORMAL','EXTENDED','VERIFY',
        'CATCH_UP','COMPLETION')),
    ADD CONSTRAINT sync_plan_sync_job_stage_check CHECK (stage IS NULL OR stage IN ('TIME','OBSERVATION','COLLECTION',
        'FINAL_SWEEP','CLASSIFICATION','CONVERSION','CALCULATION','PUBLICATION')),
    ADD CONSTRAINT sync_plan_sync_job_purpose_shape_check CHECK (
        (purpose='SYNC' AND reason_code IS NULL AND trigger IN ('ENTRY','VISIBILITY','MANUAL')
            AND (mode IS NULL OR mode IN ('NORMAL','EXTENDED')))
        OR (purpose='CATCH_UP' AND trigger='MANUAL' AND reason_code='LONG_GAP_CONTINUATION'
            AND (mode IS NULL OR mode='CATCH_UP'))
        OR (purpose='VERIFY' AND trigger='MANUAL' AND reason_code IN ('USER_REQUEST','GAP_SUSPECTED')
            AND (mode IS NULL OR mode='VERIFY'))
        OR (purpose='VERIFY' AND trigger='SYSTEM' AND reason_code IN ('PLAN_END_PREPARATION','UNRECOGNIZED_EVENT',
            'DUPLICATE_SUSPECTED','OPERATOR_INVESTIGATION') AND (mode IS NULL OR mode='VERIFY'))
        OR (purpose='COMPLETION' AND reason_code IS NULL AND trigger IN ('ENTRY','VISIBILITY','MANUAL')
            AND (mode IS NULL OR mode='COMPLETION'))
        OR (purpose='POST_END_VALUE' AND reason_code IS NULL AND trigger IN ('ENTRY','VISIBILITY','MANUAL')
            AND mode IS NULL)),
    -- A success: a progress publication has its whole result (V11's rule); a completion has one only when it published
    -- the completion version (an observation alone, after a data gap, publishes none); a post-end value never has one.
    ADD CONSTRAINT sync_plan_sync_job_success_check CHECK (status<>'SUCCEEDED' OR (failure_code IS NULL AND (
        (purpose IN ('SYNC','VERIFY','CATCH_UP') AND calculation_id IS NOT NULL
            AND published_pointer_version=base_pointer_version+1 AND mode IS NOT NULL
            AND collection_job_id IS NOT NULL AND classification_job_id IS NOT NULL AND conversion_job_id IS NOT NULL)
        OR (purpose='COMPLETION' AND (calculation_id IS NULL) = (published_pointer_version IS NULL)
            AND (calculation_id IS NULL OR (published_pointer_version=base_pointer_version+1 AND mode IS NOT NULL
                AND collection_job_id IS NOT NULL AND classification_job_id IS NOT NULL
                AND conversion_job_id IS NOT NULL)))
        OR (purpose='POST_END_VALUE' AND calculation_id IS NULL AND published_pointer_version IS NULL
            AND observation_source_snapshot_id IS NOT NULL)))),
    -- PROVIDER_END_NOT_REACHED: a completion's Bybit time or wallet measurement has not reached T yet — the ordinary
    -- clock difference right after T, not an untrusted clock. The attempt ends retryable, before the wallet when it is
    -- the time, without an observation when it is the measurement.
    ADD CONSTRAINT sync_plan_sync_job_failure_code_check CHECK (failure_code IS NULL OR failure_code IN (
        'PROVIDER_UNAVAILABLE','PROVIDER_TIME_UNTRUSTED','PROVIDER_END_NOT_REACHED','SOURCE_REJECTED',
        'CONVERSION_REQUIRED','CALCULATION_RANGE_EXCEEDED','CALCULATION_INPUT_INVALID','CALCULATION_POLICY_UNVERIFIED',
        'SYNC_INTERRUPTED','SYNC_FAILED','SOURCE_REQUIRES_REVIEW','SOURCE_EVENT_MISSING','HISTORY_OUT_OF_RANGE',
        'CLASSIFICATION_REQUIRES_REVIEW','CONVERSION_REQUIRES_REVIEW','NOT_PERMITTED','PLAN_CHANGED',
        'MEMBERSHIP_CHANGED','POINTER_CONFLICT','INPUTS_CHANGED','LONG_GAP','PLAN_PERIOD_ENDED')),
    ADD CONSTRAINT sync_plan_sync_job_failed_code_check CHECK (status<>'FAILED' OR failure_code IN (
        'PROVIDER_UNAVAILABLE','PROVIDER_TIME_UNTRUSTED','PROVIDER_END_NOT_REACHED','SOURCE_REJECTED',
        'CONVERSION_REQUIRED','CALCULATION_RANGE_EXCEEDED','CALCULATION_INPUT_INVALID','CALCULATION_POLICY_UNVERIFIED',
        'SYNC_INTERRUPTED','SYNC_FAILED')),
    -- The observation an attempt published: whole or absent, and only a completion's or a post-end value's.
    ADD CONSTRAINT sync_plan_sync_job_observation_check CHECK (
        (observation_source_snapshot_id IS NULL) = (observation_portfolio_snapshot_id IS NULL)
        AND (observation_portfolio_snapshot_id IS NULL) = (observation_portfolio_sequence IS NULL)
        AND (observation_source_snapshot_id IS NULL OR purpose IN ('COMPLETION','POST_END_VALUE')));
CREATE INDEX sync_plan_sync_job_observation_idx ON sync_plan_sync_job(observation_portfolio_snapshot_id)
    WHERE observation_portfolio_snapshot_id IS NOT NULL;

-- sync: a source snapshot read by a completion or post-end attempt ----------------------------------------------

-- A wallet observation without a collection: its origin is the attempt that read it, the third origin beside V5's
-- first-snapshot job and V10's candidate. Exactly one origin.
ALTER TABLE sync_source_snapshot ADD COLUMN plan_sync_job_id uuid UNIQUE
    REFERENCES sync_plan_sync_job(job_id) ON DELETE RESTRICT;
ALTER TABLE sync_source_snapshot DROP CONSTRAINT sync_source_snapshot_one_origin;
ALTER TABLE sync_source_snapshot ADD CONSTRAINT sync_source_snapshot_one_origin
    CHECK (num_nonnulls(job_id,initial_candidate_id,plan_sync_job_id)=1);
