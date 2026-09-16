-- V18에서 NOT VALID로 다시 건 wiki_pages_status_check를 검증한다.
-- 별도 마이그레이션(=별도 Flyway 트랜잭션)이라 V18의 ACCESS EXCLUSIVE 잠금이 커밋으로 풀린 뒤 실행된다.
-- VALIDATE CONSTRAINT는 기존 행을 스캔하지만 SHARE UPDATE EXCLUSIVE만 쓰므로
-- 스캔하는 동안에도 다른 트랜잭션의 읽기/쓰기를 막지 않는다.
ALTER TABLE wiki_pages VALIDATE CONSTRAINT wiki_pages_status_check;
