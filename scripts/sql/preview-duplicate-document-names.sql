\set ON_ERROR_STOP on
-- 문서 데이터를 바꾸지 않고 기존 중복의 이름 변경안을 CSV로 출력한다.
-- 실행 예: psql -Xq ... -f scripts/sql/preview-duplicate-document-names.sql > document-name-plan.csv
BEGIN ISOLATION LEVEL REPEATABLE READ;
CREATE TEMP TABLE name_pool ON COMMIT DROP AS
SELECT DISTINCT workspace_id, lower(normalize(btrim(filename), NFC)) AS name
FROM documents WHERE deleted_at IS NULL;
ALTER TABLE name_pool ADD PRIMARY KEY (workspace_id, name);
CREATE TEMP TABLE name_plan (
    document_id varchar(255) PRIMARY KEY,
    workspace_id varchar(255) NOT NULL,
    previous_filename varchar(255) NOT NULL,
    previous_display_name varchar(255) NOT NULL,
    previous_normalized_filename varchar(255) NOT NULL,
    previous_version bigint NOT NULL,
    next_filename varchar(255) NOT NULL
) ON COMMIT DROP;

DO $$
DECLARE
    document_record record;
    stem text;
    extension text;
    parts text[];
    suffix text;
    candidate text;
    number integer;
BEGIN
    FOR document_record IN
        SELECT * FROM (
            SELECT id, workspace_id, filename, display_name, normalized_filename, current_version,
                row_number() OVER (
                    PARTITION BY workspace_id, lower(normalize(btrim(filename), NFC))
                    ORDER BY uploaded_at, id
                ) AS duplicate_number
            FROM documents WHERE deleted_at IS NULL
        ) ranked
        WHERE duplicate_number > 1
        ORDER BY workspace_id, lower(normalize(btrim(filename), NFC)), duplicate_number
    LOOP
        stem := normalize(btrim(document_record.filename), NFC);
        extension := '';
        parts := regexp_match(stem, '^(.+)(\.[^.]+)$');
        IF parts IS NOT NULL THEN
            stem := parts[1];
            extension := parts[2];
        END IF;
        number := 2;
        LOOP
            suffix := ' (' || number || ')';
            candidate := rtrim(left(stem, 255 - char_length(extension) - char_length(suffix))) || suffix || extension;
            EXIT WHEN NOT EXISTS (
                SELECT 1 FROM name_pool
                WHERE workspace_id = document_record.workspace_id
                  AND name = lower(normalize(btrim(candidate), NFC))
            );
            number := number + 1;
        END LOOP;
        INSERT INTO name_pool VALUES (document_record.workspace_id, lower(normalize(btrim(candidate), NFC)));
        INSERT INTO name_plan VALUES (
            document_record.id, document_record.workspace_id, document_record.filename,
            document_record.display_name, document_record.normalized_filename,
            document_record.current_version, candidate
        );
    END LOOP;
END;
$$;

COPY (SELECT * FROM name_plan ORDER BY workspace_id, previous_filename, document_id) TO STDOUT WITH CSV HEADER;
ROLLBACK;
