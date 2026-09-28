-- Story 2.6 event-time USD conversion (BYBIT-PRICE-1.0, CALC-1.0).
--
-- Two kinds of rows live here and they are erased differently.
--
--   * Public market evidence (`sync_market_*`) carries no user identifier. It is shared between
--     users as a cache, is append-only (a changed value is a new revision), and survives the
--     erasure of any user. Result legs point at it with ON DELETE RESTRICT so evidence that a
--     result cites cannot silently disappear.
--   * Attempts, result bundles, per-flow results, price legs and calculable cash flows are user
--     scoped. They reference `identity_account` with ON DELETE RESTRICT (erasure must be explicit
--     and ordered) and the upstream classification bundle with ON DELETE CASCADE.
--
-- Nothing here creates a plan, a calculation version, a snapshot pointer or a watermark.

CREATE TABLE sync_market_instrument_observation (
    observation_id uuid PRIMARY KEY,
    provider text NOT NULL CHECK (provider='BYBIT'),
    category text NOT NULL CHECK (category='spot'),
    symbol text NOT NULL CHECK (symbol ~ '^[A-Z0-9]{2,64}$'),
    revision integer NOT NULL CHECK (revision>0),
    listed boolean NOT NULL,
    base_asset text,
    quote_asset text,
    status text,
    evidence_hash text NOT NULL CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    policy_version text NOT NULL,
    observed_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    UNIQUE(provider,category,symbol,revision),
    CHECK (listed = (base_asset IS NOT NULL AND quote_asset IS NOT NULL AND status IS NOT NULL)),
    CHECK (listed OR (base_asset IS NULL AND quote_asset IS NULL AND status IS NULL))
);

CREATE TABLE sync_market_candle (
    candle_id uuid PRIMARY KEY,
    provider text NOT NULL CHECK (provider='BYBIT'),
    category text NOT NULL CHECK (category='spot'),
    symbol text NOT NULL CHECK (symbol ~ '^[A-Z0-9]{2,64}$'),
    interval_code text NOT NULL CHECK (interval_code='1'),
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    revision integer NOT NULL CHECK (revision>0),
    close_price text NOT NULL CHECK (close_price ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    -- Raw provenance: the hash of the seven provider fields of the row that first carried this
    -- revision. A revision is decided on close_price (the price value), not on this hash, so a
    -- volume- or turnover-only difference never becomes a new revision.
    evidence_hash text NOT NULL CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    policy_version text NOT NULL,
    -- The Bybit reference time of the attempt that observed this candle. Closure is judged
    -- against it: only candles with start_at + 1 minute <= observed_at are ever stored.
    observed_at timestamptz NOT NULL,
    -- The kline request window ([start, end], both candle starts) that returned this revision.
    request_start_at timestamptz NOT NULL,
    request_end_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    UNIQUE(provider,category,symbol,interval_code,start_at,revision),
    CHECK (date_trunc('minute',start_at)=start_at),
    CHECK (end_at=start_at+interval '1 minute'),
    CHECK (end_at<=observed_at),
    CHECK (date_trunc('minute',request_start_at)=request_start_at),
    CHECK (date_trunc('minute',request_end_at)=request_end_at),
    CHECK (start_at BETWEEN request_start_at AND request_end_at),
    CHECK (request_end_at<=request_start_at+interval '999 minutes')
);

CREATE TABLE sync_cashflow_conversion_job (
    job_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    classification_id uuid NOT NULL
        REFERENCES sync_cashflow_classification_bundle(classification_id) ON DELETE CASCADE,
    input_scope_hash text NOT NULL CHECK (input_scope_hash ~ '^[0-9a-f]{64}$'),
    flow_count integer NOT NULL CHECK (flow_count>=0),
    policy_version text NOT NULL,
    calculation_policy_version text NOT NULL,
    idempotency_key text NOT NULL,
    status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','CONVERTED','CONVERTED_ESTIMATED',
        'CONVERSION_REQUIRED','REVIEW_REQUIRED','CANCELLED')),
    failure_code text,
    retryable boolean NOT NULL DEFAULT false,
    reference_time timestamptz,
    conversion_id uuid,
    fencing_generation bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    heartbeat_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    last_checked_at timestamptz,
    finished_at timestamptz,
    UNIQUE(user_id,intent_id,idempotency_key),
    CHECK (NOT retryable OR status='CONVERSION_REQUIRED'),
    CHECK (failure_code IS NULL OR failure_code IN ('PRICE_PROVIDER_UNAVAILABLE','PRICE_CANDLE_NOT_CLOSED',
        'PRICE_ROUTE_UNAVAILABLE','PRICE_CANDLE_MISSING','PRICE_NON_POSITIVE','PRICE_RESPONSE_MALFORMED',
        'PRICE_CANDLE_CONFLICT','CALCULATION_RANGE_EXCEEDED','INPUT_INVALID','NOT_PERMITTED',
        'CONVERSION_INTERRUPTED')),
    -- Lifecycle consistency. In progress: no result, no finish time; a running attempt holds a
    -- lease. Finished: a finish time and no lease. A result bundle exists exactly for the published
    -- outcomes; the two result-less endings are a cancellation and an interrupted attempt.
    CHECK (status NOT IN ('QUEUED','RUNNING')
        OR (finished_at IS NULL AND conversion_id IS NULL AND failure_code IS NULL AND NOT retryable)),
    CHECK (status<>'QUEUED' OR lease_until IS NULL),
    CHECK (status<>'RUNNING' OR lease_until IS NOT NULL),
    CHECK (status IN ('QUEUED','RUNNING') OR (finished_at IS NOT NULL AND lease_until IS NULL)),
    CHECK (status NOT IN ('CONVERTED','CONVERTED_ESTIMATED') OR (conversion_id IS NOT NULL AND failure_code IS NULL)),
    CHECK (status<>'REVIEW_REQUIRED' OR (conversion_id IS NOT NULL AND failure_code IS NOT NULL)),
    CHECK (status<>'CONVERSION_REQUIRED' OR (failure_code IS NOT NULL
        AND (conversion_id IS NOT NULL OR failure_code='CONVERSION_INTERRUPTED'))),
    CHECK (status<>'CANCELLED' OR (conversion_id IS NULL AND failure_code='NOT_PERMITTED'))
);
CREATE INDEX sync_cashflow_conversion_job_result_idx ON sync_cashflow_conversion_job(conversion_id);
CREATE INDEX sync_cashflow_conversion_job_classification_idx ON sync_cashflow_conversion_job(classification_id);
CREATE UNIQUE INDEX sync_cashflow_conversion_one_active_idx
    ON sync_cashflow_conversion_job(user_id,classification_id)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX sync_cashflow_conversion_queue_idx
    ON sync_cashflow_conversion_job(created_at)
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX sync_cashflow_conversion_job_user_idx
    ON sync_cashflow_conversion_job(user_id,intent_id,created_at DESC);

-- The immutable inputs of one attempt, copied from the COMPLETE classification at admission and
-- re-verified against it (input_scope_hash) at claim and at publication.
CREATE TABLE sync_cashflow_conversion_input (
    job_id uuid NOT NULL REFERENCES sync_cashflow_conversion_job(job_id) ON DELETE CASCADE,
    source_event_id uuid NOT NULL REFERENCES sync_initial_source_event(event_id) ON DELETE RESTRICT,
    source_id text NOT NULL,
    source_revision integer NOT NULL CHECK (source_revision>0),
    semantic_hash text NOT NULL CHECK (semantic_hash ~ '^[0-9a-f]{64}$'),
    provenance_connection_id uuid NOT NULL,
    classification_verdict text NOT NULL CHECK (classification_verdict IN ('EXTERNAL_IN','EXTERNAL_OUT')),
    classification_rule_id text NOT NULL,
    classification_policy_version text NOT NULL,
    asset_code text NOT NULL,
    signed_quantity text NOT NULL CHECK (signed_quantity ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY(job_id,source_event_id),
    CHECK ((classification_verdict='EXTERNAL_IN') = (signed_quantity !~ '^-'))
);
CREATE INDEX sync_cashflow_conversion_input_event_idx ON sync_cashflow_conversion_input(source_event_id);

CREATE TABLE sync_cashflow_conversion_bundle (
    conversion_id uuid PRIMARY KEY,
    created_by_job_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    portfolio_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    classification_id uuid NOT NULL
        REFERENCES sync_cashflow_classification_bundle(classification_id) ON DELETE CASCADE,
    input_scope_hash text NOT NULL CHECK (input_scope_hash ~ '^[0-9a-f]{64}$'),
    policy_version text NOT NULL,
    calculation_policy_version text NOT NULL,
    outcome_fingerprint text NOT NULL CHECK (outcome_fingerprint ~ '^[0-9a-f]{64}$'),
    status text NOT NULL CHECK (status IN ('CONVERTED','CONVERTED_ESTIMATED','CONVERSION_REQUIRED','REVIEW_REQUIRED')),
    reason_code text,
    retryable boolean NOT NULL,
    calculable boolean NOT NULL,
    estimated boolean NOT NULL,
    flow_count integer NOT NULL CHECK (flow_count>=0),
    converted_count integer NOT NULL CHECK (converted_count>=0),
    estimated_count integer NOT NULL CHECK (estimated_count>=0),
    conversion_required_count integer NOT NULL CHECK (conversion_required_count>=0),
    review_count integer NOT NULL CHECK (review_count>=0),
    reference_time timestamptz,
    previous_conversion_id uuid
        REFERENCES sync_cashflow_conversion_bundle(conversion_id) ON DELETE CASCADE,
    change_reason text CHECK (change_reason IN ('PRICE_REVISION','POLICY_CHANGE','EVIDENCE_CHANGE')),
    processed_at timestamptz NOT NULL DEFAULT date_trunc('milliseconds',now()),
    UNIQUE(user_id,classification_id,policy_version,outcome_fingerprint),
    UNIQUE(conversion_id,calculable),
    CHECK (flow_count=converted_count+estimated_count+conversion_required_count+review_count),
    CHECK (calculable = (status IN ('CONVERTED','CONVERTED_ESTIMATED'))),
    CHECK (calculable = (conversion_required_count=0 AND review_count=0)),
    CHECK (estimated = (estimated_count>0)),
    CHECK (status<>'CONVERTED' OR estimated_count=0),
    CHECK (status<>'CONVERTED_ESTIMATED' OR estimated_count>0),
    CHECK (status<>'CONVERSION_REQUIRED' OR (review_count=0 AND conversion_required_count>0)),
    CHECK (status<>'REVIEW_REQUIRED' OR review_count>0),
    CHECK ((status IN ('CONVERTED','CONVERTED_ESTIMATED')) = (reason_code IS NULL)),
    CHECK (NOT retryable OR status='CONVERSION_REQUIRED'),
    CHECK ((previous_conversion_id IS NULL) = (change_reason IS NULL)),
    CHECK (reason_code IS NULL OR reason_code IN ('PRICE_PROVIDER_UNAVAILABLE','PRICE_CANDLE_NOT_CLOSED',
        'PRICE_ROUTE_UNAVAILABLE','PRICE_CANDLE_MISSING','PRICE_NON_POSITIVE','PRICE_RESPONSE_MALFORMED',
        'PRICE_CANDLE_CONFLICT','CALCULATION_RANGE_EXCEEDED','INPUT_INVALID'))
);
CREATE INDEX sync_cashflow_conversion_bundle_user_idx
    ON sync_cashflow_conversion_bundle(user_id,classification_id,processed_at DESC);
CREATE INDEX sync_cashflow_conversion_bundle_classification_idx
    ON sync_cashflow_conversion_bundle(classification_id);
CREATE INDEX sync_cashflow_conversion_bundle_previous_idx
    ON sync_cashflow_conversion_bundle(previous_conversion_id);

ALTER TABLE sync_cashflow_conversion_job
    ADD CONSTRAINT sync_cashflow_conversion_job_result_fk
    FOREIGN KEY(conversion_id) REFERENCES sync_cashflow_conversion_bundle(conversion_id);

CREATE TABLE sync_cashflow_conversion_flow (
    conversion_id uuid NOT NULL REFERENCES sync_cashflow_conversion_bundle(conversion_id) ON DELETE CASCADE,
    source_event_id uuid NOT NULL REFERENCES sync_initial_source_event(event_id) ON DELETE RESTRICT,
    source_id text NOT NULL,
    semantic_hash text NOT NULL CHECK (semantic_hash ~ '^[0-9a-f]{64}$'),
    classification_rule_id text NOT NULL,
    classification_policy_version text NOT NULL,
    asset_code text NOT NULL,
    signed_quantity text NOT NULL CHECK (signed_quantity ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    occurred_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('CONVERTED','CONVERTED_ESTIMATED','CONVERSION_REQUIRED','REVIEW_REQUIRED')),
    reason_code text,
    retryable boolean NOT NULL,
    evidence_type text CHECK (evidence_type IN ('DIRECT_NORMALIZATION','SPOT_KLINE_CLOSE')),
    route_id text,
    usd_unit_price text CHECK (usd_unit_price IS NULL
        OR usd_unit_price ~ '^(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    usd_value text CHECK (usd_value IS NULL OR usd_value ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    estimated boolean NOT NULL,
    PRIMARY KEY(conversion_id,source_event_id),
    CHECK ((status IN ('CONVERTED','CONVERTED_ESTIMATED')) = (usd_value IS NOT NULL)),
    CHECK ((status IN ('CONVERTED','CONVERTED_ESTIMATED')) = (reason_code IS NULL)),
    CHECK (status<>'CONVERTED' OR (evidence_type='DIRECT_NORMALIZATION' AND NOT estimated)),
    CHECK (status<>'CONVERTED_ESTIMATED'
        OR (evidence_type='SPOT_KLINE_CLOSE' AND estimated AND usd_unit_price IS NOT NULL)),
    CHECK (status IN ('CONVERTED','CONVERTED_ESTIMATED')
        OR (evidence_type IS NULL AND route_id IS NULL AND usd_unit_price IS NULL AND NOT estimated)),
    CHECK (usd_value IS NULL OR ((usd_value ~ '^-') = (signed_quantity ~ '^-'))),
    CHECK (NOT retryable OR status='CONVERSION_REQUIRED'),
    CHECK (reason_code IS NULL OR reason_code IN ('PRICE_PROVIDER_UNAVAILABLE','PRICE_CANDLE_NOT_CLOSED',
        'PRICE_ROUTE_UNAVAILABLE','PRICE_CANDLE_MISSING','PRICE_NON_POSITIVE','PRICE_RESPONSE_MALFORMED',
        'PRICE_CANDLE_CONFLICT','CALCULATION_RANGE_EXCEEDED','INPUT_INVALID'))
);
CREATE INDEX sync_cashflow_conversion_flow_event_idx ON sync_cashflow_conversion_flow(source_event_id);

CREATE TABLE sync_cashflow_conversion_leg (
    conversion_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    leg_index integer NOT NULL CHECK (leg_index IN (0,1)),
    symbol text NOT NULL,
    base_asset text NOT NULL,
    quote_asset text NOT NULL,
    instrument_observation_id uuid NOT NULL
        REFERENCES sync_market_instrument_observation(observation_id) ON DELETE RESTRICT,
    candle_id uuid NOT NULL REFERENCES sync_market_candle(candle_id) ON DELETE RESTRICT,
    candle_start_at timestamptz NOT NULL,
    candle_end_at timestamptz NOT NULL,
    close_price text NOT NULL CHECK (close_price ~ '^(0|[1-9][0-9]*)([.][0-9]*[1-9])?$' AND close_price<>'0'),
    selection_rule text NOT NULL CHECK (selection_rule IN ('EVENT_MINUTE','PRIOR_CLOSED_MINUTE')),
    lag_minutes integer NOT NULL CHECK (lag_minutes BETWEEN 0 AND 5),
    PRIMARY KEY(conversion_id,source_event_id,leg_index),
    FOREIGN KEY(conversion_id,source_event_id)
        REFERENCES sync_cashflow_conversion_flow(conversion_id,source_event_id) ON DELETE CASCADE,
    CHECK ((selection_rule='EVENT_MINUTE') = (lag_minutes=0)),
    CHECK (candle_end_at=candle_start_at+interval '1 minute')
);
CREATE INDEX sync_cashflow_conversion_leg_candle_idx ON sync_cashflow_conversion_leg(candle_id);
CREATE INDEX sync_cashflow_conversion_leg_instrument_idx ON sync_cashflow_conversion_leg(instrument_observation_id);

-- Story 2.7 consumes only this table. A row can exist only under a calculable bundle: the
-- composite key (conversion_id, calculable) with calculable fixed to true references the
-- bundle's (conversion_id, calculable) pair, so a non-calculable bundle cannot own any row.
CREATE TABLE sync_calculable_cashflow (
    conversion_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    calculable boolean NOT NULL DEFAULT true CHECK (calculable),
    user_id uuid NOT NULL REFERENCES identity_account(user_id) ON DELETE RESTRICT,
    classification_id uuid NOT NULL,
    source_id text NOT NULL,
    semantic_hash text NOT NULL CHECK (semantic_hash ~ '^[0-9a-f]{64}$'),
    asset_code text NOT NULL,
    signed_quantity text NOT NULL CHECK (signed_quantity ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    occurred_at timestamptz NOT NULL,
    usd_value text NOT NULL CHECK (usd_value ~ '^-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?$'),
    estimated boolean NOT NULL,
    evidence_type text NOT NULL CHECK (evidence_type IN ('DIRECT_NORMALIZATION','SPOT_KLINE_CLOSE')),
    price_policy_version text NOT NULL,
    calculation_policy_version text NOT NULL,
    PRIMARY KEY(conversion_id,source_event_id),
    FOREIGN KEY(conversion_id,calculable)
        REFERENCES sync_cashflow_conversion_bundle(conversion_id,calculable) ON DELETE CASCADE,
    FOREIGN KEY(conversion_id,source_event_id)
        REFERENCES sync_cashflow_conversion_flow(conversion_id,source_event_id) ON DELETE CASCADE,
    CHECK ((usd_value ~ '^-') = (signed_quantity ~ '^-')),
    CHECK (estimated = (evidence_type='SPOT_KLINE_CLOSE'))
);
CREATE INDEX sync_calculable_cashflow_user_idx ON sync_calculable_cashflow(user_id,occurred_at);
