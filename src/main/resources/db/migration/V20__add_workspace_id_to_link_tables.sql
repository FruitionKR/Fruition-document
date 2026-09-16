-- 링크성 테이블 3개에 workspace_id 추가 (workspace 격리를 DB 레벨로 내림)
--
-- 배경: wiki_page_links / document_wiki_links / chat_partial_wiki에는 workspace 컬럼이
-- 없어서 WikiService가 "이 workspace의 page id 집합 안에 양 끝점이 존재하는 링크"를
-- 메모리에서 걸러내야 했다. 컬럼을 추가해 쿼리 단계에서 격리한다.
--
-- backfill 소스:
--   - wiki_page_links: from_page(wiki_pages.workspace_id). 링크는 workspace를 넘지 않으므로
--     from/to 어느 쪽이든 같다.
--   - document_wiki_links: wiki_page_id가 가리키는 wiki_pages.workspace_id
--   - chat_partial_wiki: session_id가 가리키는 chat_sessions.workspace_id
-- backfill 불가능한 고아 행(참조 대상이 사라진 행)은 삭제한다.
--
-- users/workspaces는 access_db 소유 — FK 없이 ID만 보관 (MSA DB 분리).

-- ===== wiki_page_links =====
ALTER TABLE wiki_page_links ADD COLUMN workspace_id character varying(255);

UPDATE wiki_page_links l
SET workspace_id = p.workspace_id
FROM wiki_pages p
WHERE p.id = l.from_page_id;

DELETE FROM wiki_page_links WHERE workspace_id IS NULL;

ALTER TABLE wiki_page_links ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX idx_wiki_page_links_workspace ON wiki_page_links (workspace_id);

-- ===== document_wiki_links =====
ALTER TABLE document_wiki_links ADD COLUMN workspace_id character varying(255);

UPDATE document_wiki_links l
SET workspace_id = p.workspace_id
FROM wiki_pages p
WHERE p.id = l.wiki_page_id;

DELETE FROM document_wiki_links WHERE workspace_id IS NULL;

ALTER TABLE document_wiki_links ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX idx_document_wiki_links_workspace ON document_wiki_links (workspace_id);

-- ===== chat_partial_wiki =====
ALTER TABLE chat_partial_wiki ADD COLUMN workspace_id character varying(255);

UPDATE chat_partial_wiki w
SET workspace_id = s.workspace_id
FROM chat_sessions s
WHERE s.id = w.session_id;

DELETE FROM chat_partial_wiki WHERE workspace_id IS NULL;

ALTER TABLE chat_partial_wiki ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX idx_chat_partial_wiki_workspace ON chat_partial_wiki (workspace_id);
