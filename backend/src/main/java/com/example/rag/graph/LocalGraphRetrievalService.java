package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.graph.GraphRetrievalContracts.ProfileView;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
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

@Service
public class LocalGraphRetrievalService {

    private static final UUID EMPTY_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final NamedParameterJdbcTemplate jdbc;
    private final GraphRetrievalConfigurationService configurations;
    private final GraphEntityLinkShadowService entityLinkShadow;

    LocalGraphRetrievalService(
            NamedParameterJdbcTemplate jdbc,
            GraphRetrievalConfigurationService configurations,
            GraphEntityLinkShadowService entityLinkShadow
    ) {
        this.jdbc = jdbc;
        this.configurations = configurations;
        this.entityLinkShadow = entityLinkShadow;
    }

    @Transactional(readOnly = true)
    public Expansion expand(
            String query,
            List<UUID> hybridChildIds,
            List<UUID> authorizedDocumentIds
    ) {
        return expand(
                query, hybridChildIds, authorizedDocumentIds,
                null, null
        );
    }

    @Transactional(readOnly = true)
    public Expansion expand(
            String query,
            List<UUID> hybridChildIds,
            List<UUID> authorizedDocumentIds,
            String profileVersion,
            Long requestedGeneration
    ) {
        long started = System.nanoTime();
        ProfileView profile;
        try {
            profile = profileVersion == null
                    ? configurations.currentProfile()
                    : configurations.profile(profileVersion);
        } catch (DataAccessException exception) {
            throw authorizationUnavailable(exception);
        }
        Long generation = null;
        try {
            generation = requestedGeneration == null
                    ? activeGeneration()
                    : eligibleGeneration(requestedGeneration);
            if (generation == null) {
                return Expansion.unavailable(
                        profile,
                        "GRAPH_NOT_PUBLISHED",
                        elapsed(started)
                );
            }
            if (authorizedDocumentIds.isEmpty()) {
                return Expansion.unavailable(
                        profile,
                        generation,
                        "GRAPH_NO_AUTHORIZED_SOURCE",
                        elapsed(started)
                );
            }
            setStatementTimeout(profile.statementTimeoutMs());
            List<Seed> seeds = seeds(
                    generation,
                    query,
                    hybridChildIds,
                    authorizedDocumentIds,
                    profile.seedLimit()
            );
            if (seeds.isEmpty()) {
                return Expansion.unavailable(
                        profile,
                        generation,
                        "GRAPH_NO_SEED",
                        elapsed(started),
                        List.of()
                );
            }
            List<PathEdge> traversed = paths(
                    generation,
                    seeds.stream().map(Seed::entityId).toList(),
                    authorizedDocumentIds,
                    profile
            );
            if (traversed.isEmpty()) {
                return Expansion.unavailable(
                        profile,
                        generation,
                        "GRAPH_NO_PATH",
                        elapsed(started),
                        seeds.stream().map(Seed::entityId).toList()
                );
            }
            List<GraphCandidate> candidates = candidates(
                    generation,
                    traversed,
                    authorizedDocumentIds,
                    profile.graphChildLimit()
            );
            if (candidates.isEmpty()) {
                return Expansion.unavailable(
                        profile,
                        generation,
                        "GRAPH_NO_EVIDENCE",
                        elapsed(started),
                        seeds.stream().map(Seed::entityId).toList()
                );
            }
            return new Expansion(
                    profile,
                    generation,
                    seeds.size(),
                    seeds.stream()
                            .flatMap(seed -> seed.documentIds().stream())
                            .distinct()
                            .sorted()
                            .toList(),
                    seeds.stream().map(Seed::entityId).toList(),
                    traversed.size(),
                    candidates,
                    null,
                    elapsed(started)
            );
        } catch (QueryTimeoutException exception) {
            return Expansion.unavailable(
                    profile,
                    generation,
                    "GRAPH_TIMEOUT",
                    elapsed(started)
            );
        } catch (DataAccessException exception) {
            throw authorizationUnavailable(exception);
        }
    }

    /**
     * Evaluates broader entity-name matching after retrieval has completed.
     * The result is diagnostic-only and cannot change seeds or candidates.
     */
    public ShadowSeedDiagnostics diagnoseEntityLinks(
            String query,
            List<UUID> authorizedDocumentIds,
            Expansion actual
    ) {
        if (actual == null || actual.graphGeneration() == null) {
            return ShadowSeedDiagnostics.unavailable(
                    "GRAPH_ENTITY_LINK_SHADOW_NO_GENERATION"
            );
        }
        try {
            GraphEntityLinkShadowService.ShadowScan scan =
                    entityLinkShadow.scan(
                            actual.graphGeneration(),
                            query,
                            authorizedDocumentIds,
                            Math.max(
                                    actual.profile().seedLimit() * 4,
                                    actual.profile().seedLimit()
                                            + actual.seedEntityIds().size()
                            ),
                            actual.profile().statementTimeoutMs()
                    );
            if (!scan.measured()) {
                return ShadowSeedDiagnostics.unavailable(scan.reasonCode());
            }
            Set<UUID> actualEntityIds = new LinkedHashSet<>(
                    actual.seedEntityIds()
            );
            List<UUID> actualDocuments = new ArrayList<>(
                    actual.seedDocumentIds()
            );
            scan.candidates().stream()
                    .filter(candidate ->
                            actualEntityIds.contains(candidate.entityId()))
                    .forEach(candidate ->
                            actualDocuments.addAll(candidate.documentIds()));
            List<GraphEntityLinkShadowService.ShadowCandidate> additions =
                    scan.candidates().stream()
                            .filter(candidate ->
                                    !actualEntityIds.contains(
                                            candidate.entityId()
                                    ))
                            .limit(Math.max(
                                    0,
                                    actual.profile().seedLimit()
                                            - actualEntityIds.size()
                            ))
                            .toList();
            List<UUID> documents = new ArrayList<>(actualDocuments);
            additions.forEach(candidate ->
                    documents.addAll(candidate.documentIds()));
            return ShadowSeedDiagnostics.measured(
                    actualEntityIds.size() + additions.size(),
                    documents.stream().distinct().sorted().toList(),
                    additions.size(),
                    additions.stream()
                            .map(candidate -> candidate.mode().name())
                            .distinct()
                            .toList()
            );
        } catch (DataAccessException exception) {
            return ShadowSeedDiagnostics.unavailable(
                    exception instanceof QueryTimeoutException
                            ? "GRAPH_ENTITY_LINK_SHADOW_TIMEOUT"
                            : "GRAPH_ENTITY_LINK_SHADOW_UNAVAILABLE"
            );
        } catch (RuntimeException exception) {
            return ShadowSeedDiagnostics.unavailable(
                    "GRAPH_ENTITY_LINK_SHADOW_UNAVAILABLE"
            );
        }
    }

    private Long activeGeneration() {
        return jdbc.query(
                """
                SELECT publication.graph_generation
                FROM graph_publications publication
                JOIN graph_manifests manifest
                  ON manifest.graph_generation =
                     publication.graph_generation
                 AND manifest.status = 'ACTIVE'
                WHERE publication.singleton_id = 1
                """,
                new MapSqlParameterSource(),
                (resultSet, rowNumber) -> resultSet.getLong(
                        "graph_generation"
                )
        ).stream().findFirst().orElse(null);
    }

    private Long eligibleGeneration(long generation) {
        return jdbc.query(
                """
                SELECT graph_generation
                FROM graph_manifests
                WHERE graph_generation = :generation
                  AND status IN ('READY', 'ACTIVE', 'RETIRED')
                """,
                new MapSqlParameterSource("generation", generation),
                (resultSet, rowNumber) -> resultSet.getLong(
                        "graph_generation"
                )
        ).stream().findFirst().orElse(null);
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

    private List<Seed> seeds(
            long generation,
            String query,
            List<UUID> hybridChildIds,
            List<UUID> authorizedDocumentIds,
            int limit
    ) {
        String normalized = GraphAssembler.normalize(query);
        MapSqlParameterSource parameters = baseParameters(
                generation,
                authorizedDocumentIds
        ).addValue(
                "hybridChildIds",
                hybridChildIds.isEmpty()
                        ? List.of(EMPTY_UUID)
                        : hybridChildIds
        ).addValue("query", normalized)
                .addValue("seedLimit", limit);
        return jdbc.query(
                """
                WITH authorized_sources AS MATERIALIZED (
                    SELECT source.document_id, source.revision_id
                    FROM graph_generation_sources source
                    JOIN documents document
                      ON document.id = source.document_id
                     AND document.current_revision_id =
                         source.revision_id
                     AND document.deleted_at IS NULL
                    JOIN document_revisions revision
                      ON revision.id = source.revision_id
                     AND revision.document_id = source.document_id
                     AND revision.status = 'READY'
                    JOIN graph_projection_states projection
                      ON projection.graph_generation =
                         source.graph_generation
                     AND projection.document_id = source.document_id
                     AND projection.revision_id = source.revision_id
                     AND projection.acl_version = document.acl_version
                     AND projection.state = 'PROJECTED'
                    WHERE source.graph_generation = :generation
                      AND source.acl_version = document.acl_version
                      AND source.document_id IN (:authorizedDocumentIds)
                ),
                candidates AS (
                    SELECT mention.entity_id, 0 AS priority,
                           count(*) AS evidence_count,
                           mention.document_id
                    FROM graph_entity_mentions mention
                    JOIN authorized_sources source
                      ON source.document_id = mention.document_id
                     AND source.revision_id = mention.revision_id
                    WHERE mention.graph_generation = :generation
                      AND mention.child_chunk_id IN (:hybridChildIds)
                    GROUP BY mention.entity_id, mention.document_id
                    UNION ALL
                    SELECT alias.entity_id, 1 AS priority,
                           count(*) AS evidence_count,
                           mention.document_id
                    FROM graph_entity_aliases alias
                    JOIN graph_entity_alias_evidence alias_evidence
                      ON alias_evidence.graph_generation =
                         alias.graph_generation
                     AND alias_evidence.entity_id = alias.entity_id
                     AND alias_evidence.normalized_alias =
                         alias.normalized_alias
                    JOIN graph_entity_mentions mention
                      ON mention.id = alias_evidence.mention_id
                     AND mention.graph_generation =
                         alias_evidence.graph_generation
                    JOIN authorized_sources source
                      ON source.document_id = mention.document_id
                     AND source.revision_id = mention.revision_id
                    WHERE alias.graph_generation = :generation
                      AND (
                        alias.normalized_alias = :query
                        OR (
                          length(alias.normalized_alias) >= 2
                          AND (
                            (
                              alias.normalized_alias ~
                              '^[a-z0-9_]+([ ][a-z0-9_]+)*$'
                              AND (
                                ' ' || regexp_replace(
                                  :query,
                                  '[^[:alnum:]_]+',
                                  ' ',
                                  'g'
                                ) || ' '
                              ) LIKE
                              '% ' || alias.normalized_alias || ' %'
                            )
                            OR (
                              alias.normalized_alias !~
                              '^[a-z0-9_]+([ ][a-z0-9_]+)*$'
                              AND position(
                                alias.normalized_alias IN :query
                              ) > 0
                            )
                          )
                        )
                      )
                    GROUP BY alias.entity_id, mention.document_id
                )
                SELECT entity_id,
                       array_agg(
                         DISTINCT document_id ORDER BY document_id
                       ) AS document_ids
                FROM candidates
                GROUP BY entity_id
                ORDER BY min(priority),
                         sum(evidence_count) DESC,
                         entity_id
                LIMIT :seedLimit
                """,
                parameters,
                (resultSet, rowNumber) -> new Seed(
                        resultSet.getObject("entity_id", UUID.class),
                        List.of((UUID[]) resultSet.getArray(
                                "document_ids"
                        ).getArray())
                )
        );
    }

    private List<PathEdge> paths(
            long generation,
            List<UUID> seeds,
            List<UUID> authorizedDocumentIds,
            ProfileView profile
    ) {
        int perNodeLimit = Math.max(
                1,
                (int) Math.ceil(
                        (double) profile.edgeLimit()
                                / seeds.size()
                )
        );
        MapSqlParameterSource parameters = baseParameters(
                generation,
                authorizedDocumentIds
        ).addValue("seedIds", seeds)
                .addValue("maxHops", profile.maxHops())
                .addValue("perNodeLimit", perNodeLimit);
        List<PathEdge> rows = jdbc.query(
                """
                WITH RECURSIVE authorized_sources AS MATERIALIZED (
                    SELECT source.document_id, source.revision_id
                    FROM graph_generation_sources source
                    JOIN documents document
                      ON document.id = source.document_id
                     AND document.current_revision_id =
                         source.revision_id
                     AND document.deleted_at IS NULL
                    JOIN document_revisions revision
                      ON revision.id = source.revision_id
                     AND revision.document_id = source.document_id
                     AND revision.status = 'READY'
                    JOIN graph_projection_states projection
                      ON projection.graph_generation =
                         source.graph_generation
                     AND projection.document_id = source.document_id
                     AND projection.revision_id = source.revision_id
                     AND projection.acl_version = document.acl_version
                     AND projection.state = 'PROJECTED'
                    WHERE source.graph_generation = :generation
                      AND source.acl_version = document.acl_version
                      AND source.document_id IN (:authorizedDocumentIds)
                ),
                walk AS (
                    SELECT entity.id AS seed_entity_id,
                           entity.id AS current_entity_id,
                           NULL::UUID AS from_entity_id,
                           NULL::UUID AS relationship_id,
                           NULL::UUID AS parent_relationship_id,
                           NULL::VARCHAR AS relationship_type,
                           0 AS depth,
                           0::BIGINT AS evidence_count,
                           ARRAY[entity.id]::UUID[] AS entity_path
                    FROM graph_entities entity
                    WHERE entity.graph_generation = :generation
                      AND entity.id IN (:seedIds)
                    UNION ALL
                    SELECT walk.seed_entity_id,
                           edge.target_entity_id,
                           walk.current_entity_id,
                           edge.relationship_id,
                           walk.relationship_id,
                           edge.relationship_type,
                           walk.depth + 1,
                           edge.evidence_count,
                           walk.entity_path || edge.target_entity_id
                    FROM walk
                    JOIN LATERAL (
                        SELECT adjacency.target_entity_id,
                               adjacency.relationship_id,
                               relationship.relationship_type,
                               (
                                 SELECT count(*)
                                 FROM graph_relationship_evidence evidence
                                 JOIN authorized_sources source
                                   ON source.document_id =
                                      evidence.document_id
                                  AND source.revision_id =
                                      evidence.revision_id
                                 WHERE evidence.graph_generation =
                                       adjacency.graph_generation
                                   AND evidence.relationship_id =
                                       adjacency.relationship_id
                               ) AS evidence_count
                        FROM graph_adjacency adjacency
                        JOIN graph_relationships relationship
                          ON relationship.graph_generation =
                             adjacency.graph_generation
                         AND relationship.id =
                             adjacency.relationship_id
                        WHERE adjacency.graph_generation =
                              :generation
                          AND adjacency.source_entity_id =
                              walk.current_entity_id
                          AND NOT adjacency.target_entity_id =
                              ANY(walk.entity_path)
                          AND EXISTS (
                            SELECT 1
                            FROM graph_relationship_evidence evidence
                            JOIN authorized_sources source
                              ON source.document_id =
                                 evidence.document_id
                             AND source.revision_id =
                                 evidence.revision_id
                            WHERE evidence.graph_generation =
                                  adjacency.graph_generation
                              AND evidence.relationship_id =
                                  adjacency.relationship_id
                          )
                        ORDER BY evidence_count DESC,
                                 adjacency.relationship_id,
                                 adjacency.target_entity_id
                        LIMIT :perNodeLimit
                    ) edge ON TRUE
                    WHERE walk.depth < :maxHops
                )
                SELECT seed_entity_id, from_entity_id,
                       current_entity_id AS target_entity_id,
                       relationship_id, parent_relationship_id,
                       relationship_type,
                       depth, evidence_count
                FROM walk
                WHERE depth > 0
                ORDER BY depth, evidence_count DESC,
                         relationship_id, target_entity_id,
                         seed_entity_id
                """,
                parameters,
                (resultSet, rowNumber) -> edge(resultSet)
        );
        Set<UUID> entities = new LinkedHashSet<>(seeds);
        Map<UUID, PathEdge> edges = new LinkedHashMap<>();
        for (PathEdge edge : rows) {
            if (edges.containsKey(edge.relationshipId())) {
                continue;
            }
            if (edge.parentRelationshipId() != null
                    && !edges.containsKey(edge.parentRelationshipId())) {
                continue;
            }
            boolean newEntity = !entities.contains(edge.targetEntityId());
            if (newEntity && entities.size() >= profile.entityLimit()) {
                continue;
            }
            entities.add(edge.targetEntityId());
            edges.put(edge.relationshipId(), edge);
            if (edges.size() >= profile.edgeLimit()) {
                break;
            }
        }
        return List.copyOf(edges.values());
    }

    private List<GraphCandidate> candidates(
            long generation,
            List<PathEdge> edges,
            List<UUID> authorizedDocumentIds,
            int childLimit
    ) {
        Map<UUID, Integer> edgeOrder = new LinkedHashMap<>();
        for (int index = 0; index < edges.size(); index++) {
            edgeOrder.put(edges.get(index).relationshipId(), index);
        }
        MapSqlParameterSource parameters = baseParameters(
                generation,
                authorizedDocumentIds
        ).addValue("relationshipIds", edgeOrder.keySet());
        List<EvidenceRow> evidence = jdbc.query(
                """
                SELECT evidence.relationship_id,
                       relationship.relationship_type,
                       evidence.child_chunk_id,
                       evidence.source_span_id,
                       evidence.document_id,
                       document.title AS document_title,
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
                FROM graph_relationship_evidence evidence
                JOIN graph_relationships relationship
                  ON relationship.id = evidence.relationship_id
                 AND relationship.graph_generation =
                     evidence.graph_generation
                JOIN documents document
                  ON document.id = evidence.document_id
                 AND document.current_revision_id =
                     evidence.revision_id
                 AND document.deleted_at IS NULL
                JOIN document_revisions revision
                  ON revision.id = evidence.revision_id
                 AND revision.document_id = evidence.document_id
                 AND revision.status = 'READY'
                JOIN source_spans span
                  ON span.id = evidence.source_span_id
                 AND span.chunk_id = evidence.child_chunk_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                JOIN graph_generation_sources source
                  ON source.graph_generation =
                     evidence.graph_generation
                 AND source.document_id = evidence.document_id
                 AND source.revision_id = evidence.revision_id
                 AND source.acl_version = document.acl_version
                JOIN graph_projection_states projection
                  ON projection.graph_generation =
                     evidence.graph_generation
                 AND projection.document_id = evidence.document_id
                 AND projection.revision_id = evidence.revision_id
                 AND projection.acl_version = document.acl_version
                 AND projection.state = 'PROJECTED'
                WHERE evidence.graph_generation = :generation
                  AND evidence.relationship_id IN (:relationshipIds)
                  AND document.id IN (:authorizedDocumentIds)
                ORDER BY evidence.relationship_id,
                         evidence.document_id,
                         evidence.child_chunk_id,
                         evidence.source_span_id
                """,
                parameters,
                (resultSet, rowNumber) -> evidence(resultSet)
        );
        Map<UUID, CandidateRows> byChild = new LinkedHashMap<>();
        evidence.stream()
                .sorted(Comparator
                        .comparingInt((EvidenceRow row) -> edgeOrder.get(
                                row.relationshipId()
                        ))
                        .thenComparing(row -> row.childId().toString())
                        .thenComparing(row -> row.spanId().toString()))
                .forEach(row -> {
                    if (!byChild.containsKey(row.childId())
                            && byChild.size() >= childLimit) {
                        return;
                    }
                    PathEdge edge = edges.get(
                            edgeOrder.get(row.relationshipId())
                    );
                    CandidateRows candidate = byChild.computeIfAbsent(
                            row.childId(),
                            ignored -> new CandidateRows(
                                    row.childId(),
                                    new ArrayList<>()
                            )
                    );
                    candidate.paths().add(new GraphPath(
                            edge.depth(),
                            row.relationshipId(),
                            edge.parentRelationshipId(),
                            row.relationshipType(),
                            row.childId(),
                            row.spanId(),
                            row.documentId(),
                            row.documentTitle(),
                            row.startPage(),
                            row.endPage(),
                            concise(row.evidenceText(), 220),
                            row.documentFormat(),
                            row.sourceLocator(),
                            row.sourceLabel()
                    ));
                });
        List<GraphCandidate> result = new ArrayList<>();
        int rank = 0;
        for (CandidateRows row : byChild.values()) {
            result.add(new GraphCandidate(
                    row.childId(),
                    ++rank,
                    List.copyOf(row.paths())
            ));
        }
        return List.copyOf(result);
    }

    private static MapSqlParameterSource baseParameters(
            long generation,
            List<UUID> authorizedDocumentIds
    ) {
        return new MapSqlParameterSource()
                .addValue("generation", generation)
                .addValue("authorizedDocumentIds", authorizedDocumentIds);
    }

    private static PathEdge edge(ResultSet resultSet)
            throws SQLException {
        return new PathEdge(
                resultSet.getObject("seed_entity_id", UUID.class),
                resultSet.getObject("from_entity_id", UUID.class),
                resultSet.getObject("target_entity_id", UUID.class),
                resultSet.getObject("relationship_id", UUID.class),
                resultSet.getObject(
                        "parent_relationship_id",
                        UUID.class
                ),
                resultSet.getString("relationship_type"),
                resultSet.getInt("depth"),
                resultSet.getLong("evidence_count")
        );
    }

    private static EvidenceRow evidence(ResultSet resultSet)
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
        return new EvidenceRow(
                resultSet.getObject("relationship_id", UUID.class),
                resultSet.getString("relationship_type"),
                resultSet.getObject("child_chunk_id", UUID.class),
                resultSet.getObject("source_span_id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getString("document_title"),
                resultSet.getString("evidence_text"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class),
                resultSet.getString("document_format"),
                locator,
                locator.sourceLabel()
        );
    }

    private static String concise(String text, int maximum) {
        String value = text == null ? "" : text.trim();
        return value.substring(0, Math.min(value.length(), maximum));
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static ApiException authorizationUnavailable(
            DataAccessException exception
    ) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GRAPH_AUTHORIZATION_RECHECK_UNAVAILABLE",
                "Local GraphRAG 权限复核暂时不可用",
                exception
        );
    }

    public record Expansion(
            ProfileView profile,
            Long graphGeneration,
            int seedCount,
            List<UUID> seedDocumentIds,
            List<UUID> seedEntityIds,
            int edgeCount,
            List<GraphCandidate> candidates,
            String degradationCode,
            long tookMs
    ) {
        static Expansion unavailable(
                ProfileView profile,
                String code,
                long tookMs
        ) {
            return unavailable(
                    profile, null, code, tookMs, List.of()
            );
        }

        static Expansion unavailable(
                ProfileView profile,
                Long generation,
                String code,
                long tookMs
        ) {
            return unavailable(
                    profile, generation, code, tookMs, List.of()
            );
        }

        static Expansion unavailable(
                ProfileView profile,
                Long generation,
                String code,
                long tookMs,
                List<UUID> seedEntityIds
        ) {
            return new Expansion(
                    profile,
                    generation,
                    0,
                    List.of(),
                    seedEntityIds,
                    0,
                    List.of(),
                    code,
                    tookMs
            );
        }

        public boolean used() {
            return degradationCode == null && !candidates.isEmpty();
        }
    }

    public record ShadowSeedDiagnostics(
            boolean measured,
            int seedEntityCount,
            List<UUID> seedDocumentIds,
            int addedSeedEntityCount,
            List<String> matchModes,
            String reasonCode
    ) {
        public ShadowSeedDiagnostics {
            seedDocumentIds = seedDocumentIds == null
                    ? List.of() : List.copyOf(seedDocumentIds);
            matchModes = matchModes == null
                    ? List.of() : List.copyOf(matchModes);
        }

        public static ShadowSeedDiagnostics measured(
                int seedEntityCount,
                List<UUID> seedDocumentIds,
                int addedSeedEntityCount,
                List<String> matchModes
        ) {
            return new ShadowSeedDiagnostics(
                    true, seedEntityCount, seedDocumentIds,
                    addedSeedEntityCount, matchModes, null
            );
        }

        public static ShadowSeedDiagnostics notRequested() {
            return unavailable("GRAPH_ENTITY_LINK_SHADOW_NOT_REQUESTED");
        }

        public static ShadowSeedDiagnostics unavailable(String reasonCode) {
            return new ShadowSeedDiagnostics(
                    false, 0, List.of(), 0, List.of(), reasonCode
            );
        }
    }

    private record Seed(
            UUID entityId,
            List<UUID> documentIds
    ) {
    }

    public record GraphCandidate(
            UUID childId,
            int rank,
            List<GraphPath> paths
    ) {
    }

    public record GraphPath(
            int depth,
            UUID relationshipId,
            UUID parentRelationshipId,
            String relationshipType,
            UUID childId,
            UUID sourceSpanId,
            UUID documentId,
            String documentTitle,
            Integer startPage,
            Integer endPage,
            String evidenceText,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public GraphPath(
                int depth,
                UUID relationshipId,
                UUID parentRelationshipId,
                String relationshipType,
                UUID childId,
                UUID sourceSpanId,
                UUID documentId,
                String documentTitle,
                int startPage,
                int endPage,
                String evidenceText
        ) {
            this(
                    depth,
                    relationshipId,
                    parentRelationshipId,
                    relationshipType,
                    childId,
                    sourceSpanId,
                    documentId,
                    documentTitle,
                    startPage,
                    endPage,
                    evidenceText,
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

    private record PathEdge(
            UUID seedEntityId,
            UUID fromEntityId,
            UUID targetEntityId,
            UUID relationshipId,
            UUID parentRelationshipId,
            String relationshipType,
            int depth,
            long evidenceCount
    ) {
    }

    private record EvidenceRow(
            UUID relationshipId,
            String relationshipType,
            UUID childId,
            UUID spanId,
            UUID documentId,
            String documentTitle,
            String evidenceText,
            Integer startPage,
            Integer endPage,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
    }

    private record CandidateRows(
            UUID childId,
            List<GraphPath> paths
    ) {
    }
}
