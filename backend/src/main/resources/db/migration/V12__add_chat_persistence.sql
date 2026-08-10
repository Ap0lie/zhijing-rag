CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_sessions_owner_identity UNIQUE (id, owner_user_id),
    CONSTRAINT fk_chat_sessions_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_chat_sessions_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_chat_sessions_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_chat_sessions_version CHECK (version >= 0)
);

CREATE INDEX ix_chat_sessions_owner_updated
    ON chat_sessions (owner_user_id, updated_at DESC, id);

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL DEFAULT '',
    language VARCHAR(16) NOT NULL DEFAULT 'und',
    status VARCHAR(16) NOT NULL,
    token_count INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_messages_owner_identity
        UNIQUE (id, session_id, owner_user_id),
    CONSTRAINT uq_chat_messages_sequence UNIQUE (session_id, sequence_number),
    CONSTRAINT fk_chat_messages_session
        FOREIGN KEY (session_id, owner_user_id)
        REFERENCES chat_sessions (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT ck_chat_messages_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_chat_messages_role
        CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_chat_messages_language CHECK (btrim(language) <> ''),
    CONSTRAINT ck_chat_messages_status
        CHECK (status IN ('PENDING', 'STREAMING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_chat_messages_tokens
        CHECK (token_count IS NULL OR token_count >= 0),
    CONSTRAINT ck_chat_messages_content CHECK (
        status IN ('PENDING', 'STREAMING', 'FAILED', 'CANCELLED')
        OR btrim(content) <> ''
    )
);

CREATE INDEX ix_chat_messages_owner_session_sequence
    ON chat_messages (owner_user_id, session_id, sequence_number);

CREATE TABLE chat_runs (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    request_message_id UUID NOT NULL,
    response_message_id UUID NOT NULL,
    orchestration_version VARCHAR(64) NOT NULL,
    standalone_query TEXT NOT NULL,
    sub_queries JSONB NOT NULL DEFAULT '[]'::jsonb,
    budget_usage JSONB NOT NULL DEFAULT '{}'::jsonb,
    fallback_path VARCHAR(64),
    retrieval_profile_version VARCHAR(64),
    index_generation BIGINT,
    final_evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    final_source_spans JSONB NOT NULL DEFAULT '[]'::jsonb,
    trim_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    trace_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    error_detail VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_runs_response_message UNIQUE (response_message_id),
    CONSTRAINT uq_chat_runs_trace UNIQUE (trace_id),
    CONSTRAINT uq_chat_runs_citation_identity
        UNIQUE (id, response_message_id, session_id, owner_user_id),
    CONSTRAINT fk_chat_runs_session
        FOREIGN KEY (session_id, owner_user_id)
        REFERENCES chat_sessions (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_runs_request_message
        FOREIGN KEY (request_message_id, session_id, owner_user_id)
        REFERENCES chat_messages (id, session_id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_runs_response_message
        FOREIGN KEY (response_message_id, session_id, owner_user_id)
        REFERENCES chat_messages (id, session_id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_runs_retrieval_profile
        FOREIGN KEY (retrieval_profile_version) REFERENCES retrieval_profiles (version),
    CONSTRAINT fk_chat_runs_index_generation
        FOREIGN KEY (index_generation) REFERENCES index_manifests (index_generation),
    CONSTRAINT ck_chat_runs_orchestration
        CHECK (btrim(orchestration_version) <> ''),
    CONSTRAINT ck_chat_runs_query CHECK (btrim(standalone_query) <> ''),
    CONSTRAINT ck_chat_runs_json_shapes CHECK (
        jsonb_typeof(sub_queries) = 'array'
        AND jsonb_typeof(budget_usage) = 'object'
        AND jsonb_typeof(final_evidence_ids) = 'array'
        AND jsonb_typeof(final_source_spans) = 'array'
        AND jsonb_typeof(trim_reasons) = 'array'
    ),
    CONSTRAINT ck_chat_runs_trace CHECK (btrim(trace_id) <> ''),
    CONSTRAINT ck_chat_runs_status CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'REFUSED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_chat_runs_error CHECK (
        (status = 'FAILED' AND error_code IS NOT NULL AND btrim(error_code) <> '')
        OR status <> 'FAILED'
    ),
    CONSTRAINT ck_chat_runs_timestamps CHECK (
        (status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (
            status IN ('COMPLETED', 'REFUSED', 'FAILED', 'CANCELLED')
            AND started_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_at >= started_at
        )
    )
);

CREATE UNIQUE INDEX uq_chat_runs_one_active_per_session
    ON chat_runs (session_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX ix_chat_runs_owner_session_created
    ON chat_runs (owner_user_id, session_id, created_at DESC, id);

CREATE INDEX ix_chat_runs_status
    ON chat_runs (status, updated_at, id);

CREATE FUNCTION validate_chat_run_message_roles()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM chat_messages message
        WHERE message.id = NEW.request_message_id
          AND message.role = 'USER'
    ) OR NOT EXISTS (
        SELECT 1
        FROM chat_messages message
        WHERE message.id = NEW.response_message_id
          AND message.role = 'ASSISTANT'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'chat run request/response roles must be USER/ASSISTANT';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER ck_chat_runs_message_roles
    AFTER INSERT OR UPDATE OF request_message_id, response_message_id
    ON chat_runs
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW
    EXECUTE FUNCTION validate_chat_run_message_roles();

ALTER TABLE source_spans
    ADD CONSTRAINT uq_source_spans_citation_identity
        UNIQUE (id, chunk_id, document_id, revision_id);

CREATE TABLE citations (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    run_id UUID NOT NULL,
    message_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    child_chunk_id UUID NOT NULL,
    source_span_id UUID NOT NULL,
    citation_order INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_citations_message_order UNIQUE (message_id, citation_order),
    CONSTRAINT uq_citations_message_span UNIQUE (message_id, source_span_id),
    CONSTRAINT fk_citations_run_response
        FOREIGN KEY (run_id, message_id, session_id, owner_user_id)
        REFERENCES chat_runs (id, response_message_id, session_id, owner_user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_citations_source_span
        FOREIGN KEY (source_span_id, child_chunk_id, document_id, revision_id)
        REFERENCES source_spans (id, chunk_id, document_id, revision_id),
    CONSTRAINT ck_citations_order CHECK (citation_order >= 0)
);

CREATE INDEX ix_citations_owner_identity
    ON citations (owner_user_id, id);

CREATE INDEX ix_citations_owner_message_order
    ON citations (owner_user_id, message_id, citation_order);

CREATE INDEX ix_citations_document_revision
    ON citations (document_id, revision_id);

CREATE FUNCTION validate_citation_child_chunk()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM chunks chunk
        WHERE chunk.id = NEW.child_chunk_id
          AND chunk.document_id = NEW.document_id
          AND chunk.revision_id = NEW.revision_id
          AND chunk.chunk_type = 'CHILD'
          AND chunk.searchable = TRUE
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'citation source must be a searchable CHILD chunk';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER ck_citations_child_chunk
    AFTER INSERT OR UPDATE OF child_chunk_id, document_id, revision_id
    ON citations
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW
    EXECUTE FUNCTION validate_citation_child_chunk();
