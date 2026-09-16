-- V17은 wiki_pages_status_check를 NOT VALID 없이 추가해, 제약을 거는 동안 기존 행 전체를
-- 스캔하면서 ACCESS EXCLUSIVE 잠금을 잡는다. 테이블이 커질수록 그 시간 동안 읽기/쓰기가 모두 막힌다.
-- 이미 적용된 환경이 있어 V17을 고치면 Flyway 체크섬이 어긋나므로, 제약을 다시 걸어 바로잡는다.
--
-- DROP과 NOT VALID ADD는 기존 행을 읽지 않고 카탈로그만 바꾸므로 잠금이 짧게 끝난다.
-- 기존 행 검증은 V19에서 별도 트랜잭션으로 한다. Flyway는 마이그레이션 파일 하나를 트랜잭션
-- 하나로 묶으므로, 같은 파일에서 VALIDATE까지 하면 이 트랜잭션이 끝날 때까지
-- ACCESS EXCLUSIVE 잠금이 풀리지 않아 나누는 의미가 없다.
ALTER TABLE wiki_pages DROP CONSTRAINT IF EXISTS wiki_pages_status_check;

ALTER TABLE wiki_pages
    ADD CONSTRAINT wiki_pages_status_check
    CHECK (status IN ('draft', 'active', 'failed', 'deleted'))
    NOT VALID;
