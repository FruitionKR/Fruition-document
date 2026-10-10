-- AiModelCatalog의 모든 텍스트 모델에 공급사 공식 단가를 넣는다(#99). 2026-10-10 공식 단가 페이지에서 확인했다.
--   OpenAI    https://developers.openai.com/api/docs/pricing (Standard, short context)
--   Gemini    https://ai.google.dev/gemini-api/docs/pricing (Paid Tier, Standard)
--   Anthropic https://platform.claude.com/docs/en/about-claude/pricing (Standard)
--
-- 규칙
-- - 단위는 V59·V65 그대로다. 토큰 USD / 1M tokens. 오디오·TTS 단가는 NULL(이 모델들은 토큰 과금이다).
-- - effective_from은 이른 고정 시각(2026-01-01)이다. 단가 조회는 started_at 이전 행 중 가장 늦은 행을 쓰므로
--   운영자가 더 늦은 effective_from으로 넣은 행이 그 뒤 호출에 우선한다. ON CONFLICT DO NOTHING이라 같은 키로
--   운영자가 먼저 넣은 행도 덮어쓰지 않는다.
-- - cache_write: OpenAI는 페이지의 cache writes 열 값이다. 값이 "-"인 모델과 Gemini(토큰당 쓰기 단가 없음, 시간당
--   저장 단가만 있음)는 쓰기 토큰을 일반 입력으로 받으므로 입력 단가를 넣는다. Anthropic은 5분 캐시 쓰기(1.25배) 단가다.
-- - 표현하지 못하는 단가: OpenAI long context(272K 초과), Gemini 3.1 Pro 200k 초과, Gemini 3 Flash preview 오디오 입력
--   ($1.00, 캐시 $0.10), Anthropic 1시간 캐시 쓰기(2배)·US 전용 추론(1.1배)·fast mode. 기본 구간 단가로 계산한다.
-- - Gemini 3.6·3.8 Flash는 2027-01-01부터 입력 $1.50·출력 $7.50·캐시 읽기 $0.15로 오른다(공식 페이지 표기).
--   Gemini 3.7 Flash는 카탈로그에 없고 단가 페이지에 단가가 없어 넣지 않는다(3.8 Flash로 자동 전환).
INSERT INTO ai_model_prices (provider, model, effective_from, input_usd_per_mtok, output_usd_per_mtok,
    cache_read_usd_per_mtok, cache_write_usd_per_mtok, audio_usd_per_minute, tts_usd_per_mchar)
VALUES
    -- OpenAI: input, output, cached input, cache writes
    ('openai', 'gpt-6-luna',    '2026-01-01T00:00:00Z',  0.10,  0.50, 0.01,  0.125, NULL, NULL),
    ('openai', 'gpt-6.1-sol',   '2026-01-01T00:00:00Z',  2.00, 10.00, 0.10,  2.50,  NULL, NULL),
    ('openai', 'gpt-6-sol',     '2026-01-01T00:00:00Z',  2.00, 10.00, 0.20,  2.50,  NULL, NULL),
    ('openai', 'gpt-6-astra',   '2026-01-01T00:00:00Z', 10.00, 50.00, 1.00, 12.50,  NULL, NULL),
    ('openai', 'gpt-5.6-sol',   '2026-01-01T00:00:00Z',  4.00, 20.00, 0.40,  5.00,  NULL, NULL),
    ('openai', 'gpt-5.6-terra', '2026-01-01T00:00:00Z',  2.00, 12.00, 0.20,  2.50,  NULL, NULL),
    ('openai', 'gpt-5.6-luna',  '2026-01-01T00:00:00Z',  0.20,  1.20, 0.02,  0.25,  NULL, NULL),
    ('openai', 'gpt-5.5',       '2026-01-01T00:00:00Z',  5.00, 30.00, 0.50,  5.00,  NULL, NULL),
    ('openai', 'gpt-5.4',       '2026-01-01T00:00:00Z',  2.50, 15.00, 0.25,  2.50,  NULL, NULL),
    ('openai', 'gpt-5.4-mini',  '2026-01-01T00:00:00Z',  0.75,  4.50, 0.075, 0.75,  NULL, NULL),
    ('openai', 'gpt-4.1',       '2026-01-01T00:00:00Z',  2.00,  8.00, 0.50,  2.00,  NULL, NULL),
    ('openai', 'gpt-4.1-mini',  '2026-01-01T00:00:00Z',  0.40,  1.60, 0.10,  0.40,  NULL, NULL),
    ('openai', 'gpt-4o',        '2026-01-01T00:00:00Z',  2.50, 10.00, 1.25,  2.50,  NULL, NULL),
    ('openai', 'gpt-4o-mini',   '2026-01-01T00:00:00Z',  0.15,  0.60, 0.075, 0.15,  NULL, NULL),
    -- Gemini: input, output, context caching(읽기), 쓰기 = 입력
    ('gemini', 'gemini-3.5-flash-lite',  '2026-01-01T00:00:00Z', 0.30,  2.50, 0.03,  0.30, NULL, NULL),
    ('gemini', 'gemini-3.8-flash',       '2026-01-01T00:00:00Z', 0.75,  3.75, 0.075, 0.75, NULL, NULL),
    ('gemini', 'gemini-3.8-flash',       '2027-01-01T00:00:00Z', 1.50,  7.50, 0.15,  1.50, NULL, NULL),
    ('gemini', 'gemini-3.6-flash',       '2026-01-01T00:00:00Z', 0.75,  3.75, 0.075, 0.75, NULL, NULL),
    ('gemini', 'gemini-3.6-flash',       '2027-01-01T00:00:00Z', 1.50,  7.50, 0.15,  1.50, NULL, NULL),
    ('gemini', 'gemini-3.1-pro-preview', '2026-01-01T00:00:00Z', 2.00, 12.00, 0.20,  2.00, NULL, NULL),
    ('gemini', 'gemini-3-flash-preview', '2026-01-01T00:00:00Z', 0.50,  3.00, 0.05,  0.50, NULL, NULL),
    -- Anthropic: base input, output, cache hits, 5m cache writes
    ('claude', 'claude-sonnet-5',           '2026-01-01T00:00:00Z',  2.00, 10.00, 0.20,  2.50, NULL, NULL),
    ('claude', 'claude-opus-5-5',           '2026-01-01T00:00:00Z',  4.00, 20.00, 0.20,  5.00, NULL, NULL),
    ('claude', 'claude-sonnet-5-5',         '2026-01-01T00:00:00Z',  2.00, 10.00, 0.10,  2.50, NULL, NULL),
    ('claude', 'claude-fable-5-1',          '2026-01-01T00:00:00Z', 10.00, 50.00, 0.25, 12.50, NULL, NULL),
    ('claude', 'claude-opus-5',             '2026-01-01T00:00:00Z',  5.00, 25.00, 0.50,  6.25, NULL, NULL),
    ('claude', 'claude-fable-5',            '2026-01-01T00:00:00Z', 10.00, 50.00, 1.00, 12.50, NULL, NULL),
    ('claude', 'claude-opus-4-8',           '2026-01-01T00:00:00Z',  5.00, 25.00, 0.50,  6.25, NULL, NULL),
    ('claude', 'claude-sonnet-4-6',         '2026-01-01T00:00:00Z',  3.00, 15.00, 0.30,  3.75, NULL, NULL),
    ('claude', 'claude-haiku-4-5-20251001', '2026-01-01T00:00:00Z',  1.00,  5.00, 0.10,  1.25, NULL, NULL)
ON CONFLICT DO NOTHING;
