-- 버전 복원으로 만든 버전이 어느 버전을 되돌린 것인지 기록한다. 일반 저장·AI 적용·기존 버전은 NULL이다.
-- 복원 이력이 저장돼 있지 않았으므로 백필하지 않는다.
ALTER TABLE document_content_versions
    ADD COLUMN restored_from_version BIGINT;
