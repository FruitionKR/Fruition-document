-- I6 multi-operation MCP receipts retain the I5 table and make create before_version explicitly nullable.
ALTER TABLE mcp_document_edit_receipts ALTER COLUMN before_version DROP NOT NULL;

DO $$
DECLARE constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'mcp_document_edit_receipts'::regclass AND contype = 'c'
    LOOP
        EXECUTE format('ALTER TABLE mcp_document_edit_receipts DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
END $$;

ALTER TABLE mcp_document_edit_receipts
    ADD CONSTRAINT mcp_operation_receipts_tool_check CHECK (tool_name IN (
        'create_folder', 'rename_folder', 'move_folder', 'create_document',
        'rename_document', 'move_document', 'apply_document_edit')),
    ADD CONSTRAINT mcp_operation_receipts_resource_check CHECK (
        (tool_name IN ('create_folder', 'rename_folder', 'move_folder') AND resource_type = 'FOLDER')
        OR (tool_name IN ('create_document', 'rename_document', 'move_document', 'apply_document_edit')
            AND resource_type = 'DOCUMENT')),
    ADD CONSTRAINT mcp_operation_receipts_outcome_check CHECK (
        (outcome = 'COMMITTED' AND changed) OR (outcome = 'NO_CHANGE' AND NOT changed)),
    ADD CONSTRAINT mcp_operation_receipts_version_check CHECK (
        (tool_name IN ('create_folder', 'create_document')
            AND before_version IS NULL AND outcome = 'COMMITTED' AND changed AND after_version > 0)
        OR (tool_name NOT IN ('create_folder', 'create_document')
            AND before_version > 0 AND after_version > 0
            AND ((changed AND after_version > before_version)
                OR (NOT changed AND after_version = before_version))));
