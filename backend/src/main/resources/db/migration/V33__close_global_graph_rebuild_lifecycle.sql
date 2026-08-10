ALTER TABLE graph_rebuild_requests
    DROP CONSTRAINT ck_graph_rebuild_requests_state,
    DROP CONSTRAINT ck_graph_rebuild_requests_lifecycle;

ALTER TABLE graph_rebuild_requests
    ADD COLUMN global_rebuild_required BOOLEAN,
    ADD COLUMN candidate_global_generation BIGINT,
    ADD COLUMN graph_ready_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN global_ready_at TIMESTAMP WITH TIME ZONE;

UPDATE graph_rebuild_requests request
SET global_rebuild_required = (
    request.source_global_generation IS NOT NULL
    AND (
        EXISTS (
            SELECT 1
            FROM documents document
            WHERE document.id = request.document_id
              AND document.visibility = 'ALL_USERS'
        )
        OR EXISTS (
            SELECT 1
            FROM global_graph_sources source
            WHERE source.global_generation =
                    request.source_global_generation
              AND source.document_id = request.document_id
        )
    )
);

ALTER TABLE graph_rebuild_requests
    ALTER COLUMN global_rebuild_required SET DEFAULT FALSE,
    ALTER COLUMN global_rebuild_required SET NOT NULL,
    ADD CONSTRAINT fk_graph_rebuild_requests_candidate_global
        FOREIGN KEY (candidate_global_generation)
        REFERENCES global_graph_manifests (global_generation);

UPDATE graph_rebuild_requests
SET state = 'GRAPH_BUILDING'
WHERE state = 'BUILDING';

UPDATE graph_rebuild_requests
SET graph_ready_at = COALESCE(completed_at, requested_at)
WHERE state = 'FULFILLED';

WITH completed_global AS (
    SELECT DISTINCT ON (request.id)
           request.id,
           manifest.global_generation,
           COALESCE(manifest.completed_at, request.completed_at)
               AS ready_at
    FROM graph_rebuild_requests request
    JOIN global_graph_manifests manifest
      ON manifest.source_graph_generation =
            request.candidate_graph_generation
     AND manifest.status IN ('READY', 'ACTIVE', 'RETIRED', 'DELETED')
    WHERE request.state = 'FULFILLED'
      AND request.global_rebuild_required
    ORDER BY request.id, manifest.global_generation DESC
)
UPDATE graph_rebuild_requests request
SET candidate_global_generation = completed_global.global_generation,
    global_ready_at = completed_global.ready_at,
    completed_at = completed_global.ready_at
FROM completed_global
WHERE request.id = completed_global.id;

UPDATE graph_rebuild_requests
SET state = 'GRAPH_READY',
    completed_at = NULL
WHERE state = 'FULFILLED'
  AND global_rebuild_required
  AND candidate_global_generation IS NULL;

UPDATE graph_rebuild_requests
SET candidate_graph_generation = NULL,
    candidate_global_generation = NULL,
    graph_ready_at = NULL,
    global_ready_at = NULL,
    completed_at = COALESCE(completed_at, requested_at)
WHERE state = 'SUPERSEDED';

ALTER TABLE graph_rebuild_requests
    ADD CONSTRAINT ck_graph_rebuild_requests_state CHECK (
        state IN (
            'REQUESTED',
            'GRAPH_BUILDING',
            'GRAPH_READY',
            'GLOBAL_BUILDING',
            'FULFILLED',
            'SUPERSEDED'
        )
    ),
    ADD CONSTRAINT ck_graph_rebuild_requests_lifecycle CHECK (
        (
            state = 'REQUESTED'
            AND candidate_graph_generation IS NULL
            AND candidate_global_generation IS NULL
            AND graph_ready_at IS NULL
            AND global_ready_at IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'GRAPH_BUILDING'
            AND candidate_graph_generation IS NOT NULL
            AND candidate_global_generation IS NULL
            AND graph_ready_at IS NULL
            AND global_ready_at IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'GRAPH_READY'
            AND global_rebuild_required
            AND candidate_graph_generation IS NOT NULL
            AND candidate_global_generation IS NULL
            AND graph_ready_at IS NOT NULL
            AND global_ready_at IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'GLOBAL_BUILDING'
            AND global_rebuild_required
            AND candidate_graph_generation IS NOT NULL
            AND candidate_global_generation IS NOT NULL
            AND graph_ready_at IS NOT NULL
            AND global_ready_at IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'FULFILLED'
            AND candidate_graph_generation IS NOT NULL
            AND graph_ready_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND (
                (
                    NOT global_rebuild_required
                    AND candidate_global_generation IS NULL
                    AND global_ready_at IS NULL
                )
                OR
                (
                    global_rebuild_required
                    AND candidate_global_generation IS NOT NULL
                    AND global_ready_at IS NOT NULL
                )
            )
        )
        OR
        (
            state = 'SUPERSEDED'
            AND candidate_graph_generation IS NULL
            AND candidate_global_generation IS NULL
            AND graph_ready_at IS NULL
            AND global_ready_at IS NULL
            AND completed_at IS NOT NULL
        )
    );

CREATE INDEX ix_graph_rebuild_requests_global_pending
    ON graph_rebuild_requests (state, requested_at, id)
    WHERE global_rebuild_required
      AND state IN ('GRAPH_READY', 'GLOBAL_BUILDING');
