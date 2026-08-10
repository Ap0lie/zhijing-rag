CREATE FUNCTION reject_immutable_evaluation_row()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TABLE evaluation_datasets (
    id UUID PRIMARY KEY,
    dataset_key VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_evaluation_datasets_key
        CHECK (dataset_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT ck_evaluation_datasets_text
        CHECK (btrim(title) <> '' AND btrim(description) <> '')
);

CREATE TABLE evaluation_dataset_versions (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL,
    version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    case_type VARCHAR(32) NOT NULL,
    source_revision VARCHAR(255) NOT NULL,
    source_license VARCHAR(64) NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    source_manifest JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_dataset_versions_dataset
        FOREIGN KEY (dataset_id) REFERENCES evaluation_datasets (id),
    CONSTRAINT uq_evaluation_dataset_versions
        UNIQUE (dataset_id, version),
    CONSTRAINT ck_evaluation_dataset_versions_version
        CHECK (
            version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
            AND schema_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
        ),
    CONSTRAINT ck_evaluation_dataset_versions_type
        CHECK (
            case_type IN (
                'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
                'ANSWER_CITATION', 'MULTI_TURN', 'INTENT'
            )
        ),
    CONSTRAINT ck_evaluation_dataset_versions_source
        CHECK (
            btrim(source_revision) <> ''
            AND btrim(source_license) <> ''
            AND source_sha256 ~ '^[0-9a-f]{64}$'
            AND jsonb_typeof(source_manifest) = 'object'
        )
);

CREATE INDEX ix_evaluation_dataset_versions_dataset
    ON evaluation_dataset_versions (dataset_id, created_at DESC, id);

CREATE TABLE evaluation_cases (
    id UUID PRIMARY KEY,
    dataset_version_id UUID NOT NULL,
    case_key VARCHAR(160) NOT NULL,
    language VARCHAR(16) NOT NULL,
    case_type VARCHAR(32) NOT NULL,
    input_data JSONB NOT NULL,
    expected_data JSONB NOT NULL,
    mapping_status VARCHAR(24) NOT NULL,
    mapping_requirements JSONB NOT NULL DEFAULT '[]'::JSONB,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_cases_version
        FOREIGN KEY (dataset_version_id)
        REFERENCES evaluation_dataset_versions (id),
    CONSTRAINT uq_evaluation_cases_key
        UNIQUE (dataset_version_id, case_key),
    CONSTRAINT ck_evaluation_cases_key
        CHECK (btrim(case_key) <> ''),
    CONSTRAINT ck_evaluation_cases_language
        CHECK (language ~ '^[A-Za-z][A-Za-z0-9_-]{0,15}$'),
    CONSTRAINT ck_evaluation_cases_type
        CHECK (
            case_type IN (
                'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
                'ANSWER_CITATION', 'MULTI_TURN', 'INTENT'
            )
        ),
    CONSTRAINT ck_evaluation_cases_mapping
        CHECK (
            mapping_status IN ('MAPPED', 'UNMAPPED', 'NOT_REQUIRED')
            AND jsonb_typeof(mapping_requirements) = 'array'
        ),
    CONSTRAINT ck_evaluation_cases_json
        CHECK (
            jsonb_typeof(input_data) = 'object'
            AND jsonb_typeof(expected_data) IN ('object', 'array')
            AND jsonb_typeof(metadata) = 'object'
        )
);

CREATE INDEX ix_evaluation_cases_version
    ON evaluation_cases (dataset_version_id, created_at, id);
CREATE INDEX ix_evaluation_cases_mapping
    ON evaluation_cases (dataset_version_id, mapping_status, id);

CREATE TABLE evaluation_subjects (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    snapshot JSONB NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    readiness_status VARCHAR(24) NOT NULL,
    blocked_reason VARCHAR(500),
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_evaluation_subjects_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_evaluation_subjects_type
        CHECK (
            subject_type IN (
                'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
                'ANSWER_CITATION'
            )
        ),
    CONSTRAINT ck_evaluation_subjects_snapshot
        CHECK (
            jsonb_typeof(snapshot) = 'object'
            AND snapshot_hash ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_evaluation_subjects_readiness
        CHECK (
            readiness_status IN ('READY', 'BLOCKED_PREREQUISITE')
            AND (
                (readiness_status = 'READY' AND blocked_reason IS NULL)
                OR
                (
                    readiness_status = 'BLOCKED_PREREQUISITE'
                    AND btrim(blocked_reason) <> ''
                )
            )
        )
);

CREATE INDEX ix_evaluation_subjects_created
    ON evaluation_subjects (created_at DESC, id DESC);

CREATE TABLE evaluation_runs (
    id UUID PRIMARY KEY,
    evaluation_subject_id UUID NOT NULL,
    dataset_version_id UUID NOT NULL,
    original_run_id UUID,
    status VARCHAR(32) NOT NULL,
    evaluator_version VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    requested_by UUID NOT NULL,
    total_cases INTEGER NOT NULL,
    completed_cases INTEGER NOT NULL DEFAULT 0,
    succeeded_cases INTEGER NOT NULL DEFAULT 0,
    failed_cases INTEGER NOT NULL DEFAULT 0,
    blocked_cases INTEGER NOT NULL DEFAULT 0,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    heartbeat_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_runs_subject
        FOREIGN KEY (evaluation_subject_id)
        REFERENCES evaluation_subjects (id),
    CONSTRAINT fk_evaluation_runs_version
        FOREIGN KEY (dataset_version_id)
        REFERENCES evaluation_dataset_versions (id),
    CONSTRAINT fk_evaluation_runs_original
        FOREIGN KEY (original_run_id) REFERENCES evaluation_runs (id),
    CONSTRAINT uq_evaluation_runs_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_evaluation_runs_status
        CHECK (
            status IN (
                'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED',
                'CANCELLED', 'BLOCKED_PREREQUISITE'
            )
        ),
    CONSTRAINT ck_evaluation_runs_evaluator
        CHECK (
            evaluator_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
            AND btrim(idempotency_key) <> ''
        ),
    CONSTRAINT ck_evaluation_runs_counts
        CHECK (
            total_cases >= 0
            AND completed_cases >= 0
            AND succeeded_cases >= 0
            AND failed_cases >= 0
            AND blocked_cases >= 0
            AND completed_cases =
                succeeded_cases + failed_cases + blocked_cases
            AND completed_cases <= total_cases
        ),
    CONSTRAINT ck_evaluation_runs_attempts
        CHECK (
            attempt >= 0
            AND attempt <= max_attempts
            AND max_attempts BETWEEN 1 AND 10
        ),
    CONSTRAINT ck_evaluation_runs_lease
        CHECK (
            (
                lease_owner IS NULL
                AND lease_expires_at IS NULL
                AND heartbeat_at IS NULL
            )
            OR
            (
                status = 'RUNNING'
                AND lease_owner IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND heartbeat_at IS NOT NULL
            )
        )
);

CREATE INDEX ix_evaluation_runs_subject
    ON evaluation_runs (evaluation_subject_id, created_at DESC, id DESC);
CREATE INDEX ix_evaluation_runs_version
    ON evaluation_runs (dataset_version_id, created_at DESC, id DESC);
CREATE INDEX ix_evaluation_runs_requested
    ON evaluation_runs (requested_by, created_at DESC, id DESC);
CREATE INDEX ix_evaluation_runs_claim
    ON evaluation_runs (created_at, id)
    WHERE status = 'PENDING';
CREATE INDEX ix_evaluation_runs_expired_lease
    ON evaluation_runs (lease_expires_at, id)
    WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL;

CREATE TABLE evaluation_run_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL,
    sequence INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_run_events_run
        FOREIGN KEY (run_id) REFERENCES evaluation_runs (id),
    CONSTRAINT uq_evaluation_run_events_sequence
        UNIQUE (run_id, sequence),
    CONSTRAINT ck_evaluation_run_events_type
        CHECK (
            event_type IN (
                'CREATED', 'CLAIMED', 'HEARTBEAT', 'CANCEL_REQUESTED',
                'CANCELLED', 'CASE_COMPLETED', 'SUCCEEDED', 'FAILED',
                'BLOCKED_PREREQUISITE', 'RETRIED', 'LEASE_RECOVERED'
            )
        ),
    CONSTRAINT ck_evaluation_run_events_details
        CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_evaluation_run_events_run
    ON evaluation_run_events (run_id, sequence);

CREATE TABLE evaluation_case_results (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    case_id UUID NOT NULL,
    evaluator_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    output_data JSONB NOT NULL,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_case_results_run
        FOREIGN KEY (run_id) REFERENCES evaluation_runs (id),
    CONSTRAINT fk_evaluation_case_results_case
        FOREIGN KEY (case_id) REFERENCES evaluation_cases (id),
    CONSTRAINT uq_evaluation_case_results_identity
        UNIQUE (run_id, case_id, evaluator_version),
    CONSTRAINT ck_evaluation_case_results_status
        CHECK (
            status IN ('SUCCEEDED', 'FAILED', 'BLOCKED_PREREQUISITE')
        ),
    CONSTRAINT ck_evaluation_case_results_output
        CHECK (
            jsonb_typeof(output_data) = 'object'
            AND duration_ms >= 0
        )
);

CREATE INDEX ix_evaluation_case_results_run
    ON evaluation_case_results (run_id, created_at, id);
CREATE INDEX ix_evaluation_case_results_case
    ON evaluation_case_results (case_id, created_at DESC);

CREATE TABLE evaluation_metric_results (
    id UUID PRIMARY KEY,
    case_result_id UUID NOT NULL,
    metric_key VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    metric_value NUMERIC,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_metric_results_case_result
        FOREIGN KEY (case_result_id)
        REFERENCES evaluation_case_results (id),
    CONSTRAINT uq_evaluation_metric_results_key
        UNIQUE (case_result_id, metric_key),
    CONSTRAINT ck_evaluation_metric_results_key
        CHECK (metric_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT ck_evaluation_metric_results_status
        CHECK (
            status IN ('MEASURED', 'NOT_MEASURED', 'BLOCKED_PREREQUISITE')
        ),
    CONSTRAINT ck_evaluation_metric_results_value
        CHECK (
            (status = 'MEASURED' AND metric_value IS NOT NULL)
            OR (status <> 'MEASURED' AND metric_value IS NULL)
        ),
    CONSTRAINT ck_evaluation_metric_results_details
        CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_evaluation_metric_results_case
    ON evaluation_metric_results (case_result_id, metric_key);

CREATE TRIGGER reject_evaluation_dataset_mutation
    BEFORE UPDATE OR DELETE ON evaluation_datasets
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_dataset_version_mutation
    BEFORE UPDATE OR DELETE ON evaluation_dataset_versions
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_case_mutation
    BEFORE UPDATE OR DELETE ON evaluation_cases
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_subject_mutation
    BEFORE UPDATE OR DELETE ON evaluation_subjects
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_run_event_mutation
    BEFORE UPDATE OR DELETE ON evaluation_run_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_case_result_mutation
    BEFORE UPDATE OR DELETE ON evaluation_case_results
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_metric_result_mutation
    BEFORE UPDATE OR DELETE ON evaluation_metric_results
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
