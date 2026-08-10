ALTER TABLE evaluation_subjects
    DROP CONSTRAINT ck_evaluation_subjects_readiness,
    ADD CONSTRAINT ck_evaluation_subjects_readiness
        CHECK (
            readiness_status IN ('READY', 'BLOCKED_PREREQUISITE')
            AND (
                (readiness_status = 'READY' AND blocked_reason IS NULL)
                OR
                (
                    readiness_status = 'BLOCKED_PREREQUISITE'
                    AND blocked_reason IS NOT NULL
                    AND btrim(blocked_reason) <> ''
                )
            )
        );

ALTER TABLE evaluation_runs
    DROP CONSTRAINT ck_evaluation_runs_lease,
    ADD CONSTRAINT ck_evaluation_runs_lease
        CHECK (
            (
                status = 'RUNNING'
                AND lease_owner IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND heartbeat_at IS NOT NULL
            )
            OR
            (
                status <> 'RUNNING'
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL
                AND heartbeat_at IS NULL
            )
        );

ALTER TABLE evaluation_run_events
    DROP CONSTRAINT ck_evaluation_run_events_type,
    ADD CONSTRAINT ck_evaluation_run_events_type
        CHECK (
            event_type IN (
                'CREATED', 'CLAIMED', 'HEARTBEAT', 'CANCEL_REQUESTED',
                'CANCELLED', 'CASE_COMPLETED', 'SUCCEEDED', 'FAILED',
                'BLOCKED_PREREQUISITE', 'RETRIED', 'LEASE_RECOVERED',
                'YIELDED_TO_CHAT', 'REQUEUED'
            )
        ),
    ADD CONSTRAINT ck_evaluation_run_events_sequence
        CHECK (sequence > 0);

ALTER TABLE evaluation_case_results
    ADD COLUMN dataset_version_id UUID;

DROP TRIGGER reject_evaluation_case_result_mutation
    ON evaluation_case_results;

UPDATE evaluation_case_results result
SET dataset_version_id = run.dataset_version_id
FROM evaluation_runs run
WHERE run.id = result.run_id;

CREATE TRIGGER reject_evaluation_case_result_mutation
    BEFORE UPDATE OR DELETE ON evaluation_case_results
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();

ALTER TABLE evaluation_case_results
    ALTER COLUMN dataset_version_id SET NOT NULL;

ALTER TABLE evaluation_runs
    ADD CONSTRAINT uq_evaluation_runs_result_provenance
        UNIQUE (id, dataset_version_id, evaluator_version),
    ADD CONSTRAINT uq_evaluation_runs_baseline_provenance
        UNIQUE (id, dataset_version_id, evaluation_subject_id);

ALTER TABLE evaluation_cases
    ADD CONSTRAINT uq_evaluation_cases_result_provenance
        UNIQUE (id, dataset_version_id);

ALTER TABLE evaluation_case_results
    ADD CONSTRAINT fk_evaluation_case_results_run_provenance
        FOREIGN KEY (run_id, dataset_version_id, evaluator_version)
        REFERENCES evaluation_runs (id, dataset_version_id, evaluator_version),
    ADD CONSTRAINT fk_evaluation_case_results_case_provenance
        FOREIGN KEY (case_id, dataset_version_id)
        REFERENCES evaluation_cases (id, dataset_version_id);

CREATE INDEX ix_evaluation_case_results_version
    ON evaluation_case_results (dataset_version_id, run_id);
CREATE INDEX ix_evaluation_runs_original
    ON evaluation_runs (original_run_id)
    WHERE original_run_id IS NOT NULL;
CREATE INDEX ix_evaluation_subjects_creator
    ON evaluation_subjects (created_by)
    WHERE created_by IS NOT NULL;

ALTER TABLE evaluation_baselines
    ADD CONSTRAINT uq_evaluation_baselines_key_identity
        UNIQUE (id, baseline_key),
    ADD CONSTRAINT fk_evaluation_baselines_run_provenance
        FOREIGN KEY (run_id, dataset_version_id, evaluation_subject_id)
        REFERENCES evaluation_runs (
            id, dataset_version_id, evaluation_subject_id
        );

ALTER TABLE evaluation_baseline_publication_events
    ADD CONSTRAINT uq_evaluation_baseline_events_key_identity
        UNIQUE (id, baseline_id, baseline_key),
    ADD CONSTRAINT fk_evaluation_baseline_events_target_key
        FOREIGN KEY (baseline_id, baseline_key)
        REFERENCES evaluation_baselines (id, baseline_key),
    ADD CONSTRAINT fk_evaluation_baseline_events_previous_key
        FOREIGN KEY (previous_baseline_id, baseline_key)
        REFERENCES evaluation_baselines (id, baseline_key);

ALTER TABLE evaluation_baseline_publications
    ADD CONSTRAINT fk_evaluation_baseline_publications_target_key
        FOREIGN KEY (baseline_id, baseline_key)
        REFERENCES evaluation_baselines (id, baseline_key),
    ADD CONSTRAINT fk_evaluation_baseline_publications_event_key
        FOREIGN KEY (publication_event_id, baseline_id, baseline_key)
        REFERENCES evaluation_baseline_publication_events (
            id, baseline_id, baseline_key
        );

CREATE FUNCTION validate_evaluation_run_types()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    subject_case_type VARCHAR(32);
    dataset_case_type VARCHAR(32);
BEGIN
    SELECT subject_type
    INTO subject_case_type
    FROM evaluation_subjects
    WHERE id = NEW.evaluation_subject_id;

    SELECT case_type
    INTO dataset_case_type
    FROM evaluation_dataset_versions
    WHERE id = NEW.dataset_version_id;

    IF subject_case_type IS DISTINCT FROM dataset_case_type THEN
        RAISE EXCEPTION
            'EvaluationSubject type must match DatasetVersion type'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_evaluation_run_types
    BEFORE INSERT OR UPDATE OF evaluation_subject_id, dataset_version_id
    ON evaluation_runs
    FOR EACH ROW EXECUTE FUNCTION validate_evaluation_run_types();

CREATE FUNCTION prevent_evaluated_case_append()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM evaluation_runs
        WHERE dataset_version_id = NEW.dataset_version_id
    ) THEN
        RAISE EXCEPTION
            'Cannot append a case after DatasetVersion evaluation has started'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER prevent_evaluated_case_append
    BEFORE INSERT ON evaluation_cases
    FOR EACH ROW EXECUTE FUNCTION prevent_evaluated_case_append();

CREATE FUNCTION validate_evaluation_case_result_state()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    run_status VARCHAR(32);
BEGIN
    SELECT status
    INTO run_status
    FROM evaluation_runs
    WHERE id = NEW.run_id;

    IF run_status IS DISTINCT FROM 'RUNNING' THEN
        RAISE EXCEPTION
            'Evaluation results can only be appended to a RUNNING Run'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_evaluation_case_result_state
    BEFORE INSERT ON evaluation_case_results
    FOR EACH ROW EXECUTE FUNCTION validate_evaluation_case_result_state();

CREATE FUNCTION validate_real_evaluation_baseline()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    run_status VARCHAR(32);
    evaluator VARCHAR(64);
BEGIN
    SELECT status, evaluator_version
    INTO run_status, evaluator
    FROM evaluation_runs
    WHERE id = NEW.run_id;

    IF run_status IS DISTINCT FROM 'SUCCEEDED'
       OR evaluator NOT LIKE 'phase11b-real-%'
       OR NEW.gate_status <> 'PASSED'
       OR COALESCE(NEW.gate_summary ->> 'passed', 'false') <> 'true' THEN
        RAISE EXCEPTION
            'Baseline requires a successful real evaluator and passed gate'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_real_evaluation_baseline
    BEFORE INSERT ON evaluation_baselines
    FOR EACH ROW EXECUTE FUNCTION validate_real_evaluation_baseline();

CREATE FUNCTION validate_baseline_publication_target()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_key VARCHAR(160);
    target_gate VARCHAR(16);
    run_status VARCHAR(32);
    evaluator VARCHAR(64);
BEGIN
    SELECT baseline.baseline_key, baseline.gate_status,
           run.status, run.evaluator_version
    INTO target_key, target_gate, run_status, evaluator
    FROM evaluation_baselines baseline
    JOIN evaluation_runs run ON run.id = baseline.run_id
    WHERE baseline.id = NEW.baseline_id;

    IF target_key IS DISTINCT FROM NEW.baseline_key
       OR target_gate IS DISTINCT FROM 'PASSED'
       OR run_status IS DISTINCT FROM 'SUCCEEDED'
       OR evaluator NOT LIKE 'phase11b-real-%' THEN
        RAISE EXCEPTION
            'Baseline publication target is not eligible'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_baseline_publication_event_target
    BEFORE INSERT ON evaluation_baseline_publication_events
    FOR EACH ROW EXECUTE FUNCTION validate_baseline_publication_target();

CREATE TRIGGER validate_baseline_publication_target
    BEFORE INSERT OR UPDATE ON evaluation_baseline_publications
    FOR EACH ROW EXECUTE FUNCTION validate_baseline_publication_target();
