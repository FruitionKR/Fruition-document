-- Agent turn 결과도 채팅 세션에 남긴다. 지금까지 agent 모듈은 chat_messages를 건드리지 않아
-- 새로고침하면 대화가 사라졌다.
--
-- status는 늘리지 않는다. chat_messages.status는 "이 메시지가 만들어졌는가"(completed/failed)를
-- 뜻하고, 승인 여부는 agent_apply_projections.status가 이미 관리한다. 같은 사실을 두 곳이 들면
-- 승인은 됐는데 메시지 갱신이 실패하는 순간 화면과 실제가 어긋난다.
ALTER TABLE chat_messages ADD COLUMN run_id text;
ALTER TABLE chat_messages ADD COLUMN action text;

COMMENT ON COLUMN chat_messages.run_id IS
    'Agent turn이 만든 메시지의 run ID. 승인 상태와 미리보기 본문을 이 run에서 읽는다. 질의 메시지는 NULL.';
COMMENT ON COLUMN chat_messages.action IS
    'AI가 고른 갈래(chat_answer·markdown_edit·markdown_create 등). 화면이 무엇을 그릴지 판단한다. '
    '한번 정해지면 바뀌지 않아 run에서 매번 조회하지 않도록 복사해 둔다. 질의 메시지는 NULL.';

-- 한 run은 assistant 메시지 하나에만 대응한다. 재전송으로 두 번 채워지면 중복 말풍선이 남는다.
CREATE UNIQUE INDEX chat_messages_run_id_key ON chat_messages (run_id) WHERE run_id IS NOT NULL;
