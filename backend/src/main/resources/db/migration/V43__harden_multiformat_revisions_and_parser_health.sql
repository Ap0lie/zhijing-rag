ALTER TABLE document_revisions
    ADD COLUMN format_change_from VARCHAR(16),
    ADD COLUMN format_change_reason VARCHAR(500),
    ADD COLUMN format_change_requested_by UUID,
    ADD CONSTRAINT fk_document_revisions_format_change_requested_by
        FOREIGN KEY (format_change_requested_by) REFERENCES users (id),
    ADD CONSTRAINT ck_document_revisions_format_change CHECK (
        (
            format_change_from IS NULL
            AND format_change_reason IS NULL
            AND format_change_requested_by IS NULL
        )
        OR (
            format_change_from IN (
                'PDF', 'TXT', 'MARKDOWN', 'HTML',
                'DOCX', 'PPTX', 'XLSX', 'CSV'
            )
            AND format_change_from <> document_format
            AND length(btrim(format_change_reason)) BETWEEN 8 AND 500
            AND format_change_requested_by IS NOT NULL
        )
    );

CREATE TABLE pipeline_worker_capabilities (
    worker_id VARCHAR(128) NOT NULL,
    parser_provider VARCHAR(32) NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (worker_id, parser_provider),
    CONSTRAINT ck_pipeline_worker_capabilities_provider CHECK (
        parser_provider IN (
            'PDFBOX', 'MINERU', 'TEXT', 'MARKDOWN', 'HTML',
            'DOCX_POI', 'PPTX_POI', 'XLSX_POI', 'CSV_STREAM'
        )
    )
);

CREATE INDEX ix_pipeline_worker_capabilities_health
    ON pipeline_worker_capabilities (parser_provider, last_seen_at DESC);

CREATE OR REPLACE FUNCTION check_source_locator_unit_bounds() RETURNS TRIGGER AS $$
DECLARE
    start_unit source_units%ROWTYPE;
    end_unit source_units%ROWTYPE;
BEGIN
    SELECT * INTO STRICT start_unit
      FROM source_units
     WHERE id = NEW.start_source_unit_id;
    SELECT * INTO STRICT end_unit
      FROM source_units
     WHERE id = NEW.end_source_unit_id;

    IF start_unit.document_id <> NEW.document_id
       OR end_unit.document_id <> NEW.document_id
       OR start_unit.revision_id <> NEW.revision_id
       OR end_unit.revision_id <> NEW.revision_id
       OR start_unit.normalization_version <> NEW.normalization_version
       OR end_unit.normalization_version <> NEW.normalization_version
       OR start_unit.unit_order > end_unit.unit_order
       OR (
           NEW.locator_kind = 'PAGE'
           AND (
               start_unit.unit_kind <> 'PAGE'
               OR end_unit.unit_kind <> 'PAGE'
           )
       )
       OR (
           NEW.locator_kind = 'LINE_RANGE'
           AND (
               start_unit.unit_kind <> 'LINE'
               OR end_unit.unit_kind <> 'LINE'
           )
       )
       OR (
           NEW.locator_kind = 'HEADING_BLOCK'
           AND (
               start_unit.unit_kind <> 'SECTION'
               OR end_unit.unit_kind <> 'SECTION'
           )
       )
       OR (
           NEW.locator_kind = 'DOM_PATH'
           AND (
               start_unit.unit_kind <> 'DOM_BLOCK'
               OR end_unit.unit_kind <> 'DOM_BLOCK'
           )
       )
       OR (
           NEW.locator_kind = 'PARAGRAPH'
           AND (
               start_unit.unit_kind <> 'PARAGRAPH'
               OR end_unit.unit_kind <> 'PARAGRAPH'
           )
       )
       OR (
           NEW.locator_kind = 'TABLE_CELL'
           AND (
               start_unit.unit_kind <> 'TABLE_CELL'
               OR end_unit.unit_kind <> 'TABLE_CELL'
           )
       )
       OR (
           NEW.locator_kind = 'SLIDE_SHAPE'
           AND (
               start_unit.unit_kind NOT IN ('SLIDE', 'SHAPE', 'NOTES')
               OR end_unit.unit_kind NOT IN ('SLIDE', 'SHAPE', 'NOTES')
           )
       )
       OR (
           NEW.locator_kind = 'CELL_RANGE'
           AND (
               start_unit.unit_kind <> 'SHEET'
               OR end_unit.unit_kind <> 'SHEET'
           )
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'source locator units are inconsistent';
    END IF;

    IF NEW.start_offset < 0
       OR NEW.end_offset < 0
       OR NEW.start_offset >
          rag_utf16_code_unit_length(start_unit.canonical_text)
       OR NEW.end_offset >
          rag_utf16_code_unit_length(end_unit.canonical_text)
       OR (
           NEW.start_source_unit_id = NEW.end_source_unit_id
           AND NEW.end_offset <= NEW.start_offset
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'source locator offsets exceed canonical SourceUnit bounds';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TABLE pipeline_worker_capabilities IS
    'Database heartbeats proving that a parser-worker process can currently execute each parser provider.';
COMMENT ON COLUMN document_revisions.format_change_reason IS
    'Administrator audit reason recorded only when this revision changes the current document format.';
