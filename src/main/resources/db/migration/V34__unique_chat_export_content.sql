-- 기존 active 중복은 id가 가장 작은 행만 canonical로 남긴다.
-- duplicate는 삭제하지 않고 soft-delete하여 모든 FK/논리 참조와 child 행을 보존한다.
CREATE TEMP TABLE chat_export_document_dedup (
    duplicate_id varchar(255) PRIMARY KEY,
    canonical_id varchar(255) NOT NULL
) ON COMMIT DROP;

INSERT INTO chat_export_document_dedup (duplicate_id, canonical_id)
SELECT duplicate.id, canonical.id
FROM documents duplicate
JOIN LATERAL (
    SELECT d.id
    FROM documents d
    WHERE d.workspace_id = duplicate.workspace_id
      AND d.content_hash = duplicate.content_hash
      AND d.selection_mode = duplicate.selection_mode
      AND d.origin = 'chat_export'
      AND d.deleted_at IS NULL
    ORDER BY d.id
    LIMIT 1
) canonical ON canonical.id <> duplicate.id
WHERE duplicate.origin = 'chat_export'
  AND duplicate.deleted_at IS NULL;

-- 참조를 재작성하면 child의 document_id PK/UNIQUE가 충돌할 수 있고, 삭제하면
-- ON DELETE CASCADE/SET NULL로 원본 child와 source_document_id가 사라진다.
UPDATE documents d
SET deleted_at = COALESCE(d.deleted_at, now()),
    deleted_by = COALESCE(d.deleted_by, 'migration:v34')
FROM chat_export_document_dedup c
WHERE d.id = c.duplicate_id;

-- 일반 문서는 같은 본문을 허용하되, active chat export만 workspace·본문·선택 모드별로 하나만 허용한다.
CREATE UNIQUE INDEX uq_documents_chat_export_workspace_hash_mode
    ON documents(workspace_id, content_hash, selection_mode)
    WHERE origin = 'chat_export' AND deleted_at IS NULL;
