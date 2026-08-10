CREATE TABLE graph_resolution_previews (
    token UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    graph_generation BIGINT NOT NULL,
    base_config_version VARCHAR(64) NOT NULL,
    source_set_hash VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    source_entity_ids JSONB NOT NULL,
    source_entity_keys JSONB NOT NULL,
    match_aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_canonical_name TEXT NOT NULL,
    target_normalized_name TEXT NOT NULL,
    target_entity_type VARCHAR(64) NOT NULL,
    fact_hash VARCHAR(64) NOT NULL,
    mention_count INTEGER NOT NULL,
    source_span_count INTEGER NOT NULL,
    relationship_count INTEGER NOT NULL,
    relationship_evidence_count INTEGER NOT NULL,
    community_count INTEGER NOT NULL,
    document_count INTEGER NOT NULL,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    consumed_config_version VARCHAR(64),
    consumed_idempotency_key VARCHAR(120),
    CONSTRAINT fk_graph_resolution_previews_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_previews_manifest
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_previews_config
        FOREIGN KEY (base_config_version) REFERENCES graph_configs (version),
    CONSTRAINT fk_graph_resolution_previews_consumed_config
        FOREIGN KEY (consumed_config_version) REFERENCES graph_configs (version),
    CONSTRAINT ck_graph_resolution_previews_action
        CHECK (action IN ('MERGE', 'SPLIT')),
    CONSTRAINT ck_graph_resolution_previews_sources
        CHECK (
            jsonb_typeof(source_entity_ids) = 'array'
            AND jsonb_array_length(source_entity_ids) BETWEEN 1 AND 20
            AND jsonb_typeof(source_entity_keys) = 'array'
            AND jsonb_array_length(source_entity_keys) =
                jsonb_array_length(source_entity_ids)
        ),
    CONSTRAINT ck_graph_resolution_previews_aliases
        CHECK (jsonb_typeof(match_aliases) = 'array'),
    CONSTRAINT ck_graph_resolution_previews_target
        CHECK (
            btrim(target_canonical_name) <> ''
            AND btrim(target_normalized_name) <> ''
            AND btrim(target_entity_type) <> ''
        ),
    CONSTRAINT ck_graph_resolution_previews_hashes
        CHECK (
            source_set_hash ~ '^[0-9a-f]{64}$'
            AND fact_hash ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_graph_resolution_previews_counts
        CHECK (
            mention_count >= 0
            AND source_span_count >= 0
            AND relationship_count >= 0
            AND relationship_evidence_count >= 0
            AND community_count >= 0
            AND document_count >= 0
        ),
    CONSTRAINT ck_graph_resolution_previews_warnings
        CHECK (jsonb_typeof(warnings) = 'array'),
    CONSTRAINT ck_graph_resolution_previews_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_graph_resolution_previews_consumption
        CHECK (
            (consumed_at IS NULL
                AND consumed_config_version IS NULL
                AND consumed_idempotency_key IS NULL)
            OR
            (consumed_at IS NOT NULL
                AND consumed_config_version IS NOT NULL
                AND length(btrim(consumed_idempotency_key)) BETWEEN 8 AND 120)
        )
);

CREATE INDEX ix_graph_resolution_previews_actor_expiry
    ON graph_resolution_previews (actor_user_id, expires_at DESC);

CREATE INDEX ix_graph_resolution_previews_generation_expiry
    ON graph_resolution_previews (graph_generation, expires_at DESC);

COMMENT ON TABLE graph_resolution_previews IS
    'Short-lived Phase 21A preflight contracts. They are not graph facts and are ignored by retrieval.';
