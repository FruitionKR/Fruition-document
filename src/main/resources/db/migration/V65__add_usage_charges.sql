-- 호출 단위 사용 금액(#78). AI 원장(ai_model_usage)의 호출 1건을 청구 행 1건으로 옮기고 금액을 계산한다.
-- 단가·환율·정책은 행을 고치지 않고 effective_from이 다른 새 행을 넣는다. 청구 행은 계산에 쓴 버전과 금액을
-- 그대로 남겨 이후 버전이 바뀌어도 과거 청구가 변하지 않는다.

-- 단가는 이슈의 단위별 행(model_prices) 대신 V59의 ai_model_prices를 넓혀 쓴다. 정산(#59)과 같은 단가표와
-- 기간 분할을 공유해야 두 계산이 어긋나지 않는다. 오디오·TTS 단가가 없으면(NULL) 그 사용량은 단가 없음이다.
ALTER TABLE ai_model_prices
    ADD COLUMN audio_usd_per_minute NUMERIC(12, 6) CHECK (audio_usd_per_minute >= 0),
    ADD COLUMN tts_usd_per_mchar NUMERIC(12, 6) CHECK (tts_usd_per_mchar >= 0);

-- USD→KRW 고정 환율. 실시간 환율은 쓰지 않는다.
CREATE TABLE fx_rates (
    effective_from TIMESTAMPTZ PRIMARY KEY,
    krw_per_usd NUMERIC(12, 4) NOT NULL CHECK (krw_per_usd > 0)
);

-- 마진·부가세율(basis point, 10000 = 100%).
CREATE TABLE pricing_policies (
    effective_from TIMESTAMPTZ PRIMARY KEY,
    margin_bp INTEGER NOT NULL CHECK (margin_bp >= 0),
    vat_bp INTEGER NOT NULL CHECK (vat_bp >= 0)
);

-- 호출 단위 청구. call_id는 AI 원장 행 id라 같은 호출을 여러 번 수집해도 한 행이다.
-- charged: 금액 확정. unpriced: 단가·환율·정책이 없어 금액을 비워 둔다(0원으로 넘기지 않음).
-- needs_review: 토큰을 모르는 호출(unknown·failed·abandoned). 금액 0으로 두고 #79에서 정산 정책을 정한다.
CREATE TABLE usage_charges (
    id BIGSERIAL PRIMARY KEY,
    call_id TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    workspace_id TEXT,
    run_id TEXT,
    kind TEXT,
    provider TEXT,
    model TEXT,
    call_status TEXT NOT NULL,
    input_tokens BIGINT,
    cached_input_tokens BIGINT,
    cache_creation_tokens BIGINT,
    output_tokens BIGINT,
    reasoning_tokens BIGINT,
    audio_seconds NUMERIC(14, 3),
    tts_characters BIGINT,
    price_effective_from TIMESTAMPTZ,
    fx_effective_from TIMESTAMPTZ,
    policy_effective_from TIMESTAMPTZ,
    cost_usd_micro BIGINT,
    charge_krw_milli BIGINT,
    status TEXT NOT NULL CHECK (status IN ('charged', 'needs_review', 'unpriced')),
    started_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'unpriced') = (charge_krw_milli IS NULL))
);
CREATE INDEX idx_usage_charges_user ON usage_charges (user_id, started_at);
CREATE INDEX idx_usage_charges_started ON usage_charges (started_at);

-- 실행이 끝나 호출을 가져올 run_id. worker가 SKIP LOCKED로 꺼내고 성공하면 지운다.
CREATE TABLE usage_collect_queue (
    run_id TEXT PRIMARY KEY,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 종료 시각 구간 대사가 어디까지 끝났는지. 한 행만 둔다.
CREATE TABLE usage_reconcile_cursor (
    id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),
    reconciled_to TIMESTAMPTZ NOT NULL
);
INSERT INTO usage_reconcile_cursor (reconciled_to) VALUES (date_trunc('hour', now()) - interval '1 day');
