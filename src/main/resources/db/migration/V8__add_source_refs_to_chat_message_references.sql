-- query 근거(evidence)의 다중 문서 참조를 구조화한 source_refs를 저장한다.
-- 하나의 rank가 여러 문서 block을 참조할 때, 대표 문서만 표현하는 legacy source_document_id/source_block_ids로는
-- 두 번째 이후 문서의 근거를 표현할 수 없어 별도 컬럼으로 보존한다.
-- 값은 [{"source_document_id","source_block_id"}] JSON 배열 문자열(SourceRefListJsonConverter). 기존 행은 NULL.

ALTER TABLE chat_message_references ADD COLUMN source_refs text;
