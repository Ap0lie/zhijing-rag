ALTER TABLE users
    ADD COLUMN security_version BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_users_security_version CHECK (security_version > 0);

CREATE TABLE governance_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    module VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    actor_user_id UUID,
    actor_snapshot VARCHAR(100) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id VARCHAR(160) NOT NULL,
    object_label VARCHAR(500) NOT NULL,
    before_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason VARCHAR(500) NOT NULL,
    source_event VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(120),
    request_hash VARCHAR(64),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_governance_events_module CHECK (btrim(module) <> ''),
    CONSTRAINT ck_governance_events_action CHECK (btrim(action) <> ''),
    CONSTRAINT ck_governance_events_actor CHECK (btrim(actor_snapshot) <> ''),
    CONSTRAINT ck_governance_events_object CHECK (
        btrim(object_type) <> '' AND btrim(object_id) <> '' AND btrim(object_label) <> ''
    ),
    CONSTRAINT ck_governance_events_reason CHECK (length(btrim(reason)) BETWEEN 8 AND 500),
    CONSTRAINT ck_governance_events_source CHECK (btrim(source_event) <> ''),
    CONSTRAINT ck_governance_events_idempotency CHECK (
        (idempotency_key IS NULL AND request_hash IS NULL)
        OR (
            length(btrim(idempotency_key)) BETWEEN 8 AND 120
            AND request_hash ~ '^[0-9a-f]{64}$'
        )
    )
);

CREATE UNIQUE INDEX uq_governance_events_idempotency
    ON governance_events (actor_user_id, action, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_governance_events_timeline
    ON governance_events (occurred_at DESC, id DESC);

CREATE INDEX ix_governance_events_object
    ON governance_events (object_type, object_id, occurred_at DESC);

CREATE FUNCTION reject_governance_event_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'governance events are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reject_governance_event_mutation
    BEFORE UPDATE OR DELETE ON governance_events
    FOR EACH ROW EXECUTE FUNCTION reject_governance_event_mutation();

ALTER TABLE pipeline_job_action_events
    DROP CONSTRAINT ck_pipeline_job_action_events_action,
    ADD CONSTRAINT ck_pipeline_job_action_events_action CHECK (
        action IN ('CANCEL', 'RELEASE_QUARANTINE', 'RETRY', 'PARSER_OVERRIDE')
    );

CREATE VIEW admin_audit_events AS
SELECT
    'GOVERNANCE:' || event.id AS source_event,
    event.module,
    event.action,
    event.actor_user_id AS actor_id,
    event.actor_snapshot,
    event.object_type,
    event.object_id,
    event.object_label,
    event.before_summary,
    event.after_summary,
    event.reason,
    event.occurred_at
FROM governance_events event

UNION ALL

SELECT
    'RETRIEVAL:' || event.id,
    CASE WHEN event.index_generation IS NULL THEN 'RETRIEVAL' ELSE 'INDEX' END,
    event.action,
    event.actor_user_id,
    COALESCE(actor.username, 'system'),
    CASE WHEN event.index_generation IS NULL THEN 'RETRIEVAL_PROFILE' ELSE 'INDEX_GENERATION' END,
    COALESCE(event.index_generation::text, event.profile_version),
    CASE WHEN event.index_generation IS NULL
        THEN event.profile_version
        ELSE '第 ' || event.index_generation || ' 代索引'
    END,
    jsonb_build_object(
        'profileVersion', event.previous_profile_version,
        'indexGeneration', event.previous_index_generation
    ),
    jsonb_build_object(
        'profileVersion', event.profile_version,
        'indexGeneration', event.index_generation
    ),
    event.reason,
    event.created_at
FROM retrieval_publication_events event
LEFT JOIN users actor ON actor.id = event.actor_user_id

UNION ALL

SELECT
    'GRAPH_RETRIEVAL:' || event.id,
    'GRAPH', event.event_type, event.actor_user_id,
    COALESCE(actor.username, 'system'),
    'GRAPH_RETRIEVAL_PROFILE', event.profile_version, event.profile_version,
    jsonb_build_object('profileVersion', event.previous_profile_version),
    jsonb_build_object('profileVersion', event.profile_version),
    event.reason, event.created_at
FROM graph_retrieval_publication_events event
LEFT JOIN users actor ON actor.id = event.actor_user_id

UNION ALL

SELECT
    'GRAPH:' || event.id,
    'GRAPH', event.action, event.actor_user_id,
    COALESCE(actor.username, 'system'),
    'GRAPH_GENERATION', event.graph_generation::text,
    '第 ' || event.graph_generation || ' 代知识图谱',
    jsonb_build_object('generation', event.previous_graph_generation),
    jsonb_build_object('generation', event.graph_generation),
    event.reason, event.created_at
FROM graph_publication_events event
LEFT JOIN users actor ON actor.id = event.actor_user_id

UNION ALL

SELECT
    'GLOBAL_GRAPH:' || event.id,
    'GLOBAL_GRAPH', event.action, event.actor_user_id,
    COALESCE(actor.username, 'system'),
    'GLOBAL_GENERATION', event.global_generation::text,
    '第 ' || event.global_generation || ' 代公共报告',
    jsonb_build_object('generation', event.previous_global_generation),
    jsonb_build_object('generation', event.global_generation),
    event.reason, event.created_at
FROM global_graph_publication_events event
LEFT JOIN users actor ON actor.id = event.actor_user_id

UNION ALL

SELECT
    'QUERY:' || event.id,
    'QUERY', event.action, event.actor,
    COALESCE(actor.username, 'system'),
    'QUERY_PROFILE', event.profile_version, event.profile_version,
    jsonb_build_object('profileVersion', event.previous_profile_version),
    jsonb_build_object('profileVersion', event.profile_version),
    event.reason, event.created_at
FROM query_intelligence_profile_publication_events event
LEFT JOIN users actor ON actor.id = event.actor

UNION ALL

SELECT
    'ANSWER:' || event.id,
    'ANSWER', event.action, event.actor,
    COALESCE(actor.username, 'system'),
    'ANSWER_PROFILE', event.profile_version, event.profile_version,
    jsonb_build_object('profileVersion', event.previous_profile_version),
    jsonb_build_object('profileVersion', event.profile_version),
    event.reason, event.created_at
FROM answer_profile_publication_events event
LEFT JOIN users actor ON actor.id = event.actor

UNION ALL

SELECT
    'BASELINE:' || event.id,
    'BASELINE', event.action, event.actor,
    COALESCE(actor.username, 'system'),
    'BASELINE', event.baseline_id::text, event.baseline_key,
    jsonb_build_object('baselineId', event.previous_baseline_id),
    jsonb_build_object('baselineId', event.baseline_id),
    event.reason, event.created_at
FROM evaluation_baseline_publication_events event
LEFT JOIN users actor ON actor.id = event.actor

UNION ALL

SELECT
    'PIPELINE:' || event.id,
    'PIPELINE', event.action, event.actor_user_id,
    COALESCE(actor.username, 'system'),
    'PIPELINE_JOB', event.job_id::text, document.title,
    '{}'::jsonb,
    jsonb_build_object('stage', job.stage, 'status', job.status, 'attempt', job.attempt),
    event.reason, event.created_at
FROM pipeline_job_action_events event
JOIN pipeline_jobs job ON job.id = event.job_id
JOIN document_revisions revision ON revision.id = job.revision_id
JOIN documents document ON document.id = revision.document_id
LEFT JOIN users actor ON actor.id = event.actor_user_id

UNION ALL

SELECT
    'PARSER_OVERRIDE:' || job.id,
    'PIPELINE', 'PARSER_OVERRIDE', job.parser_override_by,
    COALESCE(actor.username, 'system'),
    'PIPELINE_JOB', job.id::text, document.title,
    jsonb_build_object('sourceJobId', job.parser_override_source_job_id),
    jsonb_build_object('targetParser', job.parser_requested_engine),
    job.parser_override_reason, job.created_at
FROM pipeline_jobs job
JOIN document_revisions revision ON revision.id = job.revision_id
JOIN documents document ON document.id = revision.document_id
LEFT JOIN users actor ON actor.id = job.parser_override_by
WHERE job.parser_override_source_job_id IS NOT NULL

UNION ALL

SELECT
    'REPARSE:' || revision.id,
    'PIPELINE', 'REPARSE', revision.reparse_requested_by,
    COALESCE(actor.username, 'system'),
    'DOCUMENT_REVISION', revision.id::text, document.title,
    jsonb_build_object('sourceRevisionId', revision.source_revision_id),
    jsonb_build_object('revisionNumber', revision.revision_number, 'targetParser', revision.reparse_requested_parser),
    revision.reparse_reason, revision.created_at
FROM document_revisions revision
JOIN documents document ON document.id = revision.document_id
LEFT JOIN users actor ON actor.id = revision.reparse_requested_by
WHERE revision.source_revision_id IS NOT NULL

UNION ALL

SELECT
    'FORMAT_POLICY:' || event.id,
    'RUNTIME_POLICY', event.action, event.created_by,
    event.actor_username,
    'DOCUMENT_FORMAT_POLICY', event.policy_key, event.policy_key,
    jsonb_build_object('status', event.previous_status, 'version', event.policy_version - 1),
    jsonb_build_object('status', event.new_status, 'version', event.policy_version),
    event.reason, event.created_at
FROM document_runtime_policy_events event;

COMMENT ON VIEW admin_audit_events IS
    'Read-only normalized audit projection. Existing module events remain the facts; governance_events only fills missing user and ACL actions.';
