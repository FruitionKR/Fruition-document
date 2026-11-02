-- Notion 등 외부 내보내기 가져오기 세션(DocumentImportService). 클라이언트가 파일을 풀어 문서·폴더·이미지를 하나씩 만들 때
-- X-Fruition-Import-Id로 이 세션을 가리키면 만든 문서를 세션으로 표시하고 항목 수(items_used, 상한 없음)를 센다.
-- 저장 용량(5GB)과 파일별 한도는 그대로 적용한다. 세션은 24시간 뒤 만료되고, 완료(completed)하면 더 쓸 수 없다.
-- 참고: 하루 업로드 개수 한도를 없애면서 V86의 user_upload_events는 더 쓰지 않는다. expand-only 원칙으로 테이블은 남겨 둔다.
-- documents.import_id는 그 세션에서 만든 문서 표시다. 가져온 문서는 자동 편입하지 않고 사용자가 고른 뒤에만 편입한다.
-- FK는 두지 않는다: documents에 FK를 걸면 검증 동안 잠금이 길어지고, 세션 행을 지우는 정리도 문서와 묶이지 않게 한다.
-- 새 테이블은 같은 트랜잭션에서 만든 빈 테이블이라 잠금을 다투지 않는다. documents ADD COLUMN(기본값 없음)은
-- 메타데이터만 바꾸지만 ACCESS EXCLUSIVE가 필요해 5초 안에 못 잡으면 실패한다. 인덱스는 CONCURRENTLY가 필요해 V91로 나눈다.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS document_imports (
    id               UUID PRIMARY KEY,
    workspace_id     VARCHAR(255) NOT NULL,
    user_id          VARCHAR(255) NOT NULL,
    source           VARCHAR(32)  NOT NULL CHECK (source IN ('notion')),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('open', 'completed')),
    declared_items   INT          NOT NULL CHECK (declared_items >= 0),
    declared_bytes   BIGINT       NOT NULL CHECK (declared_bytes >= 0),
    items_used       INT          NOT NULL DEFAULT 0 CHECK (items_used >= 0),
    reported_created INT,
    reported_failed  INT,
    reported_skipped INT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    completed_at     TIMESTAMPTZ
);

ALTER TABLE documents ADD COLUMN IF NOT EXISTS import_id UUID;
