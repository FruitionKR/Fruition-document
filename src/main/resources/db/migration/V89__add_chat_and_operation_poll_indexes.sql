-- 채팅·작업 알림 조회가 테이블 전체를 훑지 않게 한다. 지금은 이 테이블들에 PK 외 조회 인덱스가 없다.
--
-- 운영 테이블에 거는 인덱스라 CONCURRENTLY로 만들어 쓰기를 막지 않는다. CONCURRENTLY는 트랜잭션 안에서 못 돌므로
-- 이 파일은 CONCURRENTLY 문만 담아 Flyway가 트랜잭션 없이 실행한다(V89__...sql.conf, 다른 DDL을 섞지 않는다).
-- lock_timeout은 세션 설정이라 끝에서 되돌린다. 값이 V86·V88(5s)과 다른 이유: CONCURRENTLY는 SHARE UPDATE EXCLUSIVE만
-- 잡아 기다리는 동안에도 읽기·쓰기를 막지 않고, 대신 먼저 열린 트랜잭션(virtualxid)이 끝나길 기다린다. 5s면 업로드처럼
-- 몇 초 열린 트랜잭션 하나로 Job이 실패하고 INVALID 인덱스가 남으므로 120s로 넉넉히 기다린다.
--
-- 실패 복구(수동): CONCURRENTLY가 중간에 실패하면 INVALID 인덱스가 남고, IF NOT EXISTS 재실행은 그것을 건너뛴다.
--   1) SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid;
--   2) 이 파일의 인덱스가 보이면 DROP INDEX CONCURRENTLY IF EXISTS <이름>;
--   3) flyway repair로 실패 기록을 지운 뒤 migrate를 다시 돌린다.
SET lock_timeout = '120s';

-- 세션의 메시지를 시간순·최근순으로 읽는다(대화 화면, 질문마다 읽는 최근 맥락).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_messages_session ON chat_messages (session_id, created_at);

-- 메시지별 근거·관련 페이지 조회와 메시지 삭제 시 FK 연쇄 삭제.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cmr_message ON chat_message_references (chat_message_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cmrp_message ON chat_message_related_pages (chat_message_id);

-- 내 채팅 목록: 워크스페이스·사용자별 최근 대화순.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_sessions_actor ON chat_sessions (workspace_id, user_id, last_message_at DESC);

-- 작업 알림 polling(OperationLogRepository.findPage에 진행 중 상태만 넘기는 조회). 진행 중 행은 전체의 일부라 부분 인덱스가 작다.
-- 조회 상태가 아래 목록의 부분집합일 때만 planner가 쓴다. 끝난 상태까지 넣는 목록 조회는 idx_ai_operation_logs_workspace_type을 쓴다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_operation_logs_active
    ON ai_operation_logs (workspace_id, operation_type, created_at DESC, operation_id DESC)
    WHERE status IN ('processing', 'applying', 'rebuilding', 'notify_pending');

RESET lock_timeout;
