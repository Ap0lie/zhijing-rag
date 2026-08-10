CREATE TABLE graph_resolution_rule_sets (
    version VARCHAR(64) PRIMARY KEY,
    reason VARCHAR(500) NOT NULL,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_graph_resolution_rule_sets_version
        CHECK (btrim(version) <> ''),
    CONSTRAINT ck_graph_resolution_rule_sets_reason
        CHECK (btrim(reason) <> '')
);

COMMENT ON COLUMN graph_resolution_rule_sets.created_by IS
    'Historical actor identifier intentionally has no users FK so rules survive account deletion';

INSERT INTO graph_resolution_rule_sets (version, reason)
VALUES ('phase8-baseline-rules-v1', 'Initial empty entity resolution rule set');

CREATE TABLE graph_resolution_rules (
    id UUID PRIMARY KEY,
    rule_set_version VARCHAR(64) NOT NULL,
    rule_order INTEGER NOT NULL,
    action VARCHAR(16) NOT NULL,
    source_entity_keys JSONB NOT NULL,
    match_aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_canonical_name TEXT NOT NULL,
    target_normalized_name TEXT NOT NULL,
    target_entity_type VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_resolution_rules_set
        FOREIGN KEY (rule_set_version)
        REFERENCES graph_resolution_rule_sets (version),
    CONSTRAINT uq_graph_resolution_rules_set_order
        UNIQUE (rule_set_version, rule_order),
    CONSTRAINT ck_graph_resolution_rules_order
        CHECK (rule_order >= 0),
    CONSTRAINT ck_graph_resolution_rules_action
        CHECK (action IN ('MERGE', 'SPLIT')),
    CONSTRAINT ck_graph_resolution_rules_sources
        CHECK (
            jsonb_typeof(source_entity_keys) = 'array'
            AND jsonb_array_length(source_entity_keys) > 0
        ),
    CONSTRAINT ck_graph_resolution_rules_aliases
        CHECK (
            jsonb_typeof(match_aliases) = 'array'
            AND (
                action = 'MERGE'
                OR jsonb_array_length(match_aliases) > 0
            )
        ),
    CONSTRAINT ck_graph_resolution_rules_target
        CHECK (
            btrim(target_canonical_name) <> ''
            AND btrim(target_normalized_name) <> ''
            AND btrim(target_entity_type) <> ''
        ),
    CONSTRAINT ck_graph_resolution_rules_reason
        CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_graph_resolution_rules_set
    ON graph_resolution_rules (rule_set_version, rule_order);

CREATE TABLE graph_configs (
    version VARCHAR(64) PRIMARY KEY,
    extraction_model VARCHAR(255) NOT NULL,
    extraction_revision VARCHAR(255) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    normalization_version VARCHAR(64) NOT NULL,
    resolution_rule_set_version VARCHAR(64) NOT NULL,
    community_algorithm VARCHAR(64) NOT NULL,
    community_algorithm_version VARCHAR(64) NOT NULL,
    community_seed BIGINT NOT NULL,
    community_resolution NUMERIC(8, 4) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_configs_resolution_set
        FOREIGN KEY (resolution_rule_set_version)
        REFERENCES graph_resolution_rule_sets (version),
    CONSTRAINT ck_graph_configs_version
        CHECK (btrim(version) <> ''),
    CONSTRAINT ck_graph_configs_model
        CHECK (
            btrim(extraction_model) <> ''
            AND btrim(extraction_revision) <> ''
        ),
    CONSTRAINT ck_graph_configs_contracts
        CHECK (
            btrim(prompt_version) <> ''
            AND btrim(schema_version) <> ''
            AND btrim(normalization_version) <> ''
            AND btrim(community_algorithm) <> ''
            AND btrim(community_algorithm_version) <> ''
        ),
    CONSTRAINT ck_graph_configs_community_resolution
        CHECK (community_resolution > 0 AND community_resolution <= 10),
    CONSTRAINT ck_graph_configs_reason
        CHECK (btrim(reason) <> '')
);

COMMENT ON COLUMN graph_configs.created_by IS
    'Historical actor identifier intentionally has no users FK so config audit survives account deletion';

CREATE INDEX ix_graph_configs_resolution_set
    ON graph_configs (resolution_rule_set_version);

CREATE TABLE graph_manifests (
    graph_generation BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    graph_config_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expected_document_count BIGINT NOT NULL,
    projected_document_count BIGINT NOT NULL DEFAULT 0,
    entity_count BIGINT NOT NULL DEFAULT 0,
    mention_count BIGINT NOT NULL DEFAULT 0,
    relationship_count BIGINT NOT NULL DEFAULT 0,
    relationship_evidence_count BIGINT NOT NULL DEFAULT 0,
    community_count BIGINT NOT NULL DEFAULT 0,
    community_claim_count BIGINT NOT NULL DEFAULT 0,
    cache_hit_count BIGINT NOT NULL DEFAULT 0,
    model_call_count BIGINT NOT NULL DEFAULT 0,
    build_attempt INTEGER NOT NULL DEFAULT 0,
    build_max_attempts INTEGER NOT NULL DEFAULT 3,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    heartbeat_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    failure_code VARCHAR(64),
    failure_reason VARCHAR(1000),
    retention_until TIMESTAMP WITH TIME ZONE,
    requested_by UUID,
    build_reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_manifests_config
        FOREIGN KEY (graph_config_version)
        REFERENCES graph_configs (version),
    CONSTRAINT ck_graph_manifests_status
        CHECK (
            status IN (
                'BUILDING', 'READY', 'ACTIVE',
                'RETIRED', 'FAILED', 'DELETED'
            )
        ),
    CONSTRAINT ck_graph_manifests_counts
        CHECK (
            expected_document_count >= 0
            AND projected_document_count >= 0
            AND projected_document_count <= expected_document_count
            AND entity_count >= 0
            AND mention_count >= entity_count
            AND relationship_count >= 0
            AND relationship_evidence_count >= relationship_count
            AND community_count >= 0
            AND community_claim_count >= 0
            AND cache_hit_count >= 0
            AND model_call_count >= 0
        ),
    CONSTRAINT ck_graph_manifests_attempts
        CHECK (
            build_attempt >= 0
            AND build_attempt <= build_max_attempts
            AND build_max_attempts BETWEEN 1 AND 10
        ),
    CONSTRAINT ck_graph_manifests_lease
        CHECK (
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
    CONSTRAINT ck_graph_manifests_reason
        CHECK (btrim(build_reason) <> '')
);

COMMENT ON COLUMN graph_manifests.requested_by IS
    'Historical requester identifier intentionally has no users FK so build audit survives account deletion';

CREATE UNIQUE INDEX uq_graph_manifests_active
    ON graph_manifests ((status))
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_graph_manifests_building_config
    ON graph_manifests (graph_config_version)
    WHERE status = 'BUILDING';

CREATE INDEX ix_graph_manifests_lifecycle
    ON graph_manifests (status, graph_generation DESC);

CREATE INDEX ix_graph_manifests_claim
    ON graph_manifests (created_at, graph_generation)
    WHERE status = 'BUILDING';

CREATE INDEX ix_graph_manifests_lease_expiry
    ON graph_manifests (lease_expires_at)
    WHERE status = 'BUILDING' AND lease_expires_at IS NOT NULL;

CREATE TABLE graph_publication_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    previous_graph_generation BIGINT,
    graph_generation BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_user_id UUID,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_graph_publication_events_id_generation
        UNIQUE (id, graph_generation),
    CONSTRAINT fk_graph_publication_events_previous
        FOREIGN KEY (previous_graph_generation)
        REFERENCES graph_manifests (graph_generation),
    CONSTRAINT fk_graph_publication_events_target
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation),
    CONSTRAINT ck_graph_publication_events_action
        CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_graph_publication_events_reason
        CHECK (btrim(reason) <> '')
);

COMMENT ON COLUMN graph_publication_events.actor_user_id IS
    'Historical actor identifier intentionally has no users FK so audit events survive account deletion';

CREATE INDEX ix_graph_publication_events_target
    ON graph_publication_events (graph_generation, created_at DESC);

CREATE INDEX ix_graph_publication_events_previous
    ON graph_publication_events (previous_graph_generation)
    WHERE previous_graph_generation IS NOT NULL;

CREATE TABLE graph_publications (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    graph_generation BIGINT NOT NULL UNIQUE,
    publication_event_id BIGINT NOT NULL UNIQUE,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_graph_publications_singleton
        CHECK (singleton_id = 1),
    CONSTRAINT fk_graph_publications_manifest
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation),
    CONSTRAINT fk_graph_publications_event
        FOREIGN KEY (publication_event_id, graph_generation)
        REFERENCES graph_publication_events (id, graph_generation)
);

CREATE TABLE graph_extraction_artifacts (
    id UUID PRIMARY KEY,
    graph_config_version VARCHAR(64) NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    parent_chunk_id UUID NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    output_json JSONB NOT NULL,
    output_hash VARCHAR(64) NOT NULL,
    entity_count INTEGER NOT NULL,
    relationship_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_extraction_artifacts_config
        FOREIGN KEY (graph_config_version)
        REFERENCES graph_configs (version),
    CONSTRAINT fk_graph_extraction_artifacts_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_extraction_artifacts_parent
        FOREIGN KEY (parent_chunk_id, document_id, revision_id)
        REFERENCES chunks (id, document_id, revision_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_graph_extraction_artifacts_input
        UNIQUE (graph_config_version, parent_chunk_id, input_hash),
    CONSTRAINT ck_graph_extraction_artifacts_hashes
        CHECK (length(input_hash) = 64 AND length(output_hash) = 64),
    CONSTRAINT ck_graph_extraction_artifacts_output
        CHECK (jsonb_typeof(output_json) = 'object'),
    CONSTRAINT ck_graph_extraction_artifacts_counts
        CHECK (entity_count >= 0 AND relationship_count >= 0)
);

CREATE INDEX ix_graph_extraction_artifacts_revision
    ON graph_extraction_artifacts (revision_id, parent_chunk_id);

CREATE TABLE graph_projection_states (
    graph_generation BIGINT NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    acl_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    artifact_ids JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (graph_generation, document_id),
    CONSTRAINT fk_graph_projection_states_manifest
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_projection_states_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_graph_projection_states_acl
        CHECK (acl_version >= 0),
    CONSTRAINT ck_graph_projection_states_state
        CHECK (state IN ('PROJECTED', 'FAILED')),
    CONSTRAINT ck_graph_projection_states_hash
        CHECK (length(input_hash) = 64),
    CONSTRAINT ck_graph_projection_states_artifacts
        CHECK (jsonb_typeof(artifact_ids) = 'array')
);

CREATE INDEX ix_graph_projection_states_document
    ON graph_projection_states (document_id, graph_generation DESC);

CREATE INDEX ix_graph_projection_states_revision
    ON graph_projection_states (revision_id, graph_generation);

CREATE TABLE graph_entities (
    id UUID PRIMARY KEY,
    graph_generation BIGINT NOT NULL,
    canonical_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_entities_manifest
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT uq_graph_entities_identity
        UNIQUE (id, graph_generation),
    CONSTRAINT uq_graph_entities_key
        UNIQUE (graph_generation, entity_type, normalized_name),
    CONSTRAINT ck_graph_entities_name
        CHECK (
            btrim(canonical_name) <> ''
            AND btrim(normalized_name) <> ''
            AND btrim(entity_type) <> ''
        )
);

CREATE INDEX ix_graph_entities_browse
    ON graph_entities (graph_generation, entity_type, normalized_name);

CREATE TABLE graph_entity_aliases (
    graph_generation BIGINT NOT NULL,
    entity_id UUID NOT NULL,
    alias TEXT NOT NULL,
    normalized_alias TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (graph_generation, entity_id, normalized_alias),
    CONSTRAINT fk_graph_entity_aliases_entity
        FOREIGN KEY (entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT ck_graph_entity_aliases_value
        CHECK (btrim(alias) <> '' AND btrim(normalized_alias) <> '')
);

CREATE INDEX ix_graph_entity_aliases_lookup
    ON graph_entity_aliases (graph_generation, normalized_alias);

CREATE TABLE graph_entity_mentions (
    id UUID PRIMARY KEY,
    graph_generation BIGINT NOT NULL,
    entity_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    parent_chunk_id UUID NOT NULL,
    child_chunk_id UUID NOT NULL,
    source_span_id UUID NOT NULL,
    surface_text TEXT NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_entity_mentions_entity
        FOREIGN KEY (entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_entity_mentions_parent
        FOREIGN KEY (parent_chunk_id, document_id, revision_id)
        REFERENCES chunks (id, document_id, revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_entity_mentions_child
        FOREIGN KEY (child_chunk_id, document_id, revision_id)
        REFERENCES chunks (id, document_id, revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_entity_mentions_span
        FOREIGN KEY (
            source_span_id, child_chunk_id, document_id, revision_id
        )
        REFERENCES source_spans (
            id, chunk_id, document_id, revision_id
        )
        ON DELETE CASCADE,
    CONSTRAINT uq_graph_entity_mentions_anchor
        UNIQUE (
            graph_generation, entity_id, child_chunk_id,
            source_span_id, start_offset, end_offset
        ),
    CONSTRAINT ck_graph_entity_mentions_text
        CHECK (btrim(surface_text) <> ''),
    CONSTRAINT ck_graph_entity_mentions_offsets
        CHECK (start_offset >= 0 AND end_offset > start_offset)
);

CREATE INDEX ix_graph_entity_mentions_entity
    ON graph_entity_mentions (graph_generation, entity_id);

CREATE INDEX ix_graph_entity_mentions_document
    ON graph_entity_mentions (document_id, revision_id, graph_generation);

CREATE INDEX ix_graph_entity_mentions_child
    ON graph_entity_mentions (child_chunk_id, graph_generation);

CREATE INDEX ix_graph_entity_mentions_span
    ON graph_entity_mentions (source_span_id, graph_generation);

CREATE TABLE graph_relationships (
    id UUID PRIMARY KEY,
    graph_generation BIGINT NOT NULL,
    source_entity_id UUID NOT NULL,
    target_entity_id UUID NOT NULL,
    relationship_type VARCHAR(64) NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_relationships_source
        FOREIGN KEY (source_entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_relationships_target
        FOREIGN KEY (target_entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT uq_graph_relationships_identity
        UNIQUE (id, graph_generation),
    CONSTRAINT uq_graph_relationships_key
        UNIQUE (
            graph_generation, source_entity_id,
            target_entity_id, relationship_type
        ),
    CONSTRAINT ck_graph_relationships_type
        CHECK (btrim(relationship_type) <> ''),
    CONSTRAINT ck_graph_relationships_distinct
        CHECK (source_entity_id <> target_entity_id)
);

CREATE INDEX ix_graph_relationships_source
    ON graph_relationships (graph_generation, source_entity_id);

CREATE INDEX ix_graph_relationships_target
    ON graph_relationships (graph_generation, target_entity_id);

CREATE TABLE graph_relationship_evidence (
    id UUID PRIMARY KEY,
    graph_generation BIGINT NOT NULL,
    relationship_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    parent_chunk_id UUID NOT NULL,
    child_chunk_id UUID NOT NULL,
    source_span_id UUID NOT NULL,
    evidence_text TEXT NOT NULL,
    evidence_text_hash VARCHAR(64) NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_relationship_evidence_relationship
        FOREIGN KEY (relationship_id, graph_generation)
        REFERENCES graph_relationships (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_relationship_evidence_parent
        FOREIGN KEY (parent_chunk_id, document_id, revision_id)
        REFERENCES chunks (id, document_id, revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_relationship_evidence_child
        FOREIGN KEY (child_chunk_id, document_id, revision_id)
        REFERENCES chunks (id, document_id, revision_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_relationship_evidence_span
        FOREIGN KEY (
            source_span_id, child_chunk_id, document_id, revision_id
        )
        REFERENCES source_spans (
            id, chunk_id, document_id, revision_id
        )
        ON DELETE CASCADE,
    CONSTRAINT uq_graph_relationship_evidence_anchor
        UNIQUE (
            graph_generation, relationship_id,
            child_chunk_id, evidence_text_hash
        ),
    CONSTRAINT uq_graph_relationship_evidence_identity
        UNIQUE (id, graph_generation),
    CONSTRAINT ck_graph_relationship_evidence_text
        CHECK (btrim(evidence_text) <> ''),
    CONSTRAINT ck_graph_relationship_evidence_hash
        CHECK (length(evidence_text_hash) = 64),
    CONSTRAINT ck_graph_relationship_evidence_offsets
        CHECK (start_offset >= 0 AND end_offset > start_offset)
);

CREATE INDEX ix_graph_relationship_evidence_relationship
    ON graph_relationship_evidence (graph_generation, relationship_id);

CREATE INDEX ix_graph_relationship_evidence_document
    ON graph_relationship_evidence (
        document_id, revision_id, graph_generation
    );

CREATE INDEX ix_graph_relationship_evidence_child
    ON graph_relationship_evidence (child_chunk_id, graph_generation);

CREATE INDEX ix_graph_relationship_evidence_span
    ON graph_relationship_evidence (source_span_id, graph_generation);

CREATE TABLE graph_adjacency (
    graph_generation BIGINT NOT NULL,
    source_entity_id UUID NOT NULL,
    target_entity_id UUID NOT NULL,
    relationship_id UUID NOT NULL,
    direction VARCHAR(4) NOT NULL,
    weight NUMERIC(12, 4) NOT NULL DEFAULT 1,
    PRIMARY KEY (
        graph_generation, source_entity_id,
        target_entity_id, relationship_id, direction
    ),
    CONSTRAINT fk_graph_adjacency_source
        FOREIGN KEY (source_entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_adjacency_target
        FOREIGN KEY (target_entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_adjacency_relationship
        FOREIGN KEY (relationship_id, graph_generation)
        REFERENCES graph_relationships (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT ck_graph_adjacency_direction
        CHECK (direction IN ('OUT', 'IN')),
    CONSTRAINT ck_graph_adjacency_weight
        CHECK (weight > 0)
);

CREATE INDEX ix_graph_adjacency_traverse
    ON graph_adjacency (
        graph_generation, source_entity_id, direction, target_entity_id
    );

CREATE INDEX ix_graph_adjacency_relationship
    ON graph_adjacency (relationship_id, graph_generation);

CREATE TABLE graph_communities (
    id UUID PRIMARY KEY,
    graph_generation BIGINT NOT NULL,
    community_key INTEGER NOT NULL,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    entity_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_communities_manifest
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT uq_graph_communities_identity
        UNIQUE (id, graph_generation),
    CONSTRAINT uq_graph_communities_key
        UNIQUE (graph_generation, community_key),
    CONSTRAINT ck_graph_communities_key
        CHECK (community_key >= 0),
    CONSTRAINT ck_graph_communities_text
        CHECK (btrim(title) <> '' AND btrim(summary) <> ''),
    CONSTRAINT ck_graph_communities_count
        CHECK (entity_count > 0)
);

CREATE INDEX ix_graph_communities_browse
    ON graph_communities (graph_generation, community_key);

CREATE TABLE graph_community_members (
    graph_generation BIGINT NOT NULL,
    community_id UUID NOT NULL,
    entity_id UUID NOT NULL,
    member_order INTEGER NOT NULL,
    PRIMARY KEY (graph_generation, community_id, entity_id),
    CONSTRAINT fk_graph_community_members_community
        FOREIGN KEY (community_id, graph_generation)
        REFERENCES graph_communities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_community_members_entity
        FOREIGN KEY (entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT ck_graph_community_members_order
        CHECK (member_order >= 0)
);

CREATE INDEX ix_graph_community_members_entity
    ON graph_community_members (graph_generation, entity_id);

CREATE TABLE graph_community_claims (
    id UUID PRIMARY KEY,
    graph_generation BIGINT NOT NULL,
    community_id UUID NOT NULL,
    relationship_id UUID NOT NULL,
    relationship_evidence_id UUID NOT NULL,
    claim_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_community_claims_community
        FOREIGN KEY (community_id, graph_generation)
        REFERENCES graph_communities (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_community_claims_relationship
        FOREIGN KEY (relationship_id, graph_generation)
        REFERENCES graph_relationships (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_community_claims_evidence
        FOREIGN KEY (relationship_evidence_id, graph_generation)
        REFERENCES graph_relationship_evidence (id, graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT uq_graph_community_claims_evidence
        UNIQUE (
            graph_generation, community_id,
            relationship_evidence_id
        ),
    CONSTRAINT ck_graph_community_claims_text
        CHECK (btrim(claim_text) <> '')
);

CREATE INDEX ix_graph_community_claims_community
    ON graph_community_claims (graph_generation, community_id);

CREATE INDEX ix_graph_community_claims_relationship
    ON graph_community_claims (relationship_id, graph_generation);

CREATE INDEX ix_graph_community_claims_evidence
    ON graph_community_claims (
        relationship_evidence_id, graph_generation
    );

CREATE FUNCTION reject_immutable_graph_row()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER reject_graph_resolution_rule_set_mutation
    BEFORE UPDATE OR DELETE ON graph_resolution_rule_sets
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TRIGGER reject_graph_resolution_rule_mutation
    BEFORE UPDATE OR DELETE ON graph_resolution_rules
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TRIGGER reject_graph_config_mutation
    BEFORE UPDATE OR DELETE ON graph_configs
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TRIGGER reject_graph_publication_event_mutation
    BEFORE UPDATE OR DELETE ON graph_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE FUNCTION validate_graph_manifest_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> NEW.status AND NOT (
        (OLD.status = 'BUILDING' AND NEW.status IN ('READY', 'FAILED'))
        OR (OLD.status = 'READY' AND NEW.status IN ('ACTIVE', 'FAILED'))
        OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')
        OR (OLD.status = 'RETIRED' AND NEW.status IN ('ACTIVE', 'DELETED'))
        OR (OLD.status = 'FAILED' AND NEW.status = 'DELETED')
    ) THEN
        RAISE EXCEPTION 'Invalid graph manifest transition: % -> %',
            OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status IN ('READY', 'ACTIVE') THEN
        IF NEW.projected_document_count <> NEW.expected_document_count THEN
            RAISE EXCEPTION 'Graph generation is incomplete'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.mention_count < NEW.entity_count THEN
            RAISE EXCEPTION 'Every graph entity must have an evidence mention'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.relationship_evidence_count < NEW.relationship_count THEN
            RAISE EXCEPTION 'Every graph relationship must have evidence'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_graph_manifest_transition
    BEFORE UPDATE ON graph_manifests
    FOR EACH ROW EXECUTE FUNCTION validate_graph_manifest_transition();
