-- 문서 실패 사유의 기계 판독용 코드(예: AI_USAGE_LIMIT_EXCEEDED). 화면이 한국어 문구를 문자열 비교하지 않게 한다.
-- 기존 행과 코드 없이 실패한 문서는 NULL로 두고 error_message는 그대로 쓴다.
ALTER TABLE documents ADD COLUMN error_code VARCHAR(64);
