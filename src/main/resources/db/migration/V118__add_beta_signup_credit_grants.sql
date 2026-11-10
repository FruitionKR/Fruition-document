-- access가 가입 트랜잭션에서 배정한 슬롯만 지급한다. core에서도 50명·사용자·이메일 중복을 강제한다.
CREATE TABLE beta_signup_credit_grants (
    slot INTEGER PRIMARY KEY CHECK (slot BETWEEN 1 AND 50),
    user_id TEXT NOT NULL UNIQUE,
    email_hash TEXT NOT NULL UNIQUE CHECK (email_hash ~ '^[0-9a-f]{64}$'),
    amount_krw_milli BIGINT NOT NULL CHECK (amount_krw_milli > 0),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
