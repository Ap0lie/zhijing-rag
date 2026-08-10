ALTER TABLE document_revisions
    ADD COLUMN source_revision_id UUID,
    ADD COLUMN reparse_reason VARCHAR(500),
    ADD COLUMN reparse_requested_parser VARCHAR(16),
    ADD COLUMN reparse_requested_by UUID,
    ADD CONSTRAINT fk_document_revisions_reparse_source
        FOREIGN KEY (source_revision_id, document_id)
        REFERENCES document_revisions (id, document_id),
    ADD CONSTRAINT ck_document_revisions_reparse CHECK (
        (
            source_revision_id IS NULL
            AND reparse_reason IS NULL
            AND reparse_requested_parser IS NULL
            AND reparse_requested_by IS NULL
        )
        OR
        (
            source_revision_id IS NOT NULL
            AND length(btrim(reparse_reason)) BETWEEN 8 AND 500
            AND reparse_requested_parser IN ('AUTO', 'PDFBOX', 'MINERU')
            AND reparse_requested_by IS NOT NULL
        )
    );

CREATE INDEX ix_document_revisions_reparse_source
    ON document_revisions (source_revision_id, revision_number)
    WHERE source_revision_id IS NOT NULL;

ALTER TABLE pipeline_jobs
    DROP CONSTRAINT ck_pipeline_jobs_status,
    DROP CONSTRAINT ck_pipeline_jobs_completion,
    DROP CONSTRAINT ck_pipeline_jobs_failure_reason,
    ADD CONSTRAINT ck_pipeline_jobs_status CHECK (
        status IN (
            'PENDING', 'RUNNING', 'SUCCEEDED',
            'FAILED', 'QUARANTINED', 'CANCELLED'
        )
    ),
    ADD CONSTRAINT ck_pipeline_jobs_completion CHECK (
        (
            status IN ('SUCCEEDED', 'FAILED', 'QUARANTINED', 'CANCELLED')
            AND completed_at IS NOT NULL
            AND duration_ms IS NOT NULL
            AND duration_ms >= 0
        )
        OR
        (
            status IN ('PENDING', 'RUNNING')
            AND completed_at IS NULL
            AND duration_ms IS NULL
        )
    ),
    ADD CONSTRAINT ck_pipeline_jobs_failure_reason CHECK (
        status NOT IN ('FAILED', 'QUARANTINED', 'CANCELLED')
        OR error_code IS NOT NULL
    );

CREATE TABLE pipeline_job_action_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_user_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pipeline_job_action_events_job
        FOREIGN KEY (job_id) REFERENCES pipeline_jobs (id),
    CONSTRAINT ck_pipeline_job_action_events_action CHECK (
        action IN ('CANCEL', 'RELEASE_QUARANTINE')
    ),
    CONSTRAINT ck_pipeline_job_action_events_reason CHECK (
        length(btrim(reason)) BETWEEN 8 AND 500
    )
);

CREATE INDEX ix_pipeline_job_action_events_job
    ON pipeline_job_action_events (job_id, created_at DESC);

CREATE TABLE graph_rebuild_requests (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    target_revision_id UUID NOT NULL,
    target_acl_version BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL,
    source_graph_generation BIGINT,
    source_global_generation BIGINT,
    candidate_graph_generation BIGINT,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_graph_rebuild_requests_document
        FOREIGN KEY (document_id) REFERENCES documents (id),
    CONSTRAINT fk_graph_rebuild_requests_revision
        FOREIGN KEY (target_revision_id, document_id)
        REFERENCES document_revisions (id, document_id),
    CONSTRAINT fk_graph_rebuild_requests_source_graph
        FOREIGN KEY (source_graph_generation)
        REFERENCES graph_manifests (graph_generation),
    CONSTRAINT fk_graph_rebuild_requests_source_global
        FOREIGN KEY (source_global_generation)
        REFERENCES global_graph_manifests (global_generation),
    CONSTRAINT fk_graph_rebuild_requests_candidate
        FOREIGN KEY (candidate_graph_generation)
        REFERENCES graph_manifests (graph_generation),
    CONSTRAINT uq_graph_rebuild_requests_target
        UNIQUE (document_id, target_revision_id, target_acl_version),
    CONSTRAINT ck_graph_rebuild_requests_acl
        CHECK (target_acl_version > 0),
    CONSTRAINT ck_graph_rebuild_requests_reason
        CHECK (reason IN ('REVISION_PUBLISHED', 'ACL_CHANGED')),
    CONSTRAINT ck_graph_rebuild_requests_state
        CHECK (state IN ('REQUESTED', 'BUILDING', 'FULFILLED', 'SUPERSEDED')),
    CONSTRAINT ck_graph_rebuild_requests_lifecycle CHECK (
        (
            state IN ('REQUESTED', 'SUPERSEDED')
            AND candidate_graph_generation IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'BUILDING'
            AND candidate_graph_generation IS NOT NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'FULFILLED'
            AND candidate_graph_generation IS NOT NULL
            AND completed_at IS NOT NULL
        )
    )
);

CREATE INDEX ix_graph_rebuild_requests_state
    ON graph_rebuild_requests (state, requested_at, id);

ALTER TABLE evaluation_dataset_versions
    DROP CONSTRAINT ck_evaluation_dataset_versions_type,
    ADD CONSTRAINT ck_evaluation_dataset_versions_type CHECK (
        case_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER'
        )
    );

ALTER TABLE evaluation_cases
    DROP CONSTRAINT ck_evaluation_cases_type,
    ADD CONSTRAINT ck_evaluation_cases_type CHECK (
        case_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER'
        )
    );

ALTER TABLE evaluation_targets
    DROP CONSTRAINT ck_evaluation_targets_type,
    ADD CONSTRAINT ck_evaluation_targets_type CHECK (
        subject_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER'
        )
    );

ALTER TABLE evaluation_subjects
    DROP CONSTRAINT ck_evaluation_subjects_type,
    ADD CONSTRAINT ck_evaluation_subjects_type CHECK (
        subject_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER'
        )
    );
