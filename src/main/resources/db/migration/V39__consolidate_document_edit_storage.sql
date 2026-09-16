DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM document_edit_states)
        OR EXISTS (SELECT 1 FROM document_content_versions) THEN
        RAISE EXCEPTION
            'V39 requires empty document_edit_states and document_content_versions tables; verify the exact database and table targets, obtain approval, clear both tables, and rerun the migration';
    END IF;
END
$$;

ALTER TABLE ai_operation_logs
    ADD COLUMN document_restore_blocked boolean NOT NULL DEFAULT false;

UPDATE ai_operation_logs
SET document_restore_blocked = true
WHERE operation_type = 'document_edit';

ALTER TABLE document_edit_states
    ADD COLUMN revision bigint NOT NULL
        CONSTRAINT document_edit_states_revision_positive CHECK (revision > 0);

CREATE TABLE document_edit_writes (
    document_id varchar(255) NOT NULL,
    revision_write_id varchar(255) NOT NULL,
    request_hash varchar(64) NOT NULL,
    result_revision bigint NOT NULL,
    result_content_hash varchar(64) NOT NULL,
    result_updated_at timestamp with time zone NOT NULL,
    actor_user_id varchar(255) NOT NULL,
    changed boolean NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT document_edit_writes_revision_positive CHECK (result_revision > 0),
    CONSTRAINT document_edit_writes_content_hash_nonempty CHECK (char_length(result_content_hash) > 0),
    PRIMARY KEY (document_id, revision_write_id),
    CONSTRAINT fk_document_edit_writes_document
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE TABLE document_edit_outbox (
    event_id varchar(255) PRIMARY KEY,
    document_id varchar(255) NOT NULL,
    workspace_id varchar(255) NOT NULL,
    revision bigint NOT NULL,
    content_hash varchar(64) NOT NULL,
    event_type varchar(255) NOT NULL,
    schema_version integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    published boolean NOT NULL DEFAULT false,
    published_at timestamp with time zone,
    CONSTRAINT document_edit_outbox_revision_positive CHECK (revision > 0),
    CONSTRAINT document_edit_outbox_schema_version_positive CHECK (schema_version > 0),
    CONSTRAINT document_edit_outbox_event_type_check CHECK (event_type = 'document.edit.saved.v1'),
    CONSTRAINT document_edit_outbox_content_hash_nonempty CHECK (char_length(content_hash) > 0)
);

CREATE INDEX idx_document_edit_outbox_pending
    ON document_edit_outbox(created_at, event_id)
    WHERE published = false;
