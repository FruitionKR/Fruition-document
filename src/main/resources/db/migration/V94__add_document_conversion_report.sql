-- PDF 변환 문서의 복원 요약(원문으로 대신한 쪽과 사유 코드)과 그림 설명 집계. 변환 batch 결과가 올 때마다 합쳐 쓴다.
-- 본문은 담지 않고 쪽 번호·사유 코드·개수만 담는다. 변환 문서가 아니거나 AI가 요약을 주지 않으면 NULL이다.
-- 기본값 없는 nullable ADD COLUMN은 테이블을 다시 쓰지 않는 메타데이터 변경이지만 ACCESS EXCLUSIVE가 필요하다.
-- 앞선 긴 트랜잭션 뒤에 줄 서서 다른 요청을 막지 않게 5초 안에 못 잡으면 실패시킨다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE documents ADD COLUMN IF NOT EXISTS conversion_report JSONB;
