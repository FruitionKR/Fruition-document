ALTER TABLE mcp_wiki_drafts ALTER COLUMN preview DROP NOT NULL;
ALTER TABLE mcp_wiki_drafts DROP CONSTRAINT mcp_wiki_drafts_preview_check;
ALTER TABLE mcp_wiki_drafts ADD CONSTRAINT mcp_wiki_drafts_preview_check
    CHECK (preview IS NULL OR jsonb_typeof(preview) = 'object');
