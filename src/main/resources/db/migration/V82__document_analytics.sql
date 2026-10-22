-- No FK to product rows: analytics retries retain the original actor and target after deletion.
CREATE TABLE analytics_outbox (
    event_id uuid PRIMARY KEY,
    event_name text NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    dead_at timestamptz
);
CREATE INDEX analytics_outbox_pending ON analytics_outbox(next_attempt_at) WHERE sent_at IS NULL AND dead_at IS NULL;
CREATE TABLE analytics_query_terminals (
    query_id text PRIMARY KEY,
    event_name text NOT NULL CHECK (event_name IN ('query_answered', 'query_failed')),
    occurred_at timestamptz NOT NULL
);
CREATE TABLE analytics_run_context (
    run_id text PRIMARY KEY,
    actor_id text NOT NULL,
    workspace_id text NOT NULL,
    requested_at timestamptz NOT NULL,
    context jsonb NOT NULL
);
-- Product provenance remains available even while ANALYTICS_ENABLED=false.
CREATE TABLE chat_export_links (
    export_id uuid PRIMARY KEY,
    document_id varchar(255) NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id text NOT NULL,
    conversation_id text NOT NULL,
    mode text NOT NULL CHECK (mode IN ('all','selected')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX chat_export_links_document ON chat_export_links(document_id, created_at DESC);
-- Canonical lineage handles converted parts -> converted document -> uploaded PDF.
CREATE VIEW analytics_document_origins AS
WITH RECURSIVE lineage AS (
    SELECT id AS document_id, id AS original_id, source_document_id, workspace_id, ARRAY[id::text] AS visited FROM documents
    UNION ALL
    SELECT l.document_id, d.id, d.source_document_id, l.workspace_id, l.visited || d.id
    FROM lineage l JOIN documents d ON d.id = l.source_document_id AND d.workspace_id = l.workspace_id
    WHERE NOT d.id = ANY(l.visited)
)
SELECT DISTINCT ON (document_id) document_id, original_id, workspace_id
FROM lineage ORDER BY document_id, cardinality(visited) DESC;
-- Includes pre-deployment and teammates' successes; re-ingest never resets the minimum.
CREATE VIEW analytics_first_ingests AS
SELECT o.original_id AS document_id, o.workspace_id, min(l.completed_at) AS first_ingested_at
FROM ai_operation_logs l JOIN analytics_document_origins o ON o.document_id = l.target_document_id
WHERE l.operation_type = 'ingest' AND l.status = 'succeeded' AND l.completed_at IS NOT NULL
GROUP BY o.original_id, o.workspace_id;
