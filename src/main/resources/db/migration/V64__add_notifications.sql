-- 앱 안 알림. audience='user'는 recipient_user_id 한 명에게, 'workspace_owners'는 조회 시점의 워크스페이스 OWNER에게 보인다.
-- OWNER 목록을 주는 access 내부 API가 없어 OWNER 대상 알림은 한 행만 넣고 조회할 때 역할로 거른다.
CREATE TABLE notifications (
    id                uuid PRIMARY KEY,
    workspace_id      varchar(255)             NOT NULL,
    recipient_user_id varchar(255),
    audience          varchar(32)              NOT NULL CHECK (audience IN ('user', 'workspace_owners')),
    type              varchar(64)              NOT NULL,
    payload           jsonb                    NOT NULL,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT notifications_recipient CHECK ((audience = 'user') = (recipient_user_id IS NOT NULL))
);

CREATE INDEX idx_notifications_workspace ON notifications (workspace_id, created_at DESC);

-- 읽음은 사용자마다 한 행. OWNER 대상 알림은 OWNER마다 따로 읽는다.
CREATE TABLE notification_reads (
    notification_id uuid                     NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    user_id         varchar(255)             NOT NULL,
    read_at         timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (notification_id, user_id)
);
