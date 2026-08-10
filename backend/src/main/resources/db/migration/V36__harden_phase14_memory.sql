ALTER TABLE chat_runs
    ADD COLUMN memory_suggestion_requested_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_chat_runs_memory_suggestion_requested
    ON chat_runs (memory_suggestion_requested_at, id)
    WHERE memory_suggestion_requested_at IS NOT NULL;

ALTER TABLE memory_sources
    ADD COLUMN source_deleted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE memory_sources
    DROP CONSTRAINT fk_memory_sources_message,
    DROP CONSTRAINT fk_memory_sources_session;

CREATE INDEX ix_memory_sources_chat_session
    ON memory_sources (chat_session_id)
    WHERE source_type IN ('CHAT_SESSION', 'CHAT_MESSAGE');

CREATE INDEX ix_memory_sources_chat_message
    ON memory_sources (chat_message_id)
    WHERE source_type = 'CHAT_MESSAGE';

ALTER TABLE memory_sources
    ADD CONSTRAINT ck_memory_sources_deleted
        CHECK (
            source_deleted_at IS NULL
            OR source_type IN ('CHAT_SESSION', 'CHAT_MESSAGE')
        );

CREATE FUNCTION tombstone_memory_sources_for_session()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE memory_sources
    SET source_deleted_at = COALESCE(
        source_deleted_at,
        CURRENT_TIMESTAMP
    )
    WHERE owner_user_id = OLD.owner_user_id
      AND chat_session_id = OLD.id;
    RETURN OLD;
END;
$$;

CREATE TRIGGER tombstone_memory_sources_for_session
    BEFORE DELETE ON chat_sessions
    FOR EACH ROW EXECUTE FUNCTION tombstone_memory_sources_for_session();

CREATE FUNCTION tombstone_memory_sources_for_message()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE memory_sources
    SET source_deleted_at = COALESCE(
        source_deleted_at,
        CURRENT_TIMESTAMP
    )
    WHERE owner_user_id = OLD.owner_user_id
      AND chat_message_id = OLD.id;
    RETURN OLD;
END;
$$;

CREATE TRIGGER tombstone_memory_sources_for_message
    BEFORE DELETE ON chat_messages
    FOR EACH ROW EXECUTE FUNCTION tombstone_memory_sources_for_message();

ALTER TABLE memory_suggestion_jobs
    ADD COLUMN lease_token UUID;

UPDATE memory_suggestion_jobs
SET status = 'PENDING',
    lease_owner = NULL,
    lease_expires_at = NULL,
    lease_token = NULL,
    available_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'RUNNING';

ALTER TABLE memory_suggestion_jobs
    ADD CONSTRAINT ck_memory_suggestion_job_lease_token CHECK (
        (status = 'RUNNING' AND lease_token IS NOT NULL)
        OR
        (status <> 'RUNNING' AND lease_token IS NULL)
    );
