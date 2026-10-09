\set ON_ERROR_STOP on
-- 크레딧 계정과 원장 합계가 다른 사용자를 찾는다(#79). 결과가 비어 있어야 정상이다.
-- 잔액 = reserve·release를 뺀 원장 합계, 예약 = reserve·release 합계.
-- 실행 예: psql -Xq --csv ... -f scripts/sql/credit-balance-check.sql
SELECT a.user_id, a.balance, a.reserved,
       coalesce(sum(e.amount) FILTER (WHERE e.type NOT IN ('reserve', 'release')), 0) AS ledger_balance,
       coalesce(sum(e.amount) FILTER (WHERE e.type IN ('reserve', 'release')), 0) AS ledger_reserved
FROM credit_accounts a
LEFT JOIN credit_entries e ON e.user_id = a.user_id
GROUP BY a.user_id, a.balance, a.reserved
HAVING a.balance <> coalesce(sum(e.amount) FILTER (WHERE e.type NOT IN ('reserve', 'release')), 0)
    OR a.reserved <> coalesce(sum(e.amount) FILTER (WHERE e.type IN ('reserve', 'release')), 0)
ORDER BY a.user_id;
