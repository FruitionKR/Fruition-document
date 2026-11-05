-- 문서 영구 삭제(DocumentCopyScrubber)가 agent·query 결과 receipt의 근거를 source_document_id로 찾는다.
-- 표현식은 DocumentCopyScrubber.RECEIPT_EVIDENCE와 같아야 한다. 본문을 담지 않은 receipt는 뺀다.
-- 운영 테이블이라 CONCURRENTLY로 만들고 이 문만 담는다(V95와 같은 방식, 실패 복구 절차도 V95를 따른다).
SET lock_timeout = '120s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_task_result_receipts_evidence ON ai_task_result_receipts USING gin ((COALESCE(event_payload #> '{payload,chat,evidence_snippets}', event_payload #> '{payload,evidence_snippets}')) jsonb_path_ops) WHERE event_payload IS NOT NULL;

RESET lock_timeout;
