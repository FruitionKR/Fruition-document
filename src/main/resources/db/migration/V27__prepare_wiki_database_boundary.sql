-- Wiki 현재 상태를 ai_db로 옮기기 전에 DB 경계를 넘는 물리 FK를 논리 ID로 전환한다.
ALTER TABLE chat_partial_wiki DROP CONSTRAINT IF EXISTS fk_cpw_wiki_page;
ALTER TABLE chat_sessions DROP CONSTRAINT IF EXISTS fk_chat_sessions_wiki_page;
ALTER TABLE chat_messages DROP CONSTRAINT IF EXISTS fk_chat_messages_wiki_page;
ALTER TABLE chat_message_related_pages DROP CONSTRAINT IF EXISTS fk_cmrp_wiki_page;
ALTER TABLE wiki_page_versions DROP CONSTRAINT IF EXISTS fk_wiki_page_versions_page;
ALTER TABLE wiki_page_contributions DROP CONSTRAINT IF EXISTS fk_wiki_page_contributions_page;

ALTER TABLE document_wiki_links DROP CONSTRAINT IF EXISTS fk_dwl_document;
ALTER TABLE source_blocks DROP CONSTRAINT IF EXISTS fk_source_blocks_document;
ALTER TABLE pipeline_runs DROP CONSTRAINT IF EXISTS pipeline_runs_document_id_fkey;
ALTER TABLE wiki_embedding_units DROP CONSTRAINT IF EXISTS wiki_embedding_units_source_document_id_fkey;
ALTER TABLE wiki_page_contributions DROP CONSTRAINT IF EXISTS fk_wiki_page_contributions_source_document;

-- AI가 documents를 JOIN하지 않아도 workspace 범위를 판단할 수 있게 실행 actor를 보존한다.
ALTER TABLE pipeline_runs ADD COLUMN IF NOT EXISTS user_id varchar(255);
ALTER TABLE pipeline_runs ADD COLUMN IF NOT EXISTS workspace_id varchar(255);

UPDATE pipeline_runs run
SET user_id = document.user_id,
    workspace_id = document.workspace_id
FROM documents document
WHERE run.document_id = document.id
  AND (run.user_id IS NULL OR run.workspace_id IS NULL);

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_workspace_status
    ON pipeline_runs (workspace_id, status, created_at DESC);
