-- 문서 작업의 비용 견적 기준(모델 단가 기준액·자동 편입 몫·post_ingest 연결)을 command와 따로 둔다.
-- command는 발행한 payload 그대로여야 AI 취소가 같은 command 해시로 대조된다.
ALTER TABLE ai_task_runs ADD COLUMN cost_quote JSONB;
