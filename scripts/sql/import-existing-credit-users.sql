\set ON_ERROR_STOP on
-- core DB에서 실행. 작업 디렉터리의 검토한 existing-credit-users.csv만 가져온다.
-- cutoff는 export 때와 같아야 한다. psql -X ... -v cutoff='...' -f import-existing-credit-users.sql
BEGIN;
CREATE TEMP TABLE existing_credit_users (
    user_id TEXT PRIMARY KEY CHECK (btrim(user_id) <> ''),
    cutoff_at TIMESTAMPTZ NOT NULL
) ON COMMIT DROP;
\copy existing_credit_users FROM 'existing-credit-users.csv' WITH (FORMAT csv, HEADER true)
-- 다른 차수 파일을 잘못 가져오면 전부 롤백한다.
CREATE TEMP TABLE credit_cutoff_check (valid BOOLEAN NOT NULL CHECK (valid)) ON COMMIT DROP;
INSERT INTO credit_cutoff_check
SELECT count(*) > 0 AND bool_and(cutoff_at = :'cutoff'::timestamptz) FROM existing_credit_users;
INSERT INTO credit_accounts (user_id, limit_exempt)
SELECT user_id, true FROM existing_credit_users
ON CONFLICT (user_id) DO UPDATE SET limit_exempt = true, updated_at = now();
SELECT count(*) AS exempt_users_in_snapshot FROM existing_credit_users;
COMMIT;
