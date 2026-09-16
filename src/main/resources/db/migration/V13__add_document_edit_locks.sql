-- 문서 편집 잠금(활성 편집 추적). 문서당 1행, lease(TTL + heartbeat) 기반.
-- 편집 중이면 다른 사용자의 쓰기를 차단하고, heartbeat가 끊기면 expires_at으로 자동 만료된다.
CREATE TABLE document_edit_locks (
    document_id       varchar(255)             NOT NULL,
    holder_user_id    varchar(255)             NOT NULL,
    acquired_at       timestamp with time zone NOT NULL,
    last_heartbeat_at timestamp with time zone NOT NULL,
    expires_at        timestamp with time zone NOT NULL,
    PRIMARY KEY (document_id),
    CONSTRAINT fk_document_edit_locks_document
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE INDEX idx_document_edit_locks_expires ON document_edit_locks(expires_at);
