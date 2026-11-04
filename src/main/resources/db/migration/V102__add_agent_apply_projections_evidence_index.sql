-- 문서 영구 삭제(DocumentCopyScrubber)가 문서를 열지 않은 질의의 근거를 source_document_id로 찾는다.
-- 표현식은 DocumentCopyScrubber.PROJECTION_EVIDENCE와 같아야 한다. 근거가 있는 행만 담는다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_apply_projections_evidence ON agent_apply_projections USING gin ((result #> '{chat,evidence_snippets}') jsonb_path_ops) WHERE result #> '{chat,evidence_snippets}' IS NOT NULL;

RESET lock_timeout;
