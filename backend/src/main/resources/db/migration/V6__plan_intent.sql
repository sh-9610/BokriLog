-- Story 2.3: a condition intent is not an active plan or an official calculation.
ALTER TABLE portfolio_snapshot ADD COLUMN published_at timestamptz;
UPDATE portfolio_snapshot s SET published_at=date_trunc('milliseconds',COALESCE(
    (SELECT c.published_at FROM portfolio_current_snapshot c
        WHERE c.portfolio_snapshot_id=s.portfolio_snapshot_id),s.composed_at));
ALTER TABLE portfolio_snapshot ALTER COLUMN published_at SET DEFAULT date_trunc('milliseconds',now());
ALTER TABLE portfolio_snapshot ALTER COLUMN published_at SET NOT NULL;

CREATE TABLE plan_condition_intent (
    intent_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    portfolio_snapshot_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    source_snapshot_id uuid NOT NULL,
    p0_decimal text NOT NULL CHECK (p0_decimal ~ '^[0-9]+(\.[0-9]+)?$'),
    t0 timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    period text NOT NULL CHECK (period IN ('DAILY','WEEKLY','MONTHLY')),
    target_rate text NOT NULL CHECK (target_rate ~ '^(0|[1-9][0-9]*)(\.[0-9]+)?$'),
    source_policy_version text NOT NULL,
    portfolio_policy_version text NOT NULL,
    calendar_policy text NOT NULL,
    calculation_policy text NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN','REPLACED','INVALIDATED','CONSUMED')),
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    replaced_by uuid,
    UNIQUE(intent_id,user_id),
    FOREIGN KEY(replaced_by,user_id) REFERENCES plan_condition_intent(intent_id,user_id)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE UNIQUE INDEX plan_condition_one_open_per_user ON plan_condition_intent(user_id)
    WHERE status='OPEN';
CREATE INDEX plan_condition_user_idx ON plan_condition_intent(user_id,created_at DESC);

CREATE TABLE plan_condition_confirmation (
    confirmation_hash text PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    session_hash text NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    expires_at timestamptz NOT NULL,
    canonical_hash text NOT NULL,
    portfolio_id uuid NOT NULL,
    portfolio_snapshot_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    source_snapshot_id uuid NOT NULL,
    p0_decimal text NOT NULL,
    t0 timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    period text NOT NULL CHECK (period IN ('DAILY','WEEKLY','MONTHLY')),
    target_rate text NOT NULL,
    source_policy_version text NOT NULL,
    portfolio_policy_version text NOT NULL,
    calendar_policy text NOT NULL,
    calculation_policy text NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX plan_condition_confirmation_user_idx ON plan_condition_confirmation(user_id,expires_at);

CREATE TABLE plan_condition_idempotency (
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    command text NOT NULL CHECK (command='CONFIRM_PLAN_CONDITION'),
    idempotency_key text NOT NULL,
    canonical_hash text NOT NULL,
    intent_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    PRIMARY KEY(user_id,command,idempotency_key),
    FOREIGN KEY(intent_id,user_id) REFERENCES plan_condition_intent(intent_id,user_id)
        ON DELETE CASCADE
);
