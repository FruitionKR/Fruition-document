-- 가져오기 세션의 문서 조회(완료 집계·편입 대상·상태 조회)용 인덱스. 가져온 문서만 담는 부분 인덱스라 작다.
--
-- 문서가 계속 쓰이는 운영 테이블이라 CONCURRENTLY로 만들어 쓰기를 막지 않는다. CONCURRENTLY는 트랜잭션 안에서 못 돌므로
-- 이 파일은 CONCURRENTLY 문만 담아 Flyway가 트랜잭션 없이 실행한다(V87·V89와 같은 방식, 다른 DDL을 섞지 않는다).
-- lock_timeout은 세션 설정이라 끝에서 되돌린다. CONCURRENTLY는 먼저 열린 트랜잭션이 끝나길 기다리므로 120s로 넉넉히 둔다.
--
-- 실패 복구(수동): CONCURRENTLY가 중간에 실패하면 INVALID 인덱스가 남고, IF NOT EXISTS 재실행은 그것을 건너뛴다.
--   1) SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid;
--   2) idx_documents_import가 보이면 DROP INDEX CONCURRENTLY IF EXISTS idx_documents_import;
--   3) flyway repair로 실패 기록을 지운 뒤 migrate를 다시 돌린다.
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_documents_import ON documents (import_id) WHERE import_id IS NOT NULL;

RESET lock_timeout;
