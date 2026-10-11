-- 환율 버퍼와 청구 기본값 시드, 음성 전사 모델 단가.

-- 환율 버퍼(basis point, 10000 = 100%). 적용 환율 = krw_per_usd × (1 + fx_buffer_bp / 10000).
-- 하루 한 번 받는 환율과 실제 결제 시점 환율의 차이를 흡수한다. 기본 2%.
ALTER TABLE pricing_policies
    ADD COLUMN fx_buffer_bp INTEGER NOT NULL DEFAULT 200 CHECK (fx_buffer_bp >= 0);

-- 청구 정책 시드. 이 행이 없으면 모든 호출이 unpriced다.
-- 잠정값: margin_bp = 5000(원가의 +50%, 매출총이익률 약 33%)은 업계 기준(초기 AI 앱 최소 매출총이익률 25%, OpenRouter
-- 수수료 5.5% 등)으로 정한 잠정값이다. 실제 원가 한 달 뒤 재산정한다. 바꿀 때는 effective_from이 더 늦은 새 행으로 넣는다
-- (scripts/sql/insert-usage-price-versions.sql).
-- effective_from을 V70 단가와 같은 2026-01-01로 둔 것은 그 이후 unpriced로 남은 호출을 매시간 재계산이 다시 계산하게 하려는 것이다.
INSERT INTO pricing_policies (effective_from, margin_bp, vat_bp, fx_buffer_bp)
VALUES ('2026-01-01T00:00:00Z', 5000, 1000, 200)
ON CONFLICT DO NOTHING;

-- 초기 환율. 첫 자동 갱신(FxRateService) 전에도 금액이 계산되게 하는 값이다.
-- 2026-10-08(직전 영업일, 10-09는 한글날) USD 1339.20원. 서울외환시장 시장평균환율(매매기준율)을 한국 머니브로커
-- (https://kmbco.com/kor/rate/exchange_rate.do?mkt=fx)에서 확인했다. 수출입은행 deal_bas_r 값을 직접 확인한 것은 아니다.
-- 첫 갱신이 성공하면 수출입은행 값이 새 행으로 들어오고 이 행은 그 전 구간에만 쓰인다.
-- effective_from을 정책과 같은 2026-01-01로 둔 이유는 위와 같다. 1월~10월 호출의 환율은 실제와 다를 수 있다.
INSERT INTO fx_rates (effective_from, krw_per_usd)
VALUES ('2026-01-01T00:00:00Z', 1339.2000)
ON CONFLICT DO NOTHING;

-- 음성 전사 모델 단가. 2026-10-11 OpenAI 공식 단가 페이지(https://developers.openai.com/api/docs/pricing)와
-- LiteLLM model_prices_and_context_window.json(input_cost_per_second)이 같다.
--   gpt-transcribe       $0.0045 / 분 (LiteLLM $0.000075 / 초)
--   gpt-live-transcribe  $0.017  / 분 (LiteLLM $0.000283333 / 초)
-- ai는 두 모델 모두 audio_seconds를 원장에 남긴다(배치는 응답 usage.type=duration의 초, 실시간은 PCM 바이트로 센 초).
-- 단가 행이 과금 단위를 정하므로(UsagePricing.callCostUsd) 분당 단가만 넣고 토큰 단가는 0이다.
--
-- gpt-realtime-2.1-mini(TTS, 현재 호출하는 곳 없음)는 넣지 않는다. 공식 단가가 텍스트 토큰($0.60 입력)과 오디오 토큰
-- ($20.00 출력)으로 갈리는데 ai 원장은 토큰을 한 열로 합쳐 남기고, 글자 수당 단가 환산도 근거가 없다.
-- 토큰 종류를 나눠 남기는 변경이 먼저 필요하다.
INSERT INTO ai_model_prices (provider, model, effective_from, input_usd_per_mtok, output_usd_per_mtok,
    cache_read_usd_per_mtok, cache_write_usd_per_mtok, audio_usd_per_minute, tts_usd_per_mchar)
VALUES
    ('openai', 'gpt-transcribe',      '2026-01-01T00:00:00Z', 0, 0, 0, 0, 0.004500, NULL),
    ('openai', 'gpt-live-transcribe', '2026-01-01T00:00:00Z', 0, 0, 0, 0, 0.017000, NULL)
ON CONFLICT DO NOTHING;
