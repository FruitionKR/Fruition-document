-- 편집 충돌. 같은 revision에서 갈라진 저장이 409를 받으면 클라이언트가 자기 본문을 여기에 올려 보존하고,
-- 워크스페이스 OWNER가 서버 본·충돌 본·직접 합친 본 중 하나를 고른다. 해결해도 행은 지우지 않아 고르지 않은 본문이 남는다.
CREATE TABLE document_edit_conflicts (
    id                 uuid PRIMARY KEY,
    workspace_id       varchar(255)             NOT NULL,
    document_id        varchar(255)             NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    base_revision      bigint                   NOT NULL CHECK (base_revision > 0),
    markdown           text                     NOT NULL,
    content_hash       varchar(64)              NOT NULL,
    author_user_id     varchar(255)             NOT NULL,
    client_conflict_id varchar(255)             NOT NULL,
    status             varchar(16)              NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    resolution         varchar(16) CHECK (resolution IN ('server', 'conflict', 'merged')),
    resolved_by        varchar(255),
    resolved_revision  bigint,
    created_at         timestamp with time zone NOT NULL DEFAULT now(),
    resolved_at        timestamp with time zone,
    CONSTRAINT uq_document_edit_conflicts_client UNIQUE (document_id, client_conflict_id),
    CONSTRAINT document_edit_conflicts_resolved CHECK ((status = 'open') = (resolution IS NULL))
);

CREATE INDEX idx_document_edit_conflicts_open ON document_edit_conflicts (workspace_id, created_at) WHERE status = 'open';
