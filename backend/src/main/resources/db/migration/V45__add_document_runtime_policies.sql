CREATE TABLE document_runtime_policies (
    policy_key VARCHAR(64) PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    document_format VARCHAR(16) NOT NULL,
    parser_provider VARCHAR(32),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    policy_version BIGINT NOT NULL DEFAULT 1,
    reason VARCHAR(500),
    changed_by UUID,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_document_runtime_policies_scope CHECK (
        (scope_type = 'FORMAT' AND parser_provider IS NULL)
        OR (scope_type = 'PARSER' AND parser_provider IS NOT NULL)
    ),
    CONSTRAINT ck_document_runtime_policies_format CHECK (
        document_format IN (
            'PDF', 'TXT', 'MARKDOWN', 'HTML',
            'DOCX', 'PPTX', 'XLSX', 'CSV'
        )
    ),
    CONSTRAINT ck_document_runtime_policies_provider CHECK (
        parser_provider IS NULL OR parser_provider IN (
            'PDFBOX', 'MINERU', 'TEXT', 'MARKDOWN', 'HTML',
            'DOCX_POI', 'PPTX_POI', 'XLSX_POI', 'CSV_STREAM'
        )
    ),
    CONSTRAINT ck_document_runtime_policies_status CHECK (
        status IN ('ENABLED', 'DISABLED')
        AND policy_version > 0
        AND (reason IS NULL OR length(btrim(reason)) BETWEEN 8 AND 500)
    )
);

CREATE UNIQUE INDEX uq_document_runtime_format_policy
    ON document_runtime_policies (document_format)
    WHERE scope_type = 'FORMAT';

CREATE UNIQUE INDEX uq_document_runtime_parser_policy
    ON document_runtime_policies (document_format, parser_provider)
    WHERE scope_type = 'PARSER';

CREATE INDEX ix_document_runtime_policies_changed_by
    ON document_runtime_policies (changed_by)
    WHERE changed_by IS NOT NULL;

CREATE TABLE document_runtime_policy_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_key VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    previous_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    policy_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by UUID NOT NULL,
    actor_username VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_runtime_policy_events_policy
        FOREIGN KEY (policy_key) REFERENCES document_runtime_policies (policy_key),
    CONSTRAINT uq_document_runtime_policy_events_version
        UNIQUE (policy_key, policy_version),
    CONSTRAINT ck_document_runtime_policy_events_action CHECK (
        action IN ('DISABLE', 'RESTORE')
        AND previous_status IN ('ENABLED', 'DISABLED')
        AND new_status IN ('ENABLED', 'DISABLED')
        AND previous_status <> new_status
        AND policy_version > 1
        AND length(btrim(reason)) BETWEEN 8 AND 500
    )
);

CREATE INDEX ix_document_runtime_policy_events_history
    ON document_runtime_policy_events (policy_key, created_at DESC, id DESC);
CREATE INDEX ix_document_runtime_policy_events_created_by
    ON document_runtime_policy_events (created_by, created_at DESC);

CREATE OR REPLACE FUNCTION reject_document_runtime_policy_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'document runtime policy events are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reject_document_runtime_policy_event_mutation
    BEFORE UPDATE OR DELETE ON document_runtime_policy_events
    FOR EACH ROW EXECUTE FUNCTION reject_document_runtime_policy_event_mutation();

INSERT INTO document_runtime_policies (
    policy_key, scope_type, document_format, parser_provider
) VALUES
    ('FORMAT:PDF', 'FORMAT', 'PDF', NULL),
    ('FORMAT:TXT', 'FORMAT', 'TXT', NULL),
    ('FORMAT:MARKDOWN', 'FORMAT', 'MARKDOWN', NULL),
    ('FORMAT:HTML', 'FORMAT', 'HTML', NULL),
    ('FORMAT:DOCX', 'FORMAT', 'DOCX', NULL),
    ('FORMAT:PPTX', 'FORMAT', 'PPTX', NULL),
    ('FORMAT:XLSX', 'FORMAT', 'XLSX', NULL),
    ('FORMAT:CSV', 'FORMAT', 'CSV', NULL),
    ('PARSER:PDF:PDFBOX', 'PARSER', 'PDF', 'PDFBOX'),
    ('PARSER:PDF:MINERU', 'PARSER', 'PDF', 'MINERU'),
    ('PARSER:TXT:TEXT', 'PARSER', 'TXT', 'TEXT'),
    ('PARSER:MARKDOWN:MARKDOWN', 'PARSER', 'MARKDOWN', 'MARKDOWN'),
    ('PARSER:HTML:HTML', 'PARSER', 'HTML', 'HTML'),
    ('PARSER:DOCX:DOCX_POI', 'PARSER', 'DOCX', 'DOCX_POI'),
    ('PARSER:PPTX:PPTX_POI', 'PARSER', 'PPTX', 'PPTX_POI'),
    ('PARSER:XLSX:XLSX_POI', 'PARSER', 'XLSX', 'XLSX_POI'),
    ('PARSER:CSV:CSV_STREAM', 'PARSER', 'CSV', 'CSV_STREAM');

COMMENT ON TABLE document_runtime_policies IS
    'Single mutable operational switch for each supported document format and parser provider.';
COMMENT ON TABLE document_runtime_policy_events IS
    'Append-only administrator audit history for document runtime policy changes.';
