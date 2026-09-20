ALTER TABLE chat_messages ADD COLUMN progress jsonb NOT NULL DEFAULT '[]'::jsonb;
