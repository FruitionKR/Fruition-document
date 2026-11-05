-- 문서 영구 삭제(DocumentCopyScrubber.ORPHAN_SOURCE_REF_CITATION)가 source_refs의 문서만 지워진 인용을 찾는다. 여러 문서를 걸친 인용만 담아 정리 때 표 전체를 파싱하지 않는다. 조건은 그 상수와 같아야 한다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cmr_multi_source_quote ON chat_message_references (id) WHERE document_id IS NOT NULL AND quote <> '' AND jsonb_path_exists(source_refs::jsonb, '$[1]');

RESET lock_timeout;
