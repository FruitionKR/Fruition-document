-- 회의 녹음 원본과 업로드 파일 전사 상태. 원본은 회의 삭제 전까지 보관한다.
-- status = 'transcribing'인 회의가 파일 전사 작업 대기열이다(별도 큐 테이블 없음).
ALTER TABLE meetings
    ADD COLUMN recording_key          VARCHAR(512),
    ADD COLUMN recording_content_type VARCHAR(64),
    ADD COLUMN recording_bytes        BIGINT,
    ADD COLUMN error                  TEXT,
    ADD COLUMN claimed_at             TIMESTAMPTZ;

CREATE INDEX idx_meetings_transcribing ON meetings (claimed_at) WHERE status = 'transcribing';
