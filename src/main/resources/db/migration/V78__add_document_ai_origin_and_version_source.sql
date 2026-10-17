-- 문서를 처음 만든 AI 경로(agent | meeting_notes). 복제본은 원본의 AI 출처(chat_export 포함)를 물려받는다.
-- chat_export 원본은 origin으로 알 수 있어 넣지 않는다. 기존 문서는 근거가 없어 NULL로 둔다.
ALTER TABLE documents
    ADD COLUMN ai_origin VARCHAR(32);

-- 버전을 저장한 경로(user | agent | meeting_notes | restore). 문서의 AI 변경 표시(last_ai_change)가 이 값을 읽는다.
-- 기존 버전은 operation_id·restored_from_version으로 알 수 있는 것만 채우고 나머지는 NULL로 둔다.
ALTER TABLE document_content_versions
    ADD COLUMN source VARCHAR(32);

UPDATE document_content_versions SET source = 'agent' WHERE operation_id IS NOT NULL;
UPDATE document_content_versions SET source = 'restore'
WHERE restored_from_version IS NOT NULL AND source IS NULL;
