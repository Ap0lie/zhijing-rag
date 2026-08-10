ALTER TABLE parsed_documents
    ADD COLUMN parser_revision VARCHAR(128),
    ADD COLUMN input_hash VARCHAR(64),
    ADD COLUMN output_hash VARCHAR(64),
    ADD COLUMN result_schema_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v1',
    ADD COLUMN result_manifest_json TEXT NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_parsed_documents_input_hash CHECK (
        input_hash IS NULL OR input_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_parsed_documents_output_hash CHECK (
        output_hash IS NULL OR output_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_parsed_documents_hash_pair CHECK (
        (input_hash IS NULL AND output_hash IS NULL)
        OR (input_hash IS NOT NULL AND output_hash IS NOT NULL)
    ),
    ADD CONSTRAINT ck_parsed_documents_result_schema CHECK (
        length(btrim(result_schema_version)) BETWEEN 1 AND 64
    );

ALTER TABLE content_blocks
    ADD COLUMN bounding_boxes_json TEXT;

ALTER TABLE source_spans
    ADD COLUMN chunk_start_offset INTEGER,
    ADD COLUMN chunk_end_offset INTEGER;

WITH calculated AS (
    SELECT
        span.id,
        COALESCE(
            SUM(GREATEST(0, span.end_offset - span.start_offset) + 2)
                OVER (
                    PARTITION BY span.chunk_id
                    ORDER BY span.span_order
                    ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                ),
            0
        )::INTEGER AS relative_start,
        GREATEST(0, span.end_offset - span.start_offset) AS relative_length
    FROM source_spans span
)
UPDATE source_spans span
SET chunk_start_offset = calculated.relative_start,
    chunk_end_offset = calculated.relative_start + calculated.relative_length
FROM calculated
WHERE calculated.id = span.id;

ALTER TABLE source_spans
    ALTER COLUMN chunk_start_offset SET NOT NULL,
    ALTER COLUMN chunk_end_offset SET NOT NULL,
    ADD CONSTRAINT ck_source_spans_chunk_offsets CHECK (
        chunk_start_offset >= 0 AND chunk_end_offset > chunk_start_offset
    );

CREATE TABLE document_image_assets (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    content_block_id UUID,
    asset_order INTEGER NOT NULL,
    asset_type VARCHAR(16) NOT NULL,
    page_number INTEGER NOT NULL,
    bbox_x0 INTEGER NOT NULL,
    bbox_y0 INTEGER NOT NULL,
    bbox_x1 INTEGER NOT NULL,
    bbox_y1 INTEGER NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(1000) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    caption TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_image_assets_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id) ON DELETE CASCADE,
    CONSTRAINT fk_document_image_assets_block
        FOREIGN KEY (content_block_id) REFERENCES content_blocks (id) ON DELETE SET NULL,
    CONSTRAINT uq_document_image_assets_revision_order UNIQUE (revision_id, asset_order),
    CONSTRAINT uq_document_image_assets_object_key UNIQUE (object_key),
    CONSTRAINT ck_document_image_assets_order CHECK (asset_order >= 0),
    CONSTRAINT ck_document_image_assets_type CHECK (asset_type IN ('FIGURE', 'TABLE_PREVIEW')),
    CONSTRAINT ck_document_image_assets_page CHECK (page_number > 0),
    CONSTRAINT ck_document_image_assets_bbox CHECK (
        bbox_x0 >= 0 AND bbox_y0 >= 0
        AND bbox_x1 <= 1000 AND bbox_y1 <= 1000
        AND bbox_x1 > bbox_x0 AND bbox_y1 > bbox_y0
    ),
    CONSTRAINT ck_document_image_assets_size CHECK (byte_size > 0),
    CONSTRAINT ck_document_image_assets_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_document_image_assets_revision
    ON document_image_assets (revision_id, asset_order);

CREATE TABLE document_tables (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    content_block_id UUID NOT NULL,
    preview_asset_id UUID,
    table_order INTEGER NOT NULL,
    page_number INTEGER NOT NULL,
    bbox_x0 INTEGER NOT NULL,
    bbox_y0 INTEGER NOT NULL,
    bbox_x1 INTEGER NOT NULL,
    bbox_y1 INTEGER NOT NULL,
    caption TEXT NOT NULL DEFAULT '',
    html TEXT NOT NULL,
    source_text_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_tables_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id) ON DELETE CASCADE,
    CONSTRAINT fk_document_tables_block
        FOREIGN KEY (content_block_id) REFERENCES content_blocks (id) ON DELETE CASCADE,
    CONSTRAINT fk_document_tables_preview
        FOREIGN KEY (preview_asset_id) REFERENCES document_image_assets (id) ON DELETE SET NULL,
    CONSTRAINT uq_document_tables_revision_order UNIQUE (revision_id, table_order),
    CONSTRAINT ck_document_tables_order CHECK (table_order >= 0),
    CONSTRAINT ck_document_tables_page CHECK (page_number > 0),
    CONSTRAINT ck_document_tables_bbox CHECK (
        bbox_x0 >= 0 AND bbox_y0 >= 0
        AND bbox_x1 <= 1000 AND bbox_y1 <= 1000
        AND bbox_x1 > bbox_x0 AND bbox_y1 > bbox_y0
    ),
    CONSTRAINT ck_document_tables_html CHECK (length(btrim(html)) > 0),
    CONSTRAINT ck_document_tables_hash CHECK (source_text_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_document_tables_revision
    ON document_tables (revision_id, table_order);

CREATE TABLE document_table_cells (
    id UUID PRIMARY KEY,
    table_id UUID NOT NULL,
    row_index INTEGER NOT NULL,
    column_index INTEGER NOT NULL,
    row_span INTEGER NOT NULL,
    column_span INTEGER NOT NULL,
    header BOOLEAN NOT NULL,
    text TEXT NOT NULL,
    source_text_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_table_cells_table
        FOREIGN KEY (table_id) REFERENCES document_tables (id) ON DELETE CASCADE,
    CONSTRAINT uq_document_table_cells_position UNIQUE (table_id, row_index, column_index),
    CONSTRAINT ck_document_table_cells_position CHECK (
        row_index >= 0 AND column_index >= 0
    ),
    CONSTRAINT ck_document_table_cells_span CHECK (
        row_span > 0 AND column_span > 0
    ),
    CONSTRAINT ck_document_table_cells_hash CHECK (source_text_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_document_table_cells_table
    ON document_table_cells (table_id, row_index, column_index);
