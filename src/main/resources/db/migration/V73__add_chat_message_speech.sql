-- 채팅 답변 음성 듣기(#113). 끝까지 합성된 음성만 WAV로 object storage에 저장하고 같은 엔드포인트로 다시 들려준다.
-- 메시지(또는 세션)가 지워지면 행은 CASCADE로 사라지고, 객체는 애플리케이션이 먼저 지운다. 30일이 지나면 정리 작업이 지운다.
CREATE TABLE chat_message_speech (
    message_id   VARCHAR(255) PRIMARY KEY REFERENCES chat_messages (id) ON DELETE CASCADE,
    workspace_id VARCHAR(255) NOT NULL,
    user_id      VARCHAR(255) NOT NULL,
    object_key   VARCHAR(512) NOT NULL,
    byte_size    BIGINT       NOT NULL CHECK (byte_size > 0),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_message_speech_created_at ON chat_message_speech (created_at);
CREATE INDEX idx_chat_message_speech_user ON chat_message_speech (user_id);

-- 음성 합성 모델 단가. OpenAI 공식 단가 페이지(https://developers.openai.com/api/docs/pricing) 기준
-- gpt-realtime-2.1-mini는 텍스트 입력 $0.60 / 1M 토큰, 오디오 출력 $20.00 / 1M 토큰이다.
-- 합성 호출의 출력은 전부 오디오라 ai 원장의 output_tokens를 오디오 출력 토큰으로 보고 output 단가에 $20.00을 넣는다.
-- 입력(지시문 포함)은 텍스트 토큰이다. 캐시 단가는 0이다.
-- effective_from은 소급하지 않고 마이그레이션 시각(now())으로 둔다(V72와 같은 이유: 배포 전 호출을 소급 청구하지 않는다).
INSERT INTO ai_model_prices (provider, model, effective_from, input_usd_per_mtok, output_usd_per_mtok,
    cache_read_usd_per_mtok, cache_write_usd_per_mtok, audio_usd_per_minute, tts_usd_per_mchar)
VALUES ('openai', 'gpt-realtime-2.1-mini', now(), 0.60, 20.00, 0, 0, NULL, NULL)
ON CONFLICT DO NOTHING;
