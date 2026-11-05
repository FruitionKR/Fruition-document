-- 문서 영구 삭제 뒤 소스 페이지 버전의 변경 기록을 지울 때(DocumentCopyScrubber.scrubSourcePages) 페이지로 찾는다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_task_changes_wiki_version_page ON ai_task_changes ((row_key->>'page_id')) WHERE table_name = 'wiki_page_versions';

RESET lock_timeout;
