-- 휴지통 이동이 위키 정리를 요청하지 않던 시기에 삭제된 문서도 AI 위키에서 빠지도록 정리 command를 한 번 발행한다.
-- topic은 app.processing.command-topic 기본값이다. INGEST_COMMAND_TOPIC을 바꾼 환경은 적용 전에 맞춰야 한다.
-- 편집 문서는 위키에서 빠지므로 미편입 상태로 되돌린다. 복구하면 다시 편입해야 한다.
-- 로그 되돌리기가 삭제한 문서의 위키를 다시 살리지 않도록 그 문서의 기여도 끈다.
WITH trashed AS (
    SELECT id, workspace_id, gen_random_uuid()::text AS run_id
    FROM documents
    WHERE deleted_at IS NOT NULL
)
INSERT INTO ai_command_outbox(id, run_id, topic, message_key, payload, created_at)
SELECT gen_random_uuid()::text,
       run_id,
       'ai.ingest.command',
       id,
       jsonb_build_object(
           'run_id', run_id,
           'kind', 'document_deleted',
           'document_id', id,
           'workspace_id', workspace_id
       )::text,
       now()
FROM trashed;

UPDATE documents
SET status = 'uploaded'
WHERE deleted_at IS NOT NULL
  AND document_role = 'EDITABLE'
  AND status <> 'uploaded';

UPDATE wiki_page_contributions contribution
SET active = false
FROM documents document
WHERE contribution.source_document_id = document.id
  AND document.deleted_at IS NOT NULL
  AND contribution.active = true;
