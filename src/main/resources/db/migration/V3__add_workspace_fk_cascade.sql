-- workspace 삭제 연쇄를 위한 DB 레벨 FK 추가 (docs/issue/backend/2026-07-15.md #3)
--
-- 배경: 지금까지 workspace 삭제 시 하위 리소스 정리를 앱 코드에 의존했고,
-- wiki_pages(특히 concept page)는 아무데서도 안 지워져 고아로 남는 버그가 있었다.
-- 소유 관계는 ON DELETE CASCADE, 단순 참조(nullable)는 ON DELETE SET NULL로 건다.
--
-- 삭제 의미론:
--   - workspace 삭제 → 그 안의 모든 것(concept page 포함)이 CASCADE로 삭제된다.
--   - 단일 document 삭제 → documents는 wiki_pages의 부모가 아니므로 cascade는
--     document_wiki_links 링크만 지우고 concept page 본체는 남는다. "source page만
--     삭제/concept page 보존" 규칙은 앱 로직(DocumentService.deleteInternal)이 담당한다.
--
-- 파이프라인이 같은 트랜잭션에서 wiki_pages보다 링크를 먼저 insert해도 안 깨지도록,
-- wiki_pages를 참조하는 링크 FK 3개는 DEFERRABLE INITIALLY DEFERRED로 건다.
--
-- 주의: 고아 행이 남아있는 미리셋 DB에서는 FK 추가가 실패할 수 있다. Flyway 도입 후
-- 로컬 DB 리셋(README 워크플로)을 거친 빈 DB에는 V1~V3가 깨끗이 적용된다.

-- ===== 소유 관계: ON DELETE CASCADE =====

-- users/workspaces는 access_db 소유 — FK 없이 ID만 보관 (MSA DB 분리).
-- documents/wiki_pages/chat_sessions의 workspace·user FK는 여기서 걸지 않는다.

-- documents 하위
ALTER TABLE source_blocks
    ADD CONSTRAINT fk_source_blocks_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;
ALTER TABLE document_processing_queue
    ADD CONSTRAINT fk_dpq_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

-- document_wiki_links (링크 행. wiki_page_id는 파이프라인 insert 순서 때문에 DEFERRABLE)
ALTER TABLE document_wiki_links
    ADD CONSTRAINT fk_dwl_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;
ALTER TABLE document_wiki_links
    ADD CONSTRAINT fk_dwl_wiki_page FOREIGN KEY (wiki_page_id) REFERENCES wiki_pages(id) ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED;

-- wiki_page_links (self-ref. 파이프라인 insert 순서 때문에 DEFERRABLE)
ALTER TABLE wiki_page_links
    ADD CONSTRAINT fk_wpl_from_page FOREIGN KEY (from_page_id) REFERENCES wiki_pages(id) ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE wiki_page_links
    ADD CONSTRAINT fk_wpl_to_page FOREIGN KEY (to_page_id) REFERENCES wiki_pages(id) ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED;

-- chat_partial_wiki (세 참조 모두 NOT NULL이라 CASCADE만 가능)
ALTER TABLE chat_partial_wiki
    ADD CONSTRAINT fk_cpw_session FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE;
ALTER TABLE chat_partial_wiki
    ADD CONSTRAINT fk_cpw_wiki_page FOREIGN KEY (wiki_page_id) REFERENCES wiki_pages(id) ON DELETE CASCADE;
ALTER TABLE chat_partial_wiki
    ADD CONSTRAINT fk_cpw_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

-- ===== 단순 참조(nullable): ON DELETE SET NULL =====

ALTER TABLE chat_sessions
    ADD CONSTRAINT fk_chat_sessions_wiki_page FOREIGN KEY (wiki_page_id) REFERENCES wiki_pages(id) ON DELETE SET NULL;
ALTER TABLE chat_sessions
    ADD CONSTRAINT fk_chat_sessions_wiki_export_document FOREIGN KEY (wiki_export_document_id) REFERENCES documents(id) ON DELETE SET NULL;

ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_wiki_page FOREIGN KEY (wiki_page_id) REFERENCES wiki_pages(id) ON DELETE SET NULL;

ALTER TABLE chat_message_references
    ADD CONSTRAINT fk_cmr_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE SET NULL;

ALTER TABLE chat_message_related_pages
    ADD CONSTRAINT fk_cmrp_wiki_page FOREIGN KEY (wiki_page_id) REFERENCES wiki_pages(id) ON DELETE SET NULL;
