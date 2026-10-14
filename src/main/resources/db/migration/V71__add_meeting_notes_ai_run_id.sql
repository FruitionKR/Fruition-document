-- 회의록 초안 생성은 Kafka 비동기 작업이다. 결과 이벤트를 run_id로 찾아 같은 버전 행에 반영한다.
-- 이 변경 전의 행은 HTTP 동기 호출이라 run_id가 없다.
ALTER TABLE meeting_notes ADD COLUMN ai_run_id VARCHAR(64);
CREATE UNIQUE INDEX uq_meeting_notes_ai_run_id ON meeting_notes (ai_run_id) WHERE ai_run_id IS NOT NULL;
