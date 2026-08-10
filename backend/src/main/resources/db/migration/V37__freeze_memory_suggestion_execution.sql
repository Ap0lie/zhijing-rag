ALTER TABLE chat_runs
    ADD COLUMN memory_suggestion_snapshot_schema SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN memory_suggestion_extractor_version VARCHAR(64),
    ADD COLUMN memory_suggestion_prompt_version VARCHAR(64),
    ADD COLUMN memory_suggestion_provider_key VARCHAR(64),
    ADD COLUMN memory_suggestion_model_id VARCHAR(160),
    ADD COLUMN memory_suggestion_model_revision VARCHAR(160),
    ADD COLUMN memory_suggestion_endpoint_identity VARCHAR(255),
    ADD COLUMN memory_suggestion_prompt_hash CHAR(64);

ALTER TABLE chat_runs
    ADD CONSTRAINT ck_chat_run_memory_suggestion_snapshot CHECK (
        (
            memory_suggestion_snapshot_schema = 0
            AND memory_suggestion_extractor_version IS NULL
            AND memory_suggestion_prompt_version IS NULL
            AND memory_suggestion_provider_key IS NULL
            AND memory_suggestion_model_id IS NULL
            AND memory_suggestion_model_revision IS NULL
            AND memory_suggestion_endpoint_identity IS NULL
            AND memory_suggestion_prompt_hash IS NULL
        )
        OR
        (
            memory_suggestion_snapshot_schema = 1
            AND length(btrim(memory_suggestion_extractor_version))
                BETWEEN 1 AND 64
            AND length(btrim(memory_suggestion_prompt_version))
                BETWEEN 1 AND 64
            AND length(btrim(memory_suggestion_provider_key))
                BETWEEN 1 AND 64
            AND length(btrim(memory_suggestion_model_id))
                BETWEEN 1 AND 160
            AND length(btrim(memory_suggestion_model_revision))
                BETWEEN 1 AND 160
            AND length(btrim(memory_suggestion_endpoint_identity))
                BETWEEN 1 AND 255
            AND memory_suggestion_prompt_hash ~ '^[0-9a-f]{64}$'
        )
    );

ALTER TABLE memory_suggestion_jobs
    ADD COLUMN snapshot_schema_version SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN provider_key VARCHAR(64),
    ADD COLUMN model_id VARCHAR(160),
    ADD COLUMN model_revision VARCHAR(160),
    ADD COLUMN endpoint_identity VARCHAR(255),
    ADD COLUMN prompt_hash CHAR(64);

UPDATE memory_suggestion_jobs
SET status = 'FAILED',
    lease_owner = NULL,
    lease_expires_at = NULL,
    lease_token = NULL,
    error_code = 'MEMORY_SUGGESTION_LEGACY_SNAPSHOT_MISSING',
    error_detail = '旧任务未冻结模型与 Prompt 执行配置',
    completed_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE status IN ('PENDING', 'RUNNING');

ALTER TABLE memory_suggestion_jobs
    DROP CONSTRAINT uq_memory_suggestion_job_identity,
    ADD CONSTRAINT uq_memory_suggestion_job_run
        UNIQUE (run_id, owner_user_id),
    ADD CONSTRAINT ck_memory_suggestion_job_snapshot CHECK (
        (
            snapshot_schema_version = 0
            AND provider_key IS NULL
            AND model_id IS NULL
            AND model_revision IS NULL
            AND endpoint_identity IS NULL
            AND prompt_hash IS NULL
        )
        OR
        (
            snapshot_schema_version = 1
            AND length(btrim(provider_key)) BETWEEN 1 AND 64
            AND length(btrim(model_id)) BETWEEN 1 AND 160
            AND length(btrim(model_revision)) BETWEEN 1 AND 160
            AND length(btrim(endpoint_identity)) BETWEEN 1 AND 255
            AND prompt_hash ~ '^[0-9a-f]{64}$'
        )
    );

DROP INDEX ix_memory_suggestion_jobs_claim;
CREATE INDEX ix_memory_suggestion_jobs_claim
    ON memory_suggestion_jobs (available_at, created_at, id)
    WHERE status IN ('PENDING', 'RUNNING')
      AND snapshot_schema_version = 1;

DROP INDEX ix_memory_suggestion_jobs_owner_message;
CREATE INDEX ix_memory_suggestion_jobs_owner_message
    ON memory_suggestion_jobs (
        owner_user_id,
        source_message_id,
        created_at DESC,
        id DESC
    )
    INCLUDE (status, suggestion_count, error_code);

CREATE INDEX ix_citations_owner_run_order
    ON citations (owner_user_id, run_id, citation_order);
