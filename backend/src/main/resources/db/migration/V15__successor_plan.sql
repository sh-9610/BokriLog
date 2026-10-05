-- Story 2.15: sync owns baseline preparation; plan owns predecessor lineage.
-- Cross-module UUIDs are validated through application ports, without new cross-module foreign keys.
ALTER TABLE sync_job ADD COLUMN purpose text NOT NULL DEFAULT 'FIRST_SNAPSHOT'
    CHECK (purpose IN ('FIRST_SNAPSHOT','NEXT_PLAN_BASELINE'));
ALTER TABLE sync_job ADD CONSTRAINT sync_job_baseline_owner_key UNIQUE(job_id,user_id);
CREATE TABLE sync_next_plan_baseline (
    job_id uuid PRIMARY KEY REFERENCES sync_job(job_id) ON DELETE RESTRICT,
    user_id uuid NOT NULL,
    portfolio_id uuid NOT NULL,
    predecessor_plan_id uuid NOT NULL,
    terminal_at timestamptz NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    completed_at timestamptz,
    portfolio_snapshot_id uuid,
    source_snapshot_id uuid,
    current boolean NOT NULL DEFAULT false,
    FOREIGN KEY(job_id,user_id) REFERENCES sync_job(job_id,user_id) ON DELETE RESTRICT,
    CHECK (requested_at > terminal_at),
    CHECK ((completed_at IS NULL AND portfolio_snapshot_id IS NULL AND source_snapshot_id IS NULL AND NOT current)
        OR (completed_at > terminal_at AND portfolio_snapshot_id IS NOT NULL AND source_snapshot_id IS NOT NULL))
);
CREATE UNIQUE INDEX sync_next_plan_baseline_current_key ON sync_next_plan_baseline(user_id) WHERE current;
CREATE INDEX sync_next_plan_baseline_user_idx ON sync_next_plan_baseline(user_id);
ALTER TABLE plan_condition_confirmation ADD COLUMN predecessor_plan_id uuid;
ALTER TABLE plan_condition_confirmation ADD COLUMN baseline_job_id uuid;
ALTER TABLE plan_condition_intent ADD COLUMN predecessor_plan_id uuid;
ALTER TABLE plan_condition_intent ADD COLUMN baseline_job_id uuid;
ALTER TABLE plan_definition ADD COLUMN predecessor_plan_id uuid;
ALTER TABLE plan_definition ADD COLUMN baseline_job_id uuid;
ALTER TABLE plan_definition ADD CONSTRAINT plan_predecessor_owner_key UNIQUE(plan_id,user_id,portfolio_id);
ALTER TABLE plan_definition ADD CONSTRAINT plan_predecessor_fk
    FOREIGN KEY(predecessor_plan_id,user_id,portfolio_id)
    REFERENCES plan_definition(plan_id,user_id,portfolio_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plan_definition ADD CONSTRAINT plan_successor_pair
    CHECK ((predecessor_plan_id IS NULL) = (baseline_job_id IS NULL));
ALTER TABLE plan_condition_intent ADD CONSTRAINT plan_intent_successor_pair
    CHECK ((predecessor_plan_id IS NULL) = (baseline_job_id IS NULL));
ALTER TABLE plan_condition_confirmation ADD CONSTRAINT plan_confirmation_successor_pair
    CHECK ((predecessor_plan_id IS NULL) = (baseline_job_id IS NULL));
