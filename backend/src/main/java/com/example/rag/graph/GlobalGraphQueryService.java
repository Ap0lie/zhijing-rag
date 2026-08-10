package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.graph.GlobalGraphContracts.ReportClaimView;
import com.example.rag.graph.GlobalGraphContracts.ReportDetail;
import com.example.rag.graph.GlobalGraphContracts.ReportEvidenceView;
import com.example.rag.graph.GlobalGraphContracts.ReportPage;
import com.example.rag.graph.GlobalGraphContracts.ReportSummary;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GlobalGraphQueryService {

    private final JdbcTemplate jdbc;
    private final GraphQueryService graphQueries;
    private final GlobalGraphRepository repository;

    GlobalGraphQueryService(
            JdbcTemplate jdbc,
            GraphQueryService graphQueries,
            GlobalGraphRepository repository
    ) {
        this.jdbc = jdbc;
        this.graphQueries = graphQueries;
        this.repository = repository;
    }

    void requireAdmin(PlatformUserPrincipal user) {
        graphQueries.requireAdmin(user);
    }

    void requireAdminForUpdate(PlatformUserPrincipal user) {
        graphQueries.requireAdminForUpdate(user);
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    ReportPage reports(
            PlatformUserPrincipal user,
            Long requestedGeneration,
            int page,
            int size
    ) {
        requireAdmin(user);
        long generation = generation(requestedGeneration);
        Long totalValue = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM global_community_reports report
                WHERE report.global_generation = ?
                  AND
                """ + validReportSql("report") ,
                Long.class,
                generation
        );
        long total = totalValue == null ? 0 : totalValue;
        List<ReportSummary> items = jdbc.query(
                """
                SELECT report.id, report.global_generation,
                       report.community_key, report.title,
                       report.summary, report.token_count,
                       count(DISTINCT claim.id) AS claim_count,
                       count(evidence.id) AS evidence_count
                FROM global_community_reports report
                JOIN global_report_claims claim
                  ON claim.report_id = report.id
                 AND claim.global_generation =
                     report.global_generation
                JOIN global_report_evidence evidence
                  ON evidence.claim_id = claim.id
                 AND evidence.global_generation =
                     claim.global_generation
                WHERE report.global_generation = ?
                  AND
                """ + validReportSql("report") + """
                GROUP BY report.id, report.global_generation,
                         report.community_key, report.title,
                         report.summary, report.token_count
                ORDER BY report.community_key, report.id
                LIMIT ? OFFSET ?
                """,
                (resultSet, rowNumber) -> summary(resultSet),
                generation,
                size,
                page * size
        );
        return new ReportPage(
                generation,
                page,
                size,
                total,
                items
        );
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    ReportDetail report(
            PlatformUserPrincipal user,
            Long requestedGeneration,
            UUID reportId
    ) {
        requireAdmin(user);
        long generation = generation(requestedGeneration);
        ReportSummary summary = jdbc.query(
                """
                SELECT report.id, report.global_generation,
                       report.community_key, report.title,
                       report.summary, report.token_count,
                       count(DISTINCT claim.id) AS claim_count,
                       count(evidence.id) AS evidence_count
                FROM global_community_reports report
                JOIN global_report_claims claim
                  ON claim.report_id = report.id
                 AND claim.global_generation =
                     report.global_generation
                JOIN global_report_evidence evidence
                  ON evidence.claim_id = claim.id
                 AND evidence.global_generation =
                     claim.global_generation
                WHERE report.global_generation = ?
                  AND report.id = ?
                  AND
                """ + validReportSql("report") + """
                GROUP BY report.id, report.global_generation,
                         report.community_key, report.title,
                         report.summary, report.token_count
                """,
                (resultSet, rowNumber) -> summary(resultSet),
                generation,
                reportId
        ).stream().findFirst().orElseThrow(
                GlobalGraphQueryService::notFound
        );
        List<ClaimEvidenceRow> rows = jdbc.query(
                """
                SELECT claim.id AS claim_id,
                       claim.claim_order, claim.claim_text,
                       evidence.id AS evidence_id,
                       evidence.document_id,
                       source.document_title,
                       evidence.revision_id,
                       revision.revision_number,
                       evidence.child_chunk_id,
                       evidence.source_span_id,
                       evidence.evidence_text,
                       revision.document_format,
                       location.locator_kind,
                       location.start_source_unit_id,
                       location.end_source_unit_id,
                       location.start_offset,
                       location.end_offset,
                       location.address::text AS locator_address,
                       location.source_text_hash,
                       location.normalization_version,
                       location.start_page,
                       location.end_page,
                       location.source_label
                FROM global_report_claims claim
                JOIN global_community_reports report
                  ON report.id = claim.report_id
                 AND report.global_generation =
                     claim.global_generation
                JOIN global_report_evidence evidence
                  ON evidence.claim_id = claim.id
                 AND evidence.global_generation =
                     claim.global_generation
                JOIN global_graph_sources source
                  ON source.global_generation =
                     evidence.global_generation
                 AND source.document_id = evidence.document_id
                 AND source.revision_id = evidence.revision_id
                 AND source.acl_version = evidence.acl_version
                JOIN document_revisions revision
                  ON revision.id = evidence.revision_id
                 AND revision.document_id = evidence.document_id
                JOIN source_spans span
                  ON span.id = evidence.source_span_id
                 AND span.chunk_id = evidence.child_chunk_id
                 AND span.document_id = evidence.document_id
                 AND span.revision_id = evidence.revision_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                WHERE claim.global_generation = ?
                  AND claim.report_id = ?
                  AND
                """ + validReportSql("report") + """
                ORDER BY claim.claim_order, claim.id,
                         evidence.document_id,
                         evidence.child_chunk_id,
                         evidence.source_span_id
                """,
                (resultSet, rowNumber) -> evidence(resultSet),
                generation,
                reportId
        );
        if (rows.isEmpty()) {
            throw notFound();
        }
        Map<UUID, MutableClaim> claims = new LinkedHashMap<>();
        rows.forEach(row -> claims.computeIfAbsent(
                row.claimId(),
                ignored -> new MutableClaim(
                        row.claimId(),
                        row.claimOrder(),
                        row.claimText(),
                        new ArrayList<>()
                )
        ).evidence().add(row.evidence()));
        return new ReportDetail(
                summary,
                claims.values().stream()
                        .map(MutableClaim::freeze)
                        .toList()
        );
    }

    private long generation(Long requested) {
        if (requested != null) {
            repository.manifest(requested);
            return requested;
        }
        Long active = repository.activeGeneration();
        if (active == null) {
            throw notFound();
        }
        return active;
    }

    static String validReportSql(String alias) {
        return """
                EXISTS (
                    SELECT 1
                    FROM global_graph_manifests global_manifest
                    JOIN graph_publications graph_publication
                      ON graph_publication.singleton_id = 1
                     AND graph_publication.graph_generation =
                         global_manifest.source_graph_generation
                    JOIN graph_manifests source_manifest
                      ON source_manifest.graph_generation =
                         global_manifest.source_graph_generation
                     AND source_manifest.status = 'ACTIVE'
                    WHERE global_manifest.global_generation =
                          %s.global_generation
                )
                AND (
                    SELECT count(*)
                    FROM global_report_evidence expected
                    WHERE expected.global_generation =
                          %s.global_generation
                      AND expected.report_id = %s.id
                ) = %s.expected_evidence_count
                AND NOT EXISTS (
                    SELECT 1
                    FROM global_report_evidence current_evidence
                    LEFT JOIN global_graph_sources source
                      ON source.global_generation =
                         current_evidence.global_generation
                     AND source.source_graph_generation =
                         current_evidence.source_graph_generation
                     AND source.document_id =
                         current_evidence.document_id
                     AND source.revision_id =
                         current_evidence.revision_id
                     AND source.acl_version =
                         current_evidence.acl_version
                    LEFT JOIN documents document
                      ON document.id = current_evidence.document_id
                    LEFT JOIN document_revisions revision
                      ON revision.id = current_evidence.revision_id
                     AND revision.document_id =
                         current_evidence.document_id
                    LEFT JOIN chunks child
                      ON child.id =
                         current_evidence.child_chunk_id
                     AND child.document_id =
                         current_evidence.document_id
                     AND child.revision_id =
                         current_evidence.revision_id
                    LEFT JOIN source_spans span
                      ON span.id =
                         current_evidence.source_span_id
                     AND span.chunk_id =
                         current_evidence.child_chunk_id
                     AND span.document_id =
                         current_evidence.document_id
                     AND span.revision_id =
                         current_evidence.revision_id
                    LEFT JOIN source_units start_unit
                      ON start_unit.id =
                         span.start_source_unit_id
                     AND start_unit.document_id =
                         current_evidence.document_id
                     AND start_unit.revision_id =
                         current_evidence.revision_id
                     AND start_unit.normalization_version =
                         span.normalization_version
                    LEFT JOIN source_units end_unit
                      ON end_unit.id =
                         span.end_source_unit_id
                     AND end_unit.document_id =
                         current_evidence.document_id
                     AND end_unit.revision_id =
                         current_evidence.revision_id
                     AND end_unit.normalization_version =
                         span.normalization_version
                    LEFT JOIN graph_relationship_evidence
                              relationship_evidence
                      ON relationship_evidence.id =
                         current_evidence.relationship_evidence_id
                     AND relationship_evidence.relationship_id =
                         current_evidence.relationship_id
                     AND relationship_evidence.graph_generation =
                         current_evidence.source_graph_generation
                    LEFT JOIN graph_projection_states projection
                      ON projection.graph_generation =
                         current_evidence.source_graph_generation
                     AND projection.document_id =
                         current_evidence.document_id
                     AND projection.revision_id =
                         current_evidence.revision_id
                     AND projection.acl_version =
                         current_evidence.acl_version
                     AND projection.state = 'PROJECTED'
                    WHERE current_evidence.global_generation =
                          %s.global_generation
                      AND current_evidence.report_id = %s.id
                      AND (
                        source.document_id IS NULL
                        OR document.id IS NULL
                        OR document.deleted_at IS NOT NULL
                        OR document.visibility <> 'ALL_USERS'
                        OR document.current_revision_id IS DISTINCT FROM
                           current_evidence.revision_id
                        OR document.acl_version IS DISTINCT FROM
                           current_evidence.acl_version
                        OR revision.id IS NULL
                        OR revision.status <> 'READY'
                        OR child.id IS NULL
                        OR child.chunk_type <> 'CHILD'
                        OR child.searchable IS DISTINCT FROM TRUE
                        OR span.id IS NULL
                        OR start_unit.id IS NULL
                        OR end_unit.id IS NULL
                        OR relationship_evidence.id IS NULL
                        OR relationship_evidence.document_id
                           IS DISTINCT FROM
                           current_evidence.document_id
                        OR relationship_evidence.revision_id
                           IS DISTINCT FROM
                           current_evidence.revision_id
                        OR relationship_evidence.child_chunk_id
                           IS DISTINCT FROM
                           current_evidence.child_chunk_id
                        OR relationship_evidence.source_span_id
                           IS DISTINCT FROM
                           current_evidence.source_span_id
                        OR relationship_evidence.evidence_text_hash
                           IS DISTINCT FROM
                           current_evidence.evidence_text_hash
                        OR relationship_evidence.evidence_text
                           IS DISTINCT FROM
                           current_evidence.evidence_text
                        OR projection.document_id IS NULL
                      )
                )
                """.formatted(alias, alias, alias, alias, alias, alias);
    }

    private static ReportSummary summary(ResultSet resultSet)
            throws SQLException {
        return new ReportSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("global_generation"),
                resultSet.getInt("community_key"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                resultSet.getInt("token_count"),
                resultSet.getInt("claim_count"),
                resultSet.getInt("evidence_count")
        );
    }

    private static ClaimEvidenceRow evidence(ResultSet resultSet)
            throws SQLException {
        SourceLocatorResponse locator = new SourceLocatorResponse(
                resultSet.getString("locator_kind"),
                resultSet.getObject(
                        "start_source_unit_id", UUID.class
                ),
                resultSet.getObject(
                        "end_source_unit_id", UUID.class
                ),
                resultSet.getInt("start_offset"),
                resultSet.getInt("end_offset"),
                resultSet.getString("locator_address"),
                resultSet.getString("source_text_hash"),
                resultSet.getString("normalization_version"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class),
                resultSet.getString("source_label")
        );
        return new ClaimEvidenceRow(
                resultSet.getObject("claim_id", UUID.class),
                resultSet.getInt("claim_order"),
                resultSet.getString("claim_text"),
                new ReportEvidenceView(
                        resultSet.getObject("evidence_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("document_title"),
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getInt("revision_number"),
                        resultSet.getObject(
                                "child_chunk_id",
                                UUID.class
                        ),
                        resultSet.getObject(
                                "source_span_id",
                                UUID.class
                        ),
                        resultSet.getString("evidence_text"),
                        resultSet.getObject(
                                "start_page", Integer.class
                        ),
                        resultSet.getObject(
                                "end_page", Integer.class
                        ),
                        resultSet.getString("document_format"),
                        locator,
                        locator.sourceLabel()
                )
        );
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "GLOBAL_REPORT_NOT_FOUND",
                "Global Community Report 不存在或 Evidence 已失效"
        );
    }

    private record ClaimEvidenceRow(
            UUID claimId,
            int claimOrder,
            String claimText,
            ReportEvidenceView evidence
    ) {
    }

    private record MutableClaim(
            UUID id,
            int order,
            String text,
            List<ReportEvidenceView> evidence
    ) {
        ReportClaimView freeze() {
            return new ReportClaimView(
                    id,
                    order,
                    text,
                    List.copyOf(evidence)
            );
        }
    }
}
