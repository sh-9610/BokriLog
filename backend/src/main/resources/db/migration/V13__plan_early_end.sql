-- Story 2.13: the explicit early end of an active plan and its read-only record (ERD-1.0.0 §5 SECURITY-ACTION-1.0,
-- AD-16, AD-20, AD-23).
--
-- Expand only. V1~V12 are not modified. V10 left `plan_definition.status` at ACTIVE alone, saying that plan ending
-- (this story) and completion (Story 2.14) widen the set: this file widens it by one value, ENDED_EARLY. Only that
-- one inline check is replaced, by the name PostgreSQL generated for it (`<table>_<column>_check`; a DROP without
-- IF EXISTS fails the migration if the name is not what V10 made), and every other check of the table stays exactly
-- as it was — a test compares both schemas' definitions.
--
-- The active plan has no pointer table of its own: the partial unique index `WHERE status='ACTIVE'` (V10) is the
-- authority for "one active plan per portfolio". So the one UPDATE that moves a plan to ENDED_EARLY is what releases
-- the active pointer, and the plan's immutable terms (P0, t0, T, period, rate) stay untouched. There is no way back:
-- nothing moves a plan to ACTIVE again, and the end record's composite foreign key below makes that a rule of the schema
-- — a plan cannot leave ENDED_EARLY while its end record exists, and an end record can only cite an ENDED_EARLY plan.
--
-- The end itself is a fact of its own, in a table of its own (`plan_end`, owned by `plan`), so Story 2.14's
-- completion widens neither this row nor that one. It records the reason, the server's end time (the database clock,
-- to the millisecond), the last normal official result as references and copied times — never recalculated, and never
-- turned into a valuation at the end time — the completion comparison state, the uncollected gap of a long-gap end,
-- and the idempotency key with the request hash.
--
-- No foreign key crosses module ownership: the official calculation, pointer version and portfolio snapshot are
-- `sync` and `portfolio` rows and are referenced by value only. Erasure removes the end record before the plan
-- (`PlanConditionRepository.erase`), because the end record references its plan row (ON DELETE RESTRICT).

-- plan: the status gains ENDED_EARLY ---------------------------------------------------------------------------

ALTER TABLE plan_definition DROP CONSTRAINT plan_definition_status_check;
ALTER TABLE plan_definition ADD CONSTRAINT plan_definition_status_check
    CHECK (status IN ('ACTIVE','ENDED_EARLY'));
-- The target of the end record's composite key: a plan row with the status the record pairs with.
ALTER TABLE plan_definition ADD CONSTRAINT plan_definition_plan_user_status_key UNIQUE(plan_id,user_id,status);

-- plan: the end record ------------------------------------------------------------------------------------------

CREATE TABLE plan_end (
    plan_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    -- The plan status this record pairs with. Always ENDED_EARLY (Story 2.14's completion is not recorded here); the
    -- composite foreign key below requires the plan row to have it, and keeps it there (NO ACTION on update).
    status text NOT NULL DEFAULT 'ENDED_EARLY' CHECK (status='ENDED_EARLY'),
    -- USER_REQUEST: the user's early end. LONG_GAP_NEW_PLAN: the "start a new plan" choice after a long gap.
    reason text NOT NULL CHECK (reason IN ('USER_REQUEST','LONG_GAP_NEW_PLAN')),
    -- The database's statement time when the end was confirmed, truncated to the millisecond.
    ended_at timestamptz NOT NULL,
    -- The official result the plan ended on: the calculation version, its pointer version and its portfolio snapshot.
    official_calculation_id uuid NOT NULL,
    official_pointer_version bigint NOT NULL CHECK (official_pointer_version>=1),
    official_portfolio_snapshot_id uuid NOT NULL,
    -- That calculation's measurement and cash-flow verification times, copied as they are.
    last_measured_at timestamptz NOT NULL,
    cashflow_verified_through timestamptz NOT NULL,
    -- NOT_APPLICABLE: an early end is no completion comparison. UNAVAILABLE_DATA_GAP: not computable, a data gap.
    completion text NOT NULL CHECK (completion IN ('NOT_APPLICABLE','UNAVAILABLE_DATA_GAP')),
    -- A long-gap end's uncollected range (cashflow_verified_through, ended_at]: its history is never collected.
    uncollected_from timestamptz,
    uncollected_until timestamptz,
    idempotency_key text NOT NULL CHECK (octet_length(idempotency_key) BETWEEN 16 AND 128),
    -- The hash of the request (reason, plan and the reference shown): the same key with another request conflicts.
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    -- Also the index of every per-user statement (it leads with user_id); no separate user index is needed.
    UNIQUE(user_id,idempotency_key),
    CONSTRAINT plan_end_plan_fk FOREIGN KEY(plan_id,user_id,status)
        REFERENCES plan_definition(plan_id,user_id,status) ON DELETE RESTRICT,
    -- The completion comparison is a data gap exactly for a long-gap end.
    CONSTRAINT plan_end_completion_reason_check
        CHECK ((reason='LONG_GAP_NEW_PLAN') = (completion='UNAVAILABLE_DATA_GAP')),
    -- Only a long-gap end has an uncollected range, and then both of its ends.
    CONSTRAINT plan_end_uncollected_presence_check
        CHECK ((reason='LONG_GAP_NEW_PLAN') = (uncollected_from IS NOT NULL)
            AND (uncollected_from IS NULL) = (uncollected_until IS NULL)),
    CONSTRAINT plan_end_uncollected_range_check
        CHECK (uncollected_from IS NULL
            OR (uncollected_from<uncollected_until AND uncollected_until=ended_at
                AND uncollected_from=cashflow_verified_through)),
    -- The last normal result came before the end; its measurement is never moved to the end time.
    CONSTRAINT plan_end_measured_check CHECK (last_measured_at<=ended_at),
    CONSTRAINT plan_end_verified_check CHECK (cashflow_verified_through<=ended_at)
);
