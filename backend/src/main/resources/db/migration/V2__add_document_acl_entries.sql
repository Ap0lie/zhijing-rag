CREATE TABLE document_acl_entries (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    user_id UUID NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_acl_entries_document
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_document_acl_entries_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_document_acl_entries_document_user UNIQUE (document_id, user_id)
);

CREATE INDEX ix_document_acl_entries_user_id ON document_acl_entries (user_id);
