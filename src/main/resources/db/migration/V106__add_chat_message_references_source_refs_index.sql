-- 문서 영구 삭제(DocumentCopyScrubber.CITATION_OF_DOCUMENT)가 여러 문서를 가리키는 인용을 source_refs로 찾는다. 표현식은 그 상수와 같아야 한다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cmr_source_refs ON chat_message_references USING gin ((source_refs::jsonb) jsonb_path_ops);

RESET lock_timeout;
