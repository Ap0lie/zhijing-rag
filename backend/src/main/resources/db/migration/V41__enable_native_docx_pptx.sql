ALTER TABLE document_revisions
    DROP CONSTRAINT ck_document_revisions_format,
    DROP CONSTRAINT ck_document_revisions_media_type,
    DROP CONSTRAINT ck_document_revisions_parser_provider;

ALTER TABLE document_revisions
    ADD CONSTRAINT ck_document_revisions_format CHECK (
        document_format IN ('PDF', 'TXT', 'MARKDOWN', 'HTML', 'DOCX', 'PPTX')
    ),
    ADD CONSTRAINT ck_document_revisions_media_type CHECK (
        (document_format = 'PDF' AND media_type = 'application/pdf')
        OR (document_format = 'TXT' AND media_type = 'text/plain')
        OR (
            document_format = 'MARKDOWN'
            AND media_type IN ('text/markdown', 'text/x-markdown', 'text/plain')
        )
        OR (
            document_format = 'HTML'
            AND media_type IN ('text/html', 'application/xhtml+xml')
        )
        OR (
            document_format = 'DOCX'
            AND media_type =
                'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        )
        OR (
            document_format = 'PPTX'
            AND media_type =
                'application/vnd.openxmlformats-officedocument.presentationml.presentation'
        )
    ),
    ADD CONSTRAINT ck_document_revisions_parser_provider CHECK (
        parser_provider IS NULL
        OR (document_format = 'PDF' AND parser_provider IN ('PDFBOX', 'MINERU'))
        OR (document_format = 'TXT' AND parser_provider = 'TEXT')
        OR (document_format = 'MARKDOWN' AND parser_provider = 'MARKDOWN')
        OR (document_format = 'HTML' AND parser_provider = 'HTML')
        OR (document_format = 'DOCX' AND parser_provider = 'DOCX_POI')
        OR (document_format = 'PPTX' AND parser_provider = 'PPTX_POI')
    );

ALTER TABLE pipeline_jobs
    DROP CONSTRAINT ck_pipeline_jobs_document_format,
    DROP CONSTRAINT ck_pipeline_jobs_parser_provider;

ALTER TABLE pipeline_jobs
    ADD CONSTRAINT ck_pipeline_jobs_document_format CHECK (
        document_format IN ('PDF', 'TXT', 'MARKDOWN', 'HTML', 'DOCX', 'PPTX')
    ),
    ADD CONSTRAINT ck_pipeline_jobs_parser_provider CHECK (
        parser_provider IS NULL
        OR (document_format = 'PDF' AND parser_provider IN ('PDFBOX', 'MINERU'))
        OR (document_format = 'TXT' AND parser_provider = 'TEXT')
        OR (document_format = 'MARKDOWN' AND parser_provider = 'MARKDOWN')
        OR (document_format = 'HTML' AND parser_provider = 'HTML')
        OR (document_format = 'DOCX' AND parser_provider = 'DOCX_POI')
        OR (document_format = 'PPTX' AND parser_provider = 'PPTX_POI')
    );

ALTER TABLE parsed_documents
    DROP CONSTRAINT ck_parsed_documents_document_format,
    DROP CONSTRAINT ck_parsed_documents_parser_provider;

ALTER TABLE parsed_documents
    ADD CONSTRAINT ck_parsed_documents_document_format CHECK (
        document_format IN ('PDF', 'TXT', 'MARKDOWN', 'HTML', 'DOCX', 'PPTX')
    ),
    ADD CONSTRAINT ck_parsed_documents_parser_provider CHECK (
        (document_format = 'PDF' AND parser_provider IN ('PDFBOX', 'MINERU'))
        OR (document_format = 'TXT' AND parser_provider = 'TEXT')
        OR (document_format = 'MARKDOWN' AND parser_provider = 'MARKDOWN')
        OR (document_format = 'HTML' AND parser_provider = 'HTML')
        OR (document_format = 'DOCX' AND parser_provider = 'DOCX_POI')
        OR (document_format = 'PPTX' AND parser_provider = 'PPTX_POI')
    );

ALTER TABLE source_units
    DROP CONSTRAINT ck_source_units_kind;

ALTER TABLE source_units
    ADD CONSTRAINT ck_source_units_kind CHECK (
        unit_kind IN (
            'PAGE', 'SECTION', 'LINE', 'DOM_BLOCK',
            'PARAGRAPH', 'TABLE_CELL', 'SLIDE', 'SHAPE', 'NOTES'
        )
    );

ALTER TABLE content_blocks
    DROP CONSTRAINT ck_content_blocks_locator_kind;

ALTER TABLE content_blocks
    ADD CONSTRAINT ck_content_blocks_locator_kind CHECK (
        locator_kind IN (
            'PAGE', 'LINE_RANGE', 'HEADING_BLOCK', 'DOM_PATH',
            'PARAGRAPH', 'TABLE_CELL', 'SLIDE_SHAPE'
        )
    );

ALTER TABLE source_spans
    DROP CONSTRAINT ck_source_spans_locator_kind;

ALTER TABLE source_spans
    ADD CONSTRAINT ck_source_spans_locator_kind CHECK (
        locator_kind IN (
            'PAGE', 'LINE_RANGE', 'HEADING_BLOCK', 'DOM_PATH',
            'PARAGRAPH', 'TABLE_CELL', 'SLIDE_SHAPE'
        )
    );

ALTER TABLE document_image_assets
    DROP CONSTRAINT ck_document_image_assets_locator_kind;

ALTER TABLE document_image_assets
    ADD CONSTRAINT ck_document_image_assets_locator_kind CHECK (
        locator_kind IN ('PAGE', 'PARAGRAPH', 'TABLE_CELL', 'SLIDE_SHAPE')
    );

ALTER TABLE document_tables
    DROP CONSTRAINT ck_document_tables_locator_kind;

ALTER TABLE document_tables
    ADD CONSTRAINT ck_document_tables_locator_kind CHECK (
        locator_kind IN ('PAGE', 'TABLE_CELL', 'SLIDE_SHAPE')
    );

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

COMMENT ON TABLE source_units IS
    'Format-neutral source units for PDF, safe text, DOCX and PPTX parsers.';
