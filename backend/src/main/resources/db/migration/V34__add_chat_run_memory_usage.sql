ALTER TABLE chat_runs
    ADD CONSTRAINT uq_chat_runs_owner_identity
        UNIQUE (id, owner_user_id);

CREATE TABLE chat_run_memory_usages (
    run_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    memory_item_id UUID NOT NULL,
    usage_order INTEGER NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    usage_status VARCHAR(32) NOT NULL,
    relevance_score DOUBLE PRECISION NOT NULL,
    token_count INTEGER NOT NULL,
    source_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_hash VARCHAR(64) NOT NULL,
    trim_reason VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, memory_item_id),
    CONSTRAINT uq_chat_run_memory_usage_order
        UNIQUE (run_id, usage_order),
    CONSTRAINT fk_chat_run_memory_usage_run
        FOREIGN KEY (run_id, owner_user_id)
        REFERENCES chat_runs (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_run_memory_usage_item
        FOREIGN KEY (memory_item_id, owner_user_id)
        REFERENCES memory_items (id, owner_user_id),
    CONSTRAINT ck_chat_run_memory_usage_order
        CHECK (usage_order BETWEEN 1 AND 20),
    CONSTRAINT ck_chat_run_memory_usage_type
        CHECK (memory_type IN (
            'USER_PREFERENCE',
            'USER_FACT',
            'SESSION_SUMMARY',
            'DOCUMENT_FACT'
        )),
    CONSTRAINT ck_chat_run_memory_usage_status
        CHECK (usage_status IN (
            'USED',
            'INJECTED',
            'DOCUMENT_EVIDENCE',
            'TRIMMED',
            'REMOTE_BLOCKED'
        )),
    CONSTRAINT ck_chat_run_memory_usage_score
        CHECK (
            relevance_score >= 0.0
            AND relevance_score <= 1.0
        ),
    CONSTRAINT ck_chat_run_memory_usage_tokens
        CHECK (token_count >= 0 AND token_count <= 4096),
    CONSTRAINT ck_chat_run_memory_usage_sources
        CHECK (jsonb_typeof(source_types) = 'array'),
    CONSTRAINT ck_chat_run_memory_usage_hash
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chat_run_memory_usage_trim
        CHECK (
            (usage_status IN ('TRIMMED', 'REMOTE_BLOCKED')
                AND trim_reason IS NOT NULL
                AND btrim(trim_reason) <> '')
            OR
            (usage_status NOT IN ('TRIMMED', 'REMOTE_BLOCKED')
                AND trim_reason IS NULL)
        )
);

CREATE INDEX ix_chat_run_memory_usages_owner_run
    ON chat_run_memory_usages (owner_user_id, run_id, usage_order);

CREATE INDEX ix_chat_run_memory_usages_item
    ON chat_run_memory_usages (memory_item_id, created_at DESC);
