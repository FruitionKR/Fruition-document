-- 실패 문구의 기계 판독용 코드. 화면 언어(Accept-Language)에 맞춰 문구를 다시 만들거나 화면이 코드로 번역하게 한다.
-- 기존 행과 코드 없이 실패한 행은 NULL로 두고 저장된 한국어 문구(error_message·error)를 그대로 쓴다.
-- 기본값 없는 nullable ADD COLUMN은 테이블을 다시 쓰지 않는 메타데이터 변경이지만 ACCESS EXCLUSIVE가 필요하다.
-- 앞선 긴 트랜잭션 뒤에 줄 서서 다른 요청을 막지 않게 5초 안에 못 잡으면 실패시킨다. IF NOT EXISTS라 재실행이 안전하다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);
ALTER TABLE meetings ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);
