-- 인덱스가 없던 FK 컬럼에 인덱스를 건다. PostgreSQL은 FK 참조 컬럼에 인덱스를 자동으로 만들지 않아, 부모 행을 지울 때마다
-- 자식 테이블 전체를 훑어 FK를 검사한다(휴지통 영구 삭제, 워크스페이스 파기, 채팅 세션 삭제). 하위 폴더 트리 조회도 같은 컬럼으로
-- 조인한다. (workspace_id, folder_id ...)처럼 FK 컬럼이 복합 인덱스의 둘째 이후에만 있으면 이 검사에 쓰이지 않는다.
-- 대부분 NULL인 컬럼은 값이 있는 행만 담는 부분 인덱스로 작게 만든다. FK 검사는 col = $1이라 이 부분 인덱스를 쓴다.
-- 제외: document_convert_queue.source_document_id(변환이 끝나거나 실패하면 행을 지우는 대기열이라 늘 작다).
--
-- 운영 테이블에 거는 인덱스라 CONCURRENTLY로 만들어 쓰기를 막지 않는다. CONCURRENTLY는 트랜잭션 안에서 못 돌므로
-- 이 파일은 CONCURRENTLY 문만 담아 Flyway가 트랜잭션 없이 실행한다(V87·V89·V91과 같은 방식, 다른 DDL을 섞지 않는다).
-- lock_timeout은 세션 설정이라 끝에서 되돌린다. CONCURRENTLY는 먼저 열린 트랜잭션이 끝나길 기다리므로 120s로 넉넉히 둔다.
--
-- 실패 복구(수동): CONCURRENTLY가 중간에 실패하면 INVALID 인덱스가 남고, IF NOT EXISTS 재실행은 그것을 건너뛴다.
--   1) SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid;
--   2) 이 파일의 인덱스가 보이면 DROP INDEX CONCURRENTLY IF EXISTS <이름>;
--   3) flyway repair로 실패 기록을 지운 뒤 migrate를 다시 돌린다.
SET lock_timeout = '120s';

-- 폴더 하위 트리의 문서 일괄 삭제·복구(folder_id IN subtree), 휴지통 폴더 영구 삭제의 문서 존재 확인과 FK 검사.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_documents_folder ON documents (folder_id);
-- 하위 폴더 재귀 조회(parent_folder_id = s.id)와 폴더 삭제 시 하위 폴더 CASCADE.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_folders_parent ON folders (parent_folder_id);

-- 문서 영구 삭제 시 FK 검사(SET NULL·CASCADE).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cmr_document ON chat_message_references (document_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cpw_document ON chat_partial_wiki (document_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_sessions_wiki_export_document
    ON chat_sessions (wiki_export_document_id) WHERE wiki_export_document_id IS NOT NULL;

-- 세션의 부분 위키 조회(ChatPartialWikiRepository.findAllBySessionId)와 세션 삭제 시 CASCADE.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cpw_session ON chat_partial_wiki (session_id);

-- 워크스페이스 파기에서 작업 로그를 지울 때 FK 검사, 파기 단계의 operation_id·deactivated_by 조회.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_document_content_versions_operation ON document_content_versions (operation_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wiki_page_versions_operation ON wiki_page_versions (operation_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wiki_page_contributions_deactivated_by
    ON wiki_page_contributions (deactivated_by) WHERE deactivated_by IS NOT NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wiki_page_contributions_lint_operation
    ON wiki_page_contributions (lint_operation_id) WHERE lint_operation_id IS NOT NULL;
-- uk_ai_operation_logs_restore_token은 restore 작업만 담는 부분 인덱스라 FK 검사에 쓰이지 않는다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_operation_logs_restored_from
    ON ai_operation_logs (restored_from) WHERE restored_from IS NOT NULL;

RESET lock_timeout;
