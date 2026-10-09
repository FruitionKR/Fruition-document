\set ON_ERROR_STOP on
-- 크레딧을 수동 지급(grant)하거나 조정(adjust)한다(#79). 원장 행과 계정 잔액을 한 트랜잭션에서 함께 바꾼다.
-- type은 grant(+) 또는 adjust(±, 사유 필수), amount는 milli-KRW(1000 = 1원)다.
-- key는 재실행해도 한 번만 반영되도록 작업마다 고유하게 정한다(예: 'ops:2026-10-09:티켓번호').
-- 실행 예: psql -X ... -v user_id='user_1' -v type='adjust' -v amount=-5000 \
--          -v reason='중복 청구 환원' -v key='ops:2026-10-09:123' -f scripts/sql/credit-grant-adjust.sql
BEGIN;
INSERT INTO credit_accounts (user_id) VALUES (:'user_id') ON CONFLICT (user_id) DO NOTHING;
SELECT balance, reserved FROM credit_accounts WHERE user_id = :'user_id' FOR UPDATE;
WITH entry AS (
    INSERT INTO credit_entries (user_id, type, amount, idempotency_key, reason)
    SELECT :'user_id', :'type', :amount, :'key', nullif(btrim(:'reason'), '')
    WHERE :'type' IN ('grant', 'adjust')
    ON CONFLICT (idempotency_key) DO NOTHING
    RETURNING amount
)
UPDATE credit_accounts SET balance = balance + entry.amount, updated_at = now()
FROM entry WHERE credit_accounts.user_id = :'user_id'
RETURNING credit_accounts.user_id, credit_accounts.balance, credit_accounts.reserved;
COMMIT;
