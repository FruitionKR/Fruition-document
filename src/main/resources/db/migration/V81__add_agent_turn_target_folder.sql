-- Agent 턴이 지정한 작업 대상 폴더(폴더 정리 범위·새 문서 위치). 턴 결과 조회가 folder_id로 그대로 돌려준다.
-- NULL이면 지정하지 않은 턴(루트)이다. 기존 행은 지정한 적이 없으므로 NULL로 둔다.
-- 폴더가 나중에 삭제돼도 턴 기록은 남아야 하므로 FK를 걸지 않는다.
ALTER TABLE agent_apply_projections
    ADD COLUMN folder_id uuid;
