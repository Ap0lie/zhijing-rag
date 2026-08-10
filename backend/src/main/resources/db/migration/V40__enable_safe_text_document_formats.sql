ALTER TABLE document_revisions
    DROP CONSTRAINT ck_document_revisions_format,
    DROP CONSTRAINT ck_document_revisions_media_type,
    DROP CONSTRAINT ck_document_revisions_parser_provider;

ALTER TABLE document_revisions
    ADD CONSTRAINT ck_document_revisions_format CHECK (
        document_format IN ('PDF', 'TXT', 'MARKDOWN', 'HTML')
    ),
    ADD CONSTRAINT ck_document_revisions_media_type CHECK (
        (document_format = 'PDF' AND media_type = 'application/pdf')
        OR (document_format = 'TXT' AND media_type = 'text/plain')
        OR (
            document_format = 'MARKDOWN'
            AND media_type IN (
                'text/markdown',
                'text/x-markdown',
                'text/plain'
            )
        )
        OR (
            document_format = 'HTML'
            AND media_type IN ('text/html', 'application/xhtml+xml')
        )
    ),
    ADD CONSTRAINT ck_document_revisions_parser_provider CHECK (
        parser_provider IS NULL
        OR (
            document_format = 'PDF'
            AND parser_provider IN ('PDFBOX', 'MINERU')
        )
        OR (document_format = 'TXT' AND parser_provider = 'TEXT')
        OR (
            document_format = 'MARKDOWN'
            AND parser_provider = 'MARKDOWN'
        )
        OR (document_format = 'HTML' AND parser_provider = 'HTML')
    );

ALTER TABLE pipeline_jobs
    DROP CONSTRAINT ck_pipeline_jobs_document_format,
    DROP CONSTRAINT ck_pipeline_jobs_parser_provider,
    DROP CONSTRAINT ck_pipeline_jobs_parser_override;

ALTER TABLE pipeline_jobs
    ADD CONSTRAINT ck_pipeline_jobs_document_format CHECK (
        document_format IN ('PDF', 'TXT', 'MARKDOWN', 'HTML')
    ),
    ADD CONSTRAINT ck_pipeline_jobs_parser_provider CHECK (
        parser_provider IS NULL
        OR (
            document_format = 'PDF'
            AND parser_provider IN ('PDFBOX', 'MINERU')
        )
        OR (document_format = 'TXT' AND parser_provider = 'TEXT')
        OR (
            document_format = 'MARKDOWN'
            AND parser_provider = 'MARKDOWN'
        )
        OR (document_format = 'HTML' AND parser_provider = 'HTML')
    ),
    ADD CONSTRAINT ck_pipeline_jobs_parser_override CHECK (
        (
            parser_override_key IS NULL
            AND parser_override_source_job_id IS NULL
            AND parser_override_reason IS NULL
            AND parser_override_by IS NULL
        )
        OR (
            stage = 'PARSE'
            AND document_format = 'PDF'
            AND parser_requested_engine IN ('PDFBOX', 'MINERU')
            AND parser_override_key IS NOT NULL
            AND parser_override_source_job_id IS NOT NULL
            AND length(btrim(parser_override_reason)) BETWEEN 8 AND 500
            AND parser_override_by IS NOT NULL
        )
    );

ALTER TABLE parsed_documents
    DROP CONSTRAINT ck_parsed_documents_document_format,
    DROP CONSTRAINT ck_parsed_documents_parser_provider;

ALTER TABLE parsed_documents
    ADD CONSTRAINT ck_parsed_documents_document_format CHECK (
        document_format IN ('PDF', 'TXT', 'MARKDOWN', 'HTML')
    ),
    ADD CONSTRAINT ck_parsed_documents_parser_provider CHECK (
        (document_format = 'PDF' AND parser_provider IN ('PDFBOX', 'MINERU'))
        OR (document_format = 'TXT' AND parser_provider = 'TEXT')
        OR (
            document_format = 'MARKDOWN'
            AND parser_provider = 'MARKDOWN'
        )
        OR (document_format = 'HTML' AND parser_provider = 'HTML')
    );

ALTER TABLE source_units
    DROP CONSTRAINT ck_source_units_kind;

ALTER TABLE source_units
    ADD CONSTRAINT ck_source_units_kind CHECK (
        unit_kind IN ('PAGE', 'SECTION', 'LINE', 'DOM_BLOCK')
    );

ALTER TABLE content_blocks
    DROP CONSTRAINT ck_content_blocks_locator_kind;

ALTER TABLE content_blocks
    ADD CONSTRAINT ck_content_blocks_locator_kind CHECK (
        locator_kind IN (
            'PAGE',
            'LINE_RANGE',
            'HEADING_BLOCK',
            'DOM_PATH'
        )
    );

ALTER TABLE source_spans
    DROP CONSTRAINT ck_source_spans_locator_kind;

ALTER TABLE source_spans
    ADD CONSTRAINT ck_source_spans_locator_kind CHECK (
        locator_kind IN (
            'PAGE',
            'LINE_RANGE',
            'HEADING_BLOCK',
            'DOM_PATH'
        )
    );

COMMENT ON TABLE source_units IS
    'Format-neutral source units. Phase 15B enables PAGE, SECTION, LINE and DOM_BLOCK.';

COMMENT ON COLUMN parsed_documents.result_manifest_json IS
    'Sealed parsed-package-v3 manifest including encoding and sanitization decisions for text formats.';

CREATE OR REPLACE VIEW source_locator_projection AS
WITH locator_rows AS (
    SELECT
        'CONTENT_BLOCK'::TEXT AS source_kind,
        block.id AS source_id,
        block.document_id,
        block.revision_id,
        block.locator_kind,
        block.start_source_unit_id,
        block.end_source_unit_id,
        block.start_offset,
        block.end_offset,
        block.locator_address AS address,
        block.source_text_hash,
        block.normalization_version
    FROM content_blocks block

    UNION ALL

    SELECT
        'SOURCE_SPAN'::TEXT,
        span.id,
        span.document_id,
        span.revision_id,
        span.locator_kind,
        span.start_source_unit_id,
        span.end_source_unit_id,
        span.start_offset,
        span.end_offset,
        span.locator_address,
        span.source_text_hash,
        span.normalization_version
    FROM source_spans span

    UNION ALL

    SELECT
        'IMAGE_ASSET'::TEXT,
        asset.id,
        asset.document_id,
        asset.revision_id,
        asset.locator_kind,
        asset.source_unit_id,
        asset.source_unit_id,
        NULL::INTEGER,
        NULL::INTEGER,
        asset.locator_address,
        NULL::VARCHAR(64),
        asset.normalization_version
    FROM document_image_assets asset

    UNION ALL

    SELECT
        'TABLE'::TEXT,
        table_asset.id,
        table_asset.document_id,
        table_asset.revision_id,
        table_asset.locator_kind,
        table_asset.source_unit_id,
        table_asset.source_unit_id,
        NULL::INTEGER,
        NULL::INTEGER,
        table_asset.locator_address,
        table_asset.source_text_hash,
        table_asset.normalization_version
    FROM document_tables table_asset
)
SELECT
    locator.source_kind,
    locator.source_id,
    locator.document_id,
    locator.revision_id,
    revision.document_format,
    locator.locator_kind,
    locator.start_source_unit_id,
    start_unit.unit_order AS start_unit_order,
    start_unit.unit_kind AS start_unit_kind,
    start_unit.stable_address AS start_unit_address,
    locator.end_source_unit_id,
    end_unit.unit_order AS end_unit_order,
    end_unit.unit_kind AS end_unit_kind,
    end_unit.stable_address AS end_unit_address,
    locator.start_offset,
    locator.end_offset,
    locator.address,
    locator.source_text_hash,
    locator.normalization_version,
    CASE
        WHEN locator.locator_kind = 'PAGE'
            THEN start_unit.unit_order
        ELSE NULL
    END AS start_page,
    CASE
        WHEN locator.locator_kind = 'PAGE'
            THEN end_unit.unit_order
        ELSE NULL
    END AS end_page,
    (CASE
        WHEN locator.locator_kind = 'PAGE'
             AND start_unit.id = end_unit.id
            THEN COALESCE(
                start_unit.label_metadata ->> 'sourceLabel',
                '第 ' || start_unit.unit_order || ' 页'
            )
        WHEN locator.locator_kind = 'PAGE'
            THEN '第 ' || start_unit.unit_order
                 || '–' || end_unit.unit_order || ' 页'
        ELSE COALESCE(
            start_unit.label_metadata ->> 'sourceLabel',
            start_unit.stable_address
        )
    END)::VARCHAR AS source_label
FROM locator_rows locator
JOIN document_revisions revision
  ON revision.id = locator.revision_id
 AND revision.document_id = locator.document_id
JOIN source_units start_unit
  ON start_unit.id = locator.start_source_unit_id
JOIN source_units end_unit
  ON end_unit.id = locator.end_source_unit_id;

COMMENT ON VIEW source_locator_projection IS
    'Read-only SourceLocator projection with format-aware labels. Source facts remain in owner tables and source_units.';
