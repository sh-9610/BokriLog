-- Story 2.16: revoking a Bybit connection while keeping every plan record read-only (ERD-1.0.0 §5
-- SECURITY-ACTION-1.0, RECONNECT-1.0).
--
-- Expand only. V1~V15 are not modified. V4 already allowed `source_connection.status` to be ACTIVE or REVOKED and said
-- that revocation (this story) moves it; this file adds the facts of that move to the same row: the revocation time
-- (the database clock, to the millisecond) and the idempotency key with the request hash of the confirmation that made
-- it. The row itself stays — that is what keeps the records: `portfolio_source_membership.connection_id` is
-- ON DELETE CASCADE and the snapshot components cite the membership, so deleting the row would take the plan's
-- provenance with it. Revoking deletes only the credential row (`source_credential`), and every existing re-check that
-- joins the credential (`SourceConnectionRepository.snapshotConnection`) closes by itself.
--
-- The pair check makes the state and its facts one: an ACTIVE row has none of the three, a REVOKED row has all three.
-- A row cannot be REVOKED without a time, and cannot go back to ACTIVE while it keeps one. The partial unique index is
-- the first layer of idempotency: one key names one revocation of the user's, and a replay with another request is a
-- conflict. The grant's `(user_id, operation_id)` is the second layer.
--
-- Account erasure removes these facts with the row (`SourceConnectionRepository.eraseOf`); nothing else deletes them.

ALTER TABLE source_connection ADD COLUMN revoked_at timestamptz;
ALTER TABLE source_connection ADD COLUMN revocation_idempotency_key text;
ALTER TABLE source_connection ADD COLUMN revocation_request_hash text;

-- V4 already allowed REVOKED. A row that reached it before this file has none of the facts: it gets them here, before
-- the pair check below, from values the row itself determines — the later of its creation and verification rounded up
-- one millisecond, a key and a hash derived from its id by SQL functions (no key-shaped literal) — and loses any
-- credential row, as a revocation does.
DELETE FROM source_credential WHERE connection_id IN (SELECT connection_id FROM source_connection
    WHERE status = 'REVOKED');
UPDATE source_connection SET
    revoked_at = date_trunc('milliseconds', GREATEST(created_at, verified_at)) + interval '1 millisecond',
    revocation_idempotency_key = concat_ws('-', 'migrated', md5(connection_id::text)),
    revocation_request_hash = encode(sha256(convert_to(concat_ws('|', 'migrated', connection_id::text), 'UTF8')),
        'hex')
WHERE status = 'REVOKED' AND revoked_at IS NULL;

ALTER TABLE source_connection ADD CONSTRAINT source_connection_revocation_pair_check CHECK (
    (status = 'ACTIVE' AND revoked_at IS NULL AND revocation_idempotency_key IS NULL
        AND revocation_request_hash IS NULL)
    OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_idempotency_key IS NOT NULL
        AND revocation_request_hash IS NOT NULL));

-- The idempotency key's bounds are the HTTP surface's (16~128 characters), the hash a lower-case SHA-256.
ALTER TABLE source_connection ADD CONSTRAINT source_connection_revocation_key_length_check
    CHECK (revocation_idempotency_key IS NULL OR octet_length(revocation_idempotency_key) BETWEEN 16 AND 128);
ALTER TABLE source_connection ADD CONSTRAINT source_connection_revocation_hash_check
    CHECK (revocation_request_hash IS NULL OR revocation_request_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX source_connection_revocation_key
    ON source_connection (user_id, revocation_idempotency_key)
    WHERE revocation_idempotency_key IS NOT NULL;
