-- 되묻기 안내에 필요한 값 둘을 채팅 메시지에 남긴다. 작업 탭 항목은 채팅 메시지로 만들어져 새로고침·다른 기기에서도 유지하려면 메시지에 값이 있어야 한다.
-- clarify_reason: AI가 되물은 이유 코드. 화면이 되묻기 문장을 비교하지 않고 이 코드로 갈래를 정한다.
--   action처럼 결과가 오면 바뀌지 않으므로 turn 결과(agent_apply_projections.result)에서 복사해 둔다.
-- read_only_document_id: 보기 권한 문서를 연 채 보낸 요청의 그 문서. 보기 권한 문서는 편집 문맥 없이 요청이 나가 서버 기록에 문서가 남지 않는다.
--   표시에만 쓰는 값이라 FK를 걸지 않는다(agent_apply_projections.document_id와 같이 문서가 지워져도 ID만 남는다). 메시지는 세션과 함께 지워진다.
-- 기본값 없는 nullable ADD COLUMN은 테이블을 다시 쓰지 않아 구 코드와 함께 돌아도 안전하지만 ACCESS EXCLUSIVE가 필요하다.
-- 앞선 긴 트랜잭션 뒤에 줄 서서 다른 요청을 막지 않게 5초 안에 못 잡으면 실패시킨다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS clarify_reason text,
    ADD COLUMN IF NOT EXISTS read_only_document_id text;

COMMENT ON COLUMN chat_messages.clarify_reason IS
    'action=clarify 결과의 이유 코드(markdown_document_required 등). 코드가 없는 되묻기·다른 갈래·질의 메시지·이 컬럼 이전 메시지는 NULL. 사용자 문구로 쓰지 않는다.';
COMMENT ON COLUMN chat_messages.read_only_document_id IS
    'Agent turn 요청 때 사용자가 읽을 수 있고 편집은 못 하는 문서로 서버가 확인한 문서 ID(assistant 메시지만). 그 밖의 메시지는 NULL. 안내 표시에만 쓰고 권한 판단에 쓰지 않는다.';
