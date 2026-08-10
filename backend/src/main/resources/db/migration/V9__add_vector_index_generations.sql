UPDATE index_manifests
SET status = 'FAILED',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'CANDIDATE';

ALTER TABLE index_manifests
    DROP CONSTRAINT ck_index_manifests_status,
    ADD COLUMN expected_document_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN expected_chunk_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN indexed_chunk_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN valid_vector_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN build_attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN build_max_attempts INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN lease_owner VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN heartbeat_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN failure_code VARCHAR(64),
    ADD COLUMN failure_reason VARCHAR(1000),
    ADD COLUMN retention_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN requested_by UUID,
    ADD COLUMN build_reason VARCHAR(500);

UPDATE index_manifests
SET expected_document_count = document_count,
    expected_chunk_count = chunk_count,
    indexed_chunk_count = chunk_count,
    completed_at = updated_at
WHERE status IN ('ACTIVE', 'RETIRED');

ALTER TABLE index_manifests
    ADD CONSTRAINT ck_index_manifests_status CHECK (
        status IN ('BUILDING', 'READY', 'ACTIVE', 'RETIRED', 'FAILED', 'DELETED')
    ),
    ADD CONSTRAINT ck_index_manifests_build_counts CHECK (
        expected_document_count >= 0
        AND expected_chunk_count >= 0
        AND indexed_chunk_count >= 0
        AND indexed_chunk_count <= expected_chunk_count
        AND valid_vector_count >= 0
        AND valid_vector_count <= indexed_chunk_count
        AND build_attempt >= 0
        AND build_attempt <= build_max_attempts
        AND build_max_attempts BETWEEN 1 AND 10
    ),
    ADD CONSTRAINT ck_index_manifests_lease_shape CHECK (
        (
            lease_owner IS NULL
            AND lease_expires_at IS NULL
            AND heartbeat_at IS NULL
        )
        OR
        (
            status = 'BUILDING'
            AND lease_owner IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND heartbeat_at IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_index_manifests_build_reason CHECK (
        build_reason IS NULL OR btrim(build_reason) <> ''
    );

COMMENT ON COLUMN index_manifests.requested_by IS
    'Historical requester identifier intentionally has no users FK so build audit survives account deletion';

CREATE UNIQUE INDEX uq_index_manifests_building_alias
    ON index_manifests (index_alias)
    WHERE status = 'BUILDING';

CREATE INDEX ix_index_manifests_lifecycle
    ON index_manifests (index_alias, status, index_generation DESC);

CREATE INDEX ix_index_manifests_build_lease
    ON index_manifests (created_at, index_generation)
    WHERE status = 'BUILDING';

ALTER TABLE search_projection_states
    DROP CONSTRAINT search_projection_states_pkey,
    ADD CONSTRAINT search_projection_states_pkey
        PRIMARY KEY (document_id, index_generation),
    ADD CONSTRAINT fk_search_projection_states_generation
        FOREIGN KEY (index_generation)
        REFERENCES index_manifests (index_generation);

CREATE INDEX ix_search_projection_states_generation
    ON search_projection_states (index_generation, state, document_updated_at);

ALTER TABLE retrieval_publication_events
    ADD COLUMN previous_index_generation BIGINT,
    ADD COLUMN index_generation BIGINT,
    ADD CONSTRAINT fk_retrieval_publication_events_previous_generation
        FOREIGN KEY (previous_index_generation)
        REFERENCES index_manifests (index_generation),
    ADD CONSTRAINT fk_retrieval_publication_events_generation
        FOREIGN KEY (index_generation)
        REFERENCES index_manifests (index_generation);

DROP TRIGGER reject_retrieval_publication_event_mutation
    ON retrieval_publication_events;

UPDATE retrieval_publication_events event
SET index_generation = manifest.index_generation
FROM index_manifests manifest
WHERE event.action = 'MIGRATION'
  AND manifest.status = 'ACTIVE';

CREATE TRIGGER reject_retrieval_publication_event_mutation
    BEFORE UPDATE OR DELETE ON retrieval_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_retrieval_row();

INSERT INTO retrieval_profiles (
    version, mode, default_page_size, max_page_size,
    bm25_top_k, vector_top_k, rrf_rank_constant,
    rerank_top_k, evidence_top_k, parent_token_budget
)
VALUES (
    'phase6-hybrid-rrf-v1', 'HYBRID', 20, 50,
    50, 50, 60, 0, 8, 6000
);

CREATE FUNCTION validate_index_manifest_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    vector_dimensions INTEGER;
BEGIN
    IF OLD.status <> NEW.status AND NOT (
        (OLD.status = 'BUILDING' AND NEW.status IN ('READY', 'FAILED'))
        OR (OLD.status = 'READY' AND NEW.status IN ('ACTIVE', 'FAILED'))
        OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')
        OR (OLD.status = 'RETIRED' AND NEW.status IN ('ACTIVE', 'DELETED'))
        OR (OLD.status = 'FAILED' AND NEW.status = 'DELETED')
    ) THEN
        RAISE EXCEPTION 'Invalid index manifest transition: % -> %', OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status IN ('READY', 'ACTIVE') THEN
        IF NEW.indexed_chunk_count <> NEW.expected_chunk_count THEN
            RAISE EXCEPTION 'Index generation is incomplete'
                USING ERRCODE = '23514';
        END IF;
        SELECT config.vector_dimensions
        INTO vector_dimensions
        FROM index_configs config
        WHERE config.version = NEW.index_config_version;
        IF vector_dimensions IS NOT NULL
           AND NEW.valid_vector_count <> NEW.expected_chunk_count THEN
            RAISE EXCEPTION 'Vector coverage must be complete'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_index_manifest_transition
    BEFORE UPDATE ON index_manifests
    FOR EACH ROW EXECUTE FUNCTION validate_index_manifest_transition();
