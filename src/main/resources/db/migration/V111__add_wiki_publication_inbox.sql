CREATE TABLE wiki_publication_inbox (
    publication_id text NOT NULL UNIQUE,
    operation_id text PRIMARY KEY REFERENCES ai_operation_logs(operation_id) ON DELETE CASCADE,
    event_hash varchar(64) NOT NULL,
    result_hash varchar(64) NOT NULL,
    published_reference_hash varchar(71) NOT NULL,
    kind varchar(20) NOT NULL,
    outcome varchar(20) NOT NULL,
    workspace_id text NOT NULL,
    user_id text NOT NULL,
    committed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    disposition varchar(30) NOT NULL,
    rejection_reason varchar(30),
    CONSTRAINT ck_wiki_publication_kind
        CHECK (kind IN ('INGEST', 'MANUAL_EDIT', 'LINT', 'RESTORE', 'RECOVERY')),
    CONSTRAINT ck_wiki_publication_outcome
        CHECK (outcome IN ('PUBLISHED', 'NO_CHANGE')),
    CONSTRAINT ck_wiki_publication_disposition
        CHECK ((disposition = 'ACCEPTED' AND rejection_reason IS NULL)
            OR (disposition = 'REJECTED_CONFLICT' AND rejection_reason = 'STALE_RESTORE')),
    CONSTRAINT ck_wiki_publication_reference_hash
        CHECK (published_reference_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE wiki_publication_event_aliases (
    event_id text PRIMARY KEY,
    operation_id text NOT NULL REFERENCES wiki_publication_inbox(operation_id) ON DELETE CASCADE
);

CREATE TABLE wiki_publication_purge_fences (
    scope_type varchar(20) NOT NULL,
    scope_id text NOT NULL,
    purged_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope_type, scope_id),
    CONSTRAINT ck_wiki_publication_purge_scope
        CHECK (scope_type IN ('user', 'workspace'))
);
