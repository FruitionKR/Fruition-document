-- I5 single-document MCP approval, execution receipt, and fee settlement.
ALTER TABLE credit_entries
    ADD COLUMN reservation_type TEXT;

ALTER TABLE credit_entries
    ADD CONSTRAINT credit_entries_reservation_type_check CHECK (
        (type IN ('reserve', 'release') AND reservation_type IN ('MODEL_USAGE', 'MCP_APPROVAL'))
        OR (type NOT IN ('reserve', 'release') AND reservation_type IS NULL)
    ) NOT VALID;

UPDATE credit_entries
SET reservation_type = 'MODEL_USAGE'
WHERE type IN ('reserve', 'release') AND reservation_type IS NULL;

ALTER TABLE credit_entries VALIDATE CONSTRAINT credit_entries_reservation_type_check;

CREATE TABLE mcp_agent_approvals (
    approval_id UUID PRIMARY KEY,
    run_id TEXT NOT NULL UNIQUE,
    workspace_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    plan_version INTEGER NOT NULL CHECK (plan_version > 0),
    operation_hash TEXT NOT NULL CHECK (operation_hash ~ '^[0-9a-f]{64}$'),
    operation_ids JSONB NOT NULL CHECK (jsonb_typeof(operation_ids) = 'array'),
    canonical_hash TEXT NOT NULL CHECK (canonical_hash ~ '^sha256:[0-9a-f]{64}$'),
    grant_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    client_name TEXT NOT NULL CHECK (btrim(client_name) <> ''),
    policy_generation BIGINT NOT NULL CHECK (policy_generation > 0),
    origin TEXT NOT NULL CHECK (origin = 'MCP_CLIENT'),
    approval_mode TEXT NOT NULL CHECK (approval_mode = 'EXPLICIT_WEB'),
    unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    plan_cap_minor BIGINT NOT NULL CHECK (plan_cap_minor >= 0),
    currency TEXT NOT NULL CHECK (currency = 'KRW'),
    price_version TEXT NOT NULL CHECK (btrim(price_version) <> ''),
    maximum_charge_minor BIGINT NOT NULL CHECK (maximum_charge_minor >= 0),
    reserved_credit_milli BIGINT NOT NULL CHECK (reserved_credit_milli >= 0),
    lifecycle_status TEXT NOT NULL CHECK (lifecycle_status IN ('RESERVED', 'FORWARDED', 'CANCELLED', 'HALTED', 'PURGED')),
    forward_status TEXT NOT NULL CHECK (forward_status IN ('NOT_SENT', 'UNKNOWN', 'ACCEPTED')),
    settlement_status TEXT NOT NULL CHECK (settlement_status IN ('RESERVED', 'EXECUTING', 'PENDING_RECONCILIATION', 'SETTLED')),
    charged_minor BIGINT NOT NULL DEFAULT 0 CHECK (charged_minor >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ,
    halt_reason TEXT,
    purged_at TIMESTAMPTZ
);

CREATE INDEX idx_mcp_agent_approvals_reconcile
    ON mcp_agent_approvals (settlement_status, updated_at)
    WHERE settlement_status <> 'SETTLED';
CREATE INDEX idx_mcp_agent_approvals_workspace ON mcp_agent_approvals (workspace_id);
CREATE INDEX idx_mcp_agent_approvals_user ON mcp_agent_approvals (user_id);

CREATE TABLE mcp_document_edit_attempts (
    attempt_id UUID PRIMARY KEY,
    approval_id UUID NOT NULL REFERENCES mcp_agent_approvals(approval_id),
    operation_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('STARTED', 'FAILED', 'UNKNOWN')),
    failure_code TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (approval_id, operation_id, attempt_id)
);

CREATE INDEX idx_mcp_document_edit_attempts_operation
    ON mcp_document_edit_attempts (approval_id, operation_id, updated_at DESC);

CREATE TABLE mcp_document_edit_receipts (
    receipt_id UUID PRIMARY KEY,
    approval_id UUID NOT NULL REFERENCES mcp_agent_approvals(approval_id),
    operation_id TEXT NOT NULL,
    attempt_id UUID NOT NULL REFERENCES mcp_document_edit_attempts(attempt_id),
    workspace_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    grant_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    tool_name TEXT NOT NULL CHECK (tool_name = 'apply_document_edit'),
    outcome TEXT NOT NULL CHECK (outcome IN ('COMMITTED', 'NO_CHANGE')),
    changed BOOLEAN NOT NULL,
    resource_type TEXT NOT NULL CHECK (resource_type = 'DOCUMENT'),
    resource_id TEXT NOT NULL,
    before_version BIGINT NOT NULL CHECK (before_version > 0),
    after_version BIGINT NOT NULL CHECK (after_version > 0),
    committed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (approval_id, operation_id),
    CHECK ((outcome = 'COMMITTED' AND changed) OR (outcome = 'NO_CHANGE' AND NOT changed)),
    CHECK ((changed AND after_version > before_version) OR (NOT changed AND after_version = before_version))
);

CREATE INDEX idx_mcp_document_edit_receipts_actor
    ON mcp_document_edit_receipts (workspace_id, user_id, approval_id, operation_id);
