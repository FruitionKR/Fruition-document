-- 회의록 초안 버전. AI 호출은 트랜잭션 밖에서 하고 결과는 자기 버전 행에만 쓴다.
-- 저장(apply) 기록은 재시도가 같은 본문·대상으로 같은 저장을 반복하도록 저장 전에 남긴다.
CREATE TABLE meeting_notes (
    meeting_id            VARCHAR(64)  NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    version               INT          NOT NULL,
    generation_request_id VARCHAR(255) NOT NULL,
    status                VARCHAR(16)  NOT NULL CHECK (status IN ('generating', 'ready', 'failed', 'applied')),
    partial               BOOLEAN      NOT NULL,
    segment_snapshot      JSONB        NOT NULL,
    result                JSONB,
    error_code            VARCHAR(64),
    apply_request_id      VARCHAR(255),
    apply_mode            VARCHAR(16)  CHECK (apply_mode IN ('create', 'append')),
    apply_document_id     VARCHAR(64),
    apply_base_revision   BIGINT,
    apply_display_name    VARCHAR(255),
    apply_folder_id       UUID,
    apply_markdown        TEXT,
    applied_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (meeting_id, version),
    UNIQUE (meeting_id, generation_request_id)
);
