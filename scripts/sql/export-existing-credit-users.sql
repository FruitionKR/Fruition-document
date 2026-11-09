\set ON_ERROR_STOP on
-- access DB에서 실행. cutoff는 제한 전환 시각(시간대 포함)으로 고정하고 재실행 때도 같은 값을 쓴다.
-- psql -Xq ... -v cutoff='2026-11-09T12:00:00+09:00' -f export-existing-credit-users.sql > existing-credit-users.csv
-- SQL COPY 출력이므로 CSV 헤더 외 상태 메시지는 출력하지 않는다.
COPY (
    SELECT id AS user_id, :'cutoff'::timestamptz AS cutoff_at
    FROM users WHERE created_at < :'cutoff'::timestamptz ORDER BY id
) TO STDOUT WITH (FORMAT csv, HEADER true);
