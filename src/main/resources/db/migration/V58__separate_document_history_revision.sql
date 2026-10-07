-- Preserve historical IDs and AI log references; new history IDs are independent.
ALTER TABLE document_content_versions ADD COLUMN revision BIGINT;
UPDATE document_content_versions SET revision = version;
ALTER TABLE document_content_versions ALTER COLUMN revision SET NOT NULL;
ALTER TABLE document_content_versions ADD COLUMN record_type varchar(32) NOT NULL DEFAULT 'legacy';
ALTER TABLE document_content_versions ADD CONSTRAINT ck_document_history_revision CHECK (revision >= 1);
CREATE INDEX idx_document_history_revision ON document_content_versions(document_id, revision, version DESC);
CREATE UNIQUE INDEX idx_document_history_operation ON document_content_versions(document_id, operation_id)
    WHERE operation_id IS NOT NULL;
