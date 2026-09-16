ALTER TABLE chat_messages
    ADD COLUMN ai_provider varchar(32),
    ADD COLUMN ai_model varchar(128);
