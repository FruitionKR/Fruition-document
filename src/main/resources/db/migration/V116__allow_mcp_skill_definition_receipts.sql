-- MCP 외부 AI Skill 정의 저장(publish_skill_definition) 영수증을 허용한다.
-- Skill은 ai DB에 저장되므로 영수증은 ai 수락 API의 저장 증거(skill_id·새 버전)를 그대로 기록한다.
-- 생성은 before_version이 없고, 수정은 expected_version보다 큰 새 버전이다. 변경 없는 저장은 없다.
ALTER TABLE mcp_document_edit_receipts
    DROP CONSTRAINT mcp_operation_receipts_tool_check,
    DROP CONSTRAINT mcp_operation_receipts_resource_check,
    DROP CONSTRAINT mcp_operation_receipts_version_check;

ALTER TABLE mcp_document_edit_receipts
    ADD CONSTRAINT mcp_operation_receipts_tool_check CHECK (tool_name IN (
        'create_folder', 'rename_folder', 'move_folder', 'create_document',
        'rename_document', 'move_document', 'apply_document_edit', 'publish_skill_definition')),
    ADD CONSTRAINT mcp_operation_receipts_resource_check CHECK (
        (tool_name IN ('create_folder', 'rename_folder', 'move_folder') AND resource_type = 'FOLDER')
        OR (tool_name IN ('create_document', 'rename_document', 'move_document', 'apply_document_edit')
            AND resource_type = 'DOCUMENT')
        OR (tool_name = 'publish_skill_definition' AND resource_type = 'SKILL')),
    ADD CONSTRAINT mcp_operation_receipts_version_check CHECK (
        (tool_name IN ('create_folder', 'create_document')
            AND before_version IS NULL AND outcome = 'COMMITTED' AND changed AND after_version > 0)
        OR (tool_name = 'publish_skill_definition'
            AND outcome = 'COMMITTED' AND changed AND after_version > 0
            AND (before_version IS NULL OR after_version > before_version))
        OR (tool_name NOT IN ('create_folder', 'create_document', 'publish_skill_definition')
            AND before_version > 0 AND after_version > 0
            AND ((changed AND after_version > before_version)
                OR (NOT changed AND after_version = before_version))));
