CREATE TABLE document_assets (
    id uuid PRIMARY KEY,
    workspace_id varchar(255) NOT NULL,
    uploaded_by varchar(255),
    original_filename varchar(255) NOT NULL,
    content_type varchar(64) NOT NULL,
    byte_size bigint NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    content_hash varchar(64) NOT NULL,
    storage_key varchar(512) NOT NULL,
    unreferenced_since timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    -- workspaces·users는 access_db 소유 — 교차 DB FK를 두지 않는다 (workspace 검증은 인가 계층 담당)
    CONSTRAINT uq_document_assets_storage_key UNIQUE (storage_key),
    CONSTRAINT document_assets_byte_size_check CHECK (byte_size > 0),
    CONSTRAINT document_assets_dimensions_check CHECK (width > 0 AND height > 0)
);

CREATE INDEX idx_document_assets_workspace
    ON document_assets(workspace_id);

CREATE INDEX idx_document_assets_unreferenced_since
    ON document_assets(unreferenced_since)
    WHERE unreferenced_since IS NOT NULL;

CREATE TABLE document_asset_references (
    document_id varchar(255) NOT NULL,
    asset_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    PRIMARY KEY (document_id, asset_id),
    CONSTRAINT fk_document_asset_references_document
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT fk_document_asset_references_asset
        FOREIGN KEY (asset_id) REFERENCES document_assets(id) ON DELETE RESTRICT
);

CREATE INDEX idx_document_asset_references_asset
    ON document_asset_references(asset_id);
