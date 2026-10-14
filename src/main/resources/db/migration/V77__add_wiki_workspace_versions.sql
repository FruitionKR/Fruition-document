-- 워크스페이스 위키가 바뀔 때마다 올리는 버전(#68). wiki/graph가 ai를 부르기 전에 이 값으로 ETag를 판단한다.
-- 위키를 바꾸는 작업은 모두 document를 거친다(결과 이벤트 반영, 작업 취소 복구).
-- 행이 없으면 버전 0으로 본다.
CREATE TABLE wiki_workspace_versions (
    workspace_id varchar(255)             PRIMARY KEY,
    version      bigint                   NOT NULL,
    updated_at   timestamp with time zone NOT NULL
);
