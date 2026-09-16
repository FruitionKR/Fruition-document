-- 이름 중복은 위치·요청 경로와 무관하게 워크스페이스 전체에서 금지한다.
-- 기존 중복은 검토 후 정리해야 한다. 이 migration은 사용자 이름을 임의로 바꾸지 않는다.
CREATE UNIQUE INDEX uq_documents_active_name
    ON documents (workspace_id, lower(normalize(btrim(filename), NFC)))
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_folders_active_name
    ON folders (workspace_id, lower(normalize(btrim(name), NFC)))
    WHERE deleted_at IS NULL;
