\set ON_ERROR_STOP on
-- 단가·환율·마진 새 버전을 넣는다(#78). 행은 고치지 않고 effective_from이 다른 새 행을 추가한다.
-- 이미 계산된 청구(charged)는 바뀌지 않고, effective_from 이후 시작한 호출부터 새 버전이 적용된다.
-- 필요한 블록만 남겨 값을 채운 뒤 실행한다.
-- 실행 예: psql -X ... -v effective_from='2026-11-01T00:00:00Z' -f scripts/sql/insert-usage-price-versions.sql
BEGIN;

-- 모델 단가: 토큰은 USD / 1M tokens, 오디오는 USD / 분, TTS는 USD / 1M 글자. 해당 없는 단가는 NULL.
-- INSERT INTO ai_model_prices (provider, model, effective_from, input_usd_per_mtok, output_usd_per_mtok,
--     cache_read_usd_per_mtok, cache_write_usd_per_mtok, audio_usd_per_minute, tts_usd_per_mchar)
-- VALUES ('openai', '<응답 모델>', :'effective_from', 0, 0, 0, 0, NULL, NULL);

-- USD→KRW 고정 환율.
-- INSERT INTO fx_rates (effective_from, krw_per_usd) VALUES (:'effective_from', 0);

-- 마진·부가세율(basis point, 1000 = 10%).
-- INSERT INTO pricing_policies (effective_from, margin_bp, vat_bp) VALUES (:'effective_from', 0, 1000);

COMMIT;
