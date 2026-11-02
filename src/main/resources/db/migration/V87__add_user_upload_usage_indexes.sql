-- 업로드마다 사용자 한 명의 사용량을 합산한다(UploadLimitService). 합산 대상 열을 INCLUDE해 index-only scan으로 끝나게 한다.
--
-- 업로드가 계속 쓰는 운영 테이블이라 CONCURRENTLY로 만들어 쓰기를 막지 않는다. CONCURRENTLY는 트랜잭션 안에서 못 돌므로
-- 이 파일은 CONCURRENTLY 문만 담아 Flyway가 트랜잭션 없이 실행한다(다른 DDL을 섞지 않는다).
-- lock_timeout은 세션 설정이라 끝에서 되돌린다.
--
-- 실패 복구(수동): CONCURRENTLY가 중간에 실패하면 INVALID 인덱스가 남고, IF NOT EXISTS 재실행은 그것을 건너뛴다.
--   1) SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid;
--   2) 이 파일의 인덱스가 보이면 DROP INDEX CONCURRENTLY IF EXISTS <이름>;
--   3) flyway repair로 실패 기록을 지운 뒤 migrate를 다시 돌린다.
SET lock_timeout = '5s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_documents_user_bytes ON documents (user_id) INCLUDE (byte_size);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_document_assets_uploader_bytes ON document_assets (uploaded_by) INCLUDE (byte_size);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meetings_creator_recording_bytes ON meetings (created_by) INCLUDE (recording_bytes)
    WHERE recording_key IS NOT NULL;

RESET lock_timeout;
