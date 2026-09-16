-- AI command와 업무 상태를 한 트랜잭션에 기록한 뒤 Kafka로 발행한다.
CREATE TABLE ai_command_outbox (
    id           varchar(255)             NOT NULL,
    run_id       varchar(255)             NOT NULL,
    topic        varchar(255)             NOT NULL,
    message_key  varchar(255)             NOT NULL,
    payload      text                     NOT NULL,
    created_at   timestamp with time zone NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_command_outbox_run UNIQUE (run_id)
);

CREATE INDEX idx_ai_command_outbox_created
    ON ai_command_outbox(created_at);
