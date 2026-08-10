CREATE TABLE graph_resolution_candidate_snapshots (
    id UUID PRIMARY KEY,
    graph_generation BIGINT NOT NULL,
    graph_config_version VARCHAR(64) NOT NULL,
    source_set_hash VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    duplicate_candidate_count INTEGER NOT NULL DEFAULT 0,
    split_candidate_count INTEGER NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    stale_at TIMESTAMP WITH TIME ZONE,
    stale_reason VARCHAR(255),
    CONSTRAINT fk_graph_resolution_candidate_snapshot_manifest
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_candidate_snapshot_config
        FOREIGN KEY (graph_config_version) REFERENCES graph_configs (version),
    CONSTRAINT fk_graph_resolution_candidate_snapshot_actor
        FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_graph_resolution_candidate_snapshot_input
        UNIQUE (graph_generation, algorithm_version, input_hash),
    CONSTRAINT ck_graph_resolution_candidate_snapshot_hashes
        CHECK (
            source_set_hash ~ '^[0-9a-f]{64}$'
            AND input_hash ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_graph_resolution_candidate_snapshot_status
        CHECK (status IN ('READY', 'STALE', 'FAILED')),
    CONSTRAINT ck_graph_resolution_candidate_snapshot_counts
        CHECK (duplicate_candidate_count >= 0 AND split_candidate_count >= 0),
    CONSTRAINT ck_graph_resolution_candidate_snapshot_reason
        CHECK (length(btrim(reason)) BETWEEN 8 AND 500),
    CONSTRAINT ck_graph_resolution_candidate_snapshot_stale
        CHECK (
            (status = 'STALE' AND stale_at IS NOT NULL
                AND length(btrim(stale_reason)) > 0)
            OR
            (status <> 'STALE' AND stale_at IS NULL AND stale_reason IS NULL)
        )
);

CREATE INDEX ix_graph_resolution_candidate_snapshots_generation
    ON graph_resolution_candidate_snapshots (
        graph_generation, status, created_at DESC, id
    );

CREATE TABLE graph_resolution_candidates (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL,
    candidate_key VARCHAR(64) NOT NULL,
    candidate_type VARCHAR(32) NOT NULL,
    suggested_action VARCHAR(16) NOT NULL,
    suggested_target_name TEXT,
    suggested_target_type VARCHAR(64),
    suggested_aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    hard_signal_count INTEGER NOT NULL,
    signal_count INTEGER NOT NULL,
    evidence_count INTEGER NOT NULL,
    source_document_count INTEGER NOT NULL,
    stable_rank INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_resolution_candidates_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES graph_resolution_candidate_snapshots (id) ON DELETE CASCADE,
    CONSTRAINT uq_graph_resolution_candidates_key
        UNIQUE (snapshot_id, candidate_key),
    CONSTRAINT uq_graph_resolution_candidates_rank
        UNIQUE (snapshot_id, candidate_type, stable_rank),
    CONSTRAINT ck_graph_resolution_candidates_key
        CHECK (candidate_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_graph_resolution_candidates_type
        CHECK (candidate_type IN ('SUSPECTED_DUPLICATE', 'SUSPECTED_MERGE')),
    CONSTRAINT ck_graph_resolution_candidates_action
        CHECK (
            (candidate_type = 'SUSPECTED_DUPLICATE' AND suggested_action = 'MERGE')
            OR
            (candidate_type = 'SUSPECTED_MERGE' AND suggested_action = 'SPLIT')
        ),
    CONSTRAINT ck_graph_resolution_candidates_aliases
        CHECK (jsonb_typeof(suggested_aliases) = 'array'),
    CONSTRAINT ck_graph_resolution_candidates_counts
        CHECK (
            hard_signal_count >= 1
            AND signal_count >= hard_signal_count
            AND evidence_count >= 1
            AND source_document_count >= 1
            AND stable_rank >= 0
        )
);

CREATE INDEX ix_graph_resolution_candidates_browse
    ON graph_resolution_candidates (
        snapshot_id, candidate_type, stable_rank, id
    );

CREATE TABLE graph_resolution_candidate_entities (
    candidate_id UUID NOT NULL,
    graph_generation BIGINT NOT NULL,
    entity_id UUID NOT NULL,
    entity_order INTEGER NOT NULL,
    PRIMARY KEY (candidate_id, entity_id),
    CONSTRAINT fk_graph_resolution_candidate_entities_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES graph_resolution_candidates (id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_candidate_entities_entity
        FOREIGN KEY (entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation) ON DELETE CASCADE,
    CONSTRAINT uq_graph_resolution_candidate_entities_order
        UNIQUE (candidate_id, entity_order),
    CONSTRAINT ck_graph_resolution_candidate_entities_order
        CHECK (entity_order BETWEEN 0 AND 19)
);

CREATE INDEX ix_graph_resolution_candidate_entities_lookup
    ON graph_resolution_candidate_entities (
        graph_generation, entity_id, candidate_id
    );

CREATE TABLE graph_resolution_candidate_signals (
    candidate_id UUID NOT NULL,
    signal_order INTEGER NOT NULL,
    signal_code VARCHAR(64) NOT NULL,
    strength VARCHAR(16) NOT NULL,
    explanation VARCHAR(500) NOT NULL,
    numeric_value NUMERIC(10, 6),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (candidate_id, signal_order),
    CONSTRAINT fk_graph_resolution_candidate_signals_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES graph_resolution_candidates (id) ON DELETE CASCADE,
    CONSTRAINT uq_graph_resolution_candidate_signals_code
        UNIQUE (candidate_id, signal_code),
    CONSTRAINT ck_graph_resolution_candidate_signals_order
        CHECK (signal_order >= 0),
    CONSTRAINT ck_graph_resolution_candidate_signals_strength
        CHECK (strength IN ('HARD', 'SUPPORTING', 'WARNING')),
    CONSTRAINT ck_graph_resolution_candidate_signals_explanation
        CHECK (length(btrim(explanation)) BETWEEN 1 AND 500),
    CONSTRAINT ck_graph_resolution_candidate_signals_details
        CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_graph_resolution_candidate_signals_code
    ON graph_resolution_candidate_signals (signal_code, candidate_id);

CREATE TABLE graph_resolution_candidate_evidence (
    candidate_id UUID NOT NULL,
    evidence_order INTEGER NOT NULL,
    graph_generation BIGINT NOT NULL,
    entity_id UUID NOT NULL,
    anchor_type VARCHAR(24) NOT NULL,
    anchor_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    child_chunk_id UUID NOT NULL,
    source_span_id UUID NOT NULL,
    PRIMARY KEY (candidate_id, evidence_order),
    CONSTRAINT fk_graph_resolution_candidate_evidence_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES graph_resolution_candidates (id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_candidate_evidence_entity
        FOREIGN KEY (entity_id, graph_generation)
        REFERENCES graph_entities (id, graph_generation) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_candidate_evidence_span
        FOREIGN KEY (source_span_id, child_chunk_id, document_id, revision_id)
        REFERENCES source_spans (id, chunk_id, document_id, revision_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_graph_resolution_candidate_evidence_anchor
        UNIQUE (candidate_id, anchor_type, anchor_id),
    CONSTRAINT ck_graph_resolution_candidate_evidence_order
        CHECK (evidence_order BETWEEN 0 AND 15),
    CONSTRAINT ck_graph_resolution_candidate_evidence_type
        CHECK (anchor_type IN ('MENTION', 'RELATIONSHIP_EVIDENCE'))
);

CREATE INDEX ix_graph_resolution_candidate_evidence_document
    ON graph_resolution_candidate_evidence (
        document_id, revision_id, graph_generation, candidate_id
    );

CREATE TABLE graph_resolution_candidate_states (
    candidate_id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INTEGER NOT NULL DEFAULT 1,
    updated_by UUID,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_resolution_candidate_states_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES graph_resolution_candidates (id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_candidate_states_actor
        FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ck_graph_resolution_candidate_states_status
        CHECK (status IN ('ACTIVE', 'IGNORED')),
    CONSTRAINT ck_graph_resolution_candidate_states_version
        CHECK (version >= 1)
);

CREATE INDEX ix_graph_resolution_candidate_states_status
    ON graph_resolution_candidate_states (status, candidate_id);

CREATE TABLE graph_resolution_candidate_events (
    id BIGSERIAL PRIMARY KEY,
    candidate_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    actor_user_id UUID NOT NULL,
    previous_status VARCHAR(16) NOT NULL,
    next_status VARCHAR(16) NOT NULL,
    version INTEGER NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_resolution_candidate_events_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES graph_resolution_candidates (id) ON DELETE CASCADE,
    CONSTRAINT fk_graph_resolution_candidate_events_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_graph_resolution_candidate_events_type
        CHECK (event_type IN ('IGNORED', 'RESTORED')),
    CONSTRAINT ck_graph_resolution_candidate_events_status
        CHECK (
            previous_status IN ('ACTIVE', 'IGNORED')
            AND next_status IN ('ACTIVE', 'IGNORED')
            AND previous_status <> next_status
        ),
    CONSTRAINT ck_graph_resolution_candidate_events_version
        CHECK (version >= 2),
    CONSTRAINT ck_graph_resolution_candidate_events_reason
        CHECK (length(btrim(reason)) BETWEEN 8 AND 500)
);

CREATE INDEX ix_graph_resolution_candidate_events_candidate
    ON graph_resolution_candidate_events (candidate_id, id DESC);

COMMENT ON TABLE graph_resolution_candidate_snapshots IS
    'Phase 21B rebuildable governance projections. Retrieval and publication never read these rows.';

COMMENT ON TABLE graph_resolution_candidates IS
    'Explainable candidate hints only. They never merge, split, build, or publish graph facts.';
