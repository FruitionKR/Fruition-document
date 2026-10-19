-- 본문 저장 자동 병합(ADR 0032). 기준 revision이 지나간 저장은 409 대신 서버가 줄 단위 3-way merge한다.
-- 버전마다 자동 병합 결과를 남긴다: merged(합친 본) | overwritten(겹쳐서 요청 본으로 덮어씀) |
-- conflict_archived(아래에서 닫는 편집 충돌의 본문을 옮겨 둔 버전). 그 밖의 저장은 NULL이다.
ALTER TABLE document_content_versions
    ADD COLUMN merge_status VARCHAR(32);

-- 이미 열린 편집 충돌은 OWNER가 고를 화면이 없어지므로 여기서 닫는다. 충돌 본문은 버전 이력에 옮겨 복원할 수 있게 하고
-- (데이터 유실 없음), 현재 본문은 그대로 둔다. 버전 번호는 편집 revision과 같은 수열이라 다음 저장과 겹치지 않도록
-- 문서마다 revision을 올린다:
--   현재 revision R, 열린 충돌 n건(등록 순) → R+1..R+n = 충돌 본문(conflict_archived), R+n+1 = 현재 본문(R에서 복원)
-- 편집 상태가 없는 문서(본문을 한 번도 저장하지 않은 문서)의 충돌은 409를 받을 수 없어 생기지 않는다. 그래도 있으면 열린 채 둔다.
CREATE TEMPORARY TABLE v80_open_conflicts ON COMMIT DROP AS
SELECT c.id,
       c.document_id,
       c.markdown,
       c.content_hash,
       c.author_user_id,
       c.created_at,
       s.revision AS state_revision,
       row_number() OVER (PARTITION BY c.document_id ORDER BY c.created_at, c.id) AS seq,
       count(*) OVER (PARTITION BY c.document_id) AS open_count
FROM document_edit_conflicts c
JOIN document_edit_states s ON s.document_id = c.document_id
WHERE c.status = 'open';

-- 복원 대상인 현재 revision R의 스냅샷이 없으면 먼저 남긴다.
INSERT INTO document_content_versions(document_id, version, markdown, content_hash, created_by, created_at)
SELECT s.document_id, s.revision, s.markdown, s.content_hash, COALESCE(d.updated_by, d.user_id), s.updated_at
FROM document_edit_states s
JOIN documents d ON d.id = s.document_id
WHERE s.document_id IN (SELECT document_id FROM v80_open_conflicts)
ON CONFLICT (document_id, version) DO NOTHING;

-- 충돌 본문. R보다 큰 버전은 아직 없어야 한다(있으면 PK 위반으로 마이그레이션이 멈춰 덮어쓰지 않는다).
INSERT INTO document_content_versions(
    document_id, version, markdown, content_hash, created_by, created_at, source, merge_status)
SELECT document_id, state_revision + seq, markdown, content_hash, author_user_id, created_at, 'user', 'conflict_archived'
FROM v80_open_conflicts;

-- 현재 본문을 맨 위 버전으로 다시 남긴다(R에서 복원).
INSERT INTO document_content_versions(
    document_id, version, markdown, content_hash, created_by, created_at, restored_from_version, source)
SELECT s.document_id, s.revision + o.open_count + 1, s.markdown, s.content_hash,
       COALESCE(d.updated_by, d.user_id), now(), s.revision, 'restore'
FROM document_edit_states s
JOIN documents d ON d.id = s.document_id
JOIN (SELECT DISTINCT document_id, open_count FROM v80_open_conflicts) o ON o.document_id = s.document_id;

-- 다른 서비스가 최신 revision을 알도록 저장 event도 남긴다(본문 해시는 그대로).
INSERT INTO document_edit_outbox(
    event_id, document_id, workspace_id, revision, content_hash, event_type, schema_version, created_at, published)
SELECT 'v80-conflict-archive:' || s.document_id || ':' || (s.revision + o.open_count + 1),
       s.document_id, d.workspace_id, s.revision + o.open_count + 1, s.content_hash,
       'document.edit.saved.v1', 1, now(), false
FROM document_edit_states s
JOIN documents d ON d.id = s.document_id
JOIN (SELECT DISTINCT document_id, open_count FROM v80_open_conflicts) o ON o.document_id = s.document_id;

UPDATE document_edit_states s
SET revision = s.revision + o.open_count + 1,
    updated_at = now()
FROM (SELECT DISTINCT document_id, open_count FROM v80_open_conflicts) o
WHERE o.document_id = s.document_id;

-- 서버 본을 유지했으므로 resolution은 server, resolved_revision은 충돌 본문을 옮겨 둔 버전이다.
UPDATE document_edit_conflicts c
SET status = 'resolved',
    resolution = 'server',
    resolved_by = 'system:auto-merge-migration',
    resolved_revision = o.state_revision + o.seq,
    resolved_at = now()
FROM v80_open_conflicts o
WHERE o.id = c.id;
