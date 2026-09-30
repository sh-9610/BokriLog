-- Story 2.12: the precision verification and the long-gap continuation of an active plan, the late events they
-- find, and the change record of every incremental version (ERD-1.0.0 §2.2, §2.5, SYNC-UX-1.0, AD-7, AD-8,
-- AD-16, AD-23).
--
-- Expand only. V1~V11 are not modified. The sync attempt table gains a purpose and a reason, so one table keeps
-- the one-in-progress index, the lease and the dashboard's "in progress" for every purpose: an existing row is a
-- SYNC (the default) and keeps its meaning. Only the five V11 checks whose vocabulary widens are replaced, by the
-- names PostgreSQL generated for them (a DROP without IF EXISTS fails the migration if a name is not what V11
-- made); every other V11 check stays exactly as it was, and a test compares the two schemas' definitions.
--
-- Four append-only tables follow, all owned by `sync` and erased with the user: the ranges an attempt planned,
-- its reconciliation outcomes, the events a candidate excludes, and the flow differences between a calculation
-- version and the one it replaced.

-- sync: the attempt's purpose, reason, first-phase cutoff and resumptions -----------------------------------

ALTER TABLE sync_plan_sync_job ADD COLUMN purpose text NOT NULL DEFAULT 'SYNC';
ALTER TABLE sync_plan_sync_job ADD COLUMN reason_code text;
-- CATCH_UP only: the tc its first phase ran to, kept when the final sweep fixes the new tc (tcFinal).
ALTER TABLE sync_plan_sync_job ADD COLUMN initial_tc timestamptz;
-- How many times a reclaimed VERIFY or CATCH_UP attempt went on from its collection (at most twice).
ALTER TABLE sync_plan_sync_job ADD COLUMN resume_count integer NOT NULL DEFAULT 0;

ALTER TABLE sync_plan_sync_job
    DROP CONSTRAINT sync_plan_sync_job_trigger_check,
    DROP CONSTRAINT sync_plan_sync_job_mode_check,
    DROP CONSTRAINT sync_plan_sync_job_stage_check,
    DROP CONSTRAINT sync_plan_sync_job_failure_code_check,
    -- V11's "NEEDS_REVIEW has a review code" check (its 13th unnamed table check).
    DROP CONSTRAINT sync_plan_sync_job_check12;

ALTER TABLE sync_plan_sync_job
    ADD CONSTRAINT sync_plan_sync_job_trigger_check CHECK (trigger IN ('ENTRY','VISIBILITY','MANUAL','SYSTEM')),
    ADD CONSTRAINT sync_plan_sync_job_mode_check CHECK (mode IS NULL OR mode IN ('NORMAL','EXTENDED','VERIFY',
        'CATCH_UP')),
    ADD CONSTRAINT sync_plan_sync_job_stage_check CHECK (stage IS NULL OR stage IN ('TIME','COLLECTION','FINAL_SWEEP',
        'CLASSIFICATION','CONVERSION','CALCULATION','PUBLICATION')),
    ADD CONSTRAINT sync_plan_sync_job_failure_code_check CHECK (failure_code IS NULL OR failure_code IN (
        'PROVIDER_UNAVAILABLE','PROVIDER_TIME_UNTRUSTED','SOURCE_REJECTED','CONVERSION_REQUIRED',
        'CALCULATION_RANGE_EXCEEDED','CALCULATION_INPUT_INVALID','CALCULATION_POLICY_UNVERIFIED','SYNC_INTERRUPTED',
        'SYNC_FAILED','SOURCE_REQUIRES_REVIEW','SOURCE_EVENT_MISSING','HISTORY_OUT_OF_RANGE',
        'CLASSIFICATION_REQUIRES_REVIEW','CONVERSION_REQUIRES_REVIEW','NOT_PERMITTED','PLAN_CHANGED',
        'MEMBERSHIP_CHANGED','POINTER_CONFLICT','INPUTS_CHANGED','LONG_GAP','PLAN_PERIOD_ENDED')),
    -- SOURCE_EVENT_MISSING: an official event of a target range was not observed again.
    -- HISTORY_OUT_OF_RANGE: a target begins before tc - 730 days; the attempt ends before any signed call.
    ADD CONSTRAINT sync_plan_sync_job_review_check CHECK (status<>'NEEDS_REVIEW' OR failure_code IN (
        'SOURCE_REQUIRES_REVIEW','SOURCE_EVENT_MISSING','HISTORY_OUT_OF_RANGE','CLASSIFICATION_REQUIRES_REVIEW',
        'CONVERSION_REQUIRES_REVIEW')),
    ADD CONSTRAINT sync_plan_sync_job_purpose_check CHECK (purpose IN ('SYNC','VERIFY','CATCH_UP')),
    ADD CONSTRAINT sync_plan_sync_job_reason_check CHECK (reason_code IS NULL OR reason_code IN ('USER_REQUEST',
        'GAP_SUSPECTED','UNRECOGNIZED_EVENT','DUPLICATE_SUSPECTED','LONG_GAP_CONTINUATION','PLAN_END_PREPARATION',
        'OPERATOR_INVESTIGATION')),
    ADD CONSTRAINT sync_plan_sync_job_resume_check CHECK (resume_count BETWEEN 0 AND 2),
    -- The purpose decides the trigger, the reason and the mode. A sync has no reason and a NORMAL or EXTENDED
    -- mode; a continuation is the user's choice after a long gap; a verification is the user's (two reasons, the
    -- server derives which) or the system's (the four operational reasons, through the public method only).
    ADD CONSTRAINT sync_plan_sync_job_purpose_shape_check CHECK (
        (purpose='SYNC' AND reason_code IS NULL AND trigger IN ('ENTRY','VISIBILITY','MANUAL')
            AND (mode IS NULL OR mode IN ('NORMAL','EXTENDED')))
        OR (purpose='CATCH_UP' AND trigger='MANUAL' AND reason_code='LONG_GAP_CONTINUATION'
            AND (mode IS NULL OR mode='CATCH_UP'))
        OR (purpose='VERIFY' AND trigger='MANUAL' AND reason_code IN ('USER_REQUEST','GAP_SUSPECTED')
            AND (mode IS NULL OR mode='VERIFY'))
        OR (purpose='VERIFY' AND trigger='SYSTEM' AND reason_code IN ('PLAN_END_PREPARATION','UNRECOGNIZED_EVENT',
            'DUPLICATE_SUSPECTED','OPERATOR_INVESTIGATION') AND (mode IS NULL OR mode='VERIFY'))),
    ADD CONSTRAINT sync_plan_sync_job_resume_purpose_check CHECK (resume_count=0 OR purpose IN ('VERIFY','CATCH_UP')),
    -- The final sweep and its first-phase cutoff belong to a continuation, and tcFinal lies beyond that cutoff.
    ADD CONSTRAINT sync_plan_sync_job_final_sweep_check CHECK (stage IS DISTINCT FROM 'FINAL_SWEEP'
        OR purpose='CATCH_UP'),
    ADD CONSTRAINT sync_plan_sync_job_initial_tc_check CHECK (initial_tc IS NULL OR (purpose='CATCH_UP'
        AND tc IS NOT NULL AND initial_tc<tc AND base_verified_through<initial_tc)),
    -- A queued attempt has no first-phase cutoff either (V11's queued check, kept, knows no initial_tc).
    ADD CONSTRAINT sync_plan_sync_job_queued_initial_tc_check CHECK (status<>'QUEUED' OR initial_tc IS NULL);

-- sync: whether a collection page itself asked for review (Story 2.12) ------------------------------------------

-- A page the adapter flagged (an item that is not an object, say) is recorded on its collection, so a resumed
-- holder — or a continuation's second phase — still knows it, and the persistent-review rule can read it.
ALTER TABLE sync_initial_collection_job ADD COLUMN page_review boolean NOT NULL DEFAULT false;

-- The "same base verification" lookups: whether a precision verification of this base already ended in review.
CREATE INDEX sync_plan_sync_base_idx ON sync_plan_sync_job(user_id,plan_id,base_calculation_id,purpose);

-- sync: the ranges an attempt planned ------------------------------------------------------------------------

-- A verification's plan, written at admission (up to the base verification) and by its worker (the NEW range up
-- to tc, the FINAL_SWEEP up to tcFinal). Targets may overlap one another — the collection merges them — and
-- REUSED is the base evidence the targets leave, kept so the screen can say which evidence was reused.
CREATE TABLE sync_plan_verification_range (
    job_id uuid NOT NULL REFERENCES sync_plan_sync_job(job_id) ON DELETE CASCADE,
    seq integer NOT NULL CHECK (seq>0),
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    kind text NOT NULL CHECK (kind IN ('UNCONFIRMED','GAP','ANOMALY','EXPLICIT','NEW','FINAL_SWEEP','REUSED')),
    range_start timestamptz NOT NULL,
    range_end timestamptz NOT NULL,
    PRIMARY KEY(job_id,seq),
    CHECK (range_start<range_end)
);
CREATE INDEX sync_plan_verification_range_user_idx ON sync_plan_verification_range(user_id);

-- sync: the reconciliation of a verification -------------------------------------------------------------------

-- One outcome per Bybit id inside the targets: SAME (observed and official), NEW (observed, not official; late
-- when not after the base verification), MISSING (official, not observed), SUPERSEDED (stored, not official,
-- not observed: the new candidate excludes it) and CONFLICT (the same id with another semantic hash). Source
-- facts are never updated: this is the verification's own append-only record.
CREATE TABLE sync_plan_verification_event (
    job_id uuid NOT NULL REFERENCES sync_plan_sync_job(job_id) ON DELETE CASCADE,
    source_id text NOT NULL CHECK (octet_length(source_id) BETWEEN 1 AND 512),
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    event_id uuid REFERENCES sync_initial_source_event(event_id) ON DELETE RESTRICT,
    outcome text NOT NULL CHECK (outcome IN ('NEW','SAME','MISSING','SUPERSEDED','CONFLICT')),
    late boolean NOT NULL,
    occurred_at timestamptz,
    stored_semantic_hash text CHECK (stored_semantic_hash IS NULL OR stored_semantic_hash ~ '^[0-9a-f]{64}$'),
    observed_semantic_hash text CHECK (observed_semantic_hash IS NULL OR observed_semantic_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(job_id,source_id),
    CHECK (outcome<>'NEW' OR (event_id IS NOT NULL AND observed_semantic_hash IS NOT NULL)),
    CHECK (outcome NOT IN ('MISSING','SUPERSEDED') OR (event_id IS NOT NULL AND observed_semantic_hash IS NULL)),
    CHECK (outcome<>'CONFLICT' OR (stored_semantic_hash IS NOT NULL AND observed_semantic_hash IS NOT NULL
        AND stored_semantic_hash<>observed_semantic_hash)),
    CHECK (outcome<>'SAME' OR (event_id IS NOT NULL AND stored_semantic_hash IS NOT NULL
        AND stored_semantic_hash=observed_semantic_hash)),
    CHECK (late=false OR outcome='NEW')
);
CREATE INDEX sync_plan_verification_event_user_idx ON sync_plan_verification_event(user_id);
CREATE INDEX sync_plan_verification_event_source_idx ON sync_plan_verification_event(event_id);

-- sync: the events a candidate excludes ------------------------------------------------------------------------

-- A stored event of (t0, tc] a complete collection did not observe again, which no official candidate had. The
-- new candidate leaves it out; every later candidate inherits the exclusion (with the attempt that excluded it)
-- and counts it as explained. An exclusion is not a deletion: observed again, the event comes back as NEW.
CREATE TABLE sync_initial_candidate_excluded_event (
    candidate_id uuid NOT NULL REFERENCES sync_initial_candidate_bundle(candidate_id) ON DELETE CASCADE,
    event_id uuid NOT NULL REFERENCES sync_initial_source_event(event_id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    excluded_by_job_id uuid NOT NULL REFERENCES sync_plan_sync_job(job_id) ON DELETE RESTRICT,
    reason_code text NOT NULL CHECK (reason_code='NOT_REOBSERVED'),
    PRIMARY KEY(candidate_id,event_id)
);
CREATE INDEX sync_initial_candidate_excluded_user_idx ON sync_initial_candidate_excluded_event(user_id);
CREATE INDEX sync_initial_candidate_excluded_event_idx ON sync_initial_candidate_excluded_event(event_id);

-- sync: what a version changed ---------------------------------------------------------------------------------

-- The flow difference between an incremental calculation version and the one it replaced, one row per source
-- event, written in the publishing transaction: ADDED (late when not after the previous verification), REMOVED
-- and VALUE_CHANGED, with both USD values and estimated flags as the two versions applied them, the price
-- evidence kind and when BokriLog first observed the event. A version without a difference has no row.
CREATE TABLE sync_plan_calculation_change (
    calculation_id uuid NOT NULL REFERENCES sync_plan_calculation(calculation_id) ON DELETE CASCADE,
    source_event_id uuid NOT NULL REFERENCES sync_initial_source_event(event_id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    -- The same policy as its sibling calculation_id: a row describes the step between two versions and goes with
    -- either of them.
    previous_calculation_id uuid NOT NULL REFERENCES sync_plan_calculation(calculation_id) ON DELETE CASCADE,
    change_kind text NOT NULL CHECK (change_kind IN ('ADDED','REMOVED','VALUE_CHANGED')),
    late boolean NOT NULL,
    occurred_at timestamptz NOT NULL,
    previous_usd_value text CHECK (previous_usd_value IS NULL
        OR previous_usd_value ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    previous_estimated boolean,
    new_usd_value text CHECK (new_usd_value IS NULL OR new_usd_value ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    new_estimated boolean,
    evidence_type text NOT NULL CHECK (evidence_type IN ('DIRECT_NORMALIZATION','SPOT_KLINE_CLOSE')),
    discovered_at timestamptz NOT NULL,
    PRIMARY KEY(calculation_id,source_event_id),
    CHECK (previous_calculation_id<>calculation_id),
    CHECK ((previous_usd_value IS NULL) = (previous_estimated IS NULL)),
    CHECK ((new_usd_value IS NULL) = (new_estimated IS NULL)),
    CHECK (change_kind<>'ADDED' OR (previous_usd_value IS NULL AND new_usd_value IS NOT NULL)),
    CHECK (change_kind<>'REMOVED' OR (previous_usd_value IS NOT NULL AND new_usd_value IS NULL)),
    CHECK (change_kind<>'VALUE_CHANGED' OR (previous_usd_value IS NOT NULL AND new_usd_value IS NOT NULL))
);
CREATE INDEX sync_plan_calculation_change_user_idx ON sync_plan_calculation_change(user_id);
CREATE INDEX sync_plan_calculation_change_previous_idx ON sync_plan_calculation_change(previous_calculation_id);
CREATE INDEX sync_plan_calculation_change_event_idx ON sync_plan_calculation_change(source_event_id);

-- The versions Story 2.11 already published get their rows from the same difference the publication now writes
-- (PlanPublicationRepository.CHANGE_DIFF, with its :c, :p and :u as the columns c.calculation_id,
-- c.previous_calculation_id and c.user_id). The inputs are immutable rows only, so the backfill is deterministic. A
-- test runs the statement between the two markers below against rows the publication wrote and requires the same.
-- backfill:begin
INSERT INTO sync_plan_calculation_change(calculation_id,source_event_id,user_id,previous_calculation_id,change_kind,
    late,occurred_at,previous_usd_value,previous_estimated,new_usd_value,new_estimated,evidence_type,discovered_at)
SELECT c.calculation_id,d.source_event_id,c.user_id,c.previous_calculation_id,d.change_kind,d.late,d.occurred_at,
    d.previous_usd_value,d.previous_estimated,d.new_usd_value,d.new_estimated,d.evidence_type,d.discovered_at
FROM sync_plan_calculation c
JOIN LATERAL (SELECT COALESCE(n.source_event_id,o.source_event_id) AS source_event_id,
        CASE WHEN o.source_event_id IS NULL THEN 'ADDED' WHEN n.source_event_id IS NULL THEN 'REMOVED'
            ELSE 'VALUE_CHANGED' END AS change_kind,
        COALESCE(n.occurred_at,o.occurred_at)<=prev.cashflow_verified_through AS late,
        COALESCE(n.occurred_at,o.occurred_at) AS occurred_at,o.usd_value AS previous_usd_value,
        o.estimated AS previous_estimated,n.usd_value AS new_usd_value,n.estimated AS new_estimated,
        COALESCE(n.evidence_type,o.evidence_type) AS evidence_type,se.first_observed_at AS discovered_at
    FROM (SELECT f.source_event_id FROM sync_plan_calculation_flow f
            WHERE f.calculation_id IN (c.calculation_id,c.previous_calculation_id) GROUP BY f.source_event_id) pairs
    JOIN sync_plan_calculation prev ON prev.calculation_id=c.previous_calculation_id AND prev.user_id=c.user_id
    LEFT JOIN sync_plan_calculation_flow n ON n.calculation_id=c.calculation_id
        AND n.source_event_id=pairs.source_event_id
    LEFT JOIN sync_plan_calculation_flow o ON o.calculation_id=c.previous_calculation_id
        AND o.source_event_id=pairs.source_event_id
    JOIN sync_initial_source_event se ON se.event_id=pairs.source_event_id AND se.user_id=c.user_id
    WHERE o.source_event_id IS NULL OR n.source_event_id IS NULL
        OR o.usd_value::numeric IS DISTINCT FROM n.usd_value::numeric OR o.estimated IS DISTINCT FROM n.estimated) d
    ON true
WHERE c.previous_calculation_id IS NOT NULL;
-- backfill:end
