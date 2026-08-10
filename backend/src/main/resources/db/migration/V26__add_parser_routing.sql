ALTER TABLE pipeline_jobs
    ADD COLUMN parser_requested_engine VARCHAR(16),
    ADD COLUMN parser_selected_engine VARCHAR(16),
    ADD COLUMN parser_decision_code VARCHAR(64),
    ADD COLUMN parser_engine_version VARCHAR(64),
    ADD COLUMN parser_page_count INTEGER,
    ADD COLUMN parser_scanned_candidate BOOLEAN,
    ADD COLUMN parser_ocr_required BOOLEAN,
    ADD COLUMN parser_multicolumn_candidate BOOLEAN,
    ADD COLUMN parser_table_candidate BOOLEAN,
    ADD COLUMN parser_image_candidate BOOLEAN,
    ADD COLUMN parser_model_revision VARCHAR(64),
    ADD COLUMN parser_model_manifest_checksum VARCHAR(64),
    ADD COLUMN parser_decided_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN parser_override_source_job_id UUID,
    ADD COLUMN parser_override_key VARCHAR(128),
    ADD COLUMN parser_override_reason VARCHAR(500),
    ADD COLUMN parser_override_by UUID;

UPDATE pipeline_jobs
SET parser_requested_engine = 'AUTO'
WHERE stage = 'PARSE';

ALTER TABLE pipeline_jobs
    DROP CONSTRAINT uq_pipeline_jobs_stage,
    ADD CONSTRAINT fk_pipeline_jobs_parser_override_source
        FOREIGN KEY (parser_override_source_job_id) REFERENCES pipeline_jobs (id),
    ADD CONSTRAINT fk_pipeline_jobs_parser_override_user
        FOREIGN KEY (parser_override_by) REFERENCES users (id),
    ADD CONSTRAINT ck_pipeline_jobs_parser_requested CHECK (
        (stage = 'PARSE' AND parser_requested_engine IN ('AUTO', 'PDFBOX', 'MINERU'))
        OR (stage <> 'PARSE' AND parser_requested_engine IS NULL)
    ),
    ADD CONSTRAINT ck_pipeline_jobs_parser_selected CHECK (
        parser_selected_engine IS NULL OR parser_selected_engine IN ('PDFBOX', 'MINERU')
    ),
    ADD CONSTRAINT ck_pipeline_jobs_parser_page_count CHECK (
        parser_page_count IS NULL OR parser_page_count > 0
    ),
    ADD CONSTRAINT ck_pipeline_jobs_parser_model_checksum CHECK (
        parser_model_manifest_checksum IS NULL
        OR parser_model_manifest_checksum ~ '^[0-9a-f]{64}$'
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
            AND parser_requested_engine IN ('PDFBOX', 'MINERU')
            AND parser_override_key IS NOT NULL
            AND parser_override_source_job_id IS NOT NULL
            AND length(btrim(parser_override_reason)) BETWEEN 8 AND 500
            AND parser_override_by IS NOT NULL
        )
    );

CREATE UNIQUE INDEX uq_pipeline_jobs_base_stage
    ON pipeline_jobs (revision_id, stage, pipeline_version)
    WHERE parser_override_key IS NULL;

CREATE UNIQUE INDEX uq_pipeline_jobs_parser_override
    ON pipeline_jobs (parser_override_by, parser_override_key)
    WHERE parser_override_key IS NOT NULL;

CREATE INDEX ix_pipeline_jobs_parser_decision
    ON pipeline_jobs (parser_selected_engine, parser_decision_code, created_at)
    WHERE stage = 'PARSE';
