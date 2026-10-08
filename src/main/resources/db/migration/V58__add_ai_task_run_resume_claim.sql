-- 여러 replica가 같은 취소 run을 동시에 진행하지 않도록 짧게 선점한다(#48).
-- 행 잠금을 쥔 채 AI를 호출하면 복구 중 돌아오는 내부 요청이 같은 행을 FOR UPDATE로 기다려 교착한다.
-- 그래서 선점만 커밋하고 잠금 없이 진행한다. 선점한 Pod가 죽어도 claimed_until이 지나면 다른 Pod가 이어받는다.
ALTER TABLE ai_task_runs
    ADD COLUMN resume_claimed_by TEXT,
    ADD COLUMN resume_claimed_until TIMESTAMPTZ;
