-- PDF 변환을 Kafka command로 쪽 범위(batch) 단위 비동기 처리한다(ai ADR 0034).
-- 대기열을 DB 폴링 worker가 아니라 이벤트가 진행하므로 재시도·다음 시도 시각은 쓰지 않는다.
-- next_start_page는 다음에 발행할 batch의 시작 쪽(0부터)이다.
DROP INDEX IF EXISTS ix_document_convert_queue_claim;
ALTER TABLE document_convert_queue
    DROP COLUMN attempts,
    DROP COLUMN next_attempt_at,
    ADD COLUMN next_start_page integer NOT NULL DEFAULT 0;

-- 문서마다 발행했지만 아직 병합하지 않은 batch. 병합이 끝난 행은 지운다.
-- command는 취소 요청에 그대로 실어야 해서 발행한 JSON 전문을 둔다.
-- status: dispatched(발행, 결과 대기) / ready(결과 도착, 앞 쪽이 병합되기를 대기)
CREATE TABLE document_convert_batches (
    queue_id   bigint                   NOT NULL,
    start_page integer                  NOT NULL,
    page_count integer                  NOT NULL,
    command    jsonb                    NOT NULL,
    status     varchar(16)              NOT NULL,
    page_end   integer,
    result_key text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (queue_id, start_page),
    CONSTRAINT fk_document_convert_batches_queue
        FOREIGN KEY (queue_id) REFERENCES document_convert_queue(id) ON DELETE CASCADE,
    CONSTRAINT ck_document_convert_batches_status CHECK (status IN ('dispatched', 'ready'))
);
