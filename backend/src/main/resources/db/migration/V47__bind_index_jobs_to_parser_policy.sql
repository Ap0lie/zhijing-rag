UPDATE pipeline_jobs job
SET parser_provider = revision.parser_provider,
    parser_provider_version = COALESCE(
        job.parser_provider_version,
        revision.parser_version
    )
FROM document_revisions revision
WHERE job.revision_id = revision.id
  AND job.stage = 'INDEX'
  AND job.parser_provider IS NULL
  AND revision.parser_provider IS NOT NULL;

ALTER TABLE pipeline_jobs
    ADD CONSTRAINT ck_pipeline_index_parser_provider
    CHECK (stage <> 'INDEX' OR parser_provider IS NOT NULL);
