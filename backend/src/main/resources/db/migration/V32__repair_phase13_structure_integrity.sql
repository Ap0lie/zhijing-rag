ALTER TABLE document_revisions
    ADD CONSTRAINT fk_document_revisions_reparse_requested_by
        FOREIGN KEY (reparse_requested_by) REFERENCES users (id);

ALTER TABLE parsed_documents
    ADD COLUMN offset_encoding VARCHAR(32) NOT NULL DEFAULT 'UTF16_CODE_UNIT',
    ADD CONSTRAINT ck_parsed_documents_offset_encoding CHECK (
        offset_encoding = 'UTF16_CODE_UNIT'
    );

WITH ranked_active_parse AS (
    SELECT job.id,
           row_number() OVER (
               PARTITION BY job.revision_id
               ORDER BY
                   CASE
                       WHEN job.pipeline_version = 'phase4-v1' THEN 0
                       ELSE 1
                   END,
                   CASE job.status
                       WHEN 'RUNNING' THEN 0
                       ELSE 1
                   END,
                   job.created_at,
                   job.id
           ) AS active_rank
    FROM pipeline_jobs job
    WHERE job.stage = 'PARSE'
      AND job.status IN ('PENDING', 'RUNNING')
)
UPDATE pipeline_jobs job
SET status = 'FAILED',
    lease_owner = NULL,
    lease_expires_at = NULL,
    heartbeat_at = NULL,
    completed_at = CURRENT_TIMESTAMP,
    duration_ms = 0,
    error_code = 'MIGRATION_DUPLICATE_PARSE',
    error_message =
        'Superseded by the single active PARSE invariant in Flyway V32',
    updated_at = CURRENT_TIMESTAMP
FROM ranked_active_parse ranked
WHERE ranked.id = job.id
  AND ranked.active_rank > 1;

CREATE UNIQUE INDEX uq_pipeline_jobs_active_parse_revision
    ON pipeline_jobs (revision_id)
    WHERE stage = 'PARSE' AND status IN ('PENDING', 'RUNNING');

ALTER TABLE content_blocks
    ADD CONSTRAINT uq_content_blocks_document_revision_identity
        UNIQUE (id, document_id, revision_id);

ALTER TABLE document_image_assets
    ADD CONSTRAINT uq_document_image_assets_document_revision_identity
        UNIQUE (id, document_id, revision_id),
    DROP CONSTRAINT fk_document_image_assets_block,
    ADD CONSTRAINT fk_document_image_assets_block_revision
        FOREIGN KEY (content_block_id, document_id, revision_id)
        REFERENCES content_blocks (id, document_id, revision_id)
        ON DELETE SET NULL (content_block_id);

ALTER TABLE document_tables
    DROP CONSTRAINT fk_document_tables_block,
    DROP CONSTRAINT fk_document_tables_preview,
    ADD CONSTRAINT fk_document_tables_block_revision
        FOREIGN KEY (content_block_id, document_id, revision_id)
        REFERENCES content_blocks (id, document_id, revision_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_document_tables_preview_revision
        FOREIGN KEY (preview_asset_id, document_id, revision_id)
        REFERENCES document_image_assets (id, document_id, revision_id)
        ON DELETE SET NULL (preview_asset_id);

CREATE FUNCTION rag_utf16_code_unit_length(value TEXT) RETURNS INTEGER AS $$
    SELECT COALESCE(
        SUM(
            CASE
                WHEN octet_length(substr(value, position, 1)) = 4 THEN 2
                ELSE 1
            END
        ),
        0
    )::INTEGER
    FROM generate_series(1, char_length(value)) AS position;
$$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM source_spans span
        JOIN chunks chunk
          ON chunk.id = span.chunk_id
         AND chunk.document_id = span.document_id
         AND chunk.revision_id = span.revision_id
        WHERE span.chunk_end_offset > rag_utf16_code_unit_length(chunk.text)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'existing source span exceeds its chunk UTF-16 boundary';
    END IF;
END;
$$;

CREATE FUNCTION check_source_span_utf16_bounds() RETURNS TRIGGER AS $$
DECLARE
    referenced_chunk_text TEXT;
BEGIN
    SELECT chunk.text
      INTO referenced_chunk_text
      FROM chunks chunk
     WHERE chunk.id = NEW.chunk_id
       AND chunk.document_id = NEW.document_id
       AND chunk.revision_id = NEW.revision_id;

    IF FOUND
       AND NEW.chunk_end_offset > rag_utf16_code_unit_length(referenced_chunk_text) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'source span exceeds its chunk UTF-16 boundary';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ck_source_spans_utf16_bounds
    BEFORE INSERT OR UPDATE OF
        chunk_id,
        document_id,
        revision_id,
        chunk_start_offset,
        chunk_end_offset
    ON source_spans
    FOR EACH ROW
    EXECUTE FUNCTION check_source_span_utf16_bounds();

CREATE FUNCTION check_chunk_source_span_utf16_bounds() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM source_spans span
        WHERE span.chunk_id = NEW.id
          AND span.document_id = NEW.document_id
          AND span.revision_id = NEW.revision_id
          AND span.chunk_end_offset > rag_utf16_code_unit_length(NEW.text)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'chunk text update would invalidate a source span UTF-16 boundary';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ck_chunks_source_span_utf16_bounds
    AFTER UPDATE OF text
    ON chunks
    FOR EACH ROW
    EXECUTE FUNCTION check_chunk_source_span_utf16_bounds();
