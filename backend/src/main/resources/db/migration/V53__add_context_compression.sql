CREATE TABLE context_compression_policies (
    version VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    history_message_limit INTEGER NOT NULL,
    history_token_budget INTEGER NOT NULL,
    history_context_percent INTEGER NOT NULL,
    recent_message_limit INTEGER NOT NULL,
    summary_target_tokens INTEGER NOT NULL,
    summary_max_tokens INTEGER NOT NULL,
    summary_max_input_tokens INTEGER NOT NULL,
    max_chain_depth INTEGER NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    counter_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_context_compression_policy_values CHECK (
        history_message_limit BETWEEN 1 AND 12
        AND history_token_budget BETWEEN 64 AND 2048
        AND history_context_percent BETWEEN 1 AND 20
        AND recent_message_limit BETWEEN 1 AND 8
        AND summary_target_tokens BETWEEN 64 AND 512
        AND summary_max_tokens BETWEEN summary_target_tokens AND 512
        AND summary_max_input_tokens BETWEEN 512 AND 16384
        AND max_chain_depth BETWEEN 1 AND 8
        AND btrim(prompt_version) <> ''
        AND btrim(schema_version) <> ''
        AND btrim(counter_version) <> ''
    )
);

INSERT INTO context_compression_policies (
    version, enabled, history_message_limit, history_token_budget,
    history_context_percent, recent_message_limit, summary_target_tokens,
    summary_max_tokens, summary_max_input_tokens, max_chain_depth,
    prompt_version, schema_version, counter_version
) VALUES (
    'context-compression-v1', TRUE, 12, 2048, 20, 4, 384, 512, 4096, 8,
    'context-compression-prompt-v1', 'context-compression-schema-v1',
    'conservative-utf8-request-v2'
);

CREATE FUNCTION reject_context_compression_policy_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'context compression policies are immutable';
END;
$$;

CREATE TRIGGER reject_context_compression_policy_mutation
    BEFORE UPDATE OR DELETE ON context_compression_policies
    FOR EACH ROW EXECUTE FUNCTION reject_context_compression_policy_mutation();

CREATE TABLE chat_context_summaries (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    version_number INTEGER NOT NULL,
    parent_summary_id UUID,
    chain_depth INTEGER NOT NULL,
    covered_through_sequence INTEGER NOT NULL,
    summary_json JSONB NOT NULL,
    summary_token_count INTEGER NOT NULL,
    source_message_count INTEGER NOT NULL,
    source_token_count INTEGER NOT NULL,
    lineage_hash VARCHAR(64) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    model_revision VARCHAR(160) NOT NULL,
    endpoint_identity VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    counter_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_context_summary_owner_identity
        UNIQUE (id, owner_user_id, session_id),
    CONSTRAINT uq_chat_context_summary_version
        UNIQUE (owner_user_id, session_id, policy_version, version_number),
    CONSTRAINT uq_chat_context_summary_input
        UNIQUE (owner_user_id, session_id, policy_version, input_hash),
    CONSTRAINT fk_chat_context_summary_session
        FOREIGN KEY (session_id, owner_user_id)
        REFERENCES chat_sessions (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_context_summary_policy
        FOREIGN KEY (policy_version)
        REFERENCES context_compression_policies (version),
    CONSTRAINT fk_chat_context_summary_parent
        FOREIGN KEY (parent_summary_id, owner_user_id, session_id)
        REFERENCES chat_context_summaries (id, owner_user_id, session_id),
    CONSTRAINT ck_chat_context_summary_shape CHECK (
        version_number > 0
        AND chain_depth BETWEEN 1 AND 8
        AND covered_through_sequence > 0
        AND jsonb_typeof(summary_json) = 'object'
        AND summary_token_count BETWEEN 1 AND 512
        AND source_message_count > 0
        AND source_token_count > 0
        AND lineage_hash ~ '^[0-9a-f]{64}$'
        AND input_hash ~ '^[0-9a-f]{64}$'
        AND content_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_chat_context_summary_latest
    ON chat_context_summaries (
        owner_user_id, session_id, policy_version,
        covered_through_sequence DESC, version_number DESC
    );

CREATE FUNCTION reject_chat_context_summary_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'chat context summaries are immutable';
END;
$$;

CREATE TRIGGER reject_chat_context_summary_update
    BEFORE UPDATE ON chat_context_summaries
    FOR EACH ROW EXECUTE FUNCTION reject_chat_context_summary_update();

CREATE TABLE chat_context_summary_sources (
    summary_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    message_id UUID NOT NULL,
    source_order INTEGER NOT NULL,
    source_sequence INTEGER NOT NULL,
    source_role VARCHAR(16) NOT NULL,
    source_content_hash VARCHAR(64) NOT NULL,
    source_fact_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (summary_id, message_id),
    CONSTRAINT uq_chat_context_summary_source_order
        UNIQUE (summary_id, source_order),
    CONSTRAINT fk_chat_context_summary_source_summary
        FOREIGN KEY (summary_id, owner_user_id, session_id)
        REFERENCES chat_context_summaries (id, owner_user_id, session_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chat_context_summary_source_message
        FOREIGN KEY (message_id, session_id, owner_user_id)
        REFERENCES chat_messages (id, session_id, owner_user_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chat_context_summary_source CHECK (
        source_order > 0
        AND source_sequence > 0
        AND source_role IN ('USER', 'ASSISTANT')
        AND source_content_hash ~ '^[0-9a-f]{64}$'
        AND source_fact_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_chat_context_summary_sources_message
    ON chat_context_summary_sources (owner_user_id, message_id, summary_id);

CREATE TABLE chat_context_summary_jobs (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    parent_summary_id UUID,
    source_from_sequence INTEGER NOT NULL,
    source_through_sequence INTEGER NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    model_revision VARCHAR(160) NOT NULL,
    endpoint_identity VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    counter_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(128),
    lease_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    result_summary_id UUID,
    error_code VARCHAR(64),
    error_detail VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_chat_context_summary_job_input
        UNIQUE (owner_user_id, session_id, policy_version, input_hash),
    CONSTRAINT fk_chat_context_summary_job_session
        FOREIGN KEY (session_id, owner_user_id)
        REFERENCES chat_sessions (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_context_summary_job_policy
        FOREIGN KEY (policy_version)
        REFERENCES context_compression_policies (version),
    CONSTRAINT fk_chat_context_summary_job_parent
        FOREIGN KEY (parent_summary_id, owner_user_id, session_id)
        REFERENCES chat_context_summaries (id, owner_user_id, session_id),
    CONSTRAINT fk_chat_context_summary_job_result
        FOREIGN KEY (result_summary_id, owner_user_id, session_id)
        REFERENCES chat_context_summaries (id, owner_user_id, session_id),
    CONSTRAINT ck_chat_context_summary_job_range CHECK (
        source_from_sequence > 0
        AND source_through_sequence >= source_from_sequence
    ),
    CONSTRAINT ck_chat_context_summary_job_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_chat_context_summary_job_attempts CHECK (
        attempt_count >= 0 AND max_attempts BETWEEN 1 AND 10
    ),
    CONSTRAINT ck_chat_context_summary_job_input_hash
        CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chat_context_summary_job_lease CHECK (
        (status = 'RUNNING'
            AND lease_owner IS NOT NULL
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL)
        OR status <> 'RUNNING'
    )
);

CREATE INDEX ix_chat_context_summary_jobs_claim
    ON chat_context_summary_jobs (status, available_at, created_at, id);

CREATE UNIQUE INDEX uq_chat_context_summary_job_active_session
    ON chat_context_summary_jobs (owner_user_id, session_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE chat_context_summary_events (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    job_id UUID,
    summary_id UUID,
    event_type VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_context_summary_event_session
        FOREIGN KEY (session_id, owner_user_id)
        REFERENCES chat_sessions (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_context_summary_event_job
        FOREIGN KEY (job_id) REFERENCES chat_context_summary_jobs (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_chat_context_summary_event_summary
        FOREIGN KEY (summary_id) REFERENCES chat_context_summaries (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_chat_context_summary_event_details
        CHECK (jsonb_typeof(details) = 'object')
);

CREATE FUNCTION reject_chat_context_summary_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'chat context summary events are append-only';
END;
$$;

CREATE TRIGGER reject_chat_context_summary_event_mutation
    BEFORE UPDATE ON chat_context_summary_events
    FOR EACH ROW EXECUTE FUNCTION reject_chat_context_summary_event_mutation();

ALTER TABLE chat_runs
    ADD COLUMN context_compression_policy_version VARCHAR(64),
    ADD COLUMN history_summary_id UUID,
    ADD COLUMN history_summary_token_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN history_summary_source_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN context_compression_status VARCHAR(24),
    ADD COLUMN context_compression_reason_code VARCHAR(64),
    ADD CONSTRAINT fk_chat_runs_context_compression_policy
        FOREIGN KEY (context_compression_policy_version)
        REFERENCES context_compression_policies (version),
    ADD CONSTRAINT fk_chat_runs_history_summary
        FOREIGN KEY (history_summary_id, owner_user_id, session_id)
        REFERENCES chat_context_summaries (id, owner_user_id, session_id),
    ADD CONSTRAINT ck_chat_runs_context_compression CHECK (
        history_summary_token_count >= 0
        AND history_summary_token_count <= 512
        AND history_summary_source_count >= 0
        AND (
            context_compression_status IS NULL
            OR context_compression_status IN (
                'NOT_NEEDED', 'PENDING', 'USED', 'FALLBACK',
                'STALE', 'REMOTE_BLOCKED', 'FAILED'
            )
        )
    );

ALTER TABLE chat_runs
    DROP CONSTRAINT ck_chat_runs_history_snapshot,
    ADD CONSTRAINT ck_chat_runs_history_snapshot CHECK (
        (
            history_message_ids = '[]'::jsonb
            AND history_snapshot_hash IS NULL
            AND history_counter_version IS NULL
            AND history_token_count = 0
            AND history_trim_reasons = '[]'::jsonb
        )
        OR
        (
            (query_intelligence_profile_version IS NOT NULL
                OR context_compression_policy_version IS NOT NULL)
            AND history_snapshot_hash ~ '^[0-9a-f]{64}$'
            AND btrim(history_counter_version) <> ''
        )
    );

CREATE TABLE chat_run_context_usages (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    run_id UUID NOT NULL,
    stage VARCHAR(16) NOT NULL,
    call_index INTEGER NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    summary_id UUID,
    plan_hash VARCHAR(64) NOT NULL,
    counter_version VARCHAR(64) NOT NULL,
    input_token_cap INTEGER NOT NULL,
    input_token_count INTEGER NOT NULL,
    system_token_count INTEGER NOT NULL,
    question_token_count INTEGER NOT NULL,
    history_token_count INTEGER NOT NULL,
    memory_token_count INTEGER NOT NULL,
    evidence_token_count INTEGER NOT NULL,
    trim_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_run_context_usage_call
        UNIQUE (run_id, stage, call_index),
    CONSTRAINT fk_chat_run_context_usage_run
        FOREIGN KEY (run_id, owner_user_id)
        REFERENCES chat_runs (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_run_context_usage_session
        FOREIGN KEY (session_id, owner_user_id)
        REFERENCES chat_sessions (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_run_context_usage_policy
        FOREIGN KEY (policy_version)
        REFERENCES context_compression_policies (version),
    CONSTRAINT fk_chat_run_context_usage_summary
        FOREIGN KEY (summary_id, owner_user_id, session_id)
        REFERENCES chat_context_summaries (id, owner_user_id, session_id),
    CONSTRAINT ck_chat_run_context_usage_stage CHECK (
        stage IN ('REWRITE', 'ANSWER', 'MAP', 'REDUCE')
    ),
    CONSTRAINT ck_chat_run_context_usage_values CHECK (
        call_index >= 0
        AND input_token_cap > 0
        AND input_token_count >= 0
        AND input_token_count <= input_token_cap
        AND system_token_count >= 0
        AND question_token_count >= 0
        AND history_token_count >= 0
        AND memory_token_count >= 0
        AND evidence_token_count >= 0
        AND plan_hash ~ '^[0-9a-f]{64}$'
        AND jsonb_typeof(trim_reasons) = 'array'
    )
);

CREATE INDEX ix_chat_run_context_usage_owner_run
    ON chat_run_context_usages (owner_user_id, run_id, stage, call_index);
