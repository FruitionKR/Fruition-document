-- 문서 영구 삭제(DocumentCopyScrubber)가 원문을 비운 채팅 인용의 변경 기록을 행 id로 찾는다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_task_changes_citation ON ai_task_changes ((row_key->>'id')) WHERE table_name = 'chat_message_references';

RESET lock_timeout;
