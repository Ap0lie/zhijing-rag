ALTER TABLE evaluation_cases
    DROP CONSTRAINT ck_evaluation_cases_type,
    ADD CONSTRAINT ck_evaluation_cases_type CHECK (
        case_type IN (
            'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
            'ANSWER_CITATION', 'MULTI_TURN', 'INTENT', 'PARSER',
            'MULTIFORMAT_RELEASE', 'MULTIFORMAT_SECURITY'
        )
    );
