ALTER TABLE index_manifests
    DROP CONSTRAINT ck_index_manifests_status;

ALTER TABLE index_manifests
    ADD CONSTRAINT ck_index_manifests_status CHECK (
        status IN ('CANDIDATE', 'ACTIVE', 'RETIRED', 'FAILED', 'DELETED')
    );
