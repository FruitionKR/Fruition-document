-- Agent/Skill 저장소와 LangGraph PostgreSQL checkpoint는 document-svc가
-- core_db에서 Flyway로 생성한다. pipeline worker는 운영 중 DDL을 실행하지 않는다.

CREATE TABLE IF NOT EXISTS public.skills (
    id text PRIMARY KEY,
    workspace_id text,
    scope_type text NOT NULL,
    owner_user_id text,
    command varchar(63) NOT NULL,
    status text NOT NULL,
    enabled_version_id text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skills_scope_check CHECK (
        (scope_type = 'personal' AND workspace_id IS NULL AND owner_user_id IS NOT NULL)
        OR (scope_type = 'team' AND workspace_id IS NOT NULL AND owner_user_id IS NULL)
    ),
    CONSTRAINT skills_status_check CHECK (status IN ('enabled', 'disabled'))
);

CREATE TABLE IF NOT EXISTS public.skill_versions (
    id text PRIMARY KEY,
    skill_id text NOT NULL REFERENCES public.skills(id) ON DELETE CASCADE,
    version integer NOT NULL,
    name varchar(63) NOT NULL,
    description text NOT NULL,
    instructions_markdown text NOT NULL,
    capabilities text[] NOT NULL DEFAULT ARRAY[]::text[],
    allowed_tools text[] NOT NULL DEFAULT ARRAY[]::text[],
    safety_result jsonb NOT NULL DEFAULT '{}'::jsonb,
    status text NOT NULL,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    CONSTRAINT skill_versions_version_check CHECK (version > 0),
    CONSTRAINT skill_versions_status_check CHECK (status IN ('draft', 'published', 'rejected')),
    CONSTRAINT skill_versions_unique_version UNIQUE (skill_id, version)
);

ALTER TABLE public.skills
    ADD CONSTRAINT skills_enabled_version_fk
    FOREIGN KEY (enabled_version_id) REFERENCES public.skill_versions(id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE IF NOT EXISTS public.agent_runs (
    id text PRIMARY KEY,
    workspace_id text NOT NULL,
    user_id text NOT NULL,
    action text NOT NULL,
    skill_version_id text REFERENCES public.skill_versions(id) ON DELETE SET NULL,
    status text NOT NULL,
    request_summary text NOT NULL,
    current_plan_id text,
    error_code text,
    tool_call_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CONSTRAINT agent_runs_tool_call_count_check CHECK (tool_call_count BETWEEN 0 AND 40)
);

CREATE TABLE IF NOT EXISTS public.skill_version_sources (
    id text PRIMARY KEY,
    skill_version_id text NOT NULL REFERENCES public.skill_versions(id) ON DELETE CASCADE,
    source_agent_run_id text REFERENCES public.agent_runs(id) ON DELETE SET NULL,
    source_turn_id text,
    source_type text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.agent_plans (
    id text PRIMARY KEY,
    run_id text NOT NULL REFERENCES public.agent_runs(id) ON DELETE CASCADE,
    version integer NOT NULL,
    summary text NOT NULL,
    operation_hash text NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_plans_version_check CHECK (version > 0),
    CONSTRAINT agent_plans_unique_version UNIQUE (run_id, version)
);

ALTER TABLE public.agent_runs
    ADD CONSTRAINT agent_runs_current_plan_fk
    FOREIGN KEY (current_plan_id) REFERENCES public.agent_plans(id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE IF NOT EXISTS public.agent_plan_operations (
    id text PRIMARY KEY,
    plan_id text NOT NULL REFERENCES public.agent_plans(id) ON DELETE CASCADE,
    sequence integer NOT NULL,
    tool_name text NOT NULL,
    target_type text NOT NULL,
    target_id text,
    base_version bigint,
    source_parent_id text,
    destination_parent_id text,
    arguments jsonb NOT NULL DEFAULT '{}'::jsonb,
    reason text NOT NULL,
    depends_on text[] NOT NULL DEFAULT ARRAY[]::text[],
    status text NOT NULL,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_plan_operations_unique_sequence UNIQUE (plan_id, sequence)
);

CREATE TABLE IF NOT EXISTS public.agent_approvals (
    id text PRIMARY KEY,
    run_id text NOT NULL REFERENCES public.agent_runs(id) ON DELETE CASCADE,
    plan_id text NOT NULL REFERENCES public.agent_plans(id) ON DELETE CASCADE,
    plan_version integer NOT NULL,
    operation_hash text NOT NULL,
    user_id text NOT NULL,
    decision text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.agent_jobs (
    id text PRIMARY KEY,
    run_id text NOT NULL REFERENCES public.agent_runs(id) ON DELETE CASCADE,
    job_type text NOT NULL,
    status text NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_owner text,
    lease_token text,
    leased_until timestamptz,
    heartbeat_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_jobs_attempt_count_check CHECK (attempt_count >= 0)
);

CREATE TABLE IF NOT EXISTS public.agent_tool_executions (
    id text PRIMARY KEY,
    run_id text NOT NULL REFERENCES public.agent_runs(id) ON DELETE CASCADE,
    plan_id text NOT NULL REFERENCES public.agent_plans(id) ON DELETE CASCADE,
    operation_id text NOT NULL REFERENCES public.agent_plan_operations(id) ON DELETE CASCADE,
    tool_name text NOT NULL,
    idempotency_key text NOT NULL,
    attempt integer NOT NULL,
    status text NOT NULL,
    response_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code text,
    finished_at timestamptz,
    CONSTRAINT agent_tool_executions_unique_idempotency UNIQUE (idempotency_key)
);

CREATE TABLE IF NOT EXISTS public.agent_run_artifacts (
    id text PRIMARY KEY,
    run_id text NOT NULL REFERENCES public.agent_runs(id) ON DELETE CASCADE,
    workspace_id text NOT NULL,
    user_id text NOT NULL,
    content_hash text NOT NULL,
    purpose text NOT NULL,
    document_id text,
    base_version bigint,
    target jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_skills_personal_command
    ON public.skills (owner_user_id, command)
    WHERE scope_type = 'personal';
CREATE UNIQUE INDEX IF NOT EXISTS uq_skills_team_command
    ON public.skills (workspace_id, command)
    WHERE scope_type = 'team';
CREATE INDEX IF NOT EXISTS idx_skill_versions_skill_status
    ON public.skill_versions (skill_id, status, version DESC);
CREATE INDEX IF NOT EXISTS idx_skill_version_sources_run
    ON public.skill_version_sources (source_agent_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_runs_actor_status
    ON public.agent_runs (workspace_id, user_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_jobs_claim
    ON public.agent_jobs (status, available_at, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_jobs_run
    ON public.agent_jobs (run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_agent_plan_operations_plan_status
    ON public.agent_plan_operations (plan_id, status, sequence);
CREATE INDEX IF NOT EXISTS idx_agent_tool_executions_run
    ON public.agent_tool_executions (run_id, plan_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_artifacts_actor
    ON public.agent_run_artifacts (workspace_id, user_id, run_id);

-- langgraph-checkpoint-postgres BasePostgresSaver.MIGRATIONS 계약.
CREATE TABLE IF NOT EXISTS public.checkpoint_migrations (
    v integer PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS public.checkpoints (
    thread_id text NOT NULL,
    checkpoint_ns text NOT NULL DEFAULT '',
    checkpoint_id text NOT NULL,
    parent_checkpoint_id text,
    type text,
    checkpoint jsonb NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (thread_id, checkpoint_ns, checkpoint_id)
);

CREATE TABLE IF NOT EXISTS public.checkpoint_blobs (
    thread_id text NOT NULL,
    checkpoint_ns text NOT NULL DEFAULT '',
    channel text NOT NULL,
    version text NOT NULL,
    type text NOT NULL,
    blob bytea,
    PRIMARY KEY (thread_id, checkpoint_ns, channel, version)
);

CREATE TABLE IF NOT EXISTS public.checkpoint_writes (
    thread_id text NOT NULL,
    checkpoint_ns text NOT NULL DEFAULT '',
    checkpoint_id text NOT NULL,
    task_id text NOT NULL,
    idx integer NOT NULL,
    channel text NOT NULL,
    type text,
    blob bytea NOT NULL,
    task_path text NOT NULL DEFAULT '',
    PRIMARY KEY (thread_id, checkpoint_ns, checkpoint_id, task_id, idx)
);

INSERT INTO public.checkpoint_migrations (v)
VALUES (9)
ON CONFLICT (v) DO NOTHING;

CREATE INDEX IF NOT EXISTS checkpoints_thread_id_idx
    ON public.checkpoints (thread_id);
CREATE INDEX IF NOT EXISTS checkpoint_blobs_thread_id_idx
    ON public.checkpoint_blobs (thread_id);
CREATE INDEX IF NOT EXISTS checkpoint_writes_thread_id_idx
    ON public.checkpoint_writes (thread_id);
