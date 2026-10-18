-- 저가 모델 정책(2026-10-18): 카탈로그를 provider마다 가장 싼 모델 하나로 줄였다.
-- 아직 발행되지 않은 AI command가 뺀 모델로 실행되지 않도록 snapshot을 이관한다(V68 패턴).
-- 매핑 규칙: openai는 gpt-6-luna, gemini는 gemini-3.5-flash-lite, claude는 gemini/gemini-3.5-flash-lite.
-- 단, 사용자가 채팅에서 직접 고른 command(query·agent)의 claude-haiku-4-5-20251001은 허용 모델이라 그대로 둔다.
UPDATE ai_command_outbox
SET payload = jsonb_set(payload::jsonb, '{model}', '"gpt-6-luna"'::jsonb, false)::text
WHERE payload::jsonb ->> 'provider' = 'openai'
  AND payload::jsonb ->> 'model' IS DISTINCT FROM 'gpt-6-luna';

UPDATE ai_command_outbox
SET payload = jsonb_set(payload::jsonb, '{model}', '"gemini-3.5-flash-lite"'::jsonb, false)::text
WHERE payload::jsonb ->> 'provider' = 'gemini'
  AND payload::jsonb ->> 'model' IS DISTINCT FROM 'gemini-3.5-flash-lite';

UPDATE ai_command_outbox
SET payload = jsonb_set(
        jsonb_set(payload::jsonb, '{provider}', '"gemini"'::jsonb, false),
        '{model}', '"gemini-3.5-flash-lite"'::jsonb, false
    )::text
WHERE payload::jsonb ->> 'provider' = 'claude'
  AND (payload::jsonb ->> 'model' IS DISTINCT FROM 'claude-haiku-4-5-20251001'
       OR COALESCE(payload::jsonb ->> 'kind', '') NOT IN ('query', 'agent'));
