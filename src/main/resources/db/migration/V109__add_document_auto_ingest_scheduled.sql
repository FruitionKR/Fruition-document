-- PDF 변환본을 변환이 끝나면 서버가 자동 편입하기로 했고 그 편입이 아직 시작되지 않았는지.
-- 변환을 요청할 때 워크스페이스 설정 auto_ingest_pdf_on_convert와 요청자의 wiki.ingest로 판정해 저장하고,
-- 변환이 끝나 편입을 등록하거나 건너뛰면(또는 변환이 실패하면) false로 바꾼다.
-- NULL은 이 값을 저장하기 전에 요청된 변환이거나 변환 문서가 아니라는 뜻이다. 그런 변환은 끝나는 시점에 판정한다.
-- 기본값 없는 nullable ADD COLUMN은 테이블을 다시 쓰지 않는 메타데이터 변경이지만 ACCESS EXCLUSIVE가 필요하다.
-- 앞선 긴 트랜잭션 뒤에 줄 서서 다른 요청을 막지 않게 5초 안에 못 잡으면 실패시킨다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE documents ADD COLUMN IF NOT EXISTS auto_ingest_scheduled boolean;
