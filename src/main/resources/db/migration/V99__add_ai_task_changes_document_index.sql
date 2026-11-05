-- 문서 영구 삭제(DocumentCopyScrubber)가 ai_task_changes에서 문서 변경 기록을 찾는다. 표현식은 DocumentCopyScrubber.CHANGE_DOCUMENT_ID와 같아야 한다.
-- 문서 행을 잠근 트랜잭션에서 도는 조회라 표를 훑으면 잠금이 길어진다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_task_changes_document ON ai_task_changes ((CASE table_name WHEN 'documents' THEN row_key->>'id' ELSE row_key->>'document_id' END)) WHERE table_name IN ('documents', 'document_content_versions', 'document_edit_states', 'document_edit_writes');

RESET lock_timeout;
