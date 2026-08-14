package com.example.rag.graph;

import com.example.rag.graph.GraphEntityNameMatcher.MatchMode;
import com.example.rag.graph.GraphEntityNameMatcher.NameMatch;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.function.LongSupplier;

/** Runs the candidate entity-link policy without changing retrieval facts. */
@Service
class GraphEntityLinkShadowService {

    private static final int MAX_ALIAS_SCAN = 20_000;
    static final long MAX_FUZZY_CELLS = 1_000_000;
    static final long MAX_MATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final String MATCH_LIMIT_REASON =
            "GRAPH_ENTITY_LINK_SHADOW_MATCH_LIMIT";

    private final NamedParameterJdbcTemplate jdbc;

    GraphEntityLinkShadowService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW
    )
    ShadowScan scan(
            long generation,
            String query,
            List<UUID> authorizedDocumentIds,
            int candidateLimit,
            int statementTimeoutMs
    ) {
        return scan(
                generation,
                query,
                authorizedDocumentIds,
                candidateLimit,
                statementTimeoutMs,
                new MatchLimits(
                        MAX_FUZZY_CELLS,
                        MAX_MATCH_NANOS,
                        System::nanoTime
                )
        );
    }

    ShadowScan scan(
            long generation,
            String query,
            List<UUID> authorizedDocumentIds,
            int candidateLimit,
            int statementTimeoutMs,
            MatchLimits limits
    ) {
        if (authorizedDocumentIds == null || authorizedDocumentIds.isEmpty()) {
            return ShadowScan.measured(List.of());
        }
        jdbc.queryForObject(
                "SELECT set_config('statement_timeout', :timeout, TRUE)",
                new MapSqlParameterSource(
                        "timeout",
                        Math.max(1, Math.min(statementTimeoutMs, 250))
                                + "ms"
                ),
                String.class
        );
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("generation", generation)
                .addValue("authorizedDocumentIds", authorizedDocumentIds)
                .addValue("scanLimit", MAX_ALIAS_SCAN + 1);
        List<AliasRow> aliases = jdbc.query(
                """
                WITH authorized_sources AS MATERIALIZED (
                    SELECT source.document_id, source.revision_id
                    FROM graph_generation_sources source
                    JOIN documents document
                      ON document.id = source.document_id
                     AND document.current_revision_id = source.revision_id
                     AND document.deleted_at IS NULL
                    JOIN document_revisions revision
                      ON revision.id = source.revision_id
                     AND revision.document_id = source.document_id
                     AND revision.status = 'READY'
                    JOIN graph_projection_states projection
                      ON projection.graph_generation = source.graph_generation
                     AND projection.document_id = source.document_id
                     AND projection.revision_id = source.revision_id
                     AND projection.acl_version = document.acl_version
                     AND projection.state = 'PROJECTED'
                    WHERE source.graph_generation = :generation
                      AND source.acl_version = document.acl_version
                      AND source.document_id IN (:authorizedDocumentIds)
                )
                SELECT alias.entity_id, alias.alias,
                       alias.normalized_alias,
                       count(DISTINCT alias_evidence.mention_id)
                           AS evidence_count,
                       array_agg(
                         DISTINCT mention.document_id
                         ORDER BY mention.document_id
                       ) AS document_ids
                FROM graph_entity_aliases alias
                JOIN graph_entity_alias_evidence alias_evidence
                  ON alias_evidence.graph_generation = alias.graph_generation
                 AND alias_evidence.entity_id = alias.entity_id
                 AND alias_evidence.normalized_alias = alias.normalized_alias
                JOIN graph_entity_mentions mention
                  ON mention.id = alias_evidence.mention_id
                 AND mention.graph_generation = alias_evidence.graph_generation
                JOIN authorized_sources source
                  ON source.document_id = mention.document_id
                 AND source.revision_id = mention.revision_id
                WHERE alias.graph_generation = :generation
                GROUP BY alias.entity_id, alias.alias,
                         alias.normalized_alias
                ORDER BY alias.entity_id, alias.normalized_alias
                LIMIT :scanLimit
                """,
                parameters,
                (resultSet, rowNumber) -> alias(resultSet)
        );
        if (aliases.size() > MAX_ALIAS_SCAN) {
            return ShadowScan.unavailable(
                    "GRAPH_ENTITY_LINK_SHADOW_SCAN_LIMIT"
            );
        }

        GraphEntityNameMatcher.MatchBudget budget =
                GraphEntityNameMatcher.MatchBudget.limited(
                        limits.maximumFuzzyCells(),
                        limits.maximumNanos(),
                        limits.nanoTime()
                );
        try {
            GraphEntityNameMatcher.Query analyzed =
                    GraphEntityNameMatcher.analyze(query);
            Map<UUID, MutableCandidate> matched = new LinkedHashMap<>();
            for (AliasRow alias : aliases) {
                GraphEntityNameMatcher.match(
                        analyzed, alias.alias(), budget
                ).ifPresent(match -> matched.compute(
                        alias.entityId(),
                        (ignored, current) -> merge(current, alias, match)
                ));
            }
            List<ShadowCandidate> candidates = matched.values().stream()
                    .map(candidate -> {
                        budget.checkpoint();
                        return candidate.freeze();
                    })
                    .sorted((left, right) -> {
                        budget.checkpoint();
                        return CANDIDATE_ORDER.compare(left, right);
                    })
                    .limit(Math.max(1, candidateLimit))
                    .toList();
            budget.checkpoint();
            return ShadowScan.measured(candidates);
        } catch (GraphEntityNameMatcher.MatchLimitExceededException exception) {
            return ShadowScan.unavailable(MATCH_LIMIT_REASON);
        }
    }

    private static MutableCandidate merge(
            MutableCandidate current,
            AliasRow alias,
            NameMatch match
    ) {
        if (current == null) {
            return new MutableCandidate(
                    alias.entityId(),
                    match,
                    alias.normalizedAlias(),
                    alias.evidenceCount(),
                    new LinkedHashSet<>(alias.documentIds())
            );
        }
        current.documentIds().addAll(alias.documentIds());
        current.evidenceCount = Math.max(
                current.evidenceCount, alias.evidenceCount()
        );
        if (MATCH_ORDER.compare(
                new RankedMatch(match, alias.normalizedAlias()),
                new RankedMatch(current.match, current.normalizedAlias)
        ) < 0) {
            current.match = match;
            current.normalizedAlias = alias.normalizedAlias();
        }
        return current;
    }

    private static AliasRow alias(ResultSet resultSet) throws SQLException {
        return new AliasRow(
                resultSet.getObject("entity_id", UUID.class),
                resultSet.getString("alias"),
                resultSet.getString("normalized_alias"),
                resultSet.getLong("evidence_count"),
                List.of((UUID[]) resultSet.getArray("document_ids").getArray())
        );
    }

    private static final Comparator<RankedMatch> MATCH_ORDER = Comparator
            .comparingInt((RankedMatch item) -> item.match().mode().ordinal())
            .thenComparingInt(item -> item.match().editDistance())
            .thenComparing(
                    (RankedMatch item) -> item.match().similarity(),
                    Comparator.reverseOrder()
            )
            .thenComparing(RankedMatch::normalizedAlias);

    private static final Comparator<ShadowCandidate> CANDIDATE_ORDER =
            Comparator
                    .comparingInt((ShadowCandidate candidate) ->
                            candidate.mode().ordinal())
                    .thenComparingInt(ShadowCandidate::editDistance)
                    .thenComparing(
                            ShadowCandidate::similarity,
                            Comparator.reverseOrder()
                    )
                    .thenComparing(
                            ShadowCandidate::evidenceCount,
                            Comparator.reverseOrder()
                    )
                    .thenComparing(ShadowCandidate::entityId);

    record ShadowScan(
            boolean measured,
            List<ShadowCandidate> candidates,
            List<String> matchModes,
            String reasonCode
    ) {
        static ShadowScan measured(List<ShadowCandidate> candidates) {
            List<String> modes = candidates.stream()
                    .map(candidate -> candidate.mode().name())
                    .distinct()
                    .toList();
            return new ShadowScan(
                    true, List.copyOf(candidates), modes, null
            );
        }

        static ShadowScan unavailable(String reasonCode) {
            return new ShadowScan(false, List.of(), List.of(), reasonCode);
        }
    }

    record ShadowCandidate(
            UUID entityId,
            List<UUID> documentIds,
            MatchMode mode,
            int editDistance,
            double similarity,
            long evidenceCount
    ) {
    }

    record MatchLimits(
            long maximumFuzzyCells,
            long maximumNanos,
            LongSupplier nanoTime
    ) {
        MatchLimits {
            if (maximumFuzzyCells < 0
                    || maximumNanos <= 0
                    || nanoTime == null) {
                throw new IllegalArgumentException("Invalid match limits");
            }
        }
    }

    private record AliasRow(
            UUID entityId,
            String alias,
            String normalizedAlias,
            long evidenceCount,
            List<UUID> documentIds
    ) {
    }

    private record RankedMatch(NameMatch match, String normalizedAlias) {
    }

    private static final class MutableCandidate {
        private final UUID entityId;
        private NameMatch match;
        private String normalizedAlias;
        private long evidenceCount;
        private final Set<UUID> documentIds;

        private MutableCandidate(
                UUID entityId,
                NameMatch match,
                String normalizedAlias,
                long evidenceCount,
                Set<UUID> documentIds
        ) {
            this.entityId = entityId;
            this.match = match;
            this.normalizedAlias = normalizedAlias;
            this.evidenceCount = evidenceCount;
            this.documentIds = documentIds;
        }

        private Set<UUID> documentIds() {
            return documentIds;
        }

        private ShadowCandidate freeze() {
            return new ShadowCandidate(
                    entityId,
                    documentIds.stream().sorted().toList(),
                    match.mode(),
                    match.editDistance(),
                    match.similarity(),
                    evidenceCount
            );
        }
    }
}
