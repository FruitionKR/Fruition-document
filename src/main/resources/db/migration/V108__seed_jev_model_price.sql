-- Jev 단가 누락으로 unpriced인 호출을 기존 재계산 작업이 정산하도록 시드한다.
-- 근거: https://openrouter.ai/typesafe/jev-1.13
--       https://www.eesel.ai/blog/typesafe-jev-pricing
--       https://zuplo.com/docs/ai-gateway/jev
-- 상세 공식 페이지 미확인, 2차 자료 일치: 입력 $0.042 / 1M tokens, 출력 무료.
-- 공식 홈페이지 https://typesafe.ai/ 의 $42 / billion input tokens도 입력 단가와 일치한다.
-- 첫 운영 호출 2026-11-07 04:02:54 UTC보다 앞선 당일 00:00 UTC부터 적용해,
-- started_at 기준 단가 조회와 최근 90일 재계산이 기존 unpriced를 정리할 수 있게 한다.
-- 캐시 별도 단가는 공표 자료에서 확인하지 못했고 현재 Jev usage는 캐시 토큰을 기록하지 않는다.
-- cache_read/write=0은 미사용 항목의 스키마 NOT NULL 충족값이며 캐시 무료를 확인했다는 뜻이 아니다.
-- 캐시 사용량이 생기면 공급사 요금을 확인하고 새 effective_from 행을 추가해야 한다.
INSERT INTO ai_model_prices (provider, model, effective_from,
    input_usd_per_mtok, output_usd_per_mtok, cache_read_usd_per_mtok, cache_write_usd_per_mtok,
    audio_usd_per_minute, tts_usd_per_mchar)
VALUES ('typesafe', 'jev-1.13.0', TIMESTAMPTZ '2026-11-07T00:00:00Z',
    0.042, 0, 0, 0, NULL, NULL)
ON CONFLICT DO NOTHING;
