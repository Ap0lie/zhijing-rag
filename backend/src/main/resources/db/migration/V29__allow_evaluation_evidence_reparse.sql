CREATE TABLE evaluation_evidence_documents (
    evaluation_suite_version VARCHAR(64) NOT NULL,
    evaluation_evidence_key VARCHAR(128) NOT NULL,
    document_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (evaluation_suite_version, evaluation_evidence_key),
    CONSTRAINT fk_evaluation_evidence_documents_document
        FOREIGN KEY (document_id) REFERENCES documents (id)
);

INSERT INTO evaluation_evidence_documents (
    evaluation_suite_version,
    evaluation_evidence_key,
    document_id
)
SELECT
    evaluation_suite_version,
    evaluation_evidence_key,
    document_id
FROM document_revisions
WHERE evaluation_suite_version IS NOT NULL
ORDER BY revision_number
ON CONFLICT DO NOTHING;

ALTER TABLE document_revisions
    DROP CONSTRAINT uq_document_revisions_evaluation_evidence;

CREATE INDEX ix_document_revisions_evaluation_evidence
    ON document_revisions (
        evaluation_suite_version,
        evaluation_evidence_key,
        document_id
    )
    WHERE evaluation_suite_version IS NOT NULL;

CREATE FUNCTION bind_evaluation_evidence_document()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    bound_document_id UUID;
BEGIN
    IF NEW.evaluation_suite_version IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO evaluation_evidence_documents (
        evaluation_suite_version,
        evaluation_evidence_key,
        document_id
    )
    VALUES (
        NEW.evaluation_suite_version,
        NEW.evaluation_evidence_key,
        NEW.document_id
    )
    ON CONFLICT DO NOTHING;

    SELECT document_id
    INTO bound_document_id
    FROM evaluation_evidence_documents
    WHERE evaluation_suite_version = NEW.evaluation_suite_version
      AND evaluation_evidence_key = NEW.evaluation_evidence_key;

    IF bound_document_id <> NEW.document_id THEN
        RAISE EXCEPTION
            'evaluation evidence is already bound to another document'
            USING ERRCODE = '23505',
                  CONSTRAINT = 'uq_document_revisions_evaluation_evidence';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_document_revisions_evaluation_evidence
BEFORE INSERT OR UPDATE OF
    evaluation_suite_version,
    evaluation_evidence_key,
    document_id
ON document_revisions
FOR EACH ROW
EXECUTE FUNCTION bind_evaluation_evidence_document();
