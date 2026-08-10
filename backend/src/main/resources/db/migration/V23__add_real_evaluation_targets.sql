CREATE TABLE evaluation_targets (
    id UUID PRIMARY KEY,
    target_key VARCHAR(160) NOT NULL UNIQUE,
    subject_type VARCHAR(32) NOT NULL,
    target_kind VARCHAR(16) NOT NULL,
    snapshot JSONB NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    readiness_status VARCHAR(24) NOT NULL,
    blocked_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_evaluation_targets_key
        CHECK (btrim(target_key) <> ''),
    CONSTRAINT ck_evaluation_targets_type
        CHECK (
            subject_type IN (
                'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
                'ANSWER_CITATION'
            )
        ),
    CONSTRAINT ck_evaluation_targets_kind
        CHECK (target_kind IN ('ACTIVE', 'READY')),
    CONSTRAINT ck_evaluation_targets_snapshot
        CHECK (
            jsonb_typeof(snapshot) = 'object'
            AND snapshot_hash ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_evaluation_targets_readiness
        CHECK (
            (
                readiness_status = 'READY'
                AND blocked_reason IS NULL
            )
            OR
            (
                readiness_status = 'BLOCKED_PREREQUISITE'
                AND btrim(blocked_reason) <> ''
            )
        )
);

CREATE INDEX ix_evaluation_targets_current
    ON evaluation_targets (subject_type, target_kind, created_at DESC);

CREATE TRIGGER reject_evaluation_target_mutation
    BEFORE UPDATE OR DELETE ON evaluation_targets
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();

ALTER TABLE evaluation_subjects
    ADD COLUMN target_id UUID,
    ADD CONSTRAINT fk_evaluation_subjects_target
        FOREIGN KEY (target_id) REFERENCES evaluation_targets (id);

CREATE INDEX ix_evaluation_subjects_target
    ON evaluation_subjects (target_id)
    WHERE target_id IS NOT NULL;

ALTER TABLE chat_sessions
    ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'ONLINE',
    ADD COLUMN evaluation_run_id UUID,
    ADD COLUMN evaluation_case_id UUID,
    ADD COLUMN evaluation_drill_id UUID,
    ADD CONSTRAINT fk_chat_sessions_evaluation_run
        FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_runs (id),
    ADD CONSTRAINT fk_chat_sessions_evaluation_case
        FOREIGN KEY (evaluation_case_id) REFERENCES evaluation_cases (id),
    ADD CONSTRAINT fk_chat_sessions_evaluation_drill
        FOREIGN KEY (evaluation_drill_id) REFERENCES evaluation_drills (id),
    ADD CONSTRAINT ck_chat_sessions_purpose
        CHECK (purpose IN ('ONLINE', 'EVALUATION')),
    ADD CONSTRAINT ck_chat_sessions_evaluation_scope
        CHECK (
            (
                purpose = 'ONLINE'
                AND evaluation_run_id IS NULL
                AND evaluation_case_id IS NULL
                AND evaluation_drill_id IS NULL
            )
            OR
            (
                purpose = 'EVALUATION'
                AND evaluation_run_id IS NOT NULL
                AND evaluation_case_id IS NOT NULL
                AND evaluation_drill_id IS NULL
            )
            OR
            (
                purpose = 'EVALUATION'
                AND evaluation_run_id IS NULL
                AND evaluation_case_id IS NULL
                AND evaluation_drill_id IS NOT NULL
            )
        );

CREATE INDEX ix_chat_sessions_evaluation
    ON chat_sessions (evaluation_run_id, evaluation_case_id)
    WHERE purpose = 'EVALUATION';

CREATE INDEX ix_chat_sessions_evaluation_drill
    ON chat_sessions (evaluation_drill_id)
    WHERE evaluation_drill_id IS NOT NULL;

ALTER TABLE evaluation_drills
    ADD COLUMN execution_mode VARCHAR(24)
        NOT NULL DEFAULT 'SIMULATION_ONLY',
    ADD CONSTRAINT ck_evaluation_drills_execution_mode
        CHECK (execution_mode IN ('SIMULATION_ONLY', 'REAL_VERIFY'));
