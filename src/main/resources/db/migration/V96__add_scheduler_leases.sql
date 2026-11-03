-- 여러 Pod가 같은 주기로 도는 스케줄 작업 중 외부 호출을 반복하는 작업을 회차마다 한 Pod만 하게 하는 점유 행(SchedulerLease).
-- 행은 작업 이름마다 하나이고 처음 점유할 때 생긴다. locked_until까지 다른 Pod는 그 회차를 건너뛴다.
CREATE TABLE scheduler_leases (
    name         TEXT PRIMARY KEY,
    locked_until TIMESTAMPTZ NOT NULL
);
