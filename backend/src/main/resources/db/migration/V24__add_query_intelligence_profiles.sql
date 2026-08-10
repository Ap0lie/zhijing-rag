CREATE TABLE query_intelligence_profiles (
    version VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    planner_provider VARCHAR(64) NOT NULL,
    planner_model VARCHAR(160) NOT NULL,
    planner_revision VARCHAR(160) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    token_counter_type VARCHAR(32) NOT NULL,
    token_counter_version VARCHAR(64) NOT NULL,
    model_context_tokens INTEGER NOT NULL,
    history_message_limit INTEGER NOT NULL,
    history_token_budget INTEGER NOT NULL,
    history_context_percent INTEGER NOT NULL,
    max_sub_queries INTEGER NOT NULL,
    max_retrieval_rounds INTEGER NOT NULL,
    planner_call_limit INTEGER NOT NULL,
    timeout_ms INTEGER NOT NULL,
    fallback_mode VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_query_intelligence_profiles_creator
        FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_query_intelligence_profiles_version
        CHECK (version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT ck_query_intelligence_profiles_text
        CHECK (
            btrim(planner_provider) <> ''
            AND btrim(planner_model) <> ''
            AND btrim(planner_revision) <> ''
            AND btrim(prompt_version) <> ''
            AND btrim(schema_version) <> ''
            AND btrim(token_counter_version) <> ''
            AND btrim(reason) <> ''
        ),
    CONSTRAINT ck_query_intelligence_profiles_counter
        CHECK (
            token_counter_type IN (
                'CONSERVATIVE_UTF8',
                'MODEL_TOKENIZER'
            )
        ),
    CONSTRAINT ck_query_intelligence_profiles_budget
        CHECK (
            model_context_tokens BETWEEN 1024 AND 1048576
            AND history_message_limit BETWEEN 1 AND 12
            AND history_token_budget BETWEEN 64 AND 2048
            AND history_context_percent BETWEEN 1 AND 20
            AND max_sub_queries BETWEEN 1 AND 3
            AND max_retrieval_rounds BETWEEN 1 AND 2
            AND planner_call_limit BETWEEN 0 AND 4
            AND timeout_ms BETWEEN 100 AND 30000
        ),
    CONSTRAINT ck_query_intelligence_profiles_fallback
        CHECK (fallback_mode IN ('ORIGINAL_QUERY'))
);

CREATE INDEX ix_query_intelligence_profiles_created
    ON query_intelligence_profiles (created_at DESC, version);
CREATE INDEX ix_query_intelligence_profiles_creator
    ON query_intelligence_profiles (created_by, created_at DESC);

CREATE TABLE query_intelligence_profile_publication_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_version VARCHAR(64) NOT NULL,
    previous_profile_version VARCHAR(64),
    action VARCHAR(16) NOT NULL,
    actor UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_query_intelligence_events_profile
        FOREIGN KEY (profile_version)
        REFERENCES query_intelligence_profiles (version),
    CONSTRAINT fk_query_intelligence_events_previous
        FOREIGN KEY (previous_profile_version)
        REFERENCES query_intelligence_profiles (version),
    CONSTRAINT fk_query_intelligence_events_actor
        FOREIGN KEY (actor) REFERENCES users (id),
    CONSTRAINT uq_query_intelligence_events_identity
        UNIQUE (id, profile_version),
    CONSTRAINT ck_query_intelligence_events_action
        CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_query_intelligence_events_reason
        CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_query_intelligence_events_created
    ON query_intelligence_profile_publication_events (
        created_at DESC, id DESC
    );
CREATE INDEX ix_query_intelligence_events_profile
    ON query_intelligence_profile_publication_events (
        profile_version, created_at DESC, id DESC
    );

CREATE TABLE query_intelligence_profile_publications (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    profile_version VARCHAR(64) NOT NULL,
    publication_event_id BIGINT NOT NULL UNIQUE,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_query_intelligence_publications_singleton
        CHECK (singleton_id = 1),
    CONSTRAINT fk_query_intelligence_publications_profile
        FOREIGN KEY (profile_version)
        REFERENCES query_intelligence_profiles (version),
    CONSTRAINT fk_query_intelligence_publications_event
        FOREIGN KEY (publication_event_id, profile_version)
        REFERENCES query_intelligence_profile_publication_events (
            id, profile_version
        )
);

ALTER TABLE chat_runs
    ADD COLUMN query_intelligence_profile_version VARCHAR(64),
    ADD COLUMN history_message_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN history_snapshot_hash VARCHAR(64),
    ADD COLUMN history_counter_version VARCHAR(64),
    ADD COLUMN history_token_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN history_trim_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT fk_chat_runs_query_intelligence_profile
        FOREIGN KEY (query_intelligence_profile_version)
        REFERENCES query_intelligence_profiles (version),
    ADD CONSTRAINT ck_chat_runs_history_json
        CHECK (
            jsonb_typeof(history_message_ids) = 'array'
            AND jsonb_typeof(history_trim_reasons) = 'array'
            AND jsonb_array_length(history_message_ids) <= 12
        ),
    ADD CONSTRAINT ck_chat_runs_history_tokens
        CHECK (history_token_count >= 0 AND history_token_count <= 2048),
    ADD CONSTRAINT ck_chat_runs_history_snapshot
        CHECK (
            (
                query_intelligence_profile_version IS NULL
                AND history_message_ids = '[]'::jsonb
                AND history_snapshot_hash IS NULL
                AND history_counter_version IS NULL
                AND history_token_count = 0
                AND history_trim_reasons = '[]'::jsonb
            )
            OR
            (
                query_intelligence_profile_version IS NOT NULL
                AND history_snapshot_hash ~ '^[0-9a-f]{64}$'
                AND btrim(history_counter_version) <> ''
            )
        );

CREATE INDEX ix_chat_runs_query_intelligence_profile
    ON chat_runs (query_intelligence_profile_version, created_at DESC)
    WHERE query_intelligence_profile_version IS NOT NULL;

CREATE TRIGGER reject_query_intelligence_profile_mutation
    BEFORE UPDATE OR DELETE ON query_intelligence_profiles
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_query_intelligence_event_mutation
    BEFORE UPDATE OR DELETE ON query_intelligence_profile_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
