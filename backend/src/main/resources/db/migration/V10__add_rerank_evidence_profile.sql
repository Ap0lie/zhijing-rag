ALTER TABLE retrieval_profiles
    DROP CONSTRAINT ck_retrieval_profiles_candidate_depth,
    ADD CONSTRAINT ck_retrieval_profiles_candidate_depth
        CHECK (
            bm25_top_k BETWEEN 1 AND 200
            AND vector_top_k BETWEEN 0 AND 200
            AND rrf_rank_constant BETWEEN 1 AND 1000
            AND rerank_top_k BETWEEN 0 AND 200
            AND evidence_top_k BETWEEN 1 AND 50
            AND parent_token_budget BETWEEN 0 AND 6000
        );

INSERT INTO retrieval_profiles (
    version, mode, default_page_size, max_page_size,
    bm25_top_k, vector_top_k, rrf_rank_constant,
    rerank_top_k, evidence_top_k, parent_token_budget
)
VALUES (
    'phase6c-hybrid-rerank-v1', 'HYBRID', 20, 50,
    50, 50, 60, 30, 8, 6000
);
