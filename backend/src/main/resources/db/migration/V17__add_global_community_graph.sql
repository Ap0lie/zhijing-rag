ALTER TABLE embedding_artifacts
    DROP CONSTRAINT ck_embedding_artifacts_purpose,
    ADD CONSTRAINT ck_embedding_artifacts_purpose
        CHECK (purpose IN ('CHILD_INDEX', 'GLOBAL_REPORT_INDEX'));

CREATE TABLE global_graph_configs (
    version VARCHAR(64) PRIMARY KEY,
    report_model VARCHAR(255) NOT NULL,
    report_revision VARCHAR(255) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    community_algorithm VARCHAR(64) NOT NULL,
    community_algorithm_version VARCHAR(64) NOT NULL,
    community_seed BIGINT NOT NULL,
    community_resolution NUMERIC(8, 4) NOT NULL,
    index_config_version VARCHAR(64) NOT NULL,
    bm25_top_k SMALLINT NOT NULL,
    vector_top_k SMALLINT NOT NULL,
    rrf_rank_constant SMALLINT NOT NULL,
    report_limit SMALLINT NOT NULL,
    context_token_budget SMALLINT NOT NULL,
    map_call_limit SMALLINT NOT NULL,
    model_call_limit SMALLINT NOT NULL,
    hard_timeout_ms INTEGER NOT NULL,
    statement_timeout_ms INTEGER NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_global_graph_configs_index_config
        FOREIGN KEY (index_config_version) REFERENCES index_configs (version),
    CONSTRAINT ck_global_graph_configs_version
        CHECK (version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT ck_global_graph_configs_model
        CHECK (
            btrim(report_model) <> ''
            AND btrim(report_revision) <> ''
            AND btrim(prompt_version) <> ''
            AND btrim(schema_version) <> ''
        ),
    CONSTRAINT ck_global_graph_configs_community
        CHECK (
            btrim(community_algorithm) <> ''
            AND btrim(community_algorithm_version) <> ''
            AND community_resolution > 0
            AND community_resolution <= 10
        ),
    CONSTRAINT ck_global_graph_configs_retrieval
        CHECK (
            bm25_top_k BETWEEN 1 AND 100
            AND vector_top_k BETWEEN 0 AND 100
            AND rrf_rank_constant BETWEEN 1 AND 1000
            AND report_limit BETWEEN 1 AND 8
        ),
    CONSTRAINT ck_global_graph_configs_budget
        CHECK (
            context_token_budget BETWEEN 0 AND 2400
            AND map_call_limit BETWEEN 1 AND 8
            AND model_call_limit BETWEEN 2 AND 9
            AND model_call_limit = map_call_limit + 1
            AND hard_timeout_ms BETWEEN 1000 AND 30000
            AND statement_timeout_ms BETWEEN 50 AND 2000
        ),
    CONSTRAINT ck_global_graph_configs_reason
        CHECK (btrim(reason) <> '')
);

COMMENT ON COLUMN global_graph_configs.created_by IS
    'Historical actor identifier intentionally has no users FK so config audit survives account deletion';

CREATE INDEX ix_global_graph_configs_index
    ON global_graph_configs (index_config_version);

CREATE TRIGGER reject_global_graph_config_mutation
    BEFORE UPDATE OR DELETE ON global_graph_configs
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TABLE global_graph_manifests (
    global_generation BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    global_config_version VARCHAR(64) NOT NULL,
    source_graph_generation BIGINT NOT NULL,
    index_name VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    source_set_hash VARCHAR(64) NOT NULL,
    expected_source_count BIGINT NOT NULL,
    report_count BIGINT NOT NULL DEFAULT 0,
    claim_count BIGINT NOT NULL DEFAULT 0,
    evidence_count BIGINT NOT NULL DEFAULT 0,
    indexed_report_count BIGINT NOT NULL DEFAULT 0,
    valid_vector_count BIGINT NOT NULL DEFAULT 0,
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
    CONSTRAINT uq_global_graph_manifests_source
        UNIQUE (global_generation, source_graph_generation),
    CONSTRAINT fk_global_graph_manifests_config
        FOREIGN KEY (global_config_version)
        REFERENCES global_graph_configs (version),
    CONSTRAINT fk_global_graph_manifests_source_graph
        FOREIGN KEY (source_graph_generation)
        REFERENCES graph_manifests (graph_generation),
    CONSTRAINT ck_global_graph_manifests_status
        CHECK (
            status IN (
                'BUILDING', 'READY', 'ACTIVE',
                'RETIRED', 'FAILED', 'DELETED'
            )
        ),
    CONSTRAINT ck_global_graph_manifests_hash
        CHECK (length(source_set_hash) = 64),
    CONSTRAINT ck_global_graph_manifests_counts
        CHECK (
            expected_source_count >= 0
            AND report_count >= 0
            AND claim_count >= report_count
            AND evidence_count >= claim_count
            AND indexed_report_count >= 0
            AND indexed_report_count <= report_count
            AND valid_vector_count >= 0
            AND valid_vector_count <= indexed_report_count
            AND model_call_count >= 0
        ),
    CONSTRAINT ck_global_graph_manifests_attempts
        CHECK (
            build_attempt >= 0
            AND build_attempt <= build_max_attempts
            AND build_max_attempts BETWEEN 1 AND 10
        ),
    CONSTRAINT ck_global_graph_manifests_lease
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
    CONSTRAINT ck_global_graph_manifests_reason
        CHECK (btrim(build_reason) <> '')
);

COMMENT ON COLUMN global_graph_manifests.requested_by IS
    'Historical requester identifier intentionally has no users FK so build audit survives account deletion';

CREATE UNIQUE INDEX uq_global_graph_manifests_active
    ON global_graph_manifests ((status))
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_global_graph_manifests_building
    ON global_graph_manifests (global_config_version)
    WHERE status = 'BUILDING';

CREATE INDEX ix_global_graph_manifests_lifecycle
    ON global_graph_manifests (status, global_generation DESC);

CREATE INDEX ix_global_graph_manifests_claim
    ON global_graph_manifests (created_at, global_generation)
    WHERE status = 'BUILDING';

CREATE INDEX ix_global_graph_manifests_lease
    ON global_graph_manifests (lease_expires_at)
    WHERE status = 'BUILDING' AND lease_expires_at IS NOT NULL;

CREATE TABLE global_graph_sources (
    global_generation BIGINT NOT NULL,
    source_graph_generation BIGINT NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    acl_version BIGINT NOT NULL,
    document_title TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (global_generation, document_id),
    CONSTRAINT fk_global_graph_sources_manifest
        FOREIGN KEY (global_generation, source_graph_generation)
        REFERENCES global_graph_manifests (
            global_generation, source_graph_generation
        )
        ON DELETE CASCADE,
    CONSTRAINT fk_global_graph_sources_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id),
    CONSTRAINT ck_global_graph_sources_acl
        CHECK (acl_version >= 0),
    CONSTRAINT ck_global_graph_sources_title
        CHECK (btrim(document_title) <> '')
);

CREATE INDEX ix_global_graph_sources_revision
    ON global_graph_sources (revision_id, global_generation);

CREATE TRIGGER reject_global_graph_source_mutation
    BEFORE UPDATE OR DELETE ON global_graph_sources
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TABLE global_community_reports (
    id UUID PRIMARY KEY,
    global_generation BIGINT NOT NULL,
    community_key INTEGER NOT NULL,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    search_text TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    token_count INTEGER NOT NULL,
    expected_evidence_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_global_community_reports_manifest
        FOREIGN KEY (global_generation)
        REFERENCES global_graph_manifests (global_generation)
        ON DELETE CASCADE,
    CONSTRAINT uq_global_community_reports_identity
        UNIQUE (id, global_generation),
    CONSTRAINT uq_global_community_reports_key
        UNIQUE (global_generation, community_key),
    CONSTRAINT ck_global_community_reports_text
        CHECK (
            btrim(title) <> ''
            AND btrim(summary) <> ''
            AND btrim(search_text) <> ''
        ),
    CONSTRAINT ck_global_community_reports_shape
        CHECK (
            community_key >= 0
            AND length(content_hash) = 64
            AND token_count > 0
            AND expected_evidence_count > 0
        )
);

CREATE INDEX ix_global_community_reports_browse
    ON global_community_reports (global_generation, community_key);

CREATE TRIGGER reject_global_community_report_mutation
    BEFORE UPDATE OR DELETE ON global_community_reports
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TABLE global_report_claims (
    id UUID PRIMARY KEY,
    global_generation BIGINT NOT NULL,
    report_id UUID NOT NULL,
    claim_order INTEGER NOT NULL,
    claim_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_global_report_claims_report
        FOREIGN KEY (report_id, global_generation)
        REFERENCES global_community_reports (id, global_generation)
        ON DELETE CASCADE,
    CONSTRAINT uq_global_report_claims_identity
        UNIQUE (id, global_generation),
    CONSTRAINT uq_global_report_claims_order
        UNIQUE (global_generation, report_id, claim_order),
    CONSTRAINT ck_global_report_claims_order
        CHECK (claim_order >= 0),
    CONSTRAINT ck_global_report_claims_text
        CHECK (btrim(claim_text) <> '')
);

CREATE INDEX ix_global_report_claims_report
    ON global_report_claims (global_generation, report_id, claim_order);

CREATE TRIGGER reject_global_report_claim_mutation
    BEFORE UPDATE OR DELETE ON global_report_claims
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TABLE global_report_evidence (
    id UUID PRIMARY KEY,
    global_generation BIGINT NOT NULL,
    source_graph_generation BIGINT NOT NULL,
    report_id UUID NOT NULL,
    claim_id UUID NOT NULL,
    relationship_id UUID NOT NULL,
    relationship_evidence_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    child_chunk_id UUID NOT NULL,
    source_span_id UUID NOT NULL,
    acl_version BIGINT NOT NULL,
    evidence_text TEXT NOT NULL,
    evidence_text_hash VARCHAR(64) NOT NULL,
    start_page INTEGER NOT NULL,
    end_page INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_global_report_evidence_manifest
        FOREIGN KEY (global_generation, source_graph_generation)
        REFERENCES global_graph_manifests (
            global_generation, source_graph_generation
        )
        ON DELETE CASCADE,
    CONSTRAINT fk_global_report_evidence_report
        FOREIGN KEY (report_id, global_generation)
        REFERENCES global_community_reports (id, global_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_global_report_evidence_claim
        FOREIGN KEY (claim_id, global_generation)
        REFERENCES global_report_claims (id, global_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_global_report_evidence_relationship
        FOREIGN KEY (
            relationship_evidence_id,
            relationship_id,
            source_graph_generation
        )
        REFERENCES graph_relationship_evidence (
            id, relationship_id, graph_generation
        ),
    CONSTRAINT fk_global_report_evidence_span
        FOREIGN KEY (
            source_span_id, child_chunk_id, document_id, revision_id
        )
        REFERENCES source_spans (
            id, chunk_id, document_id, revision_id
        ),
    CONSTRAINT uq_global_report_evidence_anchor
        UNIQUE (
            global_generation, report_id, claim_id,
            relationship_evidence_id
        ),
    CONSTRAINT ck_global_report_evidence_acl
        CHECK (acl_version >= 0),
    CONSTRAINT ck_global_report_evidence_text
        CHECK (
            btrim(evidence_text) <> ''
            AND length(evidence_text_hash) = 64
        ),
    CONSTRAINT ck_global_report_evidence_pages
        CHECK (
            start_page >= 1
            AND end_page >= start_page
        )
);

CREATE INDEX ix_global_report_evidence_report
    ON global_report_evidence (
        global_generation, report_id, claim_id
    );

CREATE INDEX ix_global_report_evidence_document
    ON global_report_evidence (
        document_id, revision_id, global_generation
    );

CREATE INDEX ix_global_report_evidence_child
    ON global_report_evidence (child_chunk_id, global_generation);

CREATE INDEX ix_global_report_evidence_span
    ON global_report_evidence (source_span_id, global_generation);

CREATE TRIGGER reject_global_report_evidence_mutation
    BEFORE UPDATE OR DELETE ON global_report_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TABLE global_report_index_states (
    global_generation BIGINT PRIMARY KEY,
    index_name VARCHAR(255) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL,
    indexed_report_count BIGINT NOT NULL,
    valid_vector_count BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_global_report_index_states_manifest
        FOREIGN KEY (global_generation)
        REFERENCES global_graph_manifests (global_generation)
        ON DELETE CASCADE,
    CONSTRAINT ck_global_report_index_states_state
        CHECK (state IN ('READY', 'FAILED')),
    CONSTRAINT ck_global_report_index_states_counts
        CHECK (
            indexed_report_count >= 0
            AND valid_vector_count >= 0
            AND valid_vector_count <= indexed_report_count
        )
);

CREATE TABLE global_graph_publication_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    previous_global_generation BIGINT,
    global_generation BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_user_id UUID,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_global_graph_events_id_generation
        UNIQUE (id, global_generation),
    CONSTRAINT fk_global_graph_events_previous
        FOREIGN KEY (previous_global_generation)
        REFERENCES global_graph_manifests (global_generation),
    CONSTRAINT fk_global_graph_events_target
        FOREIGN KEY (global_generation)
        REFERENCES global_graph_manifests (global_generation),
    CONSTRAINT ck_global_graph_events_action
        CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_global_graph_events_reason
        CHECK (btrim(reason) <> '')
);

COMMENT ON COLUMN global_graph_publication_events.actor_user_id IS
    'Historical actor identifier intentionally has no users FK so publication audit survives account deletion';

CREATE INDEX ix_global_graph_events_target
    ON global_graph_publication_events (
        global_generation, created_at DESC
    );

CREATE TRIGGER reject_global_graph_event_mutation
    BEFORE UPDATE OR DELETE ON global_graph_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TABLE global_graph_publications (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    global_generation BIGINT NOT NULL UNIQUE,
    publication_event_id BIGINT NOT NULL UNIQUE,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_global_graph_publications_singleton
        CHECK (singleton_id = 1),
    CONSTRAINT fk_global_graph_publications_manifest
        FOREIGN KEY (global_generation)
        REFERENCES global_graph_manifests (global_generation),
    CONSTRAINT fk_global_graph_publications_event
        FOREIGN KEY (publication_event_id, global_generation)
        REFERENCES global_graph_publication_events (
            id, global_generation
        )
);

CREATE FUNCTION validate_global_graph_manifest_transition()
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
        RAISE EXCEPTION 'Invalid global graph manifest transition: % -> %',
            OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status IN ('READY', 'ACTIVE') THEN
        IF NEW.report_count = 0
           OR NEW.indexed_report_count <> NEW.report_count
           OR NEW.claim_count < NEW.report_count
           OR NEW.evidence_count < NEW.claim_count THEN
            RAISE EXCEPTION 'Global report generation is incomplete'
                USING ERRCODE = '23514';
        END IF;
        SELECT config.vector_dimensions
        INTO vector_dimensions
        FROM global_graph_configs global_config
        JOIN index_configs config
          ON config.version = global_config.index_config_version
        WHERE global_config.version = NEW.global_config_version;
        IF vector_dimensions IS NOT NULL
           AND NEW.valid_vector_count <> NEW.report_count THEN
            RAISE EXCEPTION 'Global report vector coverage must be complete'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_global_graph_manifest_transition
    BEFORE UPDATE ON global_graph_manifests
    FOR EACH ROW EXECUTE FUNCTION validate_global_graph_manifest_transition();

ALTER TABLE chat_runs
    DROP CONSTRAINT ck_chat_runs_graph_mode_requested,
    DROP CONSTRAINT ck_chat_runs_graph_mode_used,
    ADD COLUMN global_config_version VARCHAR(64),
    ADD COLUMN global_generation BIGINT,
    ADD COLUMN answer_strategy_requested VARCHAR(16) NOT NULL
        DEFAULT 'STANDARD',
    ADD COLUMN answer_strategy_used VARCHAR(16),
    ADD COLUMN map_call_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN reduce_call_count SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_chat_runs_global_config
        FOREIGN KEY (global_config_version)
        REFERENCES global_graph_configs (version),
    ADD CONSTRAINT fk_chat_runs_global_generation
        FOREIGN KEY (global_generation)
        REFERENCES global_graph_manifests (global_generation),
    ADD CONSTRAINT ck_chat_runs_graph_mode_requested
        CHECK (
            graph_mode_requested IS NULL
            OR graph_mode_requested IN (
                'AUTO', 'HYBRID', 'LOCAL_GRAPH', 'GLOBAL_GRAPH'
            )
        ),
    ADD CONSTRAINT ck_chat_runs_graph_mode_used
        CHECK (
            graph_mode_used IS NULL
            OR graph_mode_used IN (
                'HYBRID', 'LOCAL_GRAPH', 'GLOBAL_GRAPH'
            )
        ),
    ADD CONSTRAINT ck_chat_runs_answer_strategy
        CHECK (
            answer_strategy_requested IN ('STANDARD', 'DEEP_GLOBAL')
            AND (
                answer_strategy_used IS NULL
                OR answer_strategy_used IN ('STANDARD', 'DEEP_GLOBAL')
            )
        ),
    ADD CONSTRAINT ck_chat_runs_deep_global_mode
        CHECK (
            answer_strategy_requested <> 'DEEP_GLOBAL'
            OR graph_mode_requested = 'GLOBAL_GRAPH'
        ),
    ADD CONSTRAINT ck_chat_runs_answer_calls
        CHECK (
            map_call_count BETWEEN 0 AND 8
            AND reduce_call_count BETWEEN 0 AND 1
            AND (
                answer_strategy_used <> 'DEEP_GLOBAL'
                OR (
                    answer_strategy_requested = 'DEEP_GLOBAL'
                    AND map_call_count BETWEEN 1 AND 8
                    AND reduce_call_count = 1
                )
            )
        );
