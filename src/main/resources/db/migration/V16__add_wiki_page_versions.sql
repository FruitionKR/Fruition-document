-- Wiki 페이지의 본문 이력. document_content_versions와 같은 형태다.
-- revision은 단조 증가하며 되돌려도 줄지 않는다. 복구도 새 revision을 append한다.
CREATE TABLE wiki_page_versions (
    page_id            varchar(255)             NOT NULL,
    revision           bigint                   NOT NULL,
    -- 그 시점 살아 있던 기여 수. 되돌리면 줄어들 수 있으므로 버전으로 쓰지 않는다.
    -- 같은 값이 서로 다른 revision에 나타날 수 있다(ABA).
    contribution_count integer                  NOT NULL,
    markdown           text                     NOT NULL,
    -- 그 본문이 담긴 불변 object key. 복구는 이 값을 재사용하고 저장소에 쓰지 않는다.
    markdown_key       text                     NOT NULL,
    content_hash       varchar(64)              NOT NULL,
    operation_id       varchar(255),
    created_by         varchar(255),
    created_at         timestamp with time zone NOT NULL,
    PRIMARY KEY (page_id, revision),
    CONSTRAINT fk_wiki_page_versions_page
        FOREIGN KEY (page_id) REFERENCES wiki_pages(id) ON DELETE CASCADE,
    CONSTRAINT fk_wiki_page_versions_operation
        FOREIGN KEY (operation_id) REFERENCES ai_operation_logs(operation_id) ON DELETE SET NULL
);

-- 다음 revision 채번(max)과 이력 조회
CREATE INDEX idx_wiki_page_versions_page
    ON wiki_page_versions(page_id, revision DESC);

-- 페이지를 구성하는 ingest 기여의 현재 활성 상태. 복구 판정이 전부 이 테이블에서 나온다.
-- ai_operation_changes가 "무슨 일이 있었나"(감사 로그)라면 이 테이블은 "지금 누가 받치고 있나"(현재 상태)다.
CREATE TABLE wiki_page_contributions (
    page_id             varchar(255)             NOT NULL,
    ingest_operation_id varchar(255)             NOT NULL,
    source_document_id  varchar(255),
    -- 이 기여가 처음 적용된 페이지 revision. 조립 순서를 정하는 기준이다.
    -- created_at은 작업 시작 시각이라 실제 적용 순서와 어긋날 수 있어 쓰지 않는다.
    sequence_revision   bigint                   NOT NULL,
    -- 재조립에 사용할 불변 기여 조각 key
    object_key          text                     NOT NULL,
    -- 현재 본문에 포함되는지. 복구는 행을 지우지 않고 이 값을 끈다.
    -- 지우면 연속 복구에서 이전에 제외한 기여가 다시 살아난다.
    active              boolean                  NOT NULL DEFAULT true,
    deactivated_by      varchar(255),
    created_at          timestamp with time zone NOT NULL,
    PRIMARY KEY (page_id, ingest_operation_id),
    CONSTRAINT fk_wiki_page_contributions_page
        FOREIGN KEY (page_id) REFERENCES wiki_pages(id) ON DELETE CASCADE,
    CONSTRAINT fk_wiki_page_contributions_operation
        FOREIGN KEY (ingest_operation_id) REFERENCES ai_operation_logs(operation_id),
    CONSTRAINT fk_wiki_page_contributions_source_document
        FOREIGN KEY (source_document_id) REFERENCES documents(id) ON DELETE SET NULL,
    CONSTRAINT fk_wiki_page_contributions_deactivated_by
        FOREIGN KEY (deactivated_by) REFERENCES ai_operation_logs(operation_id)
);

-- 복구 판정의 핵심 인덱스. 살아 있는 기여를 적용 순서대로 읽는다.
CREATE INDEX idx_wiki_page_contributions_active
    ON wiki_page_contributions(page_id, active, sequence_revision);

-- 복구 대상 수집: 특정 작업이 만든 기여 찾기
CREATE INDEX idx_wiki_page_contributions_operation
    ON wiki_page_contributions(ingest_operation_id);
