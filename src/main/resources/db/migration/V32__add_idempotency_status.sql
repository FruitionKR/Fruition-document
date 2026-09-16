ALTER TABLE idempotency_records
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN claim_token uuid;

ALTER TABLE idempotency_records
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN response_status DROP NOT NULL;

ALTER TABLE idempotency_records
    ADD CONSTRAINT ck_idempotency_records_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    ADD CONSTRAINT ck_idempotency_records_claim_token
        CHECK ((status = 'IN_PROGRESS' AND claim_token IS NOT NULL)
            OR (status = 'COMPLETED' AND claim_token IS NULL));
