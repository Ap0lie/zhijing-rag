CREATE TABLE retrieval_profiles (
    version VARCHAR(64) PRIMARY KEY,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    default_page_size INTEGER NOT NULL,
    max_page_size INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_retrieval_profiles_page_sizes CHECK (
        default_page_size > 0
        AND max_page_size >= default_page_size
        AND max_page_size <= 100
    )
);

CREATE UNIQUE INDEX uq_retrieval_profiles_active
    ON retrieval_profiles (active)
    WHERE active;

INSERT INTO retrieval_profiles (version, active, default_page_size, max_page_size)
VALUES ('phase5-bm25-v1', TRUE, 20, 50);

CREATE TABLE index_manifests (
    id UUID PRIMARY KEY,
    index_generation BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    index_name VARCHAR(255) NOT NULL UNIQUE,
    index_alias VARCHAR(255) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    retrieval_profile_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    document_count BIGINT NOT NULL DEFAULT 0,
    chunk_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_index_manifests_retrieval_profile
        FOREIGN KEY (retrieval_profile_version) REFERENCES retrieval_profiles (version),
    CONSTRAINT ck_index_manifests_status CHECK (
        status IN ('CANDIDATE', 'ACTIVE', 'RETIRED', 'FAILED')
    ),
    CONSTRAINT ck_index_manifests_counts CHECK (
        document_count >= 0 AND chunk_count >= 0
    )
);

CREATE UNIQUE INDEX uq_index_manifests_active_alias
    ON index_manifests (index_alias)
    WHERE status = 'ACTIVE';

CREATE TABLE search_projection_states (
    document_id UUID PRIMARY KEY,
    revision_id UUID,
    acl_version BIGINT NOT NULL,
    index_generation BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    document_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_search_projection_states_document
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_search_projection_states_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id) ON DELETE CASCADE,
    CONSTRAINT ck_search_projection_states_acl_version CHECK (acl_version > 0),
    CONSTRAINT ck_search_projection_states_generation CHECK (index_generation > 0),
    CONSTRAINT ck_search_projection_states_state CHECK (state IN ('ACTIVE', 'DELETED')),
    CONSTRAINT ck_search_projection_states_shape CHECK (
        (state = 'ACTIVE' AND revision_id IS NOT NULL)
        OR (state = 'DELETED' AND revision_id IS NULL)
    )
);

CREATE INDEX ix_documents_search_publication
    ON documents (current_revision_id, visibility, acl_version)
    WHERE deleted_at IS NULL AND current_revision_id IS NOT NULL;

INSERT INTO pipeline_jobs (
    id, revision_id, stage, status, attempt, max_attempts, pipeline_version
)
SELECT
    gen_random_uuid(), revision.id, 'INDEX', 'PENDING', 0, 3, 'phase4-v1'
FROM document_revisions revision
JOIN documents document ON document.id = revision.document_id
WHERE revision.status = 'READY'
  AND document.deleted_at IS NULL
  AND EXISTS (
      SELECT 1 FROM chunks chunk
      WHERE chunk.revision_id = revision.id
        AND chunk.chunk_type = 'CHILD'
        AND chunk.searchable = TRUE
  )
ON CONFLICT (revision_id, stage, pipeline_version) DO NOTHING;
