-- 회의 녹음 전사를 DB 선점 작업자에서 Kafka command(ai#89)로 옮긴다.
-- transcription_run_id: 업로드마다 새로 만드는 AI run_id. 결과 이벤트와 취소를 이 값으로 회의에 맞춘다.
ALTER TABLE meetings ADD COLUMN transcription_run_id VARCHAR(128);

-- 선점 작업자 전용 컬럼과 대기열 인덱스는 더 쓰지 않는다.
DROP INDEX idx_meetings_transcribing;
ALTER TABLE meetings DROP COLUMN claimed_at;

-- 배포 시점에 작업자가 처리하던 전사는 run이 없어 결과를 받을 수 없다. 다시 올릴 수 있게 실패로 닫는다.
UPDATE meetings
SET status = 'failed', error = '전사 방식이 바뀌어 중단되었습니다. 녹음을 다시 올려 주세요.', updated_at = now()
WHERE status = 'transcribing';
