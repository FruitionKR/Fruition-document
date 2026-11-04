-- 문서 영구 삭제(DocumentCopyScrubber)가 ai_task_runs.command의 문서 id로 그 문서의 실행을 찾는다.
-- 문서 행을 잠근 트랜잭션에서 도는 조회라 표를 훑으면 잠금이 길어진다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_task_runs_command_document ON ai_task_runs ((command->>'document_id')) WHERE command->>'document_id' IS NOT NULL;

RESET lock_timeout;
