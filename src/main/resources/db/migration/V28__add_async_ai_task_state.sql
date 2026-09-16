CREATE TABLE IF NOT EXISTS ai_task_result_receipts (
    event_id text PRIMARY KEY,
    run_id text NOT NULL,
    task_kind text NOT NULL,
    event_payload jsonb,
    received_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_task_query_terminal
    ON ai_task_result_receipts (run_id, task_kind)
    WHERE task_kind = 'query';

ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS document_id text,
    ADD COLUMN IF NOT EXISTS base_version bigint,
    ADD COLUMN IF NOT EXISTS apply_operation_id text,
    ADD COLUMN IF NOT EXISTS apply_consumed_at timestamptz,
    ADD COLUMN IF NOT EXISTS result jsonb;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_runs_apply_operation
    ON agent_runs (apply_operation_id)
    WHERE apply_operation_id IS NOT NULL;
