CREATE TABLE user_memory_settings (
    user_id UUID PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    suggestion_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_memory_settings_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_memory_settings_version CHECK (version >= 0)
);

CREATE TABLE memory_items (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    memory_key VARCHAR(160) NOT NULL,
    normalized_key VARCHAR(160) NOT NULL,
    content TEXT,
    status VARCHAR(16) NOT NULL,
    version_number INTEGER NOT NULL,
    origin VARCHAR(16) NOT NULL,
    supersedes_memory_id UUID,
    idempotency_key VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_memory_items_owner_identity UNIQUE (id, owner_user_id),
    CONSTRAINT uq_memory_items_idempotency
        UNIQUE (owner_user_id, idempotency_key),
    CONSTRAINT fk_memory_items_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_items_supersedes
        FOREIGN KEY (supersedes_memory_id) REFERENCES memory_items (id),
    CONSTRAINT ck_memory_items_type CHECK (
        memory_type IN (
            'USER_PREFERENCE',
            'USER_FACT',
            'SESSION_SUMMARY',
            'DOCUMENT_FACT'
        )
    ),
    CONSTRAINT ck_memory_items_status CHECK (
        status IN (
            'CANDIDATE',
            'ACTIVE',
            'REJECTED',
            'REVOKED',
            'EXPIRED',
            'FORGOTTEN'
        )
    ),
    CONSTRAINT ck_memory_items_origin CHECK (
        origin IN ('USER', 'SUGGESTION')
    ),
    CONSTRAINT ck_memory_items_text CHECK (
        length(btrim(memory_key)) BETWEEN 1 AND 160
        AND length(btrim(normalized_key)) BETWEEN 1 AND 160
        AND (
            (status = 'FORGOTTEN' AND content IS NULL)
            OR (
                status <> 'FORGOTTEN'
                AND content IS NOT NULL
                AND length(btrim(content)) BETWEEN 1 AND 1200
            )
        )
    ),
    CONSTRAINT ck_memory_items_version CHECK (version_number > 0),
    CONSTRAINT ck_memory_items_idempotency CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
    ),
    CONSTRAINT ck_memory_items_expiry CHECK (
        expires_at IS NULL OR expires_at > created_at
    ),
    CONSTRAINT ck_memory_items_not_self_superseding CHECK (
        supersedes_memory_id IS NULL OR supersedes_memory_id <> id
    )
);

CREATE UNIQUE INDEX uq_memory_items_one_active_key
    ON memory_items (owner_user_id, memory_type, normalized_key)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_memory_items_version
    ON memory_items (
        owner_user_id,
        memory_type,
        normalized_key,
        version_number
    );

CREATE INDEX ix_memory_items_owner_status_updated
    ON memory_items (owner_user_id, status, updated_at DESC, id);

CREATE INDEX ix_memory_items_owner_type_key
    ON memory_items (owner_user_id, memory_type, normalized_key);

CREATE TABLE memory_sources (
    id UUID PRIMARY KEY,
    memory_item_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    chat_session_id UUID,
    chat_message_id UUID,
    document_id UUID,
    revision_id UUID,
    child_chunk_id UUID,
    source_span_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_sources_item
        FOREIGN KEY (memory_item_id, owner_user_id)
        REFERENCES memory_items (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_sources_session
        FOREIGN KEY (chat_session_id, owner_user_id)
        REFERENCES chat_sessions (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_sources_message
        FOREIGN KEY (chat_message_id, chat_session_id, owner_user_id)
        REFERENCES chat_messages (id, session_id, owner_user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_memory_sources_span
        FOREIGN KEY (
            source_span_id,
            child_chunk_id,
            document_id,
            revision_id
        )
        REFERENCES source_spans (id, chunk_id, document_id, revision_id),
    CONSTRAINT ck_memory_sources_type CHECK (
        source_type IN ('CHAT_SESSION', 'CHAT_MESSAGE', 'DOCUMENT_SPAN')
    ),
    CONSTRAINT ck_memory_sources_shape CHECK (
        (
            source_type = 'CHAT_SESSION'
            AND chat_session_id IS NOT NULL
            AND chat_message_id IS NULL
            AND document_id IS NULL
            AND revision_id IS NULL
            AND child_chunk_id IS NULL
            AND source_span_id IS NULL
        )
        OR
        (
            source_type = 'CHAT_MESSAGE'
            AND chat_session_id IS NOT NULL
            AND chat_message_id IS NOT NULL
            AND document_id IS NULL
            AND revision_id IS NULL
            AND child_chunk_id IS NULL
            AND source_span_id IS NULL
        )
        OR
        (
            source_type = 'DOCUMENT_SPAN'
            AND chat_session_id IS NULL
            AND chat_message_id IS NULL
            AND document_id IS NOT NULL
            AND revision_id IS NOT NULL
            AND child_chunk_id IS NOT NULL
            AND source_span_id IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uq_memory_sources_session
    ON memory_sources (memory_item_id, chat_session_id)
    WHERE source_type = 'CHAT_SESSION';

CREATE UNIQUE INDEX uq_memory_sources_message
    ON memory_sources (memory_item_id, chat_message_id)
    WHERE source_type = 'CHAT_MESSAGE';

CREATE UNIQUE INDEX uq_memory_sources_span
    ON memory_sources (memory_item_id, source_span_id)
    WHERE source_type = 'DOCUMENT_SPAN';

CREATE INDEX ix_memory_sources_item
    ON memory_sources (memory_item_id, created_at, id);

CREATE INDEX ix_memory_sources_document_revision
    ON memory_sources (document_id, revision_id)
    WHERE source_type = 'DOCUMENT_SPAN';

CREATE TABLE memory_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    memory_item_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    actor_user_id UUID,
    related_memory_id UUID,
    reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_events_item
        FOREIGN KEY (memory_item_id, owner_user_id)
        REFERENCES memory_items (id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_events_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT fk_memory_events_related
        FOREIGN KEY (related_memory_id) REFERENCES memory_items (id),
    CONSTRAINT ck_memory_events_type CHECK (
        event_type IN (
            'CREATED',
            'SUGGESTED',
            'CONFIRMED',
            'REJECTED',
            'REPLACED',
            'SUPERSEDED',
            'REVOKED',
            'EXPIRED',
            'FORGOTTEN'
        )
    ),
    CONSTRAINT ck_memory_events_reason CHECK (
        reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500
    )
);

CREATE INDEX ix_memory_events_item_created
    ON memory_events (memory_item_id, created_at DESC, id DESC);

CREATE INDEX ix_memory_events_owner_created
    ON memory_events (owner_user_id, created_at DESC, id DESC);

CREATE FUNCTION protect_memory_item_fact()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        OLD.id,
        OLD.owner_user_id,
        OLD.memory_type,
        OLD.memory_key,
        OLD.normalized_key,
        OLD.version_number,
        OLD.origin,
        OLD.supersedes_memory_id,
        OLD.idempotency_key,
        OLD.expires_at,
        OLD.created_at
    ) IS DISTINCT FROM ROW(
        NEW.id,
        NEW.owner_user_id,
        NEW.memory_type,
        NEW.memory_key,
        NEW.normalized_key,
        NEW.version_number,
        NEW.origin,
        NEW.supersedes_memory_id,
        NEW.idempotency_key,
        NEW.expires_at,
        NEW.created_at
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '55000',
            MESSAGE = 'memory fact fields are immutable';
    END IF;

    IF OLD.content IS DISTINCT FROM NEW.content
       AND NOT (
           OLD.content IS NOT NULL
           AND NEW.content IS NULL
           AND NEW.status = 'FORGOTTEN'
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '55000',
            MESSAGE = 'memory content can only be cleared by forgetting';
    END IF;

    IF OLD.status <> NEW.status AND NOT (
        (OLD.status = 'CANDIDATE' AND NEW.status IN (
            'ACTIVE', 'REJECTED', 'EXPIRED', 'FORGOTTEN'
        ))
        OR
        (OLD.status = 'ACTIVE' AND NEW.status IN (
            'REVOKED', 'EXPIRED', 'FORGOTTEN'
        ))
        OR
        (OLD.status IN ('REJECTED', 'REVOKED', 'EXPIRED')
            AND NEW.status = 'FORGOTTEN')
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '55000',
            MESSAGE = 'invalid memory lifecycle transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER protect_memory_item_fact
    BEFORE UPDATE OR DELETE ON memory_items
    FOR EACH ROW EXECUTE FUNCTION protect_memory_item_fact();

CREATE FUNCTION reject_memory_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'memory events are append-only';
END;
$$;

CREATE TRIGGER reject_memory_event_mutation
    BEFORE UPDATE OR DELETE ON memory_events
    FOR EACH ROW EXECUTE FUNCTION reject_memory_event_mutation();
