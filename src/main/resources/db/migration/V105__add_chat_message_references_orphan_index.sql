-- 문서 영구 삭제(DocumentCopyScrubber.ORPHAN_CITATION)가 문서가 지워지고 원문이 남은 인용을 찾는다. 정리가 끝나면 비어 있는 인덱스다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cmr_orphan_quote ON chat_message_references (id) WHERE document_id IS NULL AND quote <> '';

RESET lock_timeout;
