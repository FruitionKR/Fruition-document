ALTER TABLE ai_operation_logs
    ADD COLUMN restore_token_hash varchar(64);

CREATE UNIQUE INDEX uk_ai_operation_logs_restore_token
    ON ai_operation_logs(restored_from, restore_token_hash)
    WHERE operation_type = 'restore' AND restore_token_hash IS NOT NULL;
