-- 잔재 unique constraint 제거 (docs/issue/2026-07-16.md 이슈 1)
--
-- 과거 엔티티 정의의 잔재인 uq_wiki_pages_type_slug (page_type, slug) 가
-- ddl-auto=update로는 삭제되지 않아 일부 DB에 남아 있다. 이 전역 constraint가
-- workspace 단위 upsert(ON CONFLICT (user_id, workspace_id, page_type, slug))보다
-- 먼저 (page_type, slug) 중복을 막아 llmPipeline의 wiki page 재실행이 실패한다.
--
-- 현행 constraint는 uq_wiki_pages_workspace_type_slug (V1에 포함).
-- 잔재가 없는 DB(리셋/신규)에서는 IF EXISTS 로 no-op, 잔재가 남은 DB에서는 제거된다.

ALTER TABLE wiki_pages DROP CONSTRAINT IF EXISTS uq_wiki_pages_type_slug;
