-- Files and folders share one active namespace per parent (NULL is the workspace root).
-- A shared unique index, rather than a check-then-insert trigger, arbitrates concurrent
-- writes across both tables, including moves, restores and conversion output.
-- Existing cross-kind collisions must be resolved before deployment; never rename user data.
LOCK TABLE documents, folders IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE document_tree_names (
    item_type varchar(8) NOT NULL CHECK (item_type IN ('document', 'folder')),
    item_id varchar(255) NOT NULL,
    workspace_id varchar(255) NOT NULL,
    parent_folder_id uuid,
    normalized_name text NOT NULL,
    PRIMARY KEY (item_type, item_id),
    CONSTRAINT uq_document_tree_active_name
        UNIQUE NULLS NOT DISTINCT (workspace_id, parent_folder_id, normalized_name)
);

INSERT INTO document_tree_names
SELECT 'document', id, workspace_id, folder_id, normalize(lower(normalize(btrim(filename), NFC)), NFC)
FROM documents WHERE deleted_at IS NULL
UNION ALL
SELECT 'folder', id::text, workspace_id, parent_folder_id, normalize(lower(normalize(btrim(name), NFC)), NFC)
FROM folders WHERE deleted_at IS NULL;

CREATE FUNCTION sync_document_tree_name() RETURNS trigger
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

CREATE TRIGGER documents_tree_name
AFTER INSERT OR DELETE OR UPDATE OF filename, folder_id, workspace_id, deleted_at ON documents
FOR EACH ROW EXECUTE FUNCTION sync_document_tree_name();

CREATE TRIGGER folders_tree_name
AFTER INSERT OR DELETE OR UPDATE OF name, parent_folder_id, workspace_id, deleted_at ON folders
FOR EACH ROW EXECUTE FUNCTION sync_document_tree_name();

DROP INDEX uq_documents_active_name;
DROP INDEX uq_folders_active_name;
