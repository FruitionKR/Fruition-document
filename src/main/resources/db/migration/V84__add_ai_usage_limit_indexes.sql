-- 사용자별 AI 사용 한도(AiUsageLimiter) 판정 조회가 테이블 전체를 훑지 않게 한다.

-- 사용자의 끝나지 않은 실행 수(동시 실행 한도)·진행 중 실행의 예상 비용. 상수 IN 조건이라 부분 인덱스 조건과 그대로 맞는다.
CREATE INDEX idx_ai_task_runs_user_active ON ai_task_runs (user_id, updated_at)
    WHERE status IN ('running', 'cancel_requested', 'rolling_back');

-- 진행 중 실행마다 이미 청구된 금액을 빼 예상 비용을 두 번 세지 않는다. 크레딧 정산(run_id 합계)도 같은 조회를 쓴다.
CREATE INDEX idx_usage_charges_run ON usage_charges (run_id);

-- 오늘(KST) 청구 합계는 기존 idx_usage_charges_user (user_id, started_at)를 쓴다.
