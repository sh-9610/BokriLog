-- Story 2.2: first official value. All payload belongs to its user and is removed explicitly.
-- Composite keys let new facts prove that their owner and provenance agree in SQL.
ALTER TABLE source_connection ADD CONSTRAINT source_connection_snapshot_owner_key
    UNIQUE(connection_id,user_id,provider_account_id);
ALTER TABLE portfolio ADD CONSTRAINT portfolio_snapshot_owner_key UNIQUE(portfolio_id,user_id);
ALTER TABLE portfolio_source_membership ADD CONSTRAINT portfolio_membership_snapshot_key
    UNIQUE(membership_id,portfolio_id,connection_id,provider_account_id);

CREATE TABLE sync_job (
    job_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    connection_id uuid NOT NULL REFERENCES source_connection(connection_id) ON DELETE RESTRICT,
    provider_account_id uuid NOT NULL REFERENCES source_provider_account(provider_account_id) ON DELETE RESTRICT,
    idempotency_key text NOT NULL,
    status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','BLOCKED')),
    failure_reason text,
    fencing_generation bigint NOT NULL DEFAULT 0 CHECK (fencing_generation >= 0),
    lease_until timestamptz,
    heartbeat_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE(connection_id, idempotency_key),
    UNIQUE(job_id,user_id,connection_id,provider_account_id),
    FOREIGN KEY(connection_id,user_id,provider_account_id)
        REFERENCES source_connection(connection_id,user_id,provider_account_id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX sync_job_active_connection_key ON sync_job(connection_id)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX sync_job_user_idx ON sync_job(user_id);

CREATE TABLE sync_source_snapshot (
    source_snapshot_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    connection_id uuid NOT NULL REFERENCES source_connection(connection_id) ON DELETE RESTRICT,
    provider_account_id uuid NOT NULL REFERENCES source_provider_account(provider_account_id) ON DELETE RESTRICT,
    job_id uuid NOT NULL UNIQUE REFERENCES sync_job(job_id) ON DELETE RESTRICT,
    provider text NOT NULL CHECK (provider = 'BYBIT'),
    equity_decimal text NOT NULL CHECK (equity_decimal ~ '^-?[0-9]+(\.[0-9]+)?$'),
    measured_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    input_status text NOT NULL CHECK (input_status = 'VERIFIED'),
    policy_version text NOT NULL,
    UNIQUE(source_snapshot_id,user_id,connection_id,provider_account_id),
    FOREIGN KEY(job_id,user_id,connection_id,provider_account_id)
        REFERENCES sync_job(job_id,user_id,connection_id,provider_account_id) ON DELETE RESTRICT
);
CREATE INDEX sync_source_snapshot_user_idx ON sync_source_snapshot(user_id);

CREATE TABLE portfolio_snapshot (
    portfolio_snapshot_id uuid PRIMARY KEY,
    portfolio_id uuid NOT NULL REFERENCES portfolio(portfolio_id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    amount_decimal text NOT NULL CHECK (amount_decimal ~ '^-?[0-9]+(\.[0-9]+)?$'),
    base_currency text NOT NULL CHECK (base_currency = 'USD'),
    measured_at timestamptz NOT NULL,
    composed_at timestamptz NOT NULL DEFAULT now(),
    publication_sequence bigint NOT NULL CHECK (publication_sequence > 0),
    UNIQUE(portfolio_id, publication_sequence),
    UNIQUE(portfolio_snapshot_id,portfolio_id,user_id),
    UNIQUE(portfolio_snapshot_id,portfolio_id,publication_sequence),
    FOREIGN KEY(portfolio_id,user_id) REFERENCES portfolio(portfolio_id,user_id) ON DELETE CASCADE
);
CREATE INDEX portfolio_snapshot_user_idx ON portfolio_snapshot(user_id);

CREATE TABLE portfolio_snapshot_component (
    portfolio_snapshot_id uuid PRIMARY KEY REFERENCES portfolio_snapshot(portfolio_snapshot_id) ON DELETE CASCADE,
    membership_id uuid NOT NULL REFERENCES portfolio_source_membership(membership_id) ON DELETE RESTRICT,
    source_snapshot_id uuid NOT NULL UNIQUE REFERENCES sync_source_snapshot(source_snapshot_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    user_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    amount_decimal text NOT NULL CHECK (amount_decimal ~ '^-?[0-9]+(\.[0-9]+)?$'),
    policy_version text NOT NULL,
    FOREIGN KEY(portfolio_snapshot_id,portfolio_id,user_id)
        REFERENCES portfolio_snapshot(portfolio_snapshot_id,portfolio_id,user_id) ON DELETE CASCADE,
    FOREIGN KEY(membership_id,portfolio_id,connection_id,provider_account_id)
        REFERENCES portfolio_source_membership(membership_id,portfolio_id,connection_id,provider_account_id)
        ON DELETE RESTRICT,
    FOREIGN KEY(source_snapshot_id,user_id,connection_id,provider_account_id)
        REFERENCES sync_source_snapshot(source_snapshot_id,user_id,connection_id,provider_account_id)
        ON DELETE RESTRICT
);

CREATE TABLE portfolio_current_snapshot (
    portfolio_id uuid PRIMARY KEY REFERENCES portfolio(portfolio_id) ON DELETE CASCADE,
    portfolio_snapshot_id uuid NOT NULL UNIQUE REFERENCES portfolio_snapshot(portfolio_snapshot_id) ON DELETE RESTRICT,
    publication_sequence bigint NOT NULL CHECK (publication_sequence > 0),
    published_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(portfolio_snapshot_id,portfolio_id,publication_sequence)
        REFERENCES portfolio_snapshot(portfolio_snapshot_id,portfolio_id,publication_sequence)
        ON DELETE RESTRICT
);
