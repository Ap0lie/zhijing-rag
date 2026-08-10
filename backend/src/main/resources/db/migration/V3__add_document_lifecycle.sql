ALTER TABLE documents
    ADD COLUMN acl_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN current_revision_id UUID,
    ADD CONSTRAINT ck_documents_acl_version CHECK (acl_version > 0);

ALTER TABLE document_revisions
    ADD COLUMN original_filename VARCHAR(255) NOT NULL DEFAULT 'legacy.pdf',
    ADD COLUMN file_size_bytes BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN media_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    ADD COLUMN idempotency_key VARCHAR(128);

UPDATE document_revisions SET status = 'UPLOADED' WHERE status = 'PENDING';

ALTER TABLE document_revisions
    DROP CONSTRAINT ck_document_revisions_status,
    ADD CONSTRAINT ck_document_revisions_status CHECK (
        status IN ('STAGED', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'QUARANTINED', 'DELETED')
    ),
    ADD CONSTRAINT ck_document_revisions_file_size CHECK (file_size_bytes > 0),
    ADD CONSTRAINT ck_document_revisions_media_type CHECK (media_type = 'application/pdf'),
    ADD CONSTRAINT ck_document_revisions_idempotency_key CHECK (
        idempotency_key IS NULL OR length(idempotency_key) BETWEEN 8 AND 128
    ),
    ADD CONSTRAINT uq_document_revisions_id_document UNIQUE (id, document_id);

ALTER TABLE document_revisions
    ALTER COLUMN original_filename DROP DEFAULT,
    ALTER COLUMN file_size_bytes DROP DEFAULT,
    ALTER COLUMN media_type DROP DEFAULT;

ALTER TABLE documents
    ADD CONSTRAINT fk_documents_current_revision
        FOREIGN KEY (current_revision_id, id)
        REFERENCES document_revisions (id, document_id);

CREATE UNIQUE INDEX uq_document_revisions_idempotency_key
    ON document_revisions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_document_revisions_source_object_key
    ON document_revisions (source_object_key);

CREATE INDEX ix_documents_current_revision_id
    ON documents (current_revision_id)
    WHERE current_revision_id IS NOT NULL;
