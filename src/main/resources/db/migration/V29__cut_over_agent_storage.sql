CREATE TABLE agent_apply_projections (
    run_id text PRIMARY KEY,
    workspace_id text NOT NULL,
    user_id text NOT NULL,
    document_id text NOT NULL,
    base_version bigint NOT NULL,
    apply_operation_id text NOT NULL UNIQUE,
    status text NOT NULL DEFAULT 'queued',
    result jsonb,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    apply_consumed_at timestamptz,
    CONSTRAINT agent_apply_projections_status_check
        CHECK (status IN ('queued', 'ready', 'failed', 'consumed'))
);

CREATE INDEX idx_agent_apply_projections_actor
    ON agent_apply_projections (workspace_id, user_id, run_id);

CREATE UNIQUE INDEX uq_ai_task_agent_terminal
    ON ai_task_result_receipts (run_id, task_kind)
    WHERE task_kind = 'agent';
