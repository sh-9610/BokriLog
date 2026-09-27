-- Story 2.5 classifications are immutable and isolated from official snapshot publication.
CREATE TABLE sync_cashflow_classification_job (
    job_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    candidate_id uuid NOT NULL REFERENCES sync_initial_candidate_bundle(candidate_id) ON DELETE CASCADE,
    policy_version text NOT NULL,
    idempotency_key text NOT NULL,
    status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETE','NEEDS_REVIEW','CANCELLED')),
    failure_code text,
    fencing_generation bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    last_checked_at timestamptz,
    finished_at timestamptz,
    UNIQUE(user_id,candidate_id,policy_version),
    UNIQUE(user_id,intent_id,idempotency_key)
);
CREATE INDEX sync_cashflow_classification_queue_idx
    ON sync_cashflow_classification_job(created_at)
    WHERE status IN ('QUEUED','RUNNING');

CREATE TABLE sync_cashflow_classification_bundle (
    classification_id uuid PRIMARY KEY,
    job_id uuid NOT NULL UNIQUE REFERENCES sync_cashflow_classification_job(job_id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    candidate_id uuid NOT NULL REFERENCES sync_initial_candidate_bundle(candidate_id) ON DELETE CASCADE,
    input_scope_hash text NOT NULL CHECK (input_scope_hash ~ '^[0-9a-f]{64}$'),
    policy_version text NOT NULL,
    status text NOT NULL CHECK (status IN ('COMPLETE','NEEDS_REVIEW')),
    deposit_count integer NOT NULL CHECK (deposit_count>=0),
    withdrawal_count integer NOT NULL CHECK (withdrawal_count>=0),
    excluded_count integer NOT NULL CHECK (excluded_count>=0),
    review_count integer NOT NULL CHECK (review_count>=0),
    processed_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now())
);
CREATE INDEX sync_cashflow_classification_user_idx
    ON sync_cashflow_classification_bundle(user_id,processed_at DESC);

CREATE TABLE sync_cashflow_classification_event (
    classification_id uuid NOT NULL REFERENCES sync_cashflow_classification_bundle(classification_id) ON DELETE CASCADE,
    source_event_id uuid NOT NULL REFERENCES sync_initial_source_event(event_id) ON DELETE RESTRICT,
    source_id text NOT NULL,
    source_revision integer NOT NULL CHECK (source_revision>0),
    semantic_hash text NOT NULL CHECK (semantic_hash ~ '^[0-9a-f]{64}$'),
    provenance_connection_id uuid NOT NULL,
    verdict text NOT NULL CHECK (verdict IN ('EXTERNAL_IN','EXTERNAL_OUT','EXCLUDED','REVIEW_REQUIRED')),
    reason_code text NOT NULL,
    rule_id text NOT NULL,
    policy_version text NOT NULL,
    signed_amount text,
    asset_code text NOT NULL,
    source_quantity text,
    occurred_at timestamptz NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY(classification_id,source_event_id),
    CHECK ((verdict IN ('EXTERNAL_IN','EXTERNAL_OUT')) = (signed_amount IS NOT NULL)),
    CHECK (signed_amount IS NULL OR signed_amount ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    CHECK (source_quantity IS NULL OR source_quantity ~ '^(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    CHECK (verdict<>'EXTERNAL_IN' OR (signed_amount IS NOT NULL AND signed_amount !~ '^-')),
    CHECK (verdict<>'EXTERNAL_OUT' OR (signed_amount IS NOT NULL AND signed_amount ~ '^-'))
);

CREATE TABLE sync_cashflow_flow_candidate (
    classification_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    signed_amount text NOT NULL,
    asset_code text NOT NULL,
    source_quantity text NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY(classification_id,source_event_id),
    FOREIGN KEY(classification_id,source_event_id)
        REFERENCES sync_cashflow_classification_event(classification_id,source_event_id) ON DELETE CASCADE
);

CREATE TABLE sync_cashflow_exclusion_summary (
    classification_id uuid NOT NULL REFERENCES sync_cashflow_classification_bundle(classification_id) ON DELETE CASCADE,
    reason_code text NOT NULL,
    rule_id text NOT NULL,
    event_count integer NOT NULL CHECK (event_count>0),
    PRIMARY KEY(classification_id,reason_code,rule_id)
);
