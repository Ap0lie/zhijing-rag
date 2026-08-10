ALTER TABLE evaluation_dataset_versions
    DROP CONSTRAINT ck_evaluation_dataset_versions_type,
    ADD CONSTRAINT ck_evaluation_dataset_versions_type CHECK (
        case_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER',
            'MULTIFORMAT_RELEASE'
        )
    );

ALTER TABLE evaluation_cases
    DROP CONSTRAINT ck_evaluation_cases_type,
    ADD CONSTRAINT ck_evaluation_cases_type CHECK (
        case_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER',
            'MULTIFORMAT_RELEASE'
        )
    ),
    DROP CONSTRAINT ck_evaluation_cases_mapping,
    ADD CONSTRAINT ck_evaluation_cases_mapping CHECK (
        mapping_status IN (
            'MAPPED', 'UNMAPPED', 'NOT_REQUIRED',
            'READY', 'BLOCKED_PREREQUISITE'
        )
        AND jsonb_typeof(mapping_requirements) = 'array'
    );

ALTER TABLE evaluation_targets
    DROP CONSTRAINT ck_evaluation_targets_type,
    ADD CONSTRAINT ck_evaluation_targets_type CHECK (
        subject_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER',
            'MULTIFORMAT_RELEASE'
        )
    );

ALTER TABLE evaluation_subjects
    DROP CONSTRAINT ck_evaluation_subjects_type,
    ADD COLUMN dataset_version_id UUID,
    ADD CONSTRAINT ck_evaluation_subjects_type CHECK (
        subject_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER',
            'MULTIFORMAT_RELEASE'
        )
    ),
    ADD CONSTRAINT fk_evaluation_subjects_dataset_version
        FOREIGN KEY (dataset_version_id)
        REFERENCES evaluation_dataset_versions (id),
    ADD CONSTRAINT ck_evaluation_subjects_dataset_scope CHECK (
        (
            subject_type = 'MULTIFORMAT_RELEASE'
            AND dataset_version_id IS NOT NULL
        )
        OR
        (
            subject_type <> 'MULTIFORMAT_RELEASE'
            AND dataset_version_id IS NULL
        )
    );

CREATE INDEX ix_evaluation_subjects_dataset_version
    ON evaluation_subjects (dataset_version_id, created_at DESC, id DESC)
    WHERE dataset_version_id IS NOT NULL;

CREATE TABLE evaluation_multiformat_case_facts (
    case_id UUID PRIMARY KEY,
    dataset_version_id UUID NOT NULL,
    document_format VARCHAR(16) NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    child_chunk_id UUID NOT NULL,
    source_span_id UUID NOT NULL,
    document_title VARCHAR(500) NOT NULL,
    document_visibility VARCHAR(16) NOT NULL,
    acl_version BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL,
    source_title VARCHAR(500) NOT NULL,
    source_license VARCHAR(64) NOT NULL,
    source_url TEXT,
    source_revision VARCHAR(255) NOT NULL,
    expected_parser_provider VARCHAR(32) NOT NULL,
    expected_parser_version VARCHAR(64) NOT NULL,
    expected_chunker_version VARCHAR(64) NOT NULL,
    locator_kind VARCHAR(32) NOT NULL,
    source_label VARCHAR(500) NOT NULL,
    source_text_hash VARCHAR(64) NOT NULL,
    locator JSONB NOT NULL,
    locator_hash VARCHAR(64) NOT NULL,
    security_assertions JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_multiformat_facts_case
        FOREIGN KEY (case_id, dataset_version_id)
        REFERENCES evaluation_cases (id, dataset_version_id),
    CONSTRAINT fk_multiformat_facts_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id),
    CONSTRAINT fk_multiformat_facts_child
        FOREIGN KEY (child_chunk_id, document_id, revision_id)
        REFERENCES chunks (id, document_id, revision_id),
    CONSTRAINT fk_multiformat_facts_span
        FOREIGN KEY (
            source_span_id, child_chunk_id, document_id, revision_id
        )
        REFERENCES source_spans (
            id, chunk_id, document_id, revision_id
        ),
    CONSTRAINT uq_multiformat_facts_version_format
        UNIQUE (dataset_version_id, document_format),
    CONSTRAINT ck_multiformat_facts_format CHECK (
        document_format IN (
            'PDF', 'TXT', 'MARKDOWN', 'HTML',
            'DOCX', 'PPTX', 'XLSX', 'CSV'
        )
    ),
    CONSTRAINT ck_multiformat_facts_visibility CHECK (
        document_visibility IN ('ALL_USERS', 'RESTRICTED')
        AND acl_version > 0
    ),
    CONSTRAINT ck_multiformat_facts_hashes CHECK (
        file_sha256 ~ '^[0-9a-f]{64}$'
        AND source_text_hash ~ '^[0-9a-f]{64}$'
        AND locator_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_multiformat_facts_text CHECK (
        btrim(document_title) <> ''
        AND btrim(original_filename) <> ''
        AND btrim(source_title) <> ''
        AND btrim(source_license) <> ''
        AND btrim(source_revision) <> ''
        AND btrim(expected_parser_provider) <> ''
        AND btrim(expected_parser_version) <> ''
        AND btrim(expected_chunker_version) <> ''
        AND btrim(source_label) <> ''
    ),
    CONSTRAINT ck_multiformat_facts_locator CHECK (
        locator_kind IN (
            'PAGE', 'LINE_RANGE', 'HEADING_BLOCK', 'DOM_PATH',
            'PARAGRAPH', 'TABLE_CELL', 'SLIDE_SHAPE', 'CELL_RANGE'
        )
        AND jsonb_typeof(locator) = 'object'
    ),
    CONSTRAINT ck_multiformat_facts_security CHECK (
        jsonb_typeof(security_assertions) = 'array'
        AND jsonb_array_length(security_assertions) > 0
    ),
    CONSTRAINT ck_multiformat_facts_source_url CHECK (
        source_url IS NULL
        OR (
            char_length(source_url) <= 2048
            AND source_url ~ '^https://[^[:space:]]+$'
        )
    )
);

CREATE INDEX ix_multiformat_facts_revision
    ON evaluation_multiformat_case_facts (document_id, revision_id);
CREATE INDEX ix_multiformat_facts_child
    ON evaluation_multiformat_case_facts (
        child_chunk_id, document_id, revision_id
    );
CREATE INDEX ix_multiformat_facts_span
    ON evaluation_multiformat_case_facts (
        source_span_id, child_chunk_id, document_id, revision_id
    );

CREATE TRIGGER reject_multiformat_case_fact_mutation
    BEFORE UPDATE OR DELETE ON evaluation_multiformat_case_facts
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();

COMMENT ON TABLE evaluation_multiformat_case_facts IS
    'Immutable Phase 18A file, Revision, Child and SourceLocator assertions.';
