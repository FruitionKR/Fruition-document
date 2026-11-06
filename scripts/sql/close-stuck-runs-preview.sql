\set ON_ERROR_STOP on
-- 멈춘 실행(close-stuck-runs.sql 대상)의 종류별 건수만 출력한다. 아무것도 바꾸지 않는다. 조건은 그 파일과 같아야 한다.
-- 실행 예: psql -X ... -f scripts/sql/close-stuck-runs-preview.sql
BEGIN READ ONLY;
SELECT r.kind, count(*) AS runs
FROM ai_task_runs r
WHERE r.status = 'running' AND r.kind IN ('convert', 'skill_author')
  AND r.updated_at < now() - interval '7 days'
  AND r.resume_claimed_by IS NULL AND r.resume_claimed_until IS NULL
  AND NOT EXISTS (SELECT 1 FROM ai_command_outbox o WHERE o.run_id = r.id)
  AND NOT EXISTS (SELECT 1 FROM agent_apply_projections p WHERE p.run_id = r.id)
  AND NOT EXISTS (SELECT 1 FROM document_convert_queue q WHERE r.id = 'convert:' || q.document_id)
GROUP BY r.kind ORDER BY r.kind;
COMMIT;
