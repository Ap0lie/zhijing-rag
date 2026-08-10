ALTER TABLE graph_manifests
    ADD COLUMN source_set_hash VARCHAR(64);

ALTER TABLE graph_manifests
    ADD CONSTRAINT ck_graph_manifests_source_set_hash
    CHECK (source_set_hash IS NULL OR length(source_set_hash) = 64);

CREATE TABLE graph_generation_sources (
    graph_generation BIGINT NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    acl_version BIGINT NOT NULL,
    document_title TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (graph_generation, document_id),
    CONSTRAINT fk_graph_generation_sources_manifest
        FOREIGN KEY (graph_generation)
        REFERENCES graph_manifests (graph_generation)
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_generation_sources_revision
        FOREIGN KEY (revision_id, document_id)
        REFERENCES document_revisions (id, document_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_graph_generation_sources_acl
        CHECK (acl_version >= 0),
    CONSTRAINT ck_graph_generation_sources_title
        CHECK (btrim(document_title) <> '')
);

CREATE INDEX ix_graph_generation_sources_revision
    ON graph_generation_sources (revision_id, graph_generation);

CREATE FUNCTION reject_graph_generation_source_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'graph_generation_sources rows are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER reject_graph_generation_source_update
    BEFORE UPDATE ON graph_generation_sources
    FOR EACH ROW EXECUTE FUNCTION reject_graph_generation_source_update();

CREATE INDEX ix_graph_entities_name_prefix
    ON graph_entities (
        graph_generation, normalized_name text_pattern_ops
    );

CREATE INDEX ix_graph_aliases_name_prefix
    ON graph_entity_aliases (
        graph_generation, normalized_alias text_pattern_ops
    );

ALTER TABLE graph_extraction_artifacts
    DROP CONSTRAINT uq_graph_extraction_artifacts_input;

ALTER TABLE graph_extraction_artifacts
    ADD CONSTRAINT uq_graph_extraction_artifacts_input
    UNIQUE (parent_chunk_id, input_hash);

CREATE FUNCTION reject_graph_artifact_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'graph_extraction_artifacts rows are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER reject_graph_artifact_update
    BEFORE UPDATE ON graph_extraction_artifacts
    FOR EACH ROW EXECUTE FUNCTION reject_graph_artifact_update();

ALTER TABLE graph_entity_mentions
    ADD CONSTRAINT uq_graph_entity_mentions_alias_anchor
    UNIQUE (id, graph_generation);

CREATE TABLE graph_entity_alias_evidence (
    graph_generation BIGINT NOT NULL,
    entity_id UUID NOT NULL,
    normalized_alias TEXT NOT NULL,
    mention_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (
        graph_generation, entity_id, normalized_alias, mention_id
    ),
    CONSTRAINT fk_graph_entity_alias_evidence_alias
        FOREIGN KEY (graph_generation, entity_id, normalized_alias)
        REFERENCES graph_entity_aliases (
            graph_generation, entity_id, normalized_alias
        )
        ON DELETE CASCADE,
    CONSTRAINT fk_graph_entity_alias_evidence_mention
        FOREIGN KEY (mention_id, graph_generation)
        REFERENCES graph_entity_mentions (id, graph_generation)
        ON DELETE CASCADE
);

CREATE INDEX ix_graph_entity_alias_evidence_mention
    ON graph_entity_alias_evidence (mention_id, graph_generation);

ALTER TABLE graph_relationship_evidence
    ADD CONSTRAINT uq_graph_relationship_evidence_claim_anchor
    UNIQUE (id, relationship_id, graph_generation);

ALTER TABLE graph_community_claims
    DROP CONSTRAINT fk_graph_community_claims_evidence;

ALTER TABLE graph_community_claims
    ADD CONSTRAINT fk_graph_community_claims_evidence
    FOREIGN KEY (
        relationship_evidence_id, relationship_id, graph_generation
    )
    REFERENCES graph_relationship_evidence (
        id, relationship_id, graph_generation
    )
    ON DELETE CASCADE;
