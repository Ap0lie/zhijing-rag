CREATE TABLE graph_retrieval_profiles (
    version VARCHAR(64) PRIMARY KEY,
    seed_limit SMALLINT NOT NULL,
    max_hops SMALLINT NOT NULL,
    entity_limit SMALLINT NOT NULL,
    edge_limit SMALLINT NOT NULL,
    graph_child_limit SMALLINT NOT NULL,
    graph_weight NUMERIC(6, 3) NOT NULL,
    graph_context_token_budget SMALLINT NOT NULL,
    graph_context_percent SMALLINT NOT NULL,
    statement_timeout_ms INTEGER NOT NULL,
    reason TEXT NOT NULL,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_retrieval_profiles_creator
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_graph_retrieval_profiles_version
        CHECK (version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT ck_graph_retrieval_profiles_limits
        CHECK (
            seed_limit BETWEEN 1 AND 5
            AND max_hops BETWEEN 1 AND 2
            AND entity_limit BETWEEN seed_limit AND 20
            AND edge_limit BETWEEN 1 AND 40
            AND graph_child_limit BETWEEN 1 AND 30
        ),
    CONSTRAINT ck_graph_retrieval_profiles_weight
        CHECK (graph_weight > 0 AND graph_weight <= 4),
    CONSTRAINT ck_graph_retrieval_profiles_context
        CHECK (
            graph_context_token_budget BETWEEN 0 AND 900
            AND graph_context_percent BETWEEN 0 AND 15
        ),
    CONSTRAINT ck_graph_retrieval_profiles_timeout
        CHECK (statement_timeout_ms BETWEEN 50 AND 1000),
    CONSTRAINT ck_graph_retrieval_profiles_reason
        CHECK (btrim(reason) <> '')
);

CREATE TABLE graph_retrieval_publication_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type VARCHAR(16) NOT NULL,
    previous_profile_version VARCHAR(64),
    profile_version VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    actor_user_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_graph_retrieval_events_previous
        FOREIGN KEY (previous_profile_version)
        REFERENCES graph_retrieval_profiles (version),
    CONSTRAINT fk_graph_retrieval_events_profile
        FOREIGN KEY (profile_version)
        REFERENCES graph_retrieval_profiles (version),
    CONSTRAINT fk_graph_retrieval_events_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_graph_retrieval_events_type
        CHECK (event_type IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_graph_retrieval_events_reason
        CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_graph_retrieval_events_profile
    ON graph_retrieval_publication_events (
        profile_version, created_at DESC
    );

CREATE TABLE graph_retrieval_publications (
    singleton_id SMALLINT PRIMARY KEY,
    profile_version VARCHAR(64) NOT NULL,
    publication_event_id BIGINT NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_graph_retrieval_publications_singleton
        CHECK (singleton_id = 1),
    CONSTRAINT fk_graph_retrieval_publications_profile
        FOREIGN KEY (profile_version)
        REFERENCES graph_retrieval_profiles (version),
    CONSTRAINT fk_graph_retrieval_publications_event
        FOREIGN KEY (publication_event_id)
        REFERENCES graph_retrieval_publication_events (id)
);

CREATE TRIGGER reject_graph_retrieval_profile_mutation
    BEFORE UPDATE OR DELETE ON graph_retrieval_profiles
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

CREATE TRIGGER reject_graph_retrieval_event_mutation
    BEFORE UPDATE OR DELETE ON graph_retrieval_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_graph_row();

INSERT INTO graph_retrieval_profiles (
    version, seed_limit, max_hops, entity_limit, edge_limit,
    graph_child_limit, graph_weight, graph_context_token_budget,
    graph_context_percent, statement_timeout_ms, reason
) VALUES (
    'phase9-local-v1', 5, 2, 20, 40,
    30, 1.000, 900, 15, 500,
    'Phase 9 deterministic Local GraphRAG baseline'
);

WITH event AS (
    INSERT INTO graph_retrieval_publication_events (
        event_type, profile_version, reason
    ) VALUES (
        'PUBLISH', 'phase9-local-v1',
        'Phase 9 bootstrap publication'
    )
    RETURNING id
)
INSERT INTO graph_retrieval_publications (
    singleton_id, profile_version, publication_event_id
)
SELECT 1, 'phase9-local-v1', id
FROM event;

ALTER TABLE chat_runs
    ADD COLUMN graph_profile_version VARCHAR(64),
    ADD COLUMN graph_generation BIGINT,
    ADD COLUMN graph_mode_requested VARCHAR(16),
    ADD COLUMN graph_mode_used VARCHAR(16),
    ADD COLUMN graph_degraded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN graph_degradation_code VARCHAR(128),
    ADD CONSTRAINT fk_chat_runs_graph_profile
        FOREIGN KEY (graph_profile_version)
        REFERENCES graph_retrieval_profiles (version),
    ADD CONSTRAINT fk_chat_runs_graph_generation
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation),
    ADD CONSTRAINT ck_chat_runs_graph_mode_requested
        CHECK (
            graph_mode_requested IS NULL
            OR graph_mode_requested IN ('AUTO', 'HYBRID', 'LOCAL_GRAPH')
        ),
    ADD CONSTRAINT ck_chat_runs_graph_mode_used
        CHECK (
            graph_mode_used IS NULL
            OR graph_mode_used IN ('HYBRID', 'LOCAL_GRAPH')
        ),
    ADD CONSTRAINT ck_chat_runs_graph_degradation
        CHECK (
            (graph_degraded AND graph_degradation_code IS NOT NULL)
            OR (NOT graph_degraded AND graph_degradation_code IS NULL)
        );
