-- AI 모델 사용량을 금액으로 정산한다(#59).
-- 단가는 실제 응답 모델(provider, model)별 USD / 1M tokens다. 단가가 바뀌면 행을 고치지 않고
-- 새 effective_from 행을 추가해 이력을 남긴다. 단가 행은 확인된 값으로 별도 마이그레이션에서 넣는다.
CREATE TABLE ai_model_prices (
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    input_usd_per_mtok NUMERIC(12, 6) NOT NULL CHECK (input_usd_per_mtok >= 0),
    output_usd_per_mtok NUMERIC(12, 6) NOT NULL CHECK (output_usd_per_mtok >= 0),
    cache_read_usd_per_mtok NUMERIC(12, 6) NOT NULL CHECK (cache_read_usd_per_mtok >= 0),
    cache_write_usd_per_mtok NUMERIC(12, 6) NOT NULL CHECK (cache_write_usd_per_mtok >= 0),
    PRIMARY KEY (provider, model, effective_from)
);

-- 마감한 정산. 계산 결과를 그대로 저장해 이후 단가표가 바뀌어도 청구 금액이 변하지 않는다.
CREATE TABLE ai_usage_settlements (
    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL,
    from_at TIMESTAMPTZ NOT NULL,
    to_at TIMESTAMPTZ NOT NULL CHECK (to_at > from_at),
    result JSONB NOT NULL,
    closed_by TEXT NOT NULL,
    closed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_usage_settlements_workspace ON ai_usage_settlements (workspace_id, from_at);
