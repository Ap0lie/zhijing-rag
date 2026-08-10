ALTER TABLE query_intelligence_profile_publication_events
    ADD COLUMN intent_evaluation_run_id UUID,
    ADD COLUMN multi_turn_evaluation_run_id UUID,
    ADD CONSTRAINT fk_query_intelligence_events_intent_run
        FOREIGN KEY (intent_evaluation_run_id)
        REFERENCES evaluation_runs (id),
    ADD CONSTRAINT fk_query_intelligence_events_multi_turn_run
        FOREIGN KEY (multi_turn_evaluation_run_id)
        REFERENCES evaluation_runs (id);

CREATE INDEX ix_query_intelligence_events_intent_run
    ON query_intelligence_profile_publication_events (
        intent_evaluation_run_id
    )
    WHERE intent_evaluation_run_id IS NOT NULL;

CREATE INDEX ix_query_intelligence_events_multi_turn_run
    ON query_intelligence_profile_publication_events (
        multi_turn_evaluation_run_id
    )
    WHERE multi_turn_evaluation_run_id IS NOT NULL;

CREATE FUNCTION validate_query_intelligence_publication_gate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.action = 'PUBLISH'
       AND (
           NEW.intent_evaluation_run_id IS NULL
           OR NEW.multi_turn_evaluation_run_id IS NULL
       ) THEN
        RAISE EXCEPTION
            'Query profile publication requires INTENT and MULTI_TURN runs'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_query_intelligence_publication_gate
    BEFORE INSERT ON query_intelligence_profile_publication_events
    FOR EACH ROW
    EXECUTE FUNCTION validate_query_intelligence_publication_gate();

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
       OR (
           evaluator NOT LIKE 'phase11b-real-%'
           AND evaluator NOT LIKE 'phase12c-real-%'
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
       OR (
           evaluator NOT LIKE 'phase11b-real-%'
           AND evaluator NOT LIKE 'phase12c-real-%'
       ) THEN
        RAISE EXCEPTION
            'Baseline publication target is not eligible'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
