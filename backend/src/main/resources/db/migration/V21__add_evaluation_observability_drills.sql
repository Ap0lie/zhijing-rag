ALTER TABLE evaluation_run_events
    DROP CONSTRAINT ck_evaluation_run_events_type,
    ADD CONSTRAINT ck_evaluation_run_events_type
        CHECK (
            event_type IN (
                'CREATED', 'CLAIMED', 'HEARTBEAT', 'CANCEL_REQUESTED',
                'CANCELLED', 'CASE_COMPLETED', 'SUCCEEDED', 'FAILED',
                'BLOCKED_PREREQUISITE', 'RETRIED', 'LEASE_RECOVERED',
                'YIELDED_TO_CHAT'
            )
        );

CREATE TABLE evaluation_drills (
    id UUID PRIMARY KEY,
    original_drill_id UUID,
    drill_type VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    requested_by UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    heartbeat_at TIMESTAMP WITH TIME ZONE,
    result_summary JSONB NOT NULL DEFAULT '{}'::JSONB,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_drills_original
        FOREIGN KEY (original_drill_id) REFERENCES evaluation_drills (id),
    CONSTRAINT uq_evaluation_drills_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_evaluation_drills_type
        CHECK (
            drill_type IN (
                'MODEL_TIMEOUT', 'OPENSEARCH_UNAVAILABLE',
                'GRAPH_STALE', 'CANARY_LEAK_SCAN'
            )
        ),
    CONSTRAINT ck_evaluation_drills_status
        CHECK (
            status IN (
                'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'
            )
        ),
    CONSTRAINT ck_evaluation_drills_text
        CHECK (
            btrim(idempotency_key) <> ''
            AND btrim(reason) <> ''
        ),
    CONSTRAINT ck_evaluation_drills_attempt
        CHECK (
            attempt >= 0
            AND attempt <= max_attempts
            AND max_attempts BETWEEN 1 AND 10
        ),
    CONSTRAINT ck_evaluation_drills_lease
        CHECK (
            (
                status = 'RUNNING'
                AND lease_owner IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND heartbeat_at IS NOT NULL
            )
            OR
            (
                status <> 'RUNNING'
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL
                AND heartbeat_at IS NULL
            )
        ),
    CONSTRAINT ck_evaluation_drills_result
        CHECK (
            jsonb_typeof(result_summary) = 'object'
            AND (
                status NOT IN ('SUCCEEDED', 'FAILED')
                OR result_summary <> '{}'::JSONB
            )
        ),
    CONSTRAINT ck_evaluation_drills_error
        CHECK (
            (status = 'FAILED' AND error_code IS NOT NULL)
            OR status <> 'FAILED'
        ),
    CONSTRAINT ck_evaluation_drills_timestamps
        CHECK (
            (
                status = 'PENDING'
                AND started_at IS NULL
                AND completed_at IS NULL
            )
            OR
            (
                status = 'RUNNING'
                AND started_at IS NOT NULL
                AND completed_at IS NULL
            )
            OR
            (
                status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                AND completed_at IS NOT NULL
                AND (
                    started_at IS NULL
                    OR completed_at >= started_at
                )
            )
        )
);

COMMENT ON COLUMN evaluation_drills.requested_by IS
    'Historical administrator identifier intentionally has no users FK';

CREATE INDEX ix_evaluation_drills_claim
    ON evaluation_drills (created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX ix_evaluation_drills_expired_lease
    ON evaluation_drills (lease_expires_at, id)
    WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL;

CREATE INDEX ix_evaluation_drills_created
    ON evaluation_drills (created_at DESC, id DESC);

CREATE TABLE evaluation_drill_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    drill_id UUID NOT NULL,
    sequence INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_drill_events_drill
        FOREIGN KEY (drill_id) REFERENCES evaluation_drills (id),
    CONSTRAINT uq_evaluation_drill_events_sequence
        UNIQUE (drill_id, sequence),
    CONSTRAINT ck_evaluation_drill_events_type
        CHECK (
            event_type IN (
                'CREATED', 'CLAIMED', 'LEASE_RECOVERED',
                'CANCEL_REQUESTED', 'CANCELLED',
                'SUCCEEDED', 'FAILED', 'RETRIED'
            )
        ),
    CONSTRAINT ck_evaluation_drill_events_details
        CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_evaluation_drill_events_drill
    ON evaluation_drill_events (drill_id, sequence);
