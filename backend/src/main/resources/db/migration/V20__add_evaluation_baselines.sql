CREATE TABLE answer_profiles (
    version VARCHAR(64) PRIMARY KEY,
    model_provider VARCHAR(64) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    model_revision VARCHAR(160) NOT NULL,
    endpoint_identity VARCHAR(255) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    orchestration_version VARCHAR(64) NOT NULL,
    timeout_ms INTEGER NOT NULL,
    max_output_tokens INTEGER NOT NULL,
    remote_evidence_allowed BOOLEAN NOT NULL,
    remote_memory_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(500) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_answer_profiles_creator
        FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_answer_profiles_version
        CHECK (version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT ck_answer_profiles_text
        CHECK (
            btrim(model_provider) <> ''
            AND btrim(model_id) <> ''
            AND btrim(model_revision) <> ''
            AND btrim(endpoint_identity) <> ''
            AND btrim(prompt_version) <> ''
            AND btrim(orchestration_version) <> ''
            AND btrim(reason) <> ''
        ),
    CONSTRAINT ck_answer_profiles_budget
        CHECK (
            timeout_ms BETWEEN 1000 AND 120000
            AND max_output_tokens BETWEEN 64 AND 4096
        )
);

CREATE INDEX ix_answer_profiles_created
    ON answer_profiles (created_at DESC, version);

CREATE TABLE answer_profile_publication_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_version VARCHAR(64) NOT NULL,
    previous_profile_version VARCHAR(64),
    action VARCHAR(16) NOT NULL,
    actor UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_answer_profile_events_profile
        FOREIGN KEY (profile_version) REFERENCES answer_profiles (version),
    CONSTRAINT fk_answer_profile_events_previous
        FOREIGN KEY (previous_profile_version) REFERENCES answer_profiles (version),
    CONSTRAINT fk_answer_profile_events_actor
        FOREIGN KEY (actor) REFERENCES users (id),
    CONSTRAINT uq_answer_profile_events_identity
        UNIQUE (id, profile_version),
    CONSTRAINT ck_answer_profile_events_action
        CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_answer_profile_events_reason
        CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_answer_profile_events_created
    ON answer_profile_publication_events (created_at DESC, id DESC);

CREATE TABLE answer_profile_publications (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    profile_version VARCHAR(64) NOT NULL,
    publication_event_id BIGINT NOT NULL UNIQUE,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_answer_profile_publications_singleton
        CHECK (singleton_id = 1),
    CONSTRAINT fk_answer_profile_publications_profile
        FOREIGN KEY (profile_version) REFERENCES answer_profiles (version),
    CONSTRAINT fk_answer_profile_publications_event
        FOREIGN KEY (publication_event_id, profile_version)
        REFERENCES answer_profile_publication_events (id, profile_version)
);

CREATE TABLE evaluation_baselines (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    baseline_key VARCHAR(160) NOT NULL,
    dataset_version_id UUID NOT NULL,
    evaluation_subject_id UUID NOT NULL,
    run_id UUID NOT NULL UNIQUE,
    gate_status VARCHAR(16) NOT NULL,
    gate_summary JSONB NOT NULL,
    metric_summary JSONB NOT NULL,
    judge_advisory JSONB NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_baselines_version
        FOREIGN KEY (dataset_version_id)
        REFERENCES evaluation_dataset_versions (id),
    CONSTRAINT fk_evaluation_baselines_subject
        FOREIGN KEY (evaluation_subject_id)
        REFERENCES evaluation_subjects (id),
    CONSTRAINT fk_evaluation_baselines_run
        FOREIGN KEY (run_id) REFERENCES evaluation_runs (id),
    CONSTRAINT fk_evaluation_baselines_creator
        FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_evaluation_baselines_text
        CHECK (
            btrim(name) <> ''
            AND baseline_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$'
        ),
    CONSTRAINT ck_evaluation_baselines_gate
        CHECK (gate_status IN ('PASSED', 'BLOCKED')),
    CONSTRAINT ck_evaluation_baselines_json
        CHECK (
            jsonb_typeof(gate_summary) = 'object'
            AND jsonb_typeof(metric_summary) = 'object'
            AND jsonb_typeof(judge_advisory) = 'object'
        )
);

CREATE INDEX ix_evaluation_baselines_key
    ON evaluation_baselines (baseline_key, created_at DESC, id DESC);
CREATE INDEX ix_evaluation_baselines_version
    ON evaluation_baselines (dataset_version_id, created_at DESC, id DESC);

CREATE TABLE evaluation_baseline_publication_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    baseline_key VARCHAR(160) NOT NULL,
    baseline_id UUID NOT NULL,
    previous_baseline_id UUID,
    action VARCHAR(16) NOT NULL,
    actor UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_baseline_events_baseline
        FOREIGN KEY (baseline_id) REFERENCES evaluation_baselines (id),
    CONSTRAINT fk_evaluation_baseline_events_previous
        FOREIGN KEY (previous_baseline_id) REFERENCES evaluation_baselines (id),
    CONSTRAINT fk_evaluation_baseline_events_actor
        FOREIGN KEY (actor) REFERENCES users (id),
    CONSTRAINT uq_evaluation_baseline_events_identity
        UNIQUE (id, baseline_id),
    CONSTRAINT ck_evaluation_baseline_events_action
        CHECK (action IN ('PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_evaluation_baseline_events_reason
        CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_evaluation_baseline_events_key
    ON evaluation_baseline_publication_events (
        baseline_key, created_at DESC, id DESC
    );

CREATE TABLE evaluation_baseline_publications (
    baseline_key VARCHAR(160) PRIMARY KEY,
    baseline_id UUID NOT NULL UNIQUE,
    publication_event_id BIGINT NOT NULL UNIQUE,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_baseline_publications_baseline
        FOREIGN KEY (baseline_id) REFERENCES evaluation_baselines (id),
    CONSTRAINT fk_evaluation_baseline_publications_event
        FOREIGN KEY (publication_event_id, baseline_id)
        REFERENCES evaluation_baseline_publication_events (id, baseline_id)
);

ALTER TABLE chat_runs
    ADD CONSTRAINT uq_chat_runs_feedback_owner
        UNIQUE (id, owner_user_id);

CREATE TABLE evaluation_feedback (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    chat_run_id UUID NOT NULL,
    rating SMALLINT NOT NULL,
    comment VARCHAR(2000),
    consent_to_share BOOLEAN NOT NULL DEFAULT FALSE,
    redacted_sample JSONB NOT NULL,
    redaction_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_feedback_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT fk_evaluation_feedback_run_owner
        FOREIGN KEY (chat_run_id, owner_user_id)
        REFERENCES chat_runs (id, owner_user_id),
    CONSTRAINT uq_evaluation_feedback_run
        UNIQUE (owner_user_id, chat_run_id),
    CONSTRAINT ck_evaluation_feedback_rating
        CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_evaluation_feedback_comment
        CHECK (comment IS NULL OR btrim(comment) <> ''),
    CONSTRAINT ck_evaluation_feedback_sample
        CHECK (
            jsonb_typeof(redacted_sample) = 'object'
            AND btrim(redaction_version) <> ''
        )
);

CREATE INDEX ix_evaluation_feedback_review_queue
    ON evaluation_feedback (created_at, id)
    WHERE consent_to_share = TRUE;

CREATE TABLE evaluation_feedback_reviews (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    feedback_id UUID NOT NULL UNIQUE,
    decision VARCHAR(16) NOT NULL,
    reviewer UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_dataset_version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_feedback_reviews_feedback
        FOREIGN KEY (feedback_id) REFERENCES evaluation_feedback (id),
    CONSTRAINT fk_evaluation_feedback_reviews_reviewer
        FOREIGN KEY (reviewer) REFERENCES users (id),
    CONSTRAINT fk_evaluation_feedback_reviews_version
        FOREIGN KEY (created_dataset_version_id)
        REFERENCES evaluation_dataset_versions (id),
    CONSTRAINT ck_evaluation_feedback_reviews_decision
        CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_evaluation_feedback_reviews_reason
        CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_evaluation_feedback_reviews_version
        CHECK (
            (decision = 'APPROVED' AND created_dataset_version_id IS NOT NULL)
            OR
            (decision = 'REJECTED' AND created_dataset_version_id IS NULL)
        )
);

CREATE TRIGGER reject_answer_profile_mutation
    BEFORE UPDATE OR DELETE ON answer_profiles
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_answer_profile_event_mutation
    BEFORE UPDATE OR DELETE ON answer_profile_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_baseline_mutation
    BEFORE UPDATE OR DELETE ON evaluation_baselines
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_baseline_event_mutation
    BEFORE UPDATE OR DELETE ON evaluation_baseline_publication_events
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_feedback_mutation
    BEFORE UPDATE OR DELETE ON evaluation_feedback
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
CREATE TRIGGER reject_evaluation_feedback_review_mutation
    BEFORE UPDATE OR DELETE ON evaluation_feedback_reviews
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_evaluation_row();
