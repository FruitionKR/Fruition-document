ALTER TABLE agent_apply_projections ADD COLUMN autonomous_tool boolean NOT NULL DEFAULT false;

-- 업무 DB의 변경 적용과 취소가 같은 작업 행을 잠그도록 한다.
CREATE TABLE ai_task_runs (
    id text PRIMARY KEY,
    workspace_id text NOT NULL,
    user_id text NOT NULL,
    kind text NOT NULL,
    command jsonb,
    status text NOT NULL DEFAULT 'running',
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ai_task_changes (
    id bigserial PRIMARY KEY,
    run_id text NOT NULL REFERENCES ai_task_runs(id),
    table_name text NOT NULL,
    row_key jsonb NOT NULL,
    before_value jsonb,
    after_value jsonb,
    undone boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_ai_task_changes_run ON ai_task_changes(run_id, id DESC);

CREATE FUNCTION record_ai_task_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    task_id text := nullif(current_setting('app.ai_task_run_id', true), '');
    previous jsonb;
    following jsonb;
    identity jsonb;
BEGIN
    IF task_id IS NULL THEN RETURN NULL; END IF;
    PERFORM 1 FROM ai_task_runs WHERE id = task_id AND status IN ('running', 'completed');
    IF NOT FOUND THEN RAISE EXCEPTION 'AI task no longer accepts changes' USING ERRCODE = '57014'; END IF;
    IF TG_OP <> 'INSERT' THEN previous := to_jsonb(OLD); END IF;
    IF TG_OP <> 'DELETE' THEN following := to_jsonb(NEW); END IF;
    IF previous IS NOT DISTINCT FROM following THEN RETURN NULL; END IF;
    SELECT jsonb_object_agg(key, COALESCE(following, previous)->key) INTO identity
      FROM unnest(string_to_array(TG_ARGV[0], ',')) AS key;
    IF previous IS NOT NULL AND following IS NOT NULL AND EXISTS (
        SELECT 1 FROM jsonb_object_keys(identity) AS key WHERE previous->key IS DISTINCT FROM following->key
    ) THEN RAISE EXCEPTION 'AI task cannot change a primary key'; END IF;
    INSERT INTO ai_task_changes(run_id, table_name, row_key, before_value, after_value)
      VALUES(task_id, TG_TABLE_NAME, identity, previous, following);
    RETURN NULL;
END $$;

DO $$
DECLARE target text; keys text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'documents', 'chat_messages', 'chat_sessions', 'chat_message_references',
        'chat_message_related_pages', 'chat_partial_wiki', 'agent_apply_projections',
        'ai_operation_logs', 'ai_operation_changes', 'wiki_page_versions', 'wiki_page_contributions',
        'wiki_lint_state', 'document_edit_writes', 'document_edit_states', 'document_content_versions',
        'document_assets', 'document_asset_references'
    ] LOOP
        IF to_regclass(target) IS NULL THEN CONTINUE; END IF;
        SELECT string_agg(a.attname, ',' ORDER BY k.ordinality) INTO keys
          FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(num, ordinality)
          JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.num
          WHERE i.indrelid = target::regclass AND i.indisprimary;
        IF keys IS NULL THEN RAISE EXCEPTION 'Missing primary key: %', target; END IF;
        EXECUTE format('CREATE TRIGGER ai_task_change AFTER INSERT OR UPDATE OR DELETE ON %I '
                       'FOR EACH ROW EXECUTE FUNCTION record_ai_task_change(%L)', target, keys);
    END LOOP;
END $$;

-- Python이 역순으로 지정한 변경 한 건만 복구한다. 동시 수정과 연쇄 삭제는 거절한다.
CREATE FUNCTION undo_ai_task_change(task_id text, change_id bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    change ai_task_changes%ROWTYPE;
    current_value jsonb;
    predicate text;
    columns text;
    reference record;
    reference_predicate text;
    has_reference boolean;
BEGIN
    PERFORM 1 FROM ai_task_runs WHERE id = task_id AND status IN ('cancel_requested', 'rolling_back') FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Task is not rolling back' USING ERRCODE = '40001'; END IF;
    SELECT * INTO STRICT change FROM ai_task_changes WHERE id = change_id AND run_id = task_id FOR UPDATE;
    IF change.undone THEN RETURN; END IF;
    IF EXISTS (SELECT 1 FROM ai_task_changes WHERE run_id = task_id AND id > change_id AND NOT undone)
        THEN RAISE EXCEPTION 'Rollback order mismatch' USING ERRCODE = '40001'; END IF;
    SELECT string_agg(format('t.%I IS NOT DISTINCT FROM k.%I', key, key), ' AND ')
      INTO predicate FROM jsonb_object_keys(change.row_key) key;
    EXECUTE format('SELECT to_jsonb(t) FROM %I t, jsonb_populate_record(NULL::%I, $1) k WHERE %s FOR UPDATE OF t',
                   change.table_name, change.table_name, predicate) INTO current_value USING change.row_key;
    IF current_value IS DISTINCT FROM change.after_value
        THEN RAISE EXCEPTION 'Rollback conflicts with a later change' USING ERRCODE = '40001'; END IF;
    PERFORM set_config('app.ai_task_run_id', '', true);
    IF change.before_value IS NULL THEN
        FOR reference IN
            SELECT c.oid, c.conrelid::regclass AS child_table,
                   array_agg(child.attname ORDER BY pair.ordinality) AS child_keys,
                   array_agg(parent.attname ORDER BY pair.ordinality) AS parent_keys
            FROM pg_constraint c
            CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY pair(child_no, parent_no, ordinality)
            JOIN pg_attribute child ON child.attrelid = c.conrelid AND child.attnum = pair.child_no
            JOIN pg_attribute parent ON parent.attrelid = c.confrelid AND parent.attnum = pair.parent_no
            WHERE c.contype = 'f' AND c.confrelid = change.table_name::regclass GROUP BY c.oid, c.conrelid
        LOOP
            SELECT string_agg(format('to_jsonb(t)->%L = $1->%L', child_key, parent_key), ' AND ')
              INTO reference_predicate FROM unnest(reference.child_keys, reference.parent_keys) pair(child_key, parent_key);
            EXECUTE format('SELECT EXISTS(SELECT 1 FROM %s t WHERE %s)', reference.child_table, reference_predicate)
              INTO has_reference USING current_value;
            IF has_reference THEN RAISE EXCEPTION 'Rollback target has surviving references' USING ERRCODE = '40001'; END IF;
        END LOOP;
        EXECUTE format('DELETE FROM %I t USING jsonb_populate_record(NULL::%I, $1) k WHERE %s',
                       change.table_name, change.table_name, predicate) USING change.row_key;
    ELSIF current_value IS NULL THEN
        EXECUTE format('INSERT INTO %I SELECT * FROM jsonb_populate_record(NULL::%I, $1)',
                       change.table_name, change.table_name) USING change.before_value;
    ELSE
        SELECT string_agg(format('%I', key), ', ') INTO columns FROM jsonb_object_keys(change.before_value) key;
        EXECUTE format('UPDATE %I t SET (%s) = (SELECT %s FROM jsonb_populate_record(NULL::%I, $1)) '
                       'FROM jsonb_populate_record(NULL::%I, $2) k WHERE %s',
                       change.table_name, columns, columns, change.table_name, change.table_name, predicate)
          USING change.before_value, change.row_key;
    END IF;
    UPDATE ai_task_changes SET undone = true WHERE id = change_id;
END $$;

-- 복구한 본문에도 새 revision을 부여한다. 늦은 편집 이벤트가 복구 결과를 뒤집지 못한다.
CREATE FUNCTION finalize_ai_task_edits(task_id text) RETURNS SETOF document_edit_outbox LANGUAGE plpgsql AS $$
DECLARE
    task ai_task_runs%ROWTYPE;
    first_change record;
    current_value jsonb;
    next_revision bigint;
    restored_hash text;
    event_key text;
BEGIN
    SELECT * INTO STRICT task FROM ai_task_runs WHERE id = task_id FOR UPDATE;
    IF task.status NOT IN ('cancel_requested', 'rolling_back') OR EXISTS (
        SELECT 1 FROM ai_task_changes WHERE run_id = task_id AND NOT undone
    ) THEN RAISE EXCEPTION 'Rollback is incomplete' USING ERRCODE = '40001'; END IF;
    PERFORM set_config('app.ai_task_run_id', '', true);
    FOR first_change IN
        SELECT DISTINCT ON (row_key->>'document_id') row_key->>'document_id' AS document_id, before_value
        FROM ai_task_changes WHERE run_id = task_id AND table_name = 'document_edit_states'
        ORDER BY row_key->>'document_id', id
    LOOP
        event_key := 'rollback:' || task_id || ':' || first_change.document_id;
        IF EXISTS (SELECT 1 FROM document_edit_outbox WHERE event_id = event_key) THEN CONTINUE; END IF;
        SELECT to_jsonb(t) INTO current_value FROM document_edit_states t
            WHERE document_id = first_change.document_id FOR UPDATE;
        IF current_value IS DISTINCT FROM first_change.before_value
            THEN RAISE EXCEPTION 'Restored document changed before finalization' USING ERRCODE = '40001'; END IF;
        SELECT COALESCE(max((after_value->>'revision')::bigint), 0) + 1 INTO next_revision
            FROM ai_task_changes WHERE run_id = task_id AND table_name = 'document_edit_states'
            AND row_key->>'document_id' = first_change.document_id;
        IF current_value IS NOT NULL THEN
            UPDATE document_edit_states SET revision = next_revision, updated_at = now()
                WHERE document_id = first_change.document_id;
            restored_hash := current_value->>'content_hash';
        ELSE
            -- 삭제된 생성 문서의 이벤트도 높은 revision의 운영 tombstone으로 흡수한다.
            restored_hash := repeat('0', 64);
        END IF;
        INSERT INTO document_edit_outbox(event_id, document_id, workspace_id, revision, content_hash, event_type, schema_version, created_at)
            VALUES(event_key, first_change.document_id, task.workspace_id, next_revision, restored_hash,
                   'document.edit.saved.v1', 1, now());
    END LOOP;
    RETURN QUERY SELECT * FROM document_edit_outbox WHERE event_id IN (
        SELECT 'rollback:' || task_id || ':' || (row_key->>'document_id')
        FROM ai_task_changes WHERE run_id = task_id AND table_name = 'document_edit_states'
    );
END $$;
