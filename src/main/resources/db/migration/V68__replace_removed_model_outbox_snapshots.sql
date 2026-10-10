-- 아직 발행되지 않은 AI command가 종료된 모델로 실행되지 않도록 snapshot을 이관한다(#86).
-- 매핑 규칙: 같은 등급의 가장 싼 현행 모델.
UPDATE ai_command_outbox
SET payload = jsonb_set(
        payload::jsonb,
        '{model}',
        to_jsonb(CASE payload::jsonb ->> 'model'
            WHEN 'gpt-5-nano' THEN 'gpt-6-luna'
            WHEN 'gpt-4.1-nano' THEN 'gpt-6-luna'
            WHEN 'gpt-5.4-nano' THEN 'gpt-6-luna'
            WHEN 'gpt-5-mini' THEN 'gpt-5.4-mini'
            WHEN 'o4-mini' THEN 'gpt-5.4-mini'
            WHEN 'gpt-5' THEN 'gpt-6.1-sol'
            WHEN 'o3' THEN 'gpt-6.1-sol'
        END),
        false
    )::text
WHERE payload::jsonb ->> 'provider' = 'openai'
  AND payload::jsonb ->> 'model' IN ('gpt-5-nano', 'gpt-4.1-nano', 'gpt-5.4-nano', 'gpt-5-mini', 'o4-mini', 'gpt-5', 'o3');

UPDATE ai_command_outbox
SET payload = jsonb_set(
        payload::jsonb,
        '{model}',
        to_jsonb(CASE payload::jsonb ->> 'model'
            WHEN 'gemini-3.1-flash-lite' THEN 'gemini-3.5-flash-lite'
            WHEN 'gemini-3.7-flash' THEN 'gemini-3.8-flash'
            WHEN 'gemini-3.5-flash' THEN 'gemini-3.6-flash'
        END),
        false
    )::text
WHERE payload::jsonb ->> 'provider' = 'gemini'
  AND payload::jsonb ->> 'model' IN ('gemini-3.1-flash-lite', 'gemini-3.7-flash', 'gemini-3.5-flash');
