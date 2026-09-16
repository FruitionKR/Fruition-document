-- 문서를 열지 않은 상태의 Agent turn을 허용한다.
-- 적용할 문서가 없는 턴은 chat_answer·clarify·reject만 낼 수 있어 편집 표(document_id,
-- base_version, apply_operation_id)가 필요 없다. 문서를 연 턴은 지금과 동일하게 채운다.
ALTER TABLE agent_apply_projections ALTER COLUMN document_id DROP NOT NULL;
ALTER TABLE agent_apply_projections ALTER COLUMN base_version DROP NOT NULL;
ALTER TABLE agent_apply_projections ALTER COLUMN apply_operation_id DROP NOT NULL;

-- 셋은 함께 있거나 함께 없어야 한다. 하나만 빠지면 적용 경로가 반쯤 성립해 위험하다.
ALTER TABLE agent_apply_projections
    ADD CONSTRAINT agent_apply_projections_document_columns_together
    CHECK (
        (document_id IS NULL AND base_version IS NULL AND apply_operation_id IS NULL)
        OR (document_id IS NOT NULL AND base_version IS NOT NULL AND apply_operation_id IS NOT NULL)
    );
