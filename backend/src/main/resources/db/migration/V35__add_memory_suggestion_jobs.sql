CREATE TABLE memory_suggestion_jobs (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    run_id UUID NOT NULL,
    session_id UUID NOT NULL,
    source_message_id UUID NOT NULL,
    extractor_version VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    suggestion_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    error_detail VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_memory_suggestion_job_identity UNIQUE (
        owner_user_id,
        source_message_id,
        extractor_version,
        prompt_version,
        input_hash
    ),
    CONSTRAINT uq_memory_suggestion_job_owner UNIQUE (id, owner_user_id),
    CONSTRAINT fk_memory_suggestion_job_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_suggestion_job_run
        FOREIGN KEY (run_id, owner_user_id)
        REFERENCES chat_runs (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_suggestion_job_message
        FOREIGN KEY (source_message_id, session_id, owner_user_id)
        REFERENCES chat_messages (id, session_id, owner_user_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_memory_suggestion_job_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT ck_memory_suggestion_job_versions CHECK (
        length(btrim(extractor_version)) BETWEEN 1 AND 64
        AND length(btrim(prompt_version)) BETWEEN 1 AND 64
    ),
    CONSTRAINT ck_memory_suggestion_job_hash CHECK (
        input_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_memory_suggestion_job_attempts CHECK (
        attempt_count >= 0
        AND max_attempts BETWEEN 1 AND 10
        AND attempt_count <= max_attempts
    ),
    CONSTRAINT ck_memory_suggestion_job_count CHECK (
        suggestion_count BETWEEN 0 AND 10
    ),
    CONSTRAINT ck_memory_suggestion_job_lease CHECK (
        (
            status = 'RUNNING'
            AND lease_owner IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND started_at IS NOT NULL
        )
        OR
        (
            status <> 'RUNNING'
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL
        )
    ),
    CONSTRAINT ck_memory_suggestion_job_terminal CHECK (
        (
            status IN ('SUCCEEDED', 'FAILED', 'SKIPPED')
            AND completed_at IS NOT NULL
        )
        OR
        (
            status IN ('PENDING', 'RUNNING')
            AND completed_at IS NULL
        )
    )
);

CREATE INDEX ix_memory_suggestion_jobs_claim
    ON memory_suggestion_jobs (available_at, created_at, id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX ix_memory_suggestion_jobs_owner_message
    ON memory_suggestion_jobs (
        owner_user_id,
        source_message_id,
        created_at DESC
    );

CREATE TABLE memory_suggestion_outputs (
    job_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    position INTEGER NOT NULL,
    memory_item_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (job_id, position),
    CONSTRAINT uq_memory_suggestion_output_item UNIQUE (memory_item_id),
    CONSTRAINT fk_memory_suggestion_output_job
        FOREIGN KEY (job_id, owner_user_id)
        REFERENCES memory_suggestion_jobs (id, owner_user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_memory_suggestion_output_item
        FOREIGN KEY (memory_item_id, owner_user_id)
        REFERENCES memory_items (id, owner_user_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_memory_suggestion_output_position CHECK (
        position BETWEEN 1 AND 10
    )
);
