-- 문서 실패 사유의 기계 판독용 코드(예: AI_USAGE_LIMIT_EXCEEDED). 화면이 한국어 문구를 문자열 비교하지 않게 한다.
-- 기존 행과 코드 없이 실패한 문서는 NULL로 두고 error_message는 그대로 쓴다.
-- ADD COLUMN(기본값 없음)은 메타데이터만 바꾸지만 ACCESS EXCLUSIVE 잠금이 필요하다. 업로드 트랜잭션 뒤에 줄 서서
-- 그동안 documents 읽기·쓰기를 모두 막지 않도록 5초 안에 못 잡으면 실패한다. IF NOT EXISTS라 Job 재실행이 안전하다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE documents ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);
