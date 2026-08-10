CREATE TABLE index_configs (
    version VARCHAR(64) PRIMARY KEY,
    schema_version VARCHAR(64) NOT NULL,
    analyzer VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(255),
    embedding_revision VARCHAR(255),
    vector_dimensions INTEGER,
    distance VARCHAR(32),
    hnsw_m INTEGER,
    hnsw_ef_construction INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_index_configs_version CHECK (btrim(version) <> ''),
    CONSTRAINT ck_index_configs_schema_version CHECK (btrim(schema_version) <> ''),
    CONSTRAINT ck_index_configs_analyzer CHECK (btrim(analyzer) <> ''),
    CONSTRAINT ck_index_configs_vector_shape CHECK (
        (
            embedding_model IS NULL
            AND embedding_revision IS NULL
            AND vector_dimensions IS NULL
            AND distance IS NULL
            AND hnsw_m IS NULL
            AND hnsw_ef_construction IS NULL
        )
        OR
        (
            embedding_model IS NOT NULL
            AND embedding_revision IS NOT NULL
            AND vector_dimensions IS NOT NULL
            AND distance IS NOT NULL
            AND hnsw_m IS NOT NULL
            AND hnsw_ef_construction IS NOT NULL
            AND btrim(embedding_model) <> ''
            AND btrim(embedding_revision) <> ''
            AND vector_dimensions BETWEEN 1 AND 4096
            AND distance IN ('COSINE', 'L2', 'INNER_PRODUCT')
            AND hnsw_m BETWEEN 2 AND 100
            AND hnsw_ef_construction BETWEEN 4 AND 1000
        )
    )
);

INSERT INTO index_configs (
    version, schema_version, analyzer
)
VALUES (
    'phase5-bm25-v1', 'phase5-bm25-v1', 'cjk+english-multifield'
);

INSERT INTO index_configs (
    version, schema_version, analyzer,
    embedding_model, embedding_revision, vector_dimensions,
    distance, hnsw_m, hnsw_ef_construction
)
VALUES (
    'phase6-hybrid-qwen3-v1',
    'phase6-hybrid-v1',
    'cjk+english-multifield',
    'Qwen/Qwen3-Embedding-0.6B',
    '97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3',
    1024,
    'COSINE',
    16,
    128
);

INSERT INTO index_configs (
    version, schema_version, analyzer
)
SELECT DISTINCT
    manifest.schema_version,
    manifest.schema_version,
    'cjk+english-multifield'
FROM index_manifests manifest
ON CONFLICT (version) DO NOTHING;

ALTER TABLE index_manifests
    ADD COLUMN index_config_version VARCHAR(64);

UPDATE index_manifests
SET index_config_version = schema_version;

ALTER TABLE index_manifests
    ALTER COLUMN index_config_version SET NOT NULL,
    ADD CONSTRAINT fk_index_manifests_index_config
        FOREIGN KEY (index_config_version) REFERENCES index_configs (version),
    DROP CONSTRAINT fk_index_manifests_retrieval_profile,
    DROP COLUMN retrieval_profile_version,
    DROP COLUMN schema_version;

CREATE INDEX ix_index_manifests_index_config
    ON index_manifests (index_config_version);

ALTER TABLE retrieval_profiles
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'BM25',
    ADD COLUMN bm25_top_k INTEGER NOT NULL DEFAULT 50,
    ADD COLUMN vector_top_k INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rrf_rank_constant INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN rerank_top_k INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN evidence_top_k INTEGER NOT NULL DEFAULT 8,
    ADD COLUMN parent_token_budget INTEGER NOT NULL DEFAULT 6000,
    ADD CONSTRAINT ck_retrieval_profiles_mode
        CHECK (mode IN ('BM25', 'HYBRID')),
    ADD CONSTRAINT ck_retrieval_profiles_candidate_depth
        CHECK (
            bm25_top_k BETWEEN 1 AND 200
            AND vector_top_k BETWEEN 0 AND 200
            AND rrf_rank_constant BETWEEN 1 AND 1000
            AND rerank_top_k BETWEEN 0 AND 200
            AND evidence_top_k BETWEEN 1 AND 50
            AND parent_token_budget BETWEEN 0 AND 32000
        ),
    ADD CONSTRAINT ck_retrieval_profiles_mode_shape
        CHECK (
            (mode = 'BM25' AND vector_top_k = 0)
            OR (mode = 'HYBRID' AND vector_top_k > 0)
        ),
    ADD CONSTRAINT ck_retrieval_profiles_result_depth
        CHECK (
            rerank_top_k <= bm25_top_k + vector_top_k
            AND evidence_top_k <= CASE
                WHEN rerank_top_k > 0 THEN rerank_top_k
                ELSE bm25_top_k + vector_top_k
            END
        );

CREATE TABLE retrieval_publication_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    previous_profile_version VARCHAR(64),
    profile_version VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_user_id UUID,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_retrieval_publication_events_id_profile
        UNIQUE (id, profile_version),
    CONSTRAINT fk_retrieval_publication_events_previous_profile
        FOREIGN KEY (previous_profile_version) REFERENCES retrieval_profiles (version),
    CONSTRAINT fk_retrieval_publication_events_profile
        FOREIGN KEY (profile_version) REFERENCES retrieval_profiles (version),
    CONSTRAINT ck_retrieval_publication_events_action
        CHECK (action IN ('MIGRATION', 'PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_retrieval_publication_events_reason
        CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_retrieval_publication_events_profile
    ON retrieval_publication_events (profile_version);

CREATE INDEX ix_retrieval_publication_events_previous_profile
    ON retrieval_publication_events (previous_profile_version)
    WHERE previous_profile_version IS NOT NULL;

CREATE INDEX ix_retrieval_publication_events_actor
    ON retrieval_publication_events (actor_user_id)
    WHERE actor_user_id IS NOT NULL;

COMMENT ON COLUMN retrieval_publication_events.actor_user_id IS
    'Historical actor identifier intentionally has no users FK so audit events survive account deletion';

INSERT INTO retrieval_publication_events (
    previous_profile_version, profile_version, action, reason
)
SELECT
    NULL,
    profile.version,
    'MIGRATION',
    'Migrated the Phase 5 active retrieval profile'
FROM retrieval_profiles profile
ORDER BY profile.active DESC, profile.created_at, profile.version
LIMIT 1;

CREATE TABLE retrieval_publications (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    profile_version VARCHAR(64) NOT NULL,
    publication_event_id BIGINT NOT NULL UNIQUE,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_retrieval_publications_singleton CHECK (singleton_id = 1),
    CONSTRAINT fk_retrieval_publications_profile
        FOREIGN KEY (profile_version) REFERENCES retrieval_profiles (version),
    CONSTRAINT fk_retrieval_publications_event
        FOREIGN KEY (publication_event_id, profile_version)
        REFERENCES retrieval_publication_events (id, profile_version)
);

INSERT INTO retrieval_publications (
    singleton_id, profile_version, publication_event_id, published_at
)
SELECT 1, event.profile_version, event.id, event.created_at
FROM retrieval_publication_events event
WHERE event.action = 'MIGRATION'
ORDER BY event.id DESC
LIMIT 1;

DROP INDEX uq_retrieval_profiles_active;

ALTER TABLE retrieval_profiles
    DROP COLUMN active;

CREATE FUNCTION reject_immutable_retrieval_row()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER reject_index_config_mutation
    BEFORE UPDATE OR DELETE ON index_configs
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_retrieval_row();

CREATE TRIGGER reject_retrieval_profile_mutation
    BEFORE UPDATE OR DELETE ON retrieval_profiles
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_retrieval_row();

CREATE TRIGGER reject_retrieval_publication_event_mutation
    BEFORE UPDATE OR DELETE ON retrieval_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_retrieval_row();
