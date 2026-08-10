package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.graph.GlobalGraphContracts.GlobalConfig;
import com.example.rag.graph.GlobalGraphContracts.ManifestRow;
import com.example.rag.search.GlobalReportIndexService;
import com.example.rag.search.GlobalReportIndexService.RankedReport;
import com.example.rag.search.GlobalReportIndexService.SearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ConditionalOnProperty(
        prefix = "rag.search",
        name = "enabled",
        havingValue = "true"
)
public class GlobalGraphRetrievalService {

    private final NamedParameterJdbcTemplate jdbc;
    private final GlobalGraphRepository repository;
    private final GlobalReportIndexService indexes;
    private final TransactionTemplate authorizationTransactions;

    GlobalGraphRetrievalService(
            NamedParameterJdbcTemplate jdbc,
            GlobalGraphRepository repository,
            GlobalReportIndexService indexes,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.indexes = indexes;
        this.authorizationTransactions =
                new TransactionTemplate(transactionManager);
        this.authorizationTransactions.setReadOnly(true);
        this.authorizationTransactions.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ
        );
    }

    public Expansion expand(
            String query,
            List<UUID> authorizedDocumentIds
    ) {
        return expand(query, authorizedDocumentIds, null);
    }

    public Expansion expand(
            String query,
            List<UUID> authorizedDocumentIds,
            Long requestedGeneration
    ) {
        long started = System.nanoTime();
        ActiveGlobal active = requestedGeneration == null
                ? active()
                : target(requestedGeneration);
        if (active == null) {
            return Expansion.unavailable(
                    null,
                    null,
                    null,
                    "GLOBAL_NOT_PUBLISHED",
                    elapsed(started)
            );
        }
        RetrievalConfig config = RetrievalConfig.from(active.config());
        List<UUID> authorized = distinct(authorizedDocumentIds);
        if (authorized.isEmpty()) {
            return Expansion.unavailable(
                    config,
                    active.manifest().generation(),
                    active.manifest().sourceGraphGeneration(),
                    "GLOBAL_NO_AUTHORIZED_SOURCE",
                    elapsed(started)
            );
        }
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            return Expansion.unavailable(
                    config,
                    active.manifest().generation(),
                    active.manifest().sourceGraphGeneration(),
                    "GLOBAL_QUERY_REQUIRED",
                    elapsed(started)
            );
        }

        SearchResult result;
        try {
            result = indexes.search(
                    active.manifest().indexName(),
                    active.config().indexConfigVersion(),
                    normalizedQuery,
                    active.config().bm25TopK(),
                    active.config().vectorTopK(),
                    active.config().rrfRankConstant()
            );
        } catch (RuntimeException exception) {
            return Expansion.unavailable(
                    config,
                    active.manifest().generation(),
                    active.manifest().sourceGraphGeneration(),
                    "GLOBAL_INDEX_UNAVAILABLE",
                    elapsed(started)
            );
        }
        if (result.reports().isEmpty()) {
            return Expansion.unavailable(
                    config,
                    active.manifest().generation(),
                    active.manifest().sourceGraphGeneration(),
                    result.degradationCode() == null
                            ? "GLOBAL_NO_REPORT_MATCH"
                            : result.degradationCode(),
                    elapsed(started)
            );
        }

        List<GlobalCandidate> candidates;
        try {
            candidates = authorizationTransactions.execute(status ->
                    loadCandidates(
                            active,
                            result.reports(),
                            authorized
                    )
            );
        } catch (DataAccessException exception) {
            throw authorizationUnavailable(exception);
        }
        if (candidates == null || candidates.isEmpty()) {
            return Expansion.unavailable(
                    config,
                    active.manifest().generation(),
                    active.manifest().sourceGraphGeneration(),
                    "GLOBAL_NO_VALID_REPORT",
                    elapsed(started)
            );
        }
        return new Expansion(
                config,
                active.manifest().generation(),
                active.manifest().sourceGraphGeneration(),
                candidates,
                result.degradationCode(),
                elapsed(started)
        );
    }

    private ActiveGlobal active() {
        try {
            Long generation = repository.activeGeneration();
            if (generation == null) {
                return null;
            }
            ManifestRow manifest = repository.manifest(generation);
            GlobalConfig config = repository.config(
                    manifest.configVersion()
            );
            return new ActiveGlobal(manifest, config);
        } catch (DataAccessException exception) {
            throw authorizationUnavailable(exception);
        }
    }

    private ActiveGlobal target(long generation) {
        try {
            ManifestRow manifest = repository.manifest(generation);
            if (!Set.of("READY", "ACTIVE", "RETIRED").contains(
                    manifest.status()
            )) {
                return null;
            }
            GlobalConfig config = repository.config(
                    manifest.configVersion()
            );
            return new ActiveGlobal(manifest, config);
        } catch (DataAccessException exception) {
            throw authorizationUnavailable(exception);
        } catch (ApiException exception) {
            return null;
        }
    }

    private List<GlobalCandidate> loadCandidates(
            ActiveGlobal active,
            List<RankedReport> ranked,
            List<UUID> authorizedDocumentIds
    ) {
        setStatementTimeout(active.config().statementTimeoutMs());
        Map<UUID, RankedReport> rankByReport = new LinkedHashMap<>();
        for (RankedReport item : ranked) {
            rankByReport.putIfAbsent(item.reportId(), item);
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue(
                        "generation",
                        active.manifest().generation()
                )
                .addValue("reportIds", rankByReport.keySet())
                .addValue(
                        "authorizedDocumentIds",
                        authorizedDocumentIds
                );
        List<CandidateRow> rows = jdbc.query(
                candidateSql(),
                parameters,
                (resultSet, rowNumber) -> row(resultSet)
        );

        Map<UUID, MutableReport> reports = new LinkedHashMap<>();
        for (CandidateRow row : rows) {
            RankedReport rank = rankByReport.get(row.reportId());
            if (rank == null) {
                continue;
            }
            MutableReport report = reports.computeIfAbsent(
                    row.reportId(),
                    ignored -> new MutableReport(
                            row.reportId(),
                            row.communityKey(),
                            row.title(),
                            row.summary(),
                            row.tokenCount(),
                            new LinkedHashMap<>()
                    )
            );
            MutableClaim claim = report.claims().computeIfAbsent(
                    row.claimId(),
                    ignored -> new MutableClaim(
                            row.claimId(),
                            row.claimOrder(),
                            row.claimText(),
                            new ArrayList<>()
                    )
            );
            claim.evidence().add(row.evidence());
        }

        Map<UUID, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < ranked.size(); index++) {
            order.putIfAbsent(ranked.get(index).reportId(), index + 1);
        }
        return reports.values().stream()
                .map(report -> report.freeze(
                        order.get(report.id()),
                        rankByReport.get(report.id())
                ))
                .sorted(Comparator
                        .comparingInt(GlobalCandidate::rank)
                        .thenComparing(item ->
                                item.reportId().toString()))
                .limit(active.config().reportLimit())
                .toList();
    }

    static String candidateSql() {
        return """
                SELECT report.id AS report_id,
                       report.community_key,
                       report.title,
                       report.summary,
                       report.token_count,
                       claim.id AS claim_id,
                       claim.claim_order,
                       claim.claim_text,
                       evidence.id AS evidence_id,
                       evidence.document_id,
                       source.document_title,
                       evidence.revision_id,
                       revision.revision_number,
                       evidence.child_chunk_id,
                       evidence.source_span_id,
                       span.source_text_hash,
                       evidence.evidence_text,
                       revision.document_format,
                       span.locator_kind,
                       span.start_source_unit_id,
                       span.end_source_unit_id,
                       span.start_offset,
                       span.end_offset,
                       span.locator_address::text AS locator_address,
                       span.normalization_version,
                       CASE
                         WHEN span.locator_kind = 'PAGE'
                           THEN start_unit.unit_order
                         ELSE NULL
                       END AS start_page,
                       CASE
                         WHEN span.locator_kind = 'PAGE'
                           THEN end_unit.unit_order
                         ELSE NULL
                       END AS end_page,
                       (CASE
                         WHEN span.locator_kind = 'PAGE'
                              AND start_unit.id = end_unit.id
                           THEN COALESCE(
                             start_unit.label_metadata ->> 'sourceLabel',
                             '第 ' || start_unit.unit_order || ' 页'
                           )
                         WHEN span.locator_kind = 'PAGE'
                           THEN '第 ' || start_unit.unit_order
                                || '–' || end_unit.unit_order || ' 页'
                         ELSE COALESCE(
                           start_unit.label_metadata ->> 'sourceLabel',
                           start_unit.stable_address
                         )
                       END)::VARCHAR AS source_label
                FROM global_community_reports report
                JOIN global_graph_manifests global_manifest
                  ON global_manifest.global_generation =
                     report.global_generation
                 AND global_manifest.status IN (
                     'READY', 'ACTIVE', 'RETIRED'
                 )
                JOIN global_report_claims claim
                  ON claim.global_generation =
                     report.global_generation
                 AND claim.report_id = report.id
                JOIN global_report_evidence evidence
                  ON evidence.global_generation =
                     claim.global_generation
                 AND evidence.report_id = report.id
                 AND evidence.claim_id = claim.id
                JOIN global_graph_sources source
                  ON source.global_generation =
                     evidence.global_generation
                 AND source.source_graph_generation =
                     evidence.source_graph_generation
                 AND source.document_id = evidence.document_id
                 AND source.revision_id = evidence.revision_id
                 AND source.acl_version = evidence.acl_version
                JOIN documents document
                  ON document.id = evidence.document_id
                 AND document.deleted_at IS NULL
                 AND document.visibility = 'ALL_USERS'
                 AND document.current_revision_id =
                     evidence.revision_id
                 AND document.acl_version = evidence.acl_version
                JOIN document_revisions revision
                  ON revision.id = evidence.revision_id
                 AND revision.document_id = evidence.document_id
                 AND revision.status = 'READY'
                JOIN chunks child
                  ON child.id = evidence.child_chunk_id
                 AND child.document_id = evidence.document_id
                 AND child.revision_id = evidence.revision_id
                 AND child.chunk_type = 'CHILD'
                 AND child.searchable = TRUE
                JOIN source_spans span
                  ON span.id = evidence.source_span_id
                 AND span.chunk_id = evidence.child_chunk_id
                 AND span.document_id = evidence.document_id
                 AND span.revision_id = evidence.revision_id
                JOIN source_units start_unit
                  ON start_unit.id = span.start_source_unit_id
                 AND start_unit.document_id = span.document_id
                 AND start_unit.revision_id = span.revision_id
                 AND start_unit.normalization_version =
                     span.normalization_version
                JOIN source_units end_unit
                  ON end_unit.id = span.end_source_unit_id
                 AND end_unit.document_id = span.document_id
                 AND end_unit.revision_id = span.revision_id
                 AND end_unit.normalization_version =
                     span.normalization_version
                WHERE report.global_generation = :generation
                  AND report.id IN (:reportIds)
                  AND evidence.document_id IN (
                      :authorizedDocumentIds
                  )
                  AND
                """ + GlobalGraphQueryService.validReportSql("report") + """
                  AND NOT EXISTS (
                    SELECT 1
                    FROM global_report_evidence denied
                    WHERE denied.global_generation =
                          report.global_generation
                      AND denied.report_id = report.id
                      AND denied.document_id NOT IN (
                          :authorizedDocumentIds
                      )
                  )
                ORDER BY report.id,
                         claim.claim_order,
                         claim.id,
                         evidence.document_id,
                         evidence.child_chunk_id,
                         evidence.source_span_id,
                         evidence.id
                """;
    }

    private void setStatementTimeout(int timeoutMs) {
        jdbc.queryForObject(
                """
                SELECT set_config(
                    'statement_timeout', :timeout, TRUE
                )
                """,
                new MapSqlParameterSource(
                        "timeout",
                        timeoutMs + "ms"
                ),
                String.class
        );
    }

    private static CandidateRow row(ResultSet resultSet)
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
        return new CandidateRow(
                resultSet.getObject("report_id", UUID.class),
                resultSet.getInt("community_key"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                resultSet.getInt("token_count"),
                resultSet.getObject("claim_id", UUID.class),
                resultSet.getInt("claim_order"),
                resultSet.getString("claim_text"),
                new GlobalEvidence(
                        resultSet.getObject(
                                "evidence_id",
                                UUID.class
                        ),
                        resultSet.getObject(
                                "document_id",
                                UUID.class
                        ),
                        resultSet.getString("document_title"),
                        resultSet.getObject(
                                "revision_id",
                                UUID.class
                        ),
                        resultSet.getInt("revision_number"),
                        resultSet.getObject(
                                "child_chunk_id",
                                UUID.class
                        ),
                        resultSet.getObject(
                                "source_span_id",
                                UUID.class
                        ),
                        resultSet.getString("source_text_hash"),
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

    private static List<UUID> distinct(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new LinkedHashSet<>();
        values.stream()
                .filter(java.util.Objects::nonNull)
                .forEach(unique::add);
        return List.copyOf(unique);
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
        );
    }

    private static ApiException authorizationUnavailable(
            DataAccessException exception
    ) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GLOBAL_AUTHORIZATION_RECHECK_UNAVAILABLE",
                "Global GraphRAG 权限复核暂时不可用",
                exception
        );
    }

    public record RetrievalConfig(
            String version,
            int reportLimit,
            int contextTokenBudget,
            int mapCallLimit,
            int modelCallLimit,
            int hardTimeoutMs
    ) {
        private static RetrievalConfig from(GlobalConfig config) {
            return new RetrievalConfig(
                    config.version(),
                    config.reportLimit(),
                    config.contextTokenBudget(),
                    config.mapCallLimit(),
                    config.modelCallLimit(),
                    config.hardTimeoutMs()
            );
        }
    }

    public record Expansion(
            RetrievalConfig config,
            Long globalGeneration,
            Long sourceGraphGeneration,
            List<GlobalCandidate> candidates,
            String degradationCode,
            long tookMs
    ) {
        static Expansion unavailable(
                RetrievalConfig config,
                Long generation,
                Long sourceGeneration,
                String code,
                long tookMs
        ) {
            return new Expansion(
                    config,
                    generation,
                    sourceGeneration,
                    List.of(),
                    code,
                    tookMs
            );
        }

        public boolean used() {
            return !candidates.isEmpty();
        }
    }

    public record GlobalCandidate(
            UUID reportId,
            int rank,
            Integer bm25Rank,
            Integer vectorRank,
            double retrievalScore,
            int communityKey,
            String title,
            String summary,
            int tokenCount,
            List<GlobalClaim> claims
    ) {
    }

    public record GlobalClaim(
            UUID claimId,
            int order,
            String text,
            List<GlobalEvidence> evidence
    ) {
    }

    public record GlobalEvidence(
            UUID evidenceId,
            UUID documentId,
            String documentTitle,
            UUID revisionId,
            int revisionNumber,
            UUID childChunkId,
            UUID sourceSpanId,
            String sourceTextHash,
            String evidenceText,
            Integer startPage,
            Integer endPage,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public GlobalEvidence(
                UUID evidenceId,
                UUID documentId,
                String documentTitle,
                UUID revisionId,
                int revisionNumber,
                UUID childChunkId,
                UUID sourceSpanId,
                String sourceTextHash,
                String evidenceText,
                int startPage,
                int endPage
        ) {
            this(
                    evidenceId,
                    documentId,
                    documentTitle,
                    revisionId,
                    revisionNumber,
                    childChunkId,
                    sourceSpanId,
                    sourceTextHash,
                    evidenceText,
                    startPage,
                    endPage,
                    "PDF",
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ),
                    SourceLocatorResponse.pdfCompatibility(
                            startPage, endPage
                    ).sourceLabel()
            );
        }
    }

    private record ActiveGlobal(
            ManifestRow manifest,
            GlobalConfig config
    ) {
    }

    private record CandidateRow(
            UUID reportId,
            int communityKey,
            String title,
            String summary,
            int tokenCount,
            UUID claimId,
            int claimOrder,
            String claimText,
            GlobalEvidence evidence
    ) {
    }

    private record MutableReport(
            UUID id,
            int communityKey,
            String title,
            String summary,
            int tokenCount,
            Map<UUID, MutableClaim> claims
    ) {
        GlobalCandidate freeze(
                int rank,
                RankedReport retrieved
        ) {
            return new GlobalCandidate(
                    id,
                    rank,
                    retrieved.bm25Rank(),
                    retrieved.vectorRank(),
                    retrieved.score(),
                    communityKey,
                    title,
                    summary,
                    tokenCount,
                    claims.values().stream()
                            .map(MutableClaim::freeze)
                            .sorted(Comparator
                                    .comparingInt(GlobalClaim::order)
                                    .thenComparing(item ->
                                            item.claimId().toString()))
                            .toList()
            );
        }
    }

    private record MutableClaim(
            UUID id,
            int order,
            String text,
            List<GlobalEvidence> evidence
    ) {
        GlobalClaim freeze() {
            return new GlobalClaim(
                    id,
                    order,
                    text,
                    List.copyOf(evidence)
            );
        }
    }
}
