-- 스킬 참고 문서(origin = 'skill_reference')는 문서 트리에 없으므로 트리 이름 공간에서 뺀다.
-- 참고 문서끼리는 워크스페이스 단위로 이름이 겹치지 않게 별도 고유 인덱스로 막는다.
-- 지금까지 skill_reference 문서는 만들 수 없었으므로 기존 행을 옮길 일은 없다.
CREATE OR REPLACE FUNCTION sync_document_tree_name() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    kind text := CASE WHEN TG_TABLE_NAME = 'documents' THEN 'document' ELSE 'folder' END;
    parent_id uuid;
    item_name text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM document_tree_names WHERE item_type = kind AND item_id = OLD.id::text;
        RETURN OLD;
    END IF;
    IF NEW.deleted_at IS NOT NULL THEN
        DELETE FROM document_tree_names WHERE item_type = kind AND item_id = NEW.id::text;
        RETURN NEW;
    END IF;
    IF kind = 'document' THEN
        -- folders에는 origin이 없으므로 문서일 때만 읽는다.
        IF NEW.origin = 'skill_reference' THEN
            RETURN NEW;
        END IF;
        parent_id := NEW.folder_id;
        item_name := NEW.filename;
    ELSE
        parent_id := NEW.parent_folder_id;
        item_name := NEW.name;
    END IF;
    INSERT INTO document_tree_names (item_type, item_id, workspace_id, parent_folder_id, normalized_name)
    VALUES (kind, NEW.id::text, NEW.workspace_id, parent_id, normalize(lower(normalize(btrim(item_name), NFC)), NFC))
    ON CONFLICT (item_type, item_id) DO UPDATE
    SET workspace_id = EXCLUDED.workspace_id,
        parent_folder_id = EXCLUDED.parent_folder_id,
        normalized_name = EXCLUDED.normalized_name;
    RETURN NEW;
END;
$$;

CREATE UNIQUE INDEX uq_documents_skill_reference_name
    ON documents (workspace_id, (normalize(lower(normalize(btrim(filename), NFC)), NFC)))
    WHERE origin = 'skill_reference' AND deleted_at IS NULL;
