-- document 중복 판별을 전역에서 workspace 범위로 전환한다.
--
-- 배경: 지금까지 content_hash에 전역 UNIQUE가 걸려 있어, 다른 workspace가 같은
-- 내용의 문서를 올려도 중복으로 막혔다. 중복 판별을 "같은 workspace 안에서만"
-- 하도록 (workspace_id, content_hash) 복합 UNIQUE로 교체한다.
--
-- 안전성: 기존 전역 UNIQUE 때문에 서로 다른 workspace에 동일 content_hash가
-- 공존할 수 없었으므로, 복합 UNIQUE 전환 시 충돌하는 데이터는 존재하지 않는다.
-- 전역 제약명(ukeafca5s6k4behm6am8avmcik3)은 Hibernate 생성명이라 DB에 따라
-- 없을 수 있어 IF EXISTS로 방어적으로 제거한다.

ALTER TABLE documents DROP CONSTRAINT IF EXISTS ukeafca5s6k4behm6am8avmcik3;
ALTER TABLE documents
    ADD CONSTRAINT uq_documents_workspace_content_hash UNIQUE (workspace_id, content_hash);
