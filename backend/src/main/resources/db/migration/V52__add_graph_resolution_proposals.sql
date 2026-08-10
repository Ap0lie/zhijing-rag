CREATE TABLE graph_resolution_proposals (
    id UUID PRIMARY KEY,
    origin_candidate_id UUID,
    status VARCHAR(16) NOT NULL,
    base_graph_generation BIGINT NOT NULL,
    base_graph_config_version VARCHAR(64) NOT NULL,
    current_revision_number INTEGER NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    materialized_config_version VARCHAR(64),
    applied_graph_generation BIGINT,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_resolution_proposals_candidate
        FOREIGN KEY (origin_candidate_id)
        REFERENCES graph_resolution_candidates (id) ON DELETE SET NULL,
    CONSTRAINT fk_graph_resolution_proposals_manifest
        FOREIGN KEY (base_graph_generation)
        REFERENCES graph_manifests (graph_generation),
    CONSTRAINT fk_graph_resolution_proposals_config
        FOREIGN KEY (base_graph_config_version)
        REFERENCES graph_configs (version),
    CONSTRAINT fk_graph_resolution_proposals_materialized_config
        FOREIGN KEY (materialized_config_version)
        REFERENCES graph_configs (version),
    CONSTRAINT fk_graph_resolution_proposals_applied_manifest
        FOREIGN KEY (applied_graph_generation)
        REFERENCES graph_manifests (graph_generation),
    CONSTRAINT fk_graph_resolution_proposals_actor
        FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_graph_resolution_proposals_materialized_config
        UNIQUE (materialized_config_version),
    CONSTRAINT ck_graph_resolution_proposals_status
        CHECK (status IN (
            'DRAFT', 'READY', 'CONFLICTED', 'STALE',
            'WITHDRAWN', 'MATERIALIZED', 'APPLIED'
        )),
    CONSTRAINT ck_graph_resolution_proposals_versions
        CHECK (current_revision_number >= 1 AND version >= 1),
    CONSTRAINT ck_graph_resolution_proposals_materialization
        CHECK (
            (status IN ('MATERIALIZED', 'APPLIED')
                AND materialized_config_version IS NOT NULL)
            OR
            (status NOT IN ('MATERIALIZED', 'APPLIED')
                AND materialized_config_version IS NULL
                AND applied_graph_generation IS NULL)
        ),
    CONSTRAINT ck_graph_resolution_proposals_applied
        CHECK (
            (status = 'APPLIED' AND applied_graph_generation IS NOT NULL)
            OR
            (status <> 'APPLIED' AND applied_graph_generation IS NULL)
        )
);

CREATE INDEX ix_graph_resolution_proposals_attention
    ON graph_resolution_proposals (status, updated_at DESC, id)
    WHERE status IN ('DRAFT', 'READY', 'CONFLICTED', 'STALE');

CREATE INDEX ix_graph_resolution_proposals_base
    ON graph_resolution_proposals (
        base_graph_config_version, base_graph_generation, updated_at DESC, id
    );

CREATE TABLE graph_resolution_proposal_revisions (
    proposal_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    id UUID NOT NULL UNIQUE,
    supersedes_revision_number INTEGER,
    action VARCHAR(16) NOT NULL,
    source_set_hash VARCHAR(64) NOT NULL,
    source_entity_ids JSONB NOT NULL,
    source_entity_keys JSONB NOT NULL,
    match_aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_canonical_name TEXT NOT NULL,
    target_normalized_name TEXT NOT NULL,
    target_entity_type VARCHAR(64) NOT NULL,
    mention_count INTEGER NOT NULL,
    source_span_count INTEGER NOT NULL,
    relationship_count INTEGER NOT NULL,
    relationship_evidence_count INTEGER NOT NULL,
    community_count INTEGER NOT NULL,
    document_count INTEGER NOT NULL,
    blockers JSONB NOT NULL DEFAULT '[]'::jsonb,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (proposal_id, revision_number),
    CONSTRAINT fk_graph_resolution_proposal_revisions_proposal
        FOREIGN KEY (proposal_id)
        REFERENCES graph_resolution_proposals (id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_proposal_revisions_actor
        FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_graph_resolution_proposal_revisions_supersedes
        FOREIGN KEY (proposal_id, supersedes_revision_number)
        REFERENCES graph_resolution_proposal_revisions (
            proposal_id, revision_number
        ),
    CONSTRAINT ck_graph_resolution_proposal_revisions_number
        CHECK (
            revision_number >= 1
            AND (
                (revision_number = 1 AND supersedes_revision_number IS NULL)
                OR
                (revision_number > 1
                    AND supersedes_revision_number = revision_number - 1)
            )
        ),
    CONSTRAINT ck_graph_resolution_proposal_revisions_action
        CHECK (action IN ('MERGE', 'SPLIT')),
    CONSTRAINT ck_graph_resolution_proposal_revisions_hash
        CHECK (source_set_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_graph_resolution_proposal_revisions_sources
        CHECK (
            jsonb_typeof(source_entity_ids) = 'array'
            AND jsonb_array_length(source_entity_ids) BETWEEN 1 AND 20
            AND jsonb_typeof(source_entity_keys) = 'array'
            AND jsonb_array_length(source_entity_keys) =
                jsonb_array_length(source_entity_ids)
        ),
    CONSTRAINT ck_graph_resolution_proposal_revisions_aliases
        CHECK (
            jsonb_typeof(match_aliases) = 'array'
            AND (action = 'MERGE' OR jsonb_array_length(match_aliases) > 0)
        ),
    CONSTRAINT ck_graph_resolution_proposal_revisions_target
        CHECK (
            btrim(target_canonical_name) <> ''
            AND btrim(target_normalized_name) <> ''
            AND btrim(target_entity_type) <> ''
        ),
    CONSTRAINT ck_graph_resolution_proposal_revisions_counts
        CHECK (
            mention_count >= 0 AND source_span_count >= 0
            AND relationship_count >= 0
            AND relationship_evidence_count >= 0
            AND community_count >= 0 AND document_count >= 0
        ),
    CONSTRAINT ck_graph_resolution_proposal_revisions_notices
        CHECK (
            jsonb_typeof(blockers) = 'array'
            AND jsonb_typeof(warnings) = 'array'
        ),
    CONSTRAINT ck_graph_resolution_proposal_revisions_reason
        CHECK (length(btrim(reason)) BETWEEN 8 AND 500)
);

CREATE INDEX ix_graph_resolution_proposal_revisions_created
    ON graph_resolution_proposal_revisions (
        proposal_id, revision_number DESC
    );

CREATE TABLE graph_resolution_proposal_revision_entities (
    proposal_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    graph_generation BIGINT NOT NULL,
    entity_id UUID NOT NULL,
    entity_order INTEGER NOT NULL,
    canonical_name TEXT NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    mention_count INTEGER NOT NULL,
    relationship_count INTEGER NOT NULL,
    relationship_evidence_count INTEGER NOT NULL,
    PRIMARY KEY (proposal_id, revision_number, entity_id),
    CONSTRAINT fk_graph_resolution_proposal_revision_entities_revision
        FOREIGN KEY (proposal_id, revision_number)
        REFERENCES graph_resolution_proposal_revisions (
            proposal_id, revision_number
        ) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_proposal_revision_entities_entity
        FOREIGN KEY (entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation),
    CONSTRAINT uq_graph_resolution_proposal_revision_entities_order
        UNIQUE (proposal_id, revision_number, entity_order),
    CONSTRAINT ck_graph_resolution_proposal_revision_entities_order
        CHECK (entity_order BETWEEN 0 AND 19),
    CONSTRAINT ck_graph_resolution_proposal_revision_entities_aliases
        CHECK (jsonb_typeof(aliases) = 'array'),
    CONSTRAINT ck_graph_resolution_proposal_revision_entities_counts
        CHECK (
            mention_count >= 0 AND relationship_count >= 0
            AND relationship_evidence_count >= 0
        )
);

CREATE INDEX ix_graph_resolution_proposal_revision_entities_lookup
    ON graph_resolution_proposal_revision_entities (
        graph_generation, entity_id, proposal_id, revision_number
    );

CREATE TABLE graph_resolution_proposal_conflicts (
    proposal_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    conflicting_proposal_id UUID NOT NULL,
    conflict_code VARCHAR(64) NOT NULL,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (
        proposal_id, revision_number,
        conflicting_proposal_id, conflict_code
    ),
    CONSTRAINT fk_graph_resolution_proposal_conflicts_revision
        FOREIGN KEY (proposal_id, revision_number)
        REFERENCES graph_resolution_proposal_revisions (
            proposal_id, revision_number
        ) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_proposal_conflicts_other
        FOREIGN KEY (conflicting_proposal_id)
        REFERENCES graph_resolution_proposals (id) ON DELETE CASCADE,
    CONSTRAINT ck_graph_resolution_proposal_conflicts_distinct
        CHECK (proposal_id <> conflicting_proposal_id),
    CONSTRAINT ck_graph_resolution_proposal_conflicts_message
        CHECK (btrim(message) <> '')
);

CREATE INDEX ix_graph_resolution_proposal_conflicts_other
    ON graph_resolution_proposal_conflicts (
        conflicting_proposal_id, proposal_id, revision_number
    );

CREATE TABLE graph_resolution_proposal_events (
    id BIGSERIAL PRIMARY KEY,
    proposal_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_user_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    previous_status VARCHAR(16),
    next_status VARCHAR(16) NOT NULL,
    proposal_version INTEGER NOT NULL,
    reason VARCHAR(500) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_resolution_proposal_events_proposal
        FOREIGN KEY (proposal_id)
        REFERENCES graph_resolution_proposals (id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_proposal_events_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_graph_resolution_proposal_events_type
        CHECK (event_type IN (
            'CREATED', 'REVISED', 'CONFLICTED', 'READY', 'STALE',
            'WITHDRAWN', 'MATERIALIZED', 'APPLIED'
        )),
    CONSTRAINT ck_graph_resolution_proposal_events_status
        CHECK (
            next_status IN (
                'DRAFT', 'READY', 'CONFLICTED', 'STALE',
                'WITHDRAWN', 'MATERIALIZED', 'APPLIED'
            )
            AND (
                previous_status IS NULL
                OR previous_status IN (
                    'DRAFT', 'READY', 'CONFLICTED', 'STALE',
                    'WITHDRAWN', 'MATERIALIZED', 'APPLIED'
                )
            )
        ),
    CONSTRAINT ck_graph_resolution_proposal_events_versions
        CHECK (revision_number >= 1 AND proposal_version >= 1),
    CONSTRAINT ck_graph_resolution_proposal_events_reason
        CHECK (length(btrim(reason)) BETWEEN 8 AND 500),
    CONSTRAINT ck_graph_resolution_proposal_events_details
        CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_graph_resolution_proposal_events_proposal
    ON graph_resolution_proposal_events (proposal_id, id DESC);

CREATE TRIGGER graph_resolution_proposal_revisions_immutable
    BEFORE UPDATE OR DELETE ON graph_resolution_proposal_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TRIGGER graph_resolution_proposal_revision_entities_immutable
    BEFORE UPDATE OR DELETE ON graph_resolution_proposal_revision_entities
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TRIGGER graph_resolution_proposal_events_immutable
    BEFORE UPDATE OR DELETE ON graph_resolution_proposal_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

COMMENT ON TABLE graph_resolution_proposals IS
    'Phase 21C mutable workflow state. It never changes graph facts or publication directly.';

COMMENT ON TABLE graph_resolution_proposal_revisions IS
    'Immutable administrator-authored proposal revisions. Corrections append a new revision.';

COMMENT ON TABLE graph_resolution_proposal_conflicts IS
    'Rebuildable conflict projection for current proposal revisions.';
