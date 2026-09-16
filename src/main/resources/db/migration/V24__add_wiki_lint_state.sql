-- 위키 lint(다듬기) 실행 상태. 마지막 lint 성공 시각을 기록해
-- needs_lint(마지막 lint 이후 위키 페이지 변경 여부) 판단에 쓴다.
-- workspaces는 access_db 소유이므로 FK 없이 ID만 보관한다 (V1 방침).
CREATE TABLE wiki_lint_state (
    workspace_id varchar(255)             NOT NULL,
    last_lint_at timestamp with time zone NOT NULL,
    PRIMARY KEY (workspace_id)
);
