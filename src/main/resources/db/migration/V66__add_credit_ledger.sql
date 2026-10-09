-- 사용자 선불 크레딧(#79). 금액은 milli-KRW 정수다.
-- credit_entries는 추가만 한다. 부호 규칙: purchase·grant·refund는 +, charge는 −, adjust는 ±(잔액에 반영),
-- reserve는 +, release는 −(예약에 반영). 그래서 항상
--   credit_accounts.balance  = SUM(amount) WHERE type NOT IN ('reserve', 'release')
--   credit_accounts.reserved = SUM(amount) WHERE type IN ('reserve', 'release')
-- 이다(scripts/sql/credit-balance-check.sql로 점검). 탈퇴해도 결제·크레딧 기록은 지우지 않는다(전자상거래법 5년 보관).
CREATE TABLE credit_accounts (
    user_id TEXT PRIMARY KEY,
    balance BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credit_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES credit_accounts (user_id),
    type TEXT NOT NULL CHECK (type IN ('purchase', 'grant', 'reserve', 'release', 'charge', 'refund', 'adjust')),
    amount BIGINT NOT NULL,
    run_id TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (type <> 'adjust' OR nullif(btrim(reason), '') IS NOT NULL),
    CHECK (CASE type WHEN 'charge' THEN amount <= 0 WHEN 'release' THEN amount <= 0
                     WHEN 'adjust' THEN true ELSE amount >= 0 END)
);
CREATE INDEX idx_credit_entries_user ON credit_entries (user_id, id DESC);
CREATE INDEX idx_credit_entries_run ON credit_entries (run_id) WHERE run_id IS NOT NULL;
-- 종료 신호 없이 남은 예약을 찾는다.
CREATE INDEX idx_credit_entries_reserve ON credit_entries (created_at) WHERE type = 'reserve';
