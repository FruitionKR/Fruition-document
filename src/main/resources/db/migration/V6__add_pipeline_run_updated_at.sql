-- pipeline 실행 중 마지막 heartbeat 시각을 pipeline_runs에도 기록한다.
ALTER TABLE public.pipeline_runs
    ADD COLUMN IF NOT EXISTS updated_at timestamp with time zone;

UPDATE public.pipeline_runs
SET updated_at = COALESCE(finished_at, created_at, now())
WHERE updated_at IS NULL;

ALTER TABLE public.pipeline_runs
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL;
