ALTER TABLE document_revisions
    ADD COLUMN evaluation_suite_version VARCHAR(64),
    ADD COLUMN evaluation_evidence_key VARCHAR(128),
    ADD COLUMN source_dataset VARCHAR(64),
    ADD COLUMN source_title VARCHAR(500),
    ADD COLUMN source_url TEXT,
    ADD COLUMN source_license VARCHAR(64),
    ADD COLUMN source_revision VARCHAR(255),
    ADD COLUMN source_content_hash VARCHAR(64),
    ADD CONSTRAINT ck_document_revisions_evaluation_provenance_group CHECK (
        num_nonnulls(
            evaluation_suite_version,
            evaluation_evidence_key,
            source_dataset,
            source_title,
            source_url,
            source_license,
            source_revision,
            source_content_hash
        ) IN (0, 8)
    ),
    ADD CONSTRAINT ck_document_revisions_evaluation_suite CHECK (
        evaluation_suite_version IS NULL
        OR evaluation_suite_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
    ),
    ADD CONSTRAINT ck_document_revisions_evaluation_evidence CHECK (
        evaluation_evidence_key IS NULL
        OR evaluation_evidence_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    ADD CONSTRAINT ck_document_revisions_source_dataset CHECK (
        source_dataset IS NULL
        OR source_dataset ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
    ),
    ADD CONSTRAINT ck_document_revisions_source_title CHECK (
        source_title IS NULL OR btrim(source_title) <> ''
    ),
    ADD CONSTRAINT ck_document_revisions_source_url CHECK (
        source_url IS NULL
        OR (
            char_length(source_url) <= 2048
            AND source_url ~ '^https://[^[:space:]]+$'
        )
    ),
    ADD CONSTRAINT ck_document_revisions_source_license CHECK (
        source_license IS NULL
        OR source_license ~ '^[A-Za-z0-9][A-Za-z0-9.+-]{0,63}$'
    ),
    ADD CONSTRAINT ck_document_revisions_source_revision CHECK (
        source_revision IS NULL
        OR source_revision ~ '^[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,254}$'
    ),
    ADD CONSTRAINT ck_document_revisions_source_content_hash CHECK (
        source_content_hash IS NULL
        OR source_content_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT uq_document_revisions_evaluation_evidence
        UNIQUE (evaluation_suite_version, evaluation_evidence_key);
