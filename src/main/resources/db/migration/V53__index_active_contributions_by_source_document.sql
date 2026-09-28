-- 문서를 휴지통으로 옮길 때 그 문서의 활성 기여를 끈다. 문서 행 잠금 중에 기여 전체를 훑지 않게 한다.
CREATE INDEX IF NOT EXISTS idx_wiki_page_contributions_active_source_document
    ON wiki_page_contributions(source_document_id)
    WHERE active;
