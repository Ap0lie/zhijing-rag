ALTER TABLE pipeline_jobs
    ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN lease_owner VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN heartbeat_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN duration_ms BIGINT,
    ADD COLUMN error_message VARCHAR(2000),
    ADD COLUMN quarantine_reason VARCHAR(512);

UPDATE pipeline_jobs
SET max_attempts = GREATEST(
        3,
        attempt + CASE WHEN status IN ('PENDING', 'RUNNING') THEN 1 ELSE 0 END
    );

UPDATE pipeline_jobs
SET status = 'PENDING',
    error_code = 'MIGRATION_REQUEUED',
    error_message = 'Running job was safely requeued by Flyway V5'
WHERE status = 'RUNNING';

UPDATE pipeline_jobs
SET started_at = created_at,
    completed_at = updated_at,
    duration_ms = 0,
    error_code = CASE
        WHEN status IN ('FAILED', 'QUARANTINED') AND error_code IS NULL THEN 'LEGACY_FAILURE'
        ELSE error_code
    END,
    quarantine_reason = CASE
        WHEN status = 'QUARANTINED' THEN 'LEGACY_QUARANTINE'
        ELSE quarantine_reason
    END
WHERE status IN ('SUCCEEDED', 'FAILED', 'QUARANTINED');

UPDATE pipeline_jobs job
SET status = 'FAILED',
    completed_at = CURRENT_TIMESTAMP,
    duration_ms = 0,
    error_code = 'DOCUMENT_DELETED',
    error_message = 'Document or revision was deleted before parsing'
FROM document_revisions revision
JOIN documents document ON document.id = revision.document_id
WHERE job.revision_id = revision.id
  AND job.status = 'PENDING'
  AND (document.deleted_at IS NOT NULL OR revision.status = 'DELETED');

ALTER TABLE pipeline_jobs
    DROP CONSTRAINT ck_pipeline_jobs_attempt,
    ADD CONSTRAINT ck_pipeline_jobs_attempt CHECK (
        attempt >= 0 AND attempt <= max_attempts
    ),
    ADD CONSTRAINT ck_pipeline_jobs_max_attempts CHECK (max_attempts > 0),
    ADD CONSTRAINT ck_pipeline_jobs_lease CHECK (
        (status = 'RUNNING'
            AND lease_owner IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND heartbeat_at IS NOT NULL
            AND started_at IS NOT NULL
            AND completed_at IS NULL
            AND duration_ms IS NULL)
        OR
        (status <> 'RUNNING'
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL
            AND heartbeat_at IS NULL)
    ),
    ADD CONSTRAINT ck_pipeline_jobs_completion CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'QUARANTINED')
            AND completed_at IS NOT NULL
            AND duration_ms IS NOT NULL
            AND duration_ms >= 0)
        OR
        (status IN ('PENDING', 'RUNNING')
            AND completed_at IS NULL
            AND duration_ms IS NULL)
    ),
    ADD CONSTRAINT ck_pipeline_jobs_failure_reason CHECK (
        status NOT IN ('FAILED', 'QUARANTINED') OR error_code IS NOT NULL
    ),
    ADD CONSTRAINT ck_pipeline_jobs_quarantine_reason CHECK (
        status <> 'QUARANTINED' OR quarantine_reason IS NOT NULL
    );

CREATE INDEX ix_pipeline_jobs_claim
    ON pipeline_jobs (created_at, id)
    WHERE status = 'PENDING' OR status = 'RUNNING';

CREATE INDEX ix_pipeline_jobs_lease_expiry
    ON pipeline_jobs (lease_expires_at)
    WHERE status = 'RUNNING';

CREATE TABLE chunking_profiles (
    version VARCHAR(64) PRIMARY KEY,
    parent_max_tokens INTEGER NOT NULL,
    child_max_tokens INTEGER NOT NULL,
    child_overlap_tokens INTEGER NOT NULL,
    token_counter_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_chunking_profiles_limits CHECK (
        parent_max_tokens > 0
        AND child_max_tokens > 0
        AND child_max_tokens <= parent_max_tokens
        AND child_overlap_tokens >= 0
        AND child_overlap_tokens < child_max_tokens
    )
);

INSERT INTO chunking_profiles (
    version,
    parent_max_tokens,
    child_max_tokens,
    child_overlap_tokens,
    token_counter_version
) VALUES ('phase4-v1', 1200, 300, 40, 'unicode-codepoint-v1');

CREATE TABLE parsed_documents (
    revision_id UUID PRIMARY KEY,
    markdown TEXT NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    page_count INTEGER NOT NULL,
    character_count BIGINT NOT NULL,
    parse_duration_ms BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_parsed_documents_revision
        FOREIGN KEY (revision_id) REFERENCES document_revisions (id) ON DELETE CASCADE,
    CONSTRAINT ck_parsed_documents_page_count CHECK (page_count > 0),
    CONSTRAINT ck_parsed_documents_character_count CHECK (character_count >= 0),
    CONSTRAINT ck_parsed_documents_duration CHECK (parse_duration_ms >= 0)
);

CREATE TABLE content_blocks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    block_order INTEGER NOT NULL,
    block_type VARCHAR(16) NOT NULL,
    text TEXT NOT NULL,
    heading_path TEXT NOT NULL,
    start_page INTEGER NOT NULL,
    end_page INTEGER NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    character_count INTEGER NOT NULL,
    token_count INTEGER NOT NULL,
    token_counter_version VARCHAR(64) NOT NULL,
    source_text_hash VARCHAR(64) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_content_blocks_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id) ON DELETE CASCADE,
    CONSTRAINT uq_content_blocks_revision_order UNIQUE (revision_id, block_order),
    CONSTRAINT ck_content_blocks_order CHECK (block_order >= 0),
    CONSTRAINT ck_content_blocks_type CHECK (
        block_type IN ('HEADING', 'PARAGRAPH', 'LIST', 'TABLE')
    ),
    CONSTRAINT ck_content_blocks_text CHECK (
        character_count = char_length(text) AND character_count > 0
    ),
    CONSTRAINT ck_content_blocks_tokens CHECK (token_count > 0),
    CONSTRAINT ck_content_blocks_pages CHECK (
        start_page > 0 AND end_page >= start_page
    ),
    CONSTRAINT ck_content_blocks_offsets CHECK (
        start_offset >= 0 AND end_offset >= 0
        AND (start_page <> end_page OR end_offset >= start_offset)
    ),
    CONSTRAINT ck_content_blocks_source_hash CHECK (length(source_text_hash) = 64)
);

CREATE INDEX ix_content_blocks_revision_order
    ON content_blocks (revision_id, block_order);

CREATE TABLE chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    parent_chunk_id UUID,
    chunk_type VARCHAR(8) NOT NULL,
    chunk_order INTEGER NOT NULL,
    text TEXT NOT NULL,
    heading_path TEXT NOT NULL,
    start_block_order INTEGER NOT NULL,
    end_block_order INTEGER NOT NULL,
    character_count INTEGER NOT NULL,
    token_count INTEGER NOT NULL,
    token_counter_version VARCHAR(64) NOT NULL,
    chunking_profile_version VARCHAR(64) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    chunker_version VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    searchable BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chunks_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id) ON DELETE CASCADE,
    CONSTRAINT fk_chunks_profile
        FOREIGN KEY (chunking_profile_version) REFERENCES chunking_profiles (version),
    CONSTRAINT uq_chunks_identity UNIQUE (id, document_id, revision_id),
    CONSTRAINT uq_chunks_revision_order UNIQUE (
        revision_id, chunking_profile_version, chunk_type, chunk_order
    ),
    CONSTRAINT ck_chunks_type CHECK (chunk_type IN ('PARENT', 'CHILD')),
    CONSTRAINT ck_chunks_parent_shape CHECK (
        (chunk_type = 'PARENT' AND parent_chunk_id IS NULL AND searchable = FALSE)
        OR
        (chunk_type = 'CHILD' AND parent_chunk_id IS NOT NULL AND searchable = TRUE)
    ),
    CONSTRAINT ck_chunks_order CHECK (chunk_order >= 0),
    CONSTRAINT ck_chunks_block_range CHECK (
        start_block_order >= 0 AND end_block_order >= start_block_order
    ),
    CONSTRAINT ck_chunks_text CHECK (
        character_count = char_length(text) AND character_count > 0
    ),
    CONSTRAINT ck_chunks_tokens CHECK (token_count > 0),
    CONSTRAINT ck_chunks_content_hash CHECK (length(content_hash) = 64),
    CONSTRAINT fk_chunks_parent
        FOREIGN KEY (parent_chunk_id, document_id, revision_id)
        REFERENCES chunks (id, document_id, revision_id) ON DELETE CASCADE
);

CREATE INDEX ix_chunks_revision_type_order
    ON chunks (revision_id, chunk_type, chunk_order);

CREATE INDEX ix_chunks_parent
    ON chunks (parent_chunk_id)
    WHERE parent_chunk_id IS NOT NULL;

CREATE FUNCTION check_child_parent_type() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.chunk_type = 'CHILD' AND NOT EXISTS (
        SELECT 1
        FROM chunks parent
        WHERE parent.id = NEW.parent_chunk_id
          AND parent.document_id = NEW.document_id
          AND parent.revision_id = NEW.revision_id
          AND parent.chunk_type = 'PARENT'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'child chunk parent must be a PARENT chunk from the same revision';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ck_chunks_parent_type
    AFTER INSERT OR UPDATE OF parent_chunk_id, document_id, revision_id, chunk_type
    ON chunks
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW
    EXECUTE FUNCTION check_child_parent_type();

CREATE TABLE source_spans (
    id UUID PRIMARY KEY,
    chunk_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    span_order INTEGER NOT NULL,
    start_page INTEGER NOT NULL,
    end_page INTEGER NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    source_text_hash VARCHAR(64) NOT NULL,
    bounding_boxes_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_source_spans_chunk
        FOREIGN KEY (chunk_id, document_id, revision_id)
        REFERENCES chunks (id, document_id, revision_id) ON DELETE CASCADE,
    CONSTRAINT uq_source_spans_chunk_order UNIQUE (chunk_id, span_order),
    CONSTRAINT ck_source_spans_order CHECK (span_order >= 0),
    CONSTRAINT ck_source_spans_pages CHECK (
        start_page > 0 AND end_page >= start_page
    ),
    CONSTRAINT ck_source_spans_offsets CHECK (
        start_offset >= 0 AND end_offset >= 0
        AND (start_page <> end_page OR end_offset >= start_offset)
    ),
    CONSTRAINT ck_source_spans_hash CHECK (length(source_text_hash) = 64)
);

CREATE INDEX ix_source_spans_chunk_order
    ON source_spans (chunk_id, span_order);

INSERT INTO pipeline_jobs (
    id,
    revision_id,
    stage,
    status,
    attempt,
    max_attempts,
    pipeline_version
)
SELECT
    gen_random_uuid(),
    revision.id,
    'PARSE',
    'PENDING',
    0,
    3,
    'phase4-v1'
FROM document_revisions revision
JOIN documents document ON document.id = revision.document_id
WHERE revision.status = 'UPLOADED'
  AND document.deleted_at IS NULL
ON CONFLICT (revision_id, stage, pipeline_version) DO NOTHING;
