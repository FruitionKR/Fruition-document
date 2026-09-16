-- 아직 발행되지 않은 AI command가 제거된 모델로 실행되지 않도록 snapshot을 이관한다.
UPDATE ai_command_outbox
SET payload = jsonb_set(
        payload::jsonb,
        '{model}',
        '"claude-sonnet-5"'::jsonb,
        false
    )::text
WHERE payload::jsonb ->> 'provider' = 'claude'
  AND payload::jsonb ->> 'model' = 'claude-haiku-4-5-20251001';
