-- PG 결제로 크레딧을 충전·환불한다(#80, ADR-0026). 특정 PG에 묶이지 않는 주문·승인·webhook·환불 골격이다.
-- 금액과 지급 크레딧은 서버 상품표(app.billing.product.*)로만 정한다. 브라우저가 보낸 금액은 승인 전에 대조만 한다.
-- 탈퇴해도 지우지 않는다(전자상거래법 대금 결제 기록 5년 보관).
CREATE TABLE credit_orders (
    order_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    product_code TEXT NOT NULL,
    amount_krw BIGINT NOT NULL CHECK (amount_krw > 0),
    credit_milli BIGINT NOT NULL CHECK (credit_milli > 0),
    status TEXT NOT NULL DEFAULT 'created'
        CHECK (status IN ('created', 'paid', 'failed', 'refunded', 'partially_refunded')),
    pg_payment_key TEXT UNIQUE,
    failure_code TEXT,
    refunded_krw BIGINT NOT NULL DEFAULT 0 CHECK (refunded_krw >= 0 AND refunded_krw <= amount_krw),
    refunded_credit_milli BIGINT NOT NULL DEFAULT 0
        CHECK (refunded_credit_milli >= 0 AND refunded_credit_milli <= credit_milli),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ
);
CREATE INDEX idx_credit_orders_user ON credit_orders (user_id, created_at DESC);

-- 서명을 검증한 webhook. event_id(본문에 없으면 본문 SHA-256)로 같은 알림을 한 번만 처리한다.
-- 결제 정보가 들어 있을 수 있어 원문은 남기지 않고 해시만 남긴다.
CREATE TABLE payment_events (
    event_id TEXT PRIMARY KEY,
    body_sha256 TEXT NOT NULL,
    event_type TEXT,
    order_id TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 결제 환불(refund)은 지급한 크레딧을 회수하므로 음수다. V66에서 +로 두었던 부호를 바로잡는다.
ALTER TABLE credit_entries DROP CONSTRAINT credit_entries_check1;
ALTER TABLE credit_entries ADD CONSTRAINT credit_entries_amount_sign
    CHECK (CASE type WHEN 'charge' THEN amount <= 0 WHEN 'release' THEN amount <= 0 WHEN 'refund' THEN amount <= 0
                     WHEN 'adjust' THEN true ELSE amount >= 0 END);
