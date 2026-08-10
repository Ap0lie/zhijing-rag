ALTER TABLE document_revisions
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD COLUMN staging_expires_at TIMESTAMP WITH TIME ZONE;

UPDATE document_revisions
SET request_fingerprint = content_hash
WHERE idempotency_key IS NOT NULL;

UPDATE document_revisions
SET status = 'STAGED',
    staging_expires_at = CURRENT_TIMESTAMP
WHERE idempotency_key IS NULL
  AND original_filename = 'legacy.pdf'
  AND status <> 'DELETED';

UPDATE document_revisions
SET staging_expires_at = CURRENT_TIMESTAMP
WHERE status = 'STAGED'
  AND staging_expires_at IS NULL;

ALTER TABLE document_revisions
    DROP CONSTRAINT ck_document_revisions_status,
    ADD CONSTRAINT ck_document_revisions_status CHECK (
        status IN (
            'STAGED', 'UPLOAD_FAILED', 'UPLOADED', 'PROCESSING',
            'READY', 'FAILED', 'QUARANTINED', 'DELETED'
        )
    ),
    ADD CONSTRAINT ck_document_revisions_request_fingerprint CHECK (
        request_fingerprint IS NULL OR length(request_fingerprint) = 64
    ),
    ADD CONSTRAINT ck_document_revisions_idempotency_fingerprint CHECK (
        idempotency_key IS NULL OR request_fingerprint IS NOT NULL
    ),
    ADD CONSTRAINT ck_document_revisions_staging_expiry CHECK (
        (status = 'STAGED' AND staging_expires_at IS NOT NULL)
        OR (status <> 'STAGED' AND staging_expires_at IS NULL)
    );

CREATE INDEX ix_document_revisions_staging_expiry
    ON document_revisions (staging_expires_at)
    WHERE status = 'STAGED';
