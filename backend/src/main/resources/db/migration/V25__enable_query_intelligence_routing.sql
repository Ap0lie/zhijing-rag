ALTER TABLE query_intelligence_profiles
    DROP CONSTRAINT ck_query_intelligence_profiles_budget,
    ADD CONSTRAINT ck_query_intelligence_profiles_budget
        CHECK (
            model_context_tokens BETWEEN 1024 AND 1048576
            AND history_message_limit BETWEEN 1 AND 12
            AND history_token_budget BETWEEN 64 AND 2048
            AND history_context_percent BETWEEN 1 AND 20
            AND max_sub_queries BETWEEN 1 AND 3
            AND max_retrieval_rounds BETWEEN 1 AND 2
            AND planner_call_limit BETWEEN 0 AND 2
            AND timeout_ms BETWEEN 100 AND 30000
        );

ALTER TABLE evaluation_targets
    DROP CONSTRAINT ck_evaluation_targets_type,
    ADD CONSTRAINT ck_evaluation_targets_type
        CHECK (
            subject_type IN (
                'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
                'ANSWER_CITATION', 'MULTI_TURN', 'INTENT'
            )
        );

ALTER TABLE evaluation_subjects
    DROP CONSTRAINT ck_evaluation_subjects_type,
    ADD CONSTRAINT ck_evaluation_subjects_type
        CHECK (
            subject_type IN (
                'RETRIEVAL', 'LOCAL_GRAPH', 'GLOBAL_GRAPH',
                'ANSWER_CITATION', 'MULTI_TURN', 'INTENT'
            )
        );
