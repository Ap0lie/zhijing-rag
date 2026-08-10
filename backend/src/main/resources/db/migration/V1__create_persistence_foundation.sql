CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'USER'))
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    title VARCHAR(500) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_documents_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT ck_documents_visibility CHECK (visibility IN ('ALL_USERS', 'RESTRICTED'))
);

CREATE TABLE document_revisions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    source_object_key VARCHAR(1000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    parser_version VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_revisions_document FOREIGN KEY (document_id) REFERENCES documents (id),
    CONSTRAINT uq_document_revisions_number UNIQUE (document_id, revision_number),
    CONSTRAINT ck_document_revisions_number CHECK (revision_number > 0),
    CONSTRAINT ck_document_revisions_hash CHECK (length(content_hash) = 64),
    CONSTRAINT ck_document_revisions_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED', 'QUARANTINED', 'DELETED')
    )
);

CREATE TABLE pipeline_jobs (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL,
    stage VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    pipeline_version VARCHAR(64) NOT NULL,
    error_code VARCHAR(64),
    trace_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pipeline_jobs_revision FOREIGN KEY (revision_id) REFERENCES document_revisions (id),
    CONSTRAINT uq_pipeline_jobs_stage UNIQUE (revision_id, stage, pipeline_version),
    CONSTRAINT ck_pipeline_jobs_stage CHECK (stage IN ('INGEST', 'PARSE', 'CHUNK', 'EMBED', 'INDEX')),
    CONSTRAINT ck_pipeline_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'QUARANTINED')),
    CONSTRAINT ck_pipeline_jobs_attempt CHECK (attempt >= 0)
);

CREATE INDEX ix_documents_owner_user_id ON documents (owner_user_id);
CREATE INDEX ix_document_revisions_document_id ON document_revisions (document_id);
CREATE INDEX ix_document_revisions_status ON document_revisions (status);
CREATE INDEX ix_pipeline_jobs_revision_id ON pipeline_jobs (revision_id);
CREATE INDEX ix_pipeline_jobs_status ON pipeline_jobs (status);
