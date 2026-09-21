ALTER TABLE document_convert_queue
    ADD COLUMN completed_pages integer NOT NULL DEFAULT 0,
    ADD COLUMN total_pages integer NOT NULL DEFAULT 0,
    ADD COLUMN attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX ix_document_convert_queue_claim ON document_convert_queue(status, next_attempt_at, created_at);
