\set ON_ERROR_STOP on
-- 일주일 넘게 running으로 멈춘 변환·Skill 작성 실행을 failed로 닫는다. 끝난 실행이 되어야 scrubOrphans가 그 변경 기록을 지운다.
-- close-stuck-runs-preview.sql의 합계를 expected로 준다. 찾은 건수나 바꾼 건수가 다르면 전체를 되돌린다.
-- 선점, 보내지 않은 command, 적용 대기 결과, 변환 대기열 행이 있으면 아직 이어질 수 있어 건드리지 않는다. agent·query는 대상이 아니다.
-- 예약을 풀도록 사용량 수집 대기열에 넣는다(실패한 동기 실행과 같다). 끝난 실행의 늦은 결과는 반영되지 않는다.
-- 실행 예: psql -X ... -v expected=2 -f scripts/sql/close-stuck-runs.sql
BEGIN;
SET LOCAL lock_timeout = '5s';
SELECT set_config('app.close_stuck_runs_expected', :'expected', true);
CREATE TEMP TABLE stuck_runs ON COMMIT DROP AS
SELECT r.id, r.kind
FROM ai_task_runs r
WHERE r.status = 'running' AND r.kind IN ('convert', 'skill_author')
  AND r.updated_at < now() - interval '7 days'
  AND r.resume_claimed_by IS NULL AND r.resume_claimed_until IS NULL
  AND NOT EXISTS (SELECT 1 FROM ai_command_outbox o WHERE o.run_id = r.id)
  AND NOT EXISTS (SELECT 1 FROM agent_apply_projections p WHERE p.run_id = r.id)
  AND NOT EXISTS (SELECT 1 FROM document_convert_queue q WHERE r.id = 'convert:' || q.document_id)
FOR UPDATE OF r;
DO $$
DECLARE
    expected integer := current_setting('app.close_stuck_runs_expected')::integer;
    found_runs integer;
    closed_runs integer;
BEGIN
    SELECT count(*) INTO found_runs FROM stuck_runs;
    IF found_runs <> expected THEN
        RAISE EXCEPTION 'expected % runs, found %', expected, found_runs;
    END IF;
    UPDATE ai_task_runs r SET status = 'failed', error_code = 'stale_run_closed', updated_at = now()
    FROM stuck_runs s WHERE r.id = s.id AND r.status = 'running';
    GET DIAGNOSTICS closed_runs = ROW_COUNT;
    IF closed_runs <> expected THEN
        RAISE EXCEPTION 'expected % runs, closed %', expected, closed_runs;
    END IF;
    INSERT INTO usage_collect_queue (run_id) SELECT id FROM stuck_runs ON CONFLICT (run_id) DO NOTHING;
END $$;
SELECT kind, count(*) AS closed_runs FROM stuck_runs GROUP BY kind ORDER BY kind;
COMMIT;
