package com.example.rag.projection;

import com.example.rag.common.ApiException;
import com.example.rag.projection.ProjectionClosureStatus.FormatCoverage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProjectionClosureService {

    private final JdbcTemplate jdbc;

    public ProjectionClosureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ProjectionClosureStatus index(
            long generation,
            boolean sourceLocatorCompatible
    ) {
        List<FormatCoverage> formats = coverage(
                "search_projection_states",
                "index_generation",
                "ACTIVE",
                generation
        );
        long orphaned = orphaned(
                "search_projection_states", "index_generation", "ACTIVE", generation
        );
        return status(
                sourceLocatorCompatible,
                formats,
                orphaned,
                0,
                0,
                0,
                false,
                List.of()
        );
    }

    public ProjectionClosureStatus graph(long generation) {
        List<FormatCoverage> formats = coverage(
                "graph_projection_states",
                "graph_generation",
                "PROJECTED",
                generation
        );
        long orphaned = orphaned(
                "graph_projection_states", "graph_generation", "PROJECTED", generation
        );
        long allUsers = sourceVisibilityCount(
                "graph_generation_sources", "graph_generation",
                generation, "ALL_USERS"
        );
        long restricted = sourceVisibilityCount(
                "graph_generation_sources", "graph_generation",
                generation, "RESTRICTED"
        );
        long invalidEvidence = graphInvalidEvidence(generation);
        return status(
                true,
                formats,
                orphaned,
                allUsers,
                restricted,
                invalidEvidence,
                false,
                List.of()
        );
    }

    public ProjectionClosureStatus global(long generation) {
        Long sourceGraphGeneration = jdbc.query(
                """
                SELECT source_graph_generation
                FROM global_graph_manifests
                WHERE global_generation = ?
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                generation
        );
        List<FormatCoverage> formats = globalCoverage(generation);
        long orphaned = globalOrphaned(generation);
        long allUsers = sourceVisibilityCount(
                "global_graph_sources", "global_generation",
                generation, "ALL_USERS"
        );
        long restricted = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM global_graph_sources source
                LEFT JOIN documents document ON document.id = source.document_id
                WHERE source.global_generation = ?
                  AND (document.id IS NULL OR document.visibility <> 'ALL_USERS')
                """,
                Long.class,
                generation
        );
        long invalidEvidence = globalInvalidEvidence(generation);
        boolean sourceGraphActive = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM global_graph_manifests manifest
                    JOIN graph_publications publication
                      ON publication.singleton_id = 1
                     AND publication.graph_generation = manifest.source_graph_generation
                    JOIN graph_manifests graph_manifest
                      ON graph_manifest.graph_generation = manifest.source_graph_generation
                     AND graph_manifest.status = 'ACTIVE'
                    WHERE manifest.global_generation = ?
                )
                """,
                Boolean.class,
                generation
        ));
        List<String> extraBlockers = new ArrayList<>();
        if (!sourceGraphActive) {
            extraBlockers.add("SOURCE_GRAPH_NOT_ACTIVE");
        } else if (sourceGraphGeneration == null
                || !graph(sourceGraphGeneration).ready()) {
            extraBlockers.add("SOURCE_GRAPH_STALE");
        }
        return status(
                true,
                formats,
                orphaned,
                allUsers,
                restricted,
                invalidEvidence,
                true,
                extraBlockers
        );
    }

    public void requireIndex(long generation, boolean compatible) {
        requireReady(
                index(generation, compatible),
                "INDEX_GENERATION_CLOSURE_INCOMPLETE",
                "Index Generation 的 Revision、ACL、格式或 Locator 闭包未完成"
        );
    }

    public void requireGraph(long generation) {
        requireReady(
                graph(generation),
                "GRAPH_GENERATION_CLOSURE_INCOMPLETE",
                "Graph Generation 的 Revision、ACL、Locator 或 Evidence 闭包未完成"
        );
    }

    public void requireGlobal(long generation) {
        requireReady(
                global(generation),
                "GLOBAL_GENERATION_CLOSURE_INCOMPLETE",
                "Global Generation 的公共来源、Locator 或 Evidence 闭包未完成"
        );
    }

    private void requireReady(
            ProjectionClosureStatus closure,
            String code,
            String message
    ) {
        if (!closure.ready()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    code,
                    message + "：" + String.join(", ", closure.blockers())
            );
        }
    }

    private List<FormatCoverage> coverage(
            String stateTable,
            String generationColumn,
            String projectedState,
            long generation
    ) {
        String sql = """
                WITH current_documents AS (
                    SELECT document.id AS document_id,
                           document.current_revision_id AS revision_id,
                           document.acl_version,
                           revision.document_format
                    FROM documents document
                    JOIN document_revisions revision
                      ON revision.id = document.current_revision_id
                     AND revision.document_id = document.id
                     AND revision.status = 'READY'
                    WHERE document.deleted_at IS NULL
                ), document_closure AS (
                    SELECT current.*,
                           state.document_id IS NOT NULL AS projected,
                           EXISTS (
                               SELECT 1 FROM chunks child
                               WHERE child.revision_id = current.revision_id
                                 AND child.document_id = current.document_id
                                 AND child.chunk_type = 'CHILD'
                                 AND child.searchable
                           ) AND NOT EXISTS (
                               SELECT 1
                               FROM chunks child
                               LEFT JOIN source_spans span
                                 ON span.chunk_id = child.id
                                AND span.document_id = child.document_id
                                AND span.revision_id = child.revision_id
                               LEFT JOIN source_locator_projection locator
                                 ON locator.source_kind = 'SOURCE_SPAN'
                                AND locator.source_id = span.id
                                AND locator.document_id = child.document_id
                                AND locator.revision_id = child.revision_id
                               WHERE child.revision_id = current.revision_id
                                 AND child.document_id = current.document_id
                                 AND child.chunk_type = 'CHILD'
                                 AND child.searchable
                                 AND locator.source_id IS NULL
                           ) AS locator_ready
                    FROM current_documents current
                    LEFT JOIN %s state
                      ON state.%s = ?
                     AND state.document_id = current.document_id
                     AND state.revision_id = current.revision_id
                     AND state.acl_version = current.acl_version
                     AND state.state = ?
                )
                SELECT document_format,
                       count(*) AS expected_count,
                       count(*) FILTER (WHERE projected) AS projected_count,
                       count(*) FILTER (WHERE locator_ready) AS locator_count,
                       count(*) FILTER (WHERE NOT projected) AS stale_count
                FROM document_closure
                GROUP BY document_format
                ORDER BY document_format
                """.formatted(stateTable, generationColumn);
        return jdbc.query(
                sql,
                (rs, rowNumber) -> new FormatCoverage(
                        rs.getString("document_format"),
                        rs.getLong("expected_count"),
                        rs.getLong("projected_count"),
                        rs.getLong("locator_count"),
                        rs.getLong("stale_count")
                ),
                generation,
                projectedState
        );
    }

    private long orphaned(
            String stateTable,
            String generationColumn,
            String projectedState,
            long generation
    ) {
        String sql = """
                SELECT count(*)
                FROM %s state
                LEFT JOIN documents document
                  ON document.id = state.document_id
                 AND document.deleted_at IS NULL
                 AND document.current_revision_id = state.revision_id
                 AND document.acl_version = state.acl_version
                LEFT JOIN document_revisions revision
                  ON revision.id = state.revision_id
                 AND revision.document_id = state.document_id
                 AND revision.status = 'READY'
                WHERE state.%s = ?
                  AND state.state = ?
                  AND (document.id IS NULL OR revision.id IS NULL)
                """.formatted(stateTable, generationColumn);
        return jdbc.queryForObject(sql, Long.class, generation, projectedState);
    }

    private List<FormatCoverage> globalCoverage(long generation) {
        return jdbc.query(
                """
                WITH expected AS (
                    SELECT source.document_id, source.revision_id,
                           source.acl_version, revision.document_format
                    FROM global_graph_manifests manifest
                    JOIN graph_generation_sources source
                      ON source.graph_generation = manifest.source_graph_generation
                    JOIN documents document
                      ON document.id = source.document_id
                     AND document.deleted_at IS NULL
                     AND document.visibility = 'ALL_USERS'
                     AND document.current_revision_id = source.revision_id
                     AND document.acl_version = source.acl_version
                    JOIN document_revisions revision
                      ON revision.id = source.revision_id
                     AND revision.document_id = source.document_id
                     AND revision.status = 'READY'
                    JOIN graph_projection_states projection
                      ON projection.graph_generation = source.graph_generation
                     AND projection.document_id = source.document_id
                     AND projection.revision_id = source.revision_id
                     AND projection.acl_version = source.acl_version
                     AND projection.state = 'PROJECTED'
                    WHERE manifest.global_generation = ?
                ), document_closure AS (
                    SELECT expected.*,
                           source.document_id IS NOT NULL AS projected,
                           EXISTS (
                               SELECT 1 FROM chunks child
                               WHERE child.document_id = expected.document_id
                                 AND child.revision_id = expected.revision_id
                                 AND child.chunk_type = 'CHILD' AND child.searchable
                           ) AND NOT EXISTS (
                               SELECT 1
                               FROM chunks child
                               LEFT JOIN source_spans span
                                 ON span.chunk_id = child.id
                                AND span.document_id = child.document_id
                                AND span.revision_id = child.revision_id
                               LEFT JOIN source_locator_projection locator
                                 ON locator.source_kind = 'SOURCE_SPAN'
                                AND locator.source_id = span.id
                                AND locator.document_id = child.document_id
                                AND locator.revision_id = child.revision_id
                               WHERE child.document_id = expected.document_id
                                 AND child.revision_id = expected.revision_id
                                 AND child.chunk_type = 'CHILD' AND child.searchable
                                 AND locator.source_id IS NULL
                           ) AS locator_ready
                    FROM expected
                    LEFT JOIN global_graph_sources source
                      ON source.global_generation = ?
                     AND source.document_id = expected.document_id
                     AND source.revision_id = expected.revision_id
                     AND source.acl_version = expected.acl_version
                )
                SELECT document_format,
                       count(*) AS expected_count,
                       count(*) FILTER (WHERE projected) AS projected_count,
                       count(*) FILTER (WHERE locator_ready) AS locator_count,
                       count(*) FILTER (WHERE NOT projected) AS stale_count
                FROM document_closure
                GROUP BY document_format
                ORDER BY document_format
                """,
                (rs, rowNumber) -> new FormatCoverage(
                        rs.getString("document_format"),
                        rs.getLong("expected_count"),
                        rs.getLong("projected_count"),
                        rs.getLong("locator_count"),
                        rs.getLong("stale_count")
                ),
                generation,
                generation
        );
    }

    private long globalOrphaned(long generation) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM global_graph_sources source
                LEFT JOIN documents document
                  ON document.id = source.document_id
                 AND document.deleted_at IS NULL
                 AND document.visibility = 'ALL_USERS'
                 AND document.current_revision_id = source.revision_id
                 AND document.acl_version = source.acl_version
                LEFT JOIN document_revisions revision
                  ON revision.id = source.revision_id
                 AND revision.document_id = source.document_id
                 AND revision.status = 'READY'
                LEFT JOIN graph_projection_states projection
                  ON projection.graph_generation = source.source_graph_generation
                 AND projection.document_id = source.document_id
                 AND projection.revision_id = source.revision_id
                 AND projection.acl_version = source.acl_version
                 AND projection.state = 'PROJECTED'
                WHERE source.global_generation = ?
                  AND (document.id IS NULL OR revision.id IS NULL
                       OR projection.document_id IS NULL)
                """,
                Long.class,
                generation
        );
    }

    private long sourceVisibilityCount(
            String table,
            String generationColumn,
            long generation,
            String visibility
    ) {
        String sql = """
                SELECT count(*)
                FROM %s source
                JOIN documents document ON document.id = source.document_id
                WHERE source.%s = ? AND document.visibility = ?
                """.formatted(table, generationColumn);
        return jdbc.queryForObject(sql, Long.class, generation, visibility);
    }

    private long graphInvalidEvidence(long generation) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM (
                    SELECT mention.id
                    FROM graph_entity_mentions mention
                    LEFT JOIN source_locator_projection locator
                      ON locator.source_kind = 'SOURCE_SPAN'
                     AND locator.source_id = mention.source_span_id
                     AND locator.document_id = mention.document_id
                     AND locator.revision_id = mention.revision_id
                    LEFT JOIN documents document
                      ON document.id = mention.document_id
                     AND document.deleted_at IS NULL
                     AND document.current_revision_id = mention.revision_id
                    LEFT JOIN graph_generation_sources source
                      ON source.graph_generation = mention.graph_generation
                     AND source.document_id = mention.document_id
                     AND source.revision_id = mention.revision_id
                     AND source.acl_version = document.acl_version
                    WHERE mention.graph_generation = ?
                      AND (locator.source_id IS NULL OR document.id IS NULL
                           OR source.document_id IS NULL)
                    UNION ALL
                    SELECT evidence.id
                    FROM graph_relationship_evidence evidence
                    LEFT JOIN source_locator_projection locator
                      ON locator.source_kind = 'SOURCE_SPAN'
                     AND locator.source_id = evidence.source_span_id
                     AND locator.document_id = evidence.document_id
                     AND locator.revision_id = evidence.revision_id
                    LEFT JOIN documents document
                      ON document.id = evidence.document_id
                     AND document.deleted_at IS NULL
                     AND document.current_revision_id = evidence.revision_id
                    LEFT JOIN graph_generation_sources source
                      ON source.graph_generation = evidence.graph_generation
                     AND source.document_id = evidence.document_id
                     AND source.revision_id = evidence.revision_id
                     AND source.acl_version = document.acl_version
                    WHERE evidence.graph_generation = ?
                      AND (locator.source_id IS NULL OR document.id IS NULL
                           OR source.document_id IS NULL)
                ) invalid
                """,
                Long.class,
                generation,
                generation
        );
    }

    private long globalInvalidEvidence(long generation) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM global_report_evidence evidence
                LEFT JOIN global_graph_sources source
                  ON source.global_generation = evidence.global_generation
                 AND source.document_id = evidence.document_id
                 AND source.revision_id = evidence.revision_id
                 AND source.acl_version = evidence.acl_version
                LEFT JOIN documents document
                  ON document.id = evidence.document_id
                 AND document.deleted_at IS NULL
                 AND document.visibility = 'ALL_USERS'
                 AND document.current_revision_id = evidence.revision_id
                 AND document.acl_version = evidence.acl_version
                LEFT JOIN source_locator_projection locator
                  ON locator.source_kind = 'SOURCE_SPAN'
                 AND locator.source_id = evidence.source_span_id
                 AND locator.document_id = evidence.document_id
                 AND locator.revision_id = evidence.revision_id
                WHERE evidence.global_generation = ?
                  AND (source.document_id IS NULL OR document.id IS NULL
                       OR locator.source_id IS NULL)
                """,
                Long.class,
                generation
        );
    }

    private ProjectionClosureStatus status(
            boolean compatible,
            List<FormatCoverage> formats,
            long orphaned,
            long allUsers,
            long restricted,
            long invalidEvidence,
            boolean restrictedIsBlocker,
            List<String> extraBlockers
    ) {
        long expected = formats.stream().mapToLong(
                FormatCoverage::expectedDocumentCount
        ).sum();
        long projected = formats.stream().mapToLong(
                FormatCoverage::projectedDocumentCount
        ).sum();
        long locatorReady = formats.stream().mapToLong(
                FormatCoverage::locatorReadyDocumentCount
        ).sum();
        long stale = formats.stream().mapToLong(
                FormatCoverage::staleDocumentCount
        ).sum();
        long missingLocator = Math.max(0, expected - locatorReady);
        List<String> blockers = new ArrayList<>(extraBlockers);
        if (!compatible) {
            blockers.add("SOURCE_LOCATOR_SCHEMA_REQUIRED");
        }
        if (stale > 0) {
            blockers.add("STALE_PROJECTION");
        }
        if (orphaned > 0) {
            blockers.add("ORPHANED_PROJECTION");
        }
        if (missingLocator > 0) {
            blockers.add("LOCATOR_CLOSURE_INCOMPLETE");
        }
        if (restrictedIsBlocker && restricted > 0) {
            blockers.add("RESTRICTED_SOURCE_PRESENT");
        }
        if (invalidEvidence > 0) {
            blockers.add("EVIDENCE_CLOSURE_INCOMPLETE");
        }
        return new ProjectionClosureStatus(
                compatible,
                blockers.isEmpty(),
                expected,
                projected,
                locatorReady,
                stale,
                missingLocator,
                orphaned,
                allUsers,
                restricted,
                invalidEvidence,
                formats,
                blockers
        );
    }
}
