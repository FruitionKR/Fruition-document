-- AI 에이전트 계획 자동 승인 사용자 설정. 사용자 단위로 모든 워크스페이스에 적용된다.
-- 행이 없으면 모든 항목이 꺼진 것(지금처럼 매번 승인)으로 본다. 새 테이블이라 기존 테이블 잠금이 없다.
CREATE TABLE IF NOT EXISTS agent_auto_approval_settings (
    user_id VARCHAR(255) PRIMARY KEY,
    -- 문서 편집·생성(apply_document_edit, create_document)
    content_edit BOOLEAN NOT NULL DEFAULT FALSE,
    -- 폴더 생성·이동·이름 변경(create_folder, move_*, rename_*)
    organize BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
