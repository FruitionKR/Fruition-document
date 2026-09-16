-- 채팅 export의 session_id:pair_id provenance를 Markdown 본문에서 분리한다.
-- 본문에는 id를 넣지 않고, 파이프라인에 넘길 문답 단위 블록만 여기에 둔다.
ALTER TABLE documents ADD COLUMN pipeline_input_blocks text;

COMMENT ON COLUMN documents.pipeline_input_blocks IS
    '채팅 export가 파이프라인에 실어 보낼 문답 단위 source block(JSON 배열). '
    'block_id가 session_id:pair_id provenance이며 본문에는 이 id가 들어가지 않는다. 일반 업로드는 NULL.';
