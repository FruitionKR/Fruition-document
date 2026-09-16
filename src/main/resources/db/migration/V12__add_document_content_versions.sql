-- 편집 가능 Markdown 문서의 콘텐츠 버전 이력. 콘텐츠가 실제 바뀌는 저장마다 전체 본문을 스냅샷으로 남긴다.
CREATE TABLE document_content_versions (
    document_id  varchar(255) NOT NULL,
    version      bigint       NOT NULL,
    markdown     text         NOT NULL,
    content_hash varchar(64)  NOT NULL,
    created_by   varchar(255),
    created_at   timestamp with time zone NOT NULL,
    PRIMARY KEY (document_id, version),
    CONSTRAINT fk_document_content_versions_document
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE INDEX idx_document_content_versions_doc
    ON document_content_versions(document_id, version DESC);
