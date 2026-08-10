CREATE OR REPLACE FUNCTION validate_real_evaluation_baseline()
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
       OR NOT (
           evaluator LIKE 'phase11b-real-%'
           OR evaluator LIKE 'phase12c-real-%'
           OR evaluator LIKE 'phase18d-real-%'
       )
       OR NEW.gate_status <> 'PASSED'
       OR COALESCE(NEW.gate_summary ->> 'passed', 'false') <> 'true' THEN
        RAISE EXCEPTION
            'Baseline requires a successful real evaluator and passed gate'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_baseline_publication_target()
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
       OR NOT (
           evaluator LIKE 'phase11b-real-%'
           OR evaluator LIKE 'phase12c-real-%'
           OR evaluator LIKE 'phase18d-real-%'
       ) THEN
        RAISE EXCEPTION
            'Baseline publication target is not eligible'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
