-- 사용자별 AI 사용 한도(AiUsageLimiter) 판정 조회가 테이블 전체를 훑지 않게 한다.
--
-- 운영 테이블에 거는 인덱스라 CONCURRENTLY로 만들어 쓰기를 막지 않는다. CONCURRENTLY는 트랜잭션 안에서 못 돌므로
-- 이 파일은 CONCURRENTLY 문만 담아 Flyway가 트랜잭션 없이 실행한다(다른 DDL을 섞지 않는다).
-- lock_timeout은 세션 설정이라 끝에서 되돌린다. 값이 V86·V88(5s)과 다른 이유: CONCURRENTLY는 SHARE UPDATE EXCLUSIVE만
-- 잡아 기다리는 동안에도 읽기·쓰기를 막지 않고, 대신 먼저 열린 트랜잭션(virtualxid)이 끝나길 기다린다. 5s면 업로드처럼
-- 몇 초 열린 트랜잭션 하나로 Job이 실패하고 INVALID 인덱스가 남으므로 120s로 넉넉히 기다린다.
--
-- 실패 복구(수동): CONCURRENTLY가 중간에 실패하면 INVALID 인덱스가 남고, IF NOT EXISTS 재실행은 그것을 건너뛴다.
--   1) SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid;
--   2) 이 파일의 인덱스가 보이면 DROP INDEX CONCURRENTLY IF EXISTS <이름>;
--   3) flyway repair로 실패 기록을 지운 뒤 migrate를 다시 돌린다.
SET lock_timeout = '120s';

-- 사용자의 끝나지 않은 실행 수(동시 실행 한도)·진행 중 실행의 예상 비용. 상수 IN 조건이라 부분 인덱스 조건과 그대로 맞는다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_task_runs_user_active ON ai_task_runs (user_id, updated_at)
    WHERE status IN ('running', 'cancel_requested', 'rolling_back');

-- 진행 중 실행마다 이미 청구된 금액을 빼 예상 비용을 두 번 세지 않는다. 크레딧 정산(run_id 합계)도 같은 조회를 쓴다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_usage_charges_run ON usage_charges (run_id);

-- 오늘(KST) 청구 합계는 기존 idx_usage_charges_user (user_id, started_at)를 쓴다.

RESET lock_timeout;
