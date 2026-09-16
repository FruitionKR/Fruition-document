-- 복구로 받치는 기여가 모두 사라진 Wiki 페이지의 상태.
-- 하드 삭제하면 wiki_page_versions와 wiki_page_contributions가 CASCADE로 함께 사라져
-- 되살릴 수 없으므로 소프트 삭제로 표시만 한다.
ALTER TABLE wiki_pages DROP CONSTRAINT IF EXISTS wiki_pages_status_check;

ALTER TABLE wiki_pages
    ADD CONSTRAINT wiki_pages_status_check
    CHECK (status IN ('draft', 'active', 'failed', 'deleted'));
