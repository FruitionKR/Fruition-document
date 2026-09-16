CREATE TABLE source_folders (
    id uuid PRIMARY KEY,
    workspace_id varchar(255) NOT NULL,
    parent_folder_id uuid,
    name varchar(255) NOT NULL,
    sort_order bigint NOT NULL,
    current_version bigint NOT NULL DEFAULT 1,
    deleted_at timestamp with time zone,
    deleted_by varchar(255),
    delete_operation_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    -- users/workspaces는 access_db 소유 — FK 없이 ID만 보관 (MSA DB 분리)
    CONSTRAINT fk_source_folders_parent
        FOREIGN KEY (parent_folder_id) REFERENCES source_folders(id) ON DELETE CASCADE
);

CREATE INDEX idx_source_folders_parent_order
    ON source_folders(workspace_id, parent_folder_id, sort_order);

ALTER TABLE documents
    ADD COLUMN display_name varchar(255),
    ADD COLUMN normalized_filename varchar(255),
    ADD COLUMN source_document_id varchar(255),
    ADD COLUMN current_content_hash varchar(64),
    ADD COLUMN current_version bigint,
    ADD COLUMN document_role varchar(16),
    ADD COLUMN parent_document_id varchar(255),
    ADD COLUMN source_folder_id uuid,
    ADD COLUMN sort_order bigint,
    ADD COLUMN updated_at timestamp with time zone,
    ADD COLUMN deleted_at timestamp with time zone,
    ADD COLUMN deleted_by varchar(255),
    ADD COLUMN delete_operation_id uuid;

UPDATE documents
SET display_name = CASE
        WHEN regexp_replace(filename, '\.[^.]+$', '') = '' THEN filename
        ELSE regexp_replace(filename, '\.[^.]+$', '')
    END,
    normalized_filename = lower(filename),
    current_content_hash = content_hash,
    current_version = 1,
    document_role = CASE
        WHEN mime_type IN ('text/markdown', 'text/x-markdown')
             OR lower(filename) LIKE '%.md'
            THEN 'EDITABLE'
        ELSE 'ORIGINAL'
    END,
    updated_at = uploaded_at;

WITH ranked_documents AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY workspace_id, document_role
               ORDER BY uploaded_at, id
           ) - 1 AS backfilled_sort_order
    FROM documents
)
UPDATE documents d
SET sort_order = ranked_documents.backfilled_sort_order
FROM ranked_documents
WHERE d.id = ranked_documents.id;

ALTER TABLE documents
    ALTER COLUMN display_name SET NOT NULL,
    ALTER COLUMN normalized_filename SET NOT NULL,
    ALTER COLUMN current_version SET NOT NULL,
    ALTER COLUMN document_role SET NOT NULL,
    ALTER COLUMN sort_order SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN source_uri DROP NOT NULL,
    ALTER COLUMN content_hash DROP NOT NULL;

ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS uq_documents_workspace_content_hash;

ALTER TABLE documents
    ADD CONSTRAINT documents_document_role_check
        CHECK (document_role IN ('EDITABLE', 'ORIGINAL')),
    ADD CONSTRAINT documents_role_parent_check
        CHECK (
            (document_role = 'EDITABLE' AND source_folder_id IS NULL)
            OR
            (document_role = 'ORIGINAL' AND parent_document_id IS NULL)
        ),
    ADD CONSTRAINT documents_source_not_self_check
        CHECK (source_document_id IS NULL OR source_document_id <> id),
    ADD CONSTRAINT fk_documents_source_document
        FOREIGN KEY (source_document_id) REFERENCES documents(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_documents_parent_document
        FOREIGN KEY (parent_document_id) REFERENCES documents(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_documents_source_folder
        FOREIGN KEY (source_folder_id) REFERENCES source_folders(id) ON DELETE SET NULL;
    -- users/workspaces는 access_db 소유 — deleted_by FK 없이 ID만 보관 (MSA DB 분리)

CREATE INDEX idx_documents_normalized_filename
    ON documents(workspace_id, normalized_filename);

CREATE INDEX idx_documents_editable_parent_order
    ON documents(workspace_id, parent_document_id, sort_order)
    WHERE document_role = 'EDITABLE';

CREATE INDEX idx_documents_original_folder_order
    ON documents(workspace_id, source_folder_id, sort_order)
    WHERE document_role = 'ORIGINAL';

CREATE INDEX idx_documents_source_document
    ON documents(source_document_id);

CREATE INDEX idx_documents_delete_operation
    ON documents(delete_operation_id)
    WHERE delete_operation_id IS NOT NULL;

CREATE TABLE document_edit_states (
    document_id varchar(255) PRIMARY KEY,
    markdown text NOT NULL,
    content_hash varchar(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT fk_document_edit_states_document
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE TABLE idempotency_records (
    id uuid PRIMARY KEY,
    user_id varchar(255) NOT NULL,
    endpoint_scope varchar(255) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response_status integer NOT NULL,
    resource_id varchar(255),
    response_body jsonb,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    -- users/workspaces는 access_db 소유 — user_id FK 없이 ID만 보관 (MSA DB 분리)
    CONSTRAINT uq_idempotency_records_scope_key
        UNIQUE (user_id, endpoint_scope, idempotency_key)
);

CREATE INDEX idx_idempotency_records_expires_at
    ON idempotency_records(expires_at);
