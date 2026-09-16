CREATE TABLE document_asset_orphans (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL,
    storage_key varchar(512) NOT NULL,
    failed_at timestamp with time zone NOT NULL,
    retry_count integer NOT NULL DEFAULT 0,
    last_error varchar(1000),
    CONSTRAINT uq_document_asset_orphans_storage_key UNIQUE (storage_key),
    CONSTRAINT document_asset_orphans_retry_count_check CHECK (retry_count >= 0)
);

CREATE INDEX idx_document_asset_orphans_failed_at
    ON document_asset_orphans(failed_at);
