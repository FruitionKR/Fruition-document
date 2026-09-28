-- 회의 받아쓰기. 회의는 만든 사람만 조회·변경한다(created_by). 녹음 원본·회의록 초안 컬럼은 해당 기능과 함께 추가한다.
CREATE TABLE meetings (
    id            VARCHAR(64)  PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL,
    created_by    VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(200) NOT NULL,
    document_id   VARCHAR(64),
    source        VARCHAR(16)  NOT NULL CHECK (source IN ('live', 'upload')),
    status        VARCHAR(16)  NOT NULL CHECK (status IN ('open', 'awaiting_upload', 'transcribing', 'failed')),
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_meetings_owner ON meetings (workspace_id, created_by);

-- 받아쓰기 연결 한 번. end_reason NULL은 진행 중이거나 인스턴스가 죽어 기록하지 못한 연결이다.
CREATE TABLE meeting_streams (
    id            VARCHAR(64)  PRIMARY KEY,
    meeting_id    VARCHAR(64)  NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    stream_order  INT          NOT NULL,
    end_reason    VARCHAR(16)  CHECK (end_reason IN ('finished', 'interrupted', 'failed')),
    started_at    TIMESTAMPTZ  NOT NULL,
    ended_at      TIMESTAMPTZ,
    UNIQUE (meeting_id, stream_order)
);

-- 발화 순서는 committed 시점의 position이다. text NULL은 확정 전(연결이 끊겼으면 누락)이다.
CREATE TABLE meeting_segments (
    meeting_id  VARCHAR(64)  NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    id          VARCHAR(128) NOT NULL,
    stream_id   VARCHAR(64)  NOT NULL REFERENCES meeting_streams(id) ON DELETE CASCADE,
    position    INT          NOT NULL,
    text        TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (meeting_id, id),
    UNIQUE (meeting_id, position)
);
CREATE INDEX idx_meeting_segments_stream ON meeting_segments (stream_id);
