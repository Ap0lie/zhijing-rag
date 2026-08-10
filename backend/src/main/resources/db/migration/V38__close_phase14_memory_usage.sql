ALTER TABLE chat_run_memory_usages
    ADD COLUMN token_limit INTEGER NOT NULL DEFAULT 512,
    ADD COLUMN token_counter_version VARCHAR(64) NOT NULL
        DEFAULT 'legacy-utf8-bytes-v1',
    ADD COLUMN token_count_exact BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_chat_run_memory_usage_token_limit
        CHECK (token_limit BETWEEN 0 AND 512),
    ADD CONSTRAINT ck_chat_run_memory_usage_counter_version
        CHECK (
            length(btrim(token_counter_version))
                BETWEEN 1 AND 64
        );
