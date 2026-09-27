-- Story 2.4 initial calculation inputs are isolated from first-snapshot publication.
CREATE TABLE sync_initial_collection_job (
    job_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    idempotency_key text NOT NULL,
    status text NOT NULL CHECK (status IN
        ('QUEUED','RUNNING','INPUTS_COMPLETE','NEEDS_REVIEW','RETRY_REQUIRED','CANCELLED')),
    t0 timestamptz NOT NULL,
    tc timestamptz,
    equity_decimal text,
    measured_at timestamptz,
    received_at timestamptz,
    policy_version text,
    failure_code text,
    fencing_generation bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    heartbeat_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    last_checked_at timestamptz,
    finished_at timestamptz,
    UNIQUE(user_id,intent_id,idempotency_key)
);
CREATE INDEX sync_initial_collection_active_user_idx
    ON sync_initial_collection_job(user_id,created_at DESC)
    WHERE status IN ('QUEUED','RUNNING');
CREATE UNIQUE INDEX sync_initial_collection_one_active_intent_idx
    ON sync_initial_collection_job(user_id,intent_id)
    WHERE status IN ('QUEUED','RUNNING');

CREATE TABLE sync_initial_source_event (
    event_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    provider_account_id uuid NOT NULL,
    source_id text NOT NULL,
    event_type text NOT NULL,
    currency text NOT NULL,
    cash_flow text NOT NULL,
    transaction_time timestamptz NOT NULL,
    account_type text NOT NULL CHECK (account_type='UNIFIED'),
    semantic_hash text NOT NULL,
    observed_connection_id uuid NOT NULL,
    first_observed_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    UNIQUE(provider_account_id,source_id),
    CHECK (octet_length(source_id) BETWEEN 1 AND 512),
    CHECK (semantic_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX sync_initial_source_event_user_idx ON sync_initial_source_event(user_id,transaction_time);

CREATE TABLE sync_initial_event_provenance (
    job_id uuid NOT NULL REFERENCES sync_initial_collection_job(job_id) ON DELETE CASCADE,
    event_id uuid NOT NULL REFERENCES sync_initial_source_event(event_id) ON DELETE RESTRICT,
    connection_id uuid NOT NULL,
    semantic_hash text NOT NULL CHECK (semantic_hash ~ '^[0-9a-f]{64}$'),
    observed_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    PRIMARY KEY(job_id,event_id)
);

CREATE TABLE sync_initial_event_observation (
    observation_id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES sync_initial_collection_job(job_id) ON DELETE CASCADE,
    page_evidence_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    provider_account_id uuid NOT NULL,
    observed_connection_id uuid NOT NULL,
    source_id text,
    event_type text,
    currency text,
    cash_flow text,
    transaction_time timestamptz,
    account_type text,
    semantic_hash text,
    diagnostic_code text NOT NULL CHECK (diagnostic_code IN
        ('MISSING_ID','SOURCE_ID_TOO_LONG','MALFORMED_EVENT','UNKNOWN_EVENT','EVENT_OUT_OF_WINDOW','PROVIDER_ID_CONFLICT')),
    observed_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now())
);
CREATE INDEX sync_initial_event_observation_job_idx ON sync_initial_event_observation(job_id,observed_at);

CREATE TABLE sync_initial_page_evidence (
    evidence_id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES sync_initial_collection_job(job_id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    event_type text NOT NULL CHECK (event_type IN ('TRANSFER_IN','TRANSFER_OUT')),
    range_start timestamptz NOT NULL,
    range_end timestamptz NOT NULL,
    cursor_hash text,
    requested_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    item_count integer NOT NULL CHECK (item_count>=0),
    first_event_at timestamptz,
    last_event_at timestamptz,
    has_next_page boolean NOT NULL,
    CHECK (range_end>range_start),
    CHECK (cursor_hash IS NULL OR cursor_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE(evidence_id,job_id)
);
ALTER TABLE sync_initial_event_observation
    ADD CONSTRAINT sync_initial_observation_page_fk
    FOREIGN KEY(page_evidence_id,job_id)
    REFERENCES sync_initial_page_evidence(evidence_id,job_id) ON DELETE CASCADE;
CREATE INDEX sync_initial_page_evidence_job_idx ON sync_initial_page_evidence(job_id,event_type,range_start);
CREATE UNIQUE INDEX sync_initial_page_evidence_cursor_key
    ON sync_initial_page_evidence(job_id,event_type,range_start,range_end,cursor_hash) NULLS NOT DISTINCT;

CREATE TABLE sync_initial_candidate_bundle (
    candidate_id uuid PRIMARY KEY,
    job_id uuid NOT NULL UNIQUE REFERENCES sync_initial_collection_job(job_id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    t0 timestamptz NOT NULL,
    tc timestamptz NOT NULL,
    equity_decimal text NOT NULL,
    measured_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    policy_version text NOT NULL,
    completeness_hash text NOT NULL CHECK (completeness_hash ~ '^[0-9a-f]{64}$'),
    completed_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now())
);

CREATE TABLE sync_initial_candidate_event (
    candidate_id uuid NOT NULL REFERENCES sync_initial_candidate_bundle(candidate_id) ON DELETE CASCADE,
    event_id uuid NOT NULL REFERENCES sync_initial_source_event(event_id) ON DELETE RESTRICT,
    PRIMARY KEY(candidate_id,event_id)
);
