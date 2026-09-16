ALTER TABLE ai_operation_logs
    ADD COLUMN target_display_name varchar(255);

ALTER TABLE ai_operation_changes
    ADD COLUMN resource_display_name varchar(255);
