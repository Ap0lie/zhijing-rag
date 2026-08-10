package com.example.rag.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GraphRebuildRequestService {

    private final JdbcTemplate jdbc;

    public GraphRebuildRequestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void revisionPublished(UUID documentId) {
        request(documentId, "REVISION_PUBLISHED");
    }

    @Transactional
    public void aclChanged(UUID documentId) {
        request(documentId, "ACL_CHANGED");
    }

    @Transactional(readOnly = true)
    List<RebuildRequestView> requests() {
        return jdbc.query(
                """
                SELECT request.id, request.document_id, document.title,
                       request.target_revision_id, revision.revision_number,
                       request.target_acl_version, request.reason, request.state,
                       request.source_graph_generation,
                       request.source_global_generation,
                       request.global_rebuild_required,
                       request.candidate_graph_generation,
                       request.candidate_global_generation,
                       request.requested_at, request.graph_ready_at,
                       request.global_ready_at, request.completed_at
                FROM graph_rebuild_requests request
                JOIN documents document ON document.id = request.document_id
                JOIN document_revisions revision
                  ON revision.id = request.target_revision_id
                 AND revision.document_id = request.document_id
                ORDER BY request.requested_at DESC, request.id
                LIMIT 100
                """,
                (resultSet, rowNumber) -> new RebuildRequestView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getObject("target_revision_id", UUID.class),
                        resultSet.getInt("revision_number"),
                        resultSet.getLong("target_acl_version"),
                        resultSet.getString("reason"),
                        resultSet.getString("state"),
                        resultSet.getObject("source_graph_generation", Long.class),
                        resultSet.getObject("source_global_generation", Long.class),
                        resultSet.getBoolean("global_rebuild_required"),
                        resultSet.getObject("candidate_graph_generation", Long.class),
                        resultSet.getObject("candidate_global_generation", Long.class),
                        resultSet.getTimestamp("requested_at").toInstant(),
                        resultSet.getTimestamp("graph_ready_at") == null
                                ? null
                                : resultSet.getTimestamp("graph_ready_at").toInstant(),
                        resultSet.getTimestamp("global_ready_at") == null
                                ? null
                                : resultSet.getTimestamp("global_ready_at").toInstant(),
                        resultSet.getTimestamp("completed_at") == null
                                ? null
                                : resultSet.getTimestamp("completed_at").toInstant()
                )
        );
    }

    private void request(UUID documentId, String reason) {
        jdbc.update(
                """
                UPDATE graph_rebuild_requests request
                SET state = 'SUPERSEDED',
                    candidate_graph_generation = NULL,
                    candidate_global_generation = NULL,
                    graph_ready_at = NULL,
                    global_ready_at = NULL,
                    completed_at = CURRENT_TIMESTAMP
                WHERE request.document_id = ?
                  AND request.state IN (
                    'REQUESTED', 'GRAPH_BUILDING',
                    'GRAPH_READY', 'GLOBAL_BUILDING'
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM documents document
                      WHERE document.id = request.document_id
                        AND (
                            document.current_revision_id
                                IS DISTINCT FROM request.target_revision_id
                            OR document.acl_version
                                IS DISTINCT FROM request.target_acl_version
                        )
                  )
                """,
                documentId
        );
        jdbc.update(
                """
                INSERT INTO graph_rebuild_requests (
                    id, document_id, target_revision_id, target_acl_version,
                    reason, state, source_graph_generation,
                    source_global_generation, global_rebuild_required
                )
                SELECT ?, document.id, document.current_revision_id,
                       document.acl_version, ?, 'REQUESTED',
                       graph_publication.graph_generation,
                       global_publication.global_generation,
                       (
                         global_publication.global_generation IS NOT NULL
                         AND (
                           document.visibility = 'ALL_USERS'
                           OR EXISTS (
                             SELECT 1
                             FROM global_graph_sources source
                             WHERE source.global_generation =
                                     global_publication.global_generation
                               AND source.document_id = document.id
                           )
                         )
                       )
                FROM documents document
                LEFT JOIN graph_publications graph_publication
                  ON graph_publication.singleton_id = 1
                LEFT JOIN global_graph_publications global_publication
                  ON global_publication.singleton_id = 1
                WHERE document.id = ?
                  AND document.deleted_at IS NULL
                  AND document.current_revision_id IS NOT NULL
                ON CONFLICT (
                    document_id, target_revision_id, target_acl_version
                ) DO NOTHING
                """,
                UUID.randomUUID(),
                reason,
                documentId
        );
    }

    public record RebuildRequestView(
            UUID id,
            UUID documentId,
            String documentTitle,
            UUID targetRevisionId,
            int targetRevisionNumber,
            long targetAclVersion,
            String reason,
            String state,
            Long sourceGraphGeneration,
            Long sourceGlobalGeneration,
            boolean globalRebuildRequired,
            Long candidateGraphGeneration,
            Long candidateGlobalGeneration,
            Instant requestedAt,
            Instant graphReadyAt,
            Instant globalReadyAt,
            Instant completedAt
    ) {
    }
}
