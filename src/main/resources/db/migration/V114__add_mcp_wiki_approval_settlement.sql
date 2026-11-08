CREATE TABLE mcp_wiki_drafts (
    draft_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    draft_kind TEXT NOT NULL CHECK (draft_kind IN ('INGEST', 'LINT')),
    draft_version BIGINT NOT NULL CHECK (draft_version > 0),
    draft_hash TEXT NOT NULL CHECK (draft_hash ~ '^sha256:[0-9a-f]{64}$'),
    public_state_hash TEXT NOT NULL CHECK (public_state_hash ~ '^sha256:[0-9a-f]{64}$'),
    workspace_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    grant_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    client_name TEXT CHECK (client_name IS NULL OR btrim(client_name) <> ''),
    policy_generation BIGINT NOT NULL CHECK (policy_generation > 0),
    source_snapshots JSONB NOT NULL CHECK (jsonb_typeof(source_snapshots) = 'array'),
    preview JSONB NOT NULL CHECK (jsonb_typeof(preview) = 'object'),
    pricing_model TEXT NOT NULL CHECK (pricing_model IN ('INGEST_SOURCE_QUANTITY', 'LINT_FIXED_APPLY')),
    currency TEXT NOT NULL CHECK (currency = 'KRW'),
    price_version TEXT NOT NULL CHECK (btrim(price_version) <> ''),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    price_unit TEXT NOT NULL CHECK (btrim(price_unit) <> ''),
    price_formula TEXT NOT NULL CHECK (btrim(price_formula) <> ''),
    maximum_charge_minor BIGINT NOT NULL CHECK (maximum_charge_minor >= 0),
    approval_id UUID UNIQUE,
    operation_id TEXT UNIQUE,
    canonical_approval_hash TEXT CHECK (canonical_approval_hash IS NULL OR canonical_approval_hash ~ '^sha256:[0-9a-f]{64}$'),
    approval_status TEXT NOT NULL DEFAULT 'AWAITING'
        CHECK (approval_status IN ('AWAITING', 'INVALIDATED', 'REJECTED', 'EXECUTING')),
    approval_reason TEXT,
    publication_status TEXT NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (publication_status IN ('NOT_STARTED', 'APPLYING', 'PUBLISHED', 'NO_CHANGE', 'CONFLICTED', 'HALTED', 'UNKNOWN')),
    settlement_status TEXT NOT NULL DEFAULT 'NOT_RESERVED'
        CHECK (settlement_status IN ('NOT_RESERVED', 'RESERVED', 'PENDING_RECONCILIATION', 'SETTLED', 'RELEASED', 'ADJUSTED')),
    reserved_credit_milli BIGINT NOT NULL DEFAULT 0 CHECK (reserved_credit_milli >= 0),
    charged_minor BIGINT NOT NULL DEFAULT 0 CHECK (charged_minor >= 0),
    changed BOOLEAN,
    published_reference_hash TEXT,
    publication_committed_at TIMESTAMPTZ,
    publication_error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_mcp_wiki_draft_approval_intent
    ON mcp_wiki_drafts (draft_id, draft_version, draft_hash, public_state_hash);
CREATE INDEX idx_mcp_wiki_draft_owner ON mcp_wiki_drafts (workspace_id, user_id, updated_at DESC);
CREATE INDEX idx_mcp_wiki_draft_reconcile
    ON mcp_wiki_drafts (settlement_status, updated_at)
    WHERE settlement_status IN ('RESERVED', 'PENDING_RECONCILIATION');

CREATE TABLE mcp_wiki_ingest_handles (
    run_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL,
    snapshot_id UUID NOT NULL UNIQUE,
    document_id TEXT NOT NULL,
    source_version BIGINT NOT NULL CHECK (source_version > 0),
    source_content_hash TEXT NOT NULL CHECK (source_content_hash ~ '^sha256:[0-9a-f]{64}$'),
    workspace_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    grant_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    policy_generation BIGINT NOT NULL CHECK (policy_generation > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mcp_wiki_lint_intents (
    workspace_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    grant_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    policy_generation BIGINT NOT NULL CHECK (policy_generation > 0),
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    source_snapshots JSONB NOT NULL CHECK (jsonb_typeof(source_snapshots) = 'array'),
    draft_id UUID,
    PRIMARY KEY (workspace_id, user_id, grant_id, client_id, idempotency_key)
);

CREATE TABLE mcp_wiki_ingest_windows (
    run_id UUID NOT NULL REFERENCES mcp_wiki_ingest_handles(run_id) ON DELETE CASCADE,
    window_id UUID NOT NULL,
    window_ordinal BIGINT NOT NULL CHECK (window_ordinal > 0),
    window_hash TEXT NOT NULL CHECK (window_hash ~ '^sha256:[0-9a-f]{64}$'),
    accepted_submission_hash TEXT CHECK (accepted_submission_hash IS NULL OR accepted_submission_hash ~ '^sha256:[0-9a-f]{64}$'),
    contributions JSONB,
    PRIMARY KEY (run_id, window_id),
    UNIQUE (run_id, window_ordinal)
);

CREATE TABLE mcp_wiki_publication_inbox (
    event_id UUID PRIMARY KEY,
    approval_id UUID NOT NULL,
    operation_id TEXT NOT NULL,
    draft_id UUID NOT NULL,
    canonical_hash TEXT NOT NULL CHECK (canonical_hash ~ '^sha256:[0-9a-f]{64}$'),
    outcome TEXT NOT NULL CHECK (outcome IN ('PUBLISHED', 'NO_CHANGE')),
    changed BOOLEAN NOT NULL,
    published_reference_hash TEXT NOT NULL CHECK (published_reference_hash ~ '^sha256:[0-9a-f]{64}$'),
    committed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (approval_id, operation_id),
    CHECK ((outcome = 'PUBLISHED' AND changed) OR (outcome = 'NO_CHANGE' AND NOT changed))
);
