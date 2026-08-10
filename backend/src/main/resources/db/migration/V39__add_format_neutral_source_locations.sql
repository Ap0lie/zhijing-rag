CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE document_revisions
    ADD COLUMN document_format VARCHAR(16) NOT NULL DEFAULT 'PDF',
    ADD COLUMN parser_provider VARCHAR(32);

ALTER TABLE document_revisions
    DROP CONSTRAINT ck_document_revisions_media_type,
    ADD CONSTRAINT ck_document_revisions_format
        CHECK (document_format = 'PDF'),
    ADD CONSTRAINT ck_document_revisions_media_type
        CHECK (
            document_format = 'PDF'
            AND media_type = 'application/pdf'
        ),
    ADD CONSTRAINT ck_document_revisions_parser_provider
        CHECK (
            parser_provider IS NULL
            OR parser_provider IN ('PDFBOX', 'MINERU')
        );

ALTER TABLE pipeline_jobs
    RENAME COLUMN parser_selected_engine TO parser_provider;

ALTER TABLE pipeline_jobs
    RENAME COLUMN parser_engine_version TO parser_provider_version;

ALTER TABLE pipeline_jobs
    RENAME COLUMN parser_page_count TO parser_source_unit_count;

ALTER TABLE pipeline_jobs
    RENAME CONSTRAINT ck_pipeline_jobs_parser_selected
        TO ck_pipeline_jobs_parser_provider;

ALTER TABLE pipeline_jobs
    RENAME CONSTRAINT ck_pipeline_jobs_parser_page_count
        TO ck_pipeline_jobs_parser_source_unit_count;

ALTER TABLE pipeline_jobs
    ADD COLUMN document_format VARCHAR(16);

UPDATE pipeline_jobs job
SET document_format = revision.document_format
FROM document_revisions revision
WHERE revision.id = job.revision_id;

ALTER TABLE pipeline_jobs
    ALTER COLUMN document_format SET NOT NULL,
    ADD CONSTRAINT ck_pipeline_jobs_document_format
        CHECK (document_format = 'PDF');

UPDATE document_revisions revision
SET parser_provider = COALESCE(
    (
        SELECT job.parser_provider
        FROM pipeline_jobs job
        WHERE job.revision_id = revision.id
          AND job.stage = 'PARSE'
          AND job.parser_provider IS NOT NULL
        ORDER BY job.completed_at DESC NULLS LAST, job.created_at DESC, job.id
        LIMIT 1
    ),
    CASE
        WHEN revision.parser_version IS NULL THEN NULL
        WHEN lower(revision.parser_version) LIKE '%mineru%' THEN 'MINERU'
        ELSE 'PDFBOX'
    END
);

ALTER TABLE parsed_documents
    RENAME COLUMN page_count TO source_unit_count;

ALTER TABLE parsed_documents
    RENAME CONSTRAINT ck_parsed_documents_page_count
        TO ck_parsed_documents_source_unit_count;

ALTER TABLE parsed_documents
    ADD COLUMN document_format VARCHAR(16),
    ADD COLUMN parser_provider VARCHAR(32);

UPDATE parsed_documents parsed
SET document_format = revision.document_format,
    parser_provider = COALESCE(revision.parser_provider, 'PDFBOX')
FROM document_revisions revision
WHERE revision.id = parsed.revision_id;

ALTER TABLE parsed_documents
    ALTER COLUMN document_format SET NOT NULL,
    ALTER COLUMN parser_provider SET NOT NULL,
    ADD CONSTRAINT ck_parsed_documents_document_format
        CHECK (document_format = 'PDF'),
    ADD CONSTRAINT ck_parsed_documents_parser_provider
        CHECK (parser_provider IN ('PDFBOX', 'MINERU'));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM content_blocks
        WHERE start_page <> end_page
           OR end_offset - start_offset
              <> rag_utf16_code_unit_length(text)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'legacy content blocks cannot be losslessly mapped to PDF source units';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM content_blocks left_block
        JOIN content_blocks right_block
          ON right_block.revision_id = left_block.revision_id
         AND right_block.start_page = left_block.start_page
         AND right_block.id > left_block.id
         AND int4range(
                right_block.start_offset,
                right_block.end_offset,
                '[)'
             ) && int4range(
                left_block.start_offset,
                left_block.end_offset,
                '[)'
             )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'overlapping legacy content blocks cannot define one canonical page';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM source_spans
        WHERE start_page <> end_page
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'legacy cross-page source spans require explicit reconciliation';
    END IF;
END;
$$;

CREATE TABLE source_units (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    unit_order INTEGER NOT NULL,
    unit_kind VARCHAR(32) NOT NULL,
    stable_address VARCHAR(500) NOT NULL,
    canonical_text TEXT NOT NULL,
    canonical_text_hash VARCHAR(64) NOT NULL,
    normalization_version VARCHAR(64) NOT NULL,
    label_metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_source_units_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_source_units_anchor
        UNIQUE (
            id, document_id, revision_id,
            normalization_version
        ),
    CONSTRAINT uq_source_units_revision_order
        UNIQUE (revision_id, unit_order),
    CONSTRAINT uq_source_units_revision_address
        UNIQUE (revision_id, unit_kind, stable_address),
    CONSTRAINT ck_source_units_order
        CHECK (unit_order > 0),
    CONSTRAINT ck_source_units_kind
        CHECK (unit_kind = 'PAGE'),
    CONSTRAINT ck_source_units_address
        CHECK (btrim(stable_address) <> ''),
    CONSTRAINT ck_source_units_hash
        CHECK (canonical_text_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_source_units_normalization
        CHECK (btrim(normalization_version) <> ''),
    CONSTRAINT ck_source_units_label_metadata
        CHECK (jsonb_typeof(label_metadata) = 'object')
);

CREATE INDEX ix_source_units_document_revision
    ON source_units (document_id, revision_id, unit_order);

WITH ordered_blocks AS (
    SELECT
        block.document_id,
        block.revision_id,
        block.start_page AS page_number,
        block.block_order,
        block.start_offset,
        block.end_offset,
        block.text,
        lag(block.end_offset, 1, 0) OVER (
            PARTITION BY block.revision_id, block.start_page
            ORDER BY block.start_offset, block.block_order, block.id
        ) AS previous_end_offset
    FROM content_blocks block
),
reconstructed_pages AS (
    SELECT
        document_id,
        revision_id,
        page_number,
        string_agg(
            repeat(
                E'\n',
                GREATEST(start_offset - previous_end_offset, 0)
            ) || text,
            ''
            ORDER BY start_offset, block_order
        ) AS canonical_text
    FROM ordered_blocks
    GROUP BY document_id, revision_id, page_number
),
all_pages AS (
    SELECT
        revision.document_id,
        parsed.revision_id,
        generated.page_number,
        COALESCE(page.canonical_text, '') AS canonical_text
    FROM parsed_documents parsed
    JOIN document_revisions revision
      ON revision.id = parsed.revision_id
    CROSS JOIN LATERAL generate_series(
        1,
        parsed.source_unit_count
    ) AS generated(page_number)
    LEFT JOIN reconstructed_pages page
      ON page.revision_id = parsed.revision_id
     AND page.page_number = generated.page_number
)
INSERT INTO source_units (
    id,
    document_id,
    revision_id,
    unit_order,
    unit_kind,
    stable_address,
    canonical_text,
    canonical_text_hash,
    normalization_version,
    label_metadata
)
SELECT
    gen_random_uuid(),
    document_id,
    revision_id,
    page_number,
    'PAGE',
    'page:' || page_number,
    canonical_text,
    encode(
        digest(convert_to(canonical_text, 'UTF8'), 'sha256'),
        'hex'
    ),
    'legacy-page-offset-v1',
    jsonb_build_object(
        'pageNumber', page_number,
        'sourceLabel', '第 ' || page_number || ' 页'
    )
FROM all_pages;

CREATE OR REPLACE FUNCTION rag_utf16_code_unit_length(
    value TEXT
) RETURNS INTEGER AS $$
    SELECT (
        char_length(value)
        + regexp_count(value, '[𐀀-􏿿]')
    )::INTEGER;
$$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

CREATE FUNCTION rag_utf16_slice(
    value TEXT,
    start_offset INTEGER,
    end_offset INTEGER
) RETURNS TEXT AS $$
DECLARE
    position INTEGER;
    unit_cursor INTEGER := 0;
    unit_width INTEGER;
    character_value TEXT;
    result TEXT := '';
BEGIN
    IF start_offset < 0 OR end_offset < start_offset THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'invalid UTF-16 slice range';
    END IF;

    IF regexp_count(value, '[𐀀-􏿿]') = 0 THEN
        IF end_offset > char_length(value) THEN
            RAISE EXCEPTION USING
                ERRCODE = '22023',
                MESSAGE = 'UTF-16 slice exceeds source text';
        END IF;
        RETURN substr(
            value,
            start_offset + 1,
            end_offset - start_offset
        );
    END IF;

    FOR position IN 1..char_length(value) LOOP
        character_value := substr(value, position, 1);
        unit_width := CASE
            WHEN octet_length(character_value) = 4 THEN 2
            ELSE 1
        END;

        IF unit_cursor < start_offset
           AND unit_cursor + unit_width > start_offset THEN
            RAISE EXCEPTION USING
                ERRCODE = '22023',
                MESSAGE = 'UTF-16 slice starts inside a surrogate pair';
        END IF;
        IF unit_cursor < end_offset
           AND unit_cursor + unit_width > end_offset THEN
            RAISE EXCEPTION USING
                ERRCODE = '22023',
                MESSAGE = 'UTF-16 slice ends inside a surrogate pair';
        END IF;
        IF unit_cursor >= start_offset
           AND unit_cursor + unit_width <= end_offset THEN
            result := result || character_value;
        END IF;

        unit_cursor := unit_cursor + unit_width;
    END LOOP;

    IF end_offset > unit_cursor THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'UTF-16 slice exceeds source text';
    END IF;
    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE;

DO $$
BEGIN
    IF EXISTS (
        WITH unit_lengths AS MATERIALIZED (
            SELECT
                id,
                rag_utf16_code_unit_length(canonical_text) AS text_length
            FROM source_units
        )
        SELECT 1
        FROM content_blocks block
        JOIN source_units unit
          ON unit.revision_id = block.revision_id
         AND unit.unit_kind = 'PAGE'
         AND unit.unit_order = block.start_page
        JOIN unit_lengths unit_length
          ON unit_length.id = unit.id
        WHERE block.start_offset < 0
           OR block.end_offset > unit_length.text_length
           OR block.end_offset - block.start_offset
              <> rag_utf16_code_unit_length(block.text)
           OR rag_utf16_slice(
                  unit.canonical_text,
                  block.start_offset,
                  block.end_offset
              ) <> block.text
           OR encode(
                  digest(convert_to(block.text, 'UTF8'), 'sha256'),
                  'hex'
              ) <> block.source_text_hash
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'reconstructed PDF SourceUnit does not preserve ContentBlock text';
    END IF;

    IF EXISTS (
        WITH unit_lengths AS MATERIALIZED (
            SELECT
                id,
                rag_utf16_code_unit_length(canonical_text) AS text_length
            FROM source_units
        )
        SELECT 1
        FROM source_spans span
        JOIN source_units unit
          ON unit.revision_id = span.revision_id
         AND unit.unit_kind = 'PAGE'
         AND unit.unit_order = span.start_page
        JOIN chunks chunk
         ON chunk.id = span.chunk_id
         AND chunk.document_id = span.document_id
         AND chunk.revision_id = span.revision_id
        JOIN unit_lengths unit_length
          ON unit_length.id = unit.id
        WHERE span.start_offset < 0
           OR span.end_offset > unit_length.text_length
           OR NOT EXISTS (
               SELECT 1
               FROM content_blocks block
               WHERE block.revision_id = span.revision_id
                 AND block.start_page = span.start_page
                 AND block.start_offset <= span.start_offset
                 AND block.end_offset >= span.end_offset
                 AND rag_utf16_slice(
                         block.text,
                         span.start_offset - block.start_offset,
                         span.end_offset - block.start_offset
                     ) = rag_utf16_slice(
                         chunk.text,
                         span.chunk_start_offset,
                         span.chunk_end_offset
                     )
           )
           OR encode(
                  digest(
                      convert_to(
                          rag_utf16_slice(
                              chunk.text,
                              span.chunk_start_offset,
                              span.chunk_end_offset
                          ),
                          'UTF8'
                      ),
                      'sha256'
                  ),
                  'hex'
              ) <> span.source_text_hash
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'reconstructed PDF SourceUnit does not preserve SourceSpan text';
    END IF;
END;
$$;

ALTER TABLE content_blocks
    ADD COLUMN locator_kind VARCHAR(32),
    ADD COLUMN start_source_unit_id UUID,
    ADD COLUMN end_source_unit_id UUID,
    ADD COLUMN locator_address JSONB,
    ADD COLUMN normalization_version VARCHAR(64);

UPDATE content_blocks block
SET locator_kind = 'PAGE',
    start_source_unit_id = start_unit.id,
    end_source_unit_id = end_unit.id,
    locator_address = jsonb_build_object(
        'kind', 'PAGE',
        'startPage', block.start_page,
        'endPage', block.end_page
    ),
    normalization_version = start_unit.normalization_version
FROM source_units start_unit,
     source_units end_unit
WHERE start_unit.revision_id = block.revision_id
  AND start_unit.unit_kind = 'PAGE'
  AND start_unit.unit_order = block.start_page
  AND end_unit.revision_id = block.revision_id
  AND end_unit.unit_kind = 'PAGE'
  AND end_unit.unit_order = block.end_page;

ALTER TABLE source_spans
    ADD COLUMN locator_kind VARCHAR(32),
    ADD COLUMN start_source_unit_id UUID,
    ADD COLUMN end_source_unit_id UUID,
    ADD COLUMN locator_address JSONB,
    ADD COLUMN normalization_version VARCHAR(64);

UPDATE source_spans span
SET locator_kind = 'PAGE',
    start_source_unit_id = start_unit.id,
    end_source_unit_id = end_unit.id,
    locator_address = jsonb_build_object(
        'kind', 'PAGE',
        'startPage', span.start_page,
        'endPage', span.end_page
    ),
    normalization_version = start_unit.normalization_version
FROM source_units start_unit,
     source_units end_unit
WHERE start_unit.revision_id = span.revision_id
  AND start_unit.unit_kind = 'PAGE'
  AND start_unit.unit_order = span.start_page
  AND end_unit.revision_id = span.revision_id
  AND end_unit.unit_kind = 'PAGE'
  AND end_unit.unit_order = span.end_page;

ALTER TABLE document_image_assets
    ADD COLUMN locator_kind VARCHAR(32),
    ADD COLUMN source_unit_id UUID,
    ADD COLUMN locator_address JSONB,
    ADD COLUMN normalization_version VARCHAR(64);

UPDATE document_image_assets asset
SET locator_kind = 'PAGE',
    source_unit_id = unit.id,
    locator_address = jsonb_build_object(
        'kind', 'PAGE',
        'pageNumber', asset.page_number
    ),
    normalization_version = unit.normalization_version
FROM source_units unit
WHERE unit.revision_id = asset.revision_id
  AND unit.unit_kind = 'PAGE'
  AND unit.unit_order = asset.page_number;

ALTER TABLE document_tables
    ADD COLUMN locator_kind VARCHAR(32),
    ADD COLUMN source_unit_id UUID,
    ADD COLUMN locator_address JSONB,
    ADD COLUMN normalization_version VARCHAR(64);

UPDATE document_tables table_asset
SET locator_kind = 'PAGE',
    source_unit_id = unit.id,
    locator_address = jsonb_build_object(
        'kind', 'PAGE',
        'pageNumber', table_asset.page_number
    ),
    normalization_version = unit.normalization_version
FROM source_units unit
WHERE unit.revision_id = table_asset.revision_id
  AND unit.unit_kind = 'PAGE'
  AND unit.unit_order = table_asset.page_number;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM content_blocks
        WHERE locator_kind IS NULL
           OR start_source_unit_id IS NULL
           OR end_source_unit_id IS NULL
           OR locator_address IS NULL
           OR normalization_version IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM source_spans
        WHERE locator_kind IS NULL
           OR start_source_unit_id IS NULL
           OR end_source_unit_id IS NULL
           OR locator_address IS NULL
           OR normalization_version IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM document_image_assets
        WHERE locator_kind IS NULL
           OR source_unit_id IS NULL
           OR locator_address IS NULL
           OR normalization_version IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM document_tables
        WHERE locator_kind IS NULL
           OR source_unit_id IS NULL
           OR locator_address IS NULL
           OR normalization_version IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'legacy source locator backfill is incomplete';
    END IF;
END;
$$;

ALTER TABLE content_blocks
    ALTER COLUMN locator_kind SET NOT NULL,
    ALTER COLUMN start_source_unit_id SET NOT NULL,
    ALTER COLUMN end_source_unit_id SET NOT NULL,
    ALTER COLUMN locator_address SET NOT NULL,
    ALTER COLUMN normalization_version SET NOT NULL,
    ADD CONSTRAINT fk_content_blocks_start_source_unit
        FOREIGN KEY (
            start_source_unit_id,
            document_id,
            revision_id,
            normalization_version
        )
        REFERENCES source_units (
            id,
            document_id,
            revision_id,
            normalization_version
        ),
    ADD CONSTRAINT fk_content_blocks_end_source_unit
        FOREIGN KEY (
            end_source_unit_id,
            document_id,
            revision_id,
            normalization_version
        )
        REFERENCES source_units (
            id,
            document_id,
            revision_id,
            normalization_version
        ),
    ADD CONSTRAINT ck_content_blocks_locator_kind
        CHECK (locator_kind = 'PAGE'),
    ADD CONSTRAINT ck_content_blocks_locator_address
        CHECK (jsonb_typeof(locator_address) = 'object');

ALTER TABLE source_spans
    ALTER COLUMN locator_kind SET NOT NULL,
    ALTER COLUMN start_source_unit_id SET NOT NULL,
    ALTER COLUMN end_source_unit_id SET NOT NULL,
    ALTER COLUMN locator_address SET NOT NULL,
    ALTER COLUMN normalization_version SET NOT NULL,
    ADD CONSTRAINT fk_source_spans_start_source_unit
        FOREIGN KEY (
            start_source_unit_id,
            document_id,
            revision_id,
            normalization_version
        )
        REFERENCES source_units (
            id,
            document_id,
            revision_id,
            normalization_version
        ),
    ADD CONSTRAINT fk_source_spans_end_source_unit
        FOREIGN KEY (
            end_source_unit_id,
            document_id,
            revision_id,
            normalization_version
        )
        REFERENCES source_units (
            id,
            document_id,
            revision_id,
            normalization_version
        ),
    ADD CONSTRAINT ck_source_spans_locator_kind
        CHECK (locator_kind = 'PAGE'),
    ADD CONSTRAINT ck_source_spans_locator_address
        CHECK (jsonb_typeof(locator_address) = 'object');

ALTER TABLE document_image_assets
    ALTER COLUMN locator_kind SET NOT NULL,
    ALTER COLUMN source_unit_id SET NOT NULL,
    ALTER COLUMN locator_address SET NOT NULL,
    ALTER COLUMN normalization_version SET NOT NULL,
    ADD CONSTRAINT fk_document_image_assets_source_unit
        FOREIGN KEY (
            source_unit_id,
            document_id,
            revision_id,
            normalization_version
        )
        REFERENCES source_units (
            id,
            document_id,
            revision_id,
            normalization_version
        ),
    ADD CONSTRAINT ck_document_image_assets_locator_kind
        CHECK (locator_kind = 'PAGE'),
    ADD CONSTRAINT ck_document_image_assets_locator_address
        CHECK (jsonb_typeof(locator_address) = 'object');

ALTER TABLE document_tables
    ALTER COLUMN locator_kind SET NOT NULL,
    ALTER COLUMN source_unit_id SET NOT NULL,
    ALTER COLUMN locator_address SET NOT NULL,
    ALTER COLUMN normalization_version SET NOT NULL,
    ADD CONSTRAINT fk_document_tables_source_unit
        FOREIGN KEY (
            source_unit_id,
            document_id,
            revision_id,
            normalization_version
        )
        REFERENCES source_units (
            id,
            document_id,
            revision_id,
            normalization_version
        ),
    ADD CONSTRAINT ck_document_tables_locator_kind
        CHECK (locator_kind = 'PAGE'),
    ADD CONSTRAINT ck_document_tables_locator_address
        CHECK (jsonb_typeof(locator_address) = 'object');

CREATE INDEX ix_content_blocks_start_source_unit
    ON content_blocks (start_source_unit_id);
CREATE INDEX ix_content_blocks_end_source_unit
    ON content_blocks (end_source_unit_id);
CREATE INDEX ix_source_spans_start_source_unit
    ON source_spans (start_source_unit_id);
CREATE INDEX ix_source_spans_end_source_unit
    ON source_spans (end_source_unit_id);
CREATE INDEX ix_document_image_assets_source_unit
    ON document_image_assets (source_unit_id);
CREATE INDEX ix_document_tables_source_unit
    ON document_tables (source_unit_id);

CREATE FUNCTION check_source_locator_unit_bounds() RETURNS TRIGGER AS $$
DECLARE
    start_unit source_units%ROWTYPE;
    end_unit source_units%ROWTYPE;
BEGIN
    SELECT *
      INTO STRICT start_unit
      FROM source_units
     WHERE id = NEW.start_source_unit_id;
    SELECT *
      INTO STRICT end_unit
      FROM source_units
     WHERE id = NEW.end_source_unit_id;

    IF start_unit.document_id <> NEW.document_id
       OR end_unit.document_id <> NEW.document_id
       OR start_unit.revision_id <> NEW.revision_id
       OR end_unit.revision_id <> NEW.revision_id
       OR (
           NEW.locator_kind = 'PAGE'
           AND (
               start_unit.unit_kind <> 'PAGE'
               OR end_unit.unit_kind <> 'PAGE'
           )
       )
       OR start_unit.normalization_version <> NEW.normalization_version
       OR end_unit.normalization_version <> NEW.normalization_version
       OR start_unit.unit_order > end_unit.unit_order THEN
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

CREATE TRIGGER ck_content_blocks_source_locator_bounds
    BEFORE INSERT OR UPDATE OF
        document_id,
        revision_id,
        locator_kind,
        start_source_unit_id,
        end_source_unit_id,
        start_offset,
        end_offset,
        normalization_version
    ON content_blocks
    FOR EACH ROW
    EXECUTE FUNCTION check_source_locator_unit_bounds();

CREATE TRIGGER ck_source_spans_source_locator_bounds
    BEFORE INSERT OR UPDATE OF
        document_id,
        revision_id,
        locator_kind,
        start_source_unit_id,
        end_source_unit_id,
        start_offset,
        end_offset,
        normalization_version
    ON source_spans
    FOR EACH ROW
    EXECUTE FUNCTION check_source_locator_unit_bounds();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM (
            SELECT bounding_boxes_json
            FROM content_blocks
            WHERE bounding_boxes_json IS NOT NULL
            UNION ALL
            SELECT bounding_boxes_json
            FROM source_spans
            WHERE bounding_boxes_json IS NOT NULL
        ) owner
        WHERE jsonb_typeof(owner.bounding_boxes_json::JSONB) <> 'array'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'legacy bounding boxes must be JSON arrays';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (
            SELECT block.revision_id, box.value
            FROM content_blocks block
            CROSS JOIN LATERAL jsonb_array_elements(
                block.bounding_boxes_json::JSONB
            ) AS box(value)
            WHERE block.bounding_boxes_json IS NOT NULL

            UNION ALL

            SELECT span.revision_id, box.value
            FROM source_spans span
            CROSS JOIN LATERAL jsonb_array_elements(
                span.bounding_boxes_json::JSONB
            ) AS box(value)
            WHERE span.bounding_boxes_json IS NOT NULL
        ) box
        LEFT JOIN source_units unit
          ON unit.revision_id = box.revision_id
         AND unit.unit_kind = 'PAGE'
         AND unit.unit_order =
             (box.value ->> 'pageNumber')::INTEGER
        WHERE box.value ->> 'pageNumber' IS NULL
           OR unit.id IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'legacy bounding box cannot be mapped to a PDF SourceUnit';
    END IF;
END;
$$;

CREATE TEMPORARY TABLE v39_bounding_box_counts (
    source_kind TEXT NOT NULL,
    source_id UUID NOT NULL,
    box_count INTEGER NOT NULL,
    PRIMARY KEY (source_kind, source_id)
) ON COMMIT DROP;

INSERT INTO v39_bounding_box_counts (
    source_kind,
    source_id,
    box_count
)
SELECT
    'CONTENT_BLOCK',
    id,
    jsonb_array_length(bounding_boxes_json::JSONB)
FROM content_blocks
WHERE bounding_boxes_json IS NOT NULL
UNION ALL
SELECT
    'SOURCE_SPAN',
    id,
    jsonb_array_length(bounding_boxes_json::JSONB)
FROM source_spans
WHERE bounding_boxes_json IS NOT NULL;

UPDATE content_blocks owner
SET bounding_boxes_json = COALESCE(
    (
        SELECT jsonb_agg(
            (box.value - 'pageNumber')
            || jsonb_build_object(
                'sourceUnitId', unit.id,
                'sourceUnitOrder', unit.unit_order,
                'sourceUnitKind', unit.unit_kind,
                'stableAddress', unit.stable_address
            )
            ORDER BY box.ordinality
        )
        FROM jsonb_array_elements(owner.bounding_boxes_json::JSONB)
             WITH ORDINALITY AS box(value, ordinality)
        JOIN source_units unit
          ON unit.revision_id = owner.revision_id
         AND unit.unit_kind = 'PAGE'
         AND unit.unit_order =
             (box.value ->> 'pageNumber')::INTEGER
    ),
    '[]'::JSONB
)::TEXT
WHERE owner.bounding_boxes_json IS NOT NULL;

UPDATE source_spans owner
SET bounding_boxes_json = COALESCE(
    (
        SELECT jsonb_agg(
            (box.value - 'pageNumber')
            || jsonb_build_object(
                'sourceUnitId', unit.id,
                'sourceUnitOrder', unit.unit_order,
                'sourceUnitKind', unit.unit_kind,
                'stableAddress', unit.stable_address
            )
            ORDER BY box.ordinality
        )
        FROM jsonb_array_elements(owner.bounding_boxes_json::JSONB)
             WITH ORDINALITY AS box(value, ordinality)
        JOIN source_units unit
          ON unit.revision_id = owner.revision_id
         AND unit.unit_kind = 'PAGE'
         AND unit.unit_order =
             (box.value ->> 'pageNumber')::INTEGER
    ),
    '[]'::JSONB
)::TEXT
WHERE owner.bounding_boxes_json IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        WITH migrated AS (
            SELECT
                'CONTENT_BLOCK'::TEXT AS source_kind,
                block.id AS source_id,
                block.bounding_boxes_json::JSONB AS boxes
            FROM content_blocks block
            WHERE block.bounding_boxes_json IS NOT NULL

            UNION ALL

            SELECT
                'SOURCE_SPAN'::TEXT,
                span.id,
                span.bounding_boxes_json::JSONB
            FROM source_spans span
            WHERE span.bounding_boxes_json IS NOT NULL
        )
        SELECT 1
        FROM v39_bounding_box_counts expected
        LEFT JOIN migrated actual
          ON actual.source_kind = expected.source_kind
         AND actual.source_id = expected.source_id
        WHERE actual.source_id IS NULL
           OR jsonb_array_length(actual.boxes) <> expected.box_count
           OR EXISTS (
               SELECT 1
               FROM jsonb_array_elements(actual.boxes) box(value)
               WHERE box.value ? 'pageNumber'
                  OR box.value ->> 'sourceUnitId' IS NULL
                  OR box.value ->> 'sourceUnitOrder' IS NULL
                  OR (box.value ->> 'sourceUnitOrder')::INTEGER < 1
                  OR box.value ->> 'sourceUnitKind' <> 'PAGE'
                  OR nullif(box.value ->> 'stableAddress', '') IS NULL
           )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'bounding box SourceUnit migration did not preserve every coordinate';
    END IF;
END;
$$;

ALTER TABLE content_blocks
    DROP CONSTRAINT ck_content_blocks_pages,
    DROP CONSTRAINT ck_content_blocks_offsets,
    DROP COLUMN start_page,
    DROP COLUMN end_page,
    ADD CONSTRAINT ck_content_blocks_offsets
        CHECK (start_offset >= 0 AND end_offset >= 0);

ALTER TABLE source_spans
    DROP CONSTRAINT ck_source_spans_pages,
    DROP CONSTRAINT ck_source_spans_offsets,
    DROP COLUMN start_page,
    DROP COLUMN end_page,
    ADD CONSTRAINT ck_source_spans_offsets
        CHECK (start_offset >= 0 AND end_offset >= 0);

ALTER TABLE document_image_assets
    DROP CONSTRAINT ck_document_image_assets_page,
    DROP COLUMN page_number;

ALTER TABLE document_tables
    DROP CONSTRAINT ck_document_tables_page,
    DROP COLUMN page_number;

ALTER TABLE global_report_evidence
    DROP CONSTRAINT ck_global_report_evidence_pages,
    DROP COLUMN start_page,
    DROP COLUMN end_page;

INSERT INTO index_configs (
    version,
    schema_version,
    analyzer,
    embedding_model,
    embedding_revision,
    vector_dimensions,
    distance,
    hnsw_m,
    hnsw_ef_construction,
    created_at,
    embedding_provider_key,
    embedding_input_format_version,
    embedding_normalization_version
)
SELECT
    'phase15a-bm25-source-locator-v1',
    'source-locator-v1',
    baseline.analyzer,
    baseline.embedding_model,
    baseline.embedding_revision,
    baseline.vector_dimensions,
    baseline.distance,
    baseline.hnsw_m,
    baseline.hnsw_ef_construction,
    CURRENT_TIMESTAMP,
    baseline.embedding_provider_key,
    baseline.embedding_input_format_version,
    baseline.embedding_normalization_version
FROM index_configs baseline
WHERE baseline.version = 'phase5-bm25-v1';

INSERT INTO index_configs (
    version,
    schema_version,
    analyzer,
    embedding_model,
    embedding_revision,
    vector_dimensions,
    distance,
    hnsw_m,
    hnsw_ef_construction,
    created_at,
    embedding_provider_key,
    embedding_input_format_version,
    embedding_normalization_version
)
SELECT
    'phase15a-hybrid-qwen3-source-locator-v1',
    'source-locator-v1',
    baseline.analyzer,
    baseline.embedding_model,
    baseline.embedding_revision,
    baseline.vector_dimensions,
    baseline.distance,
    baseline.hnsw_m,
    baseline.hnsw_ef_construction,
    CURRENT_TIMESTAMP,
    baseline.embedding_provider_key,
    baseline.embedding_input_format_version,
    baseline.embedding_normalization_version
FROM index_configs baseline
WHERE baseline.version = 'phase6-hybrid-qwen3-v1';

DO $$
BEGIN
    IF (
        SELECT count(*)
        FROM index_configs
        WHERE version IN (
            'phase15a-bm25-source-locator-v1',
            'phase15a-hybrid-qwen3-source-locator-v1'
        )
          AND schema_version = 'source-locator-v1'
    ) <> 2 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'Phase 15A SourceLocator IndexConfig baselines are incomplete';
    END IF;
END;
$$;

CREATE VIEW source_locator_projection AS
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
    CASE
        WHEN locator.locator_kind = 'PAGE'
             AND start_unit.id = end_unit.id
            THEN COALESCE(
                start_unit.label_metadata ->> 'sourceLabel',
                '第 ' || start_unit.unit_order || ' 页'
            )
        WHEN locator.locator_kind = 'PAGE'
            THEN '第 ' || start_unit.unit_order
                 || '–' || end_unit.unit_order || ' 页'
        ELSE start_unit.stable_address
    END AS source_label
FROM locator_rows locator
JOIN document_revisions revision
  ON revision.id = locator.revision_id
 AND revision.document_id = locator.document_id
JOIN source_units start_unit
  ON start_unit.id = locator.start_source_unit_id
JOIN source_units end_unit
  ON end_unit.id = locator.end_source_unit_id;

COMMENT ON VIEW source_locator_projection IS
    'Read-only SourceLocator projection. Source facts remain in owner tables and source_units.';
