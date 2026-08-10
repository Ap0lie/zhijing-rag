package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.graph.GraphApiContracts.GraphRelationshipEvidenceView;
import com.example.rag.graph.GraphApiContracts.GraphRelationshipView;
import com.example.rag.graph.GraphApiContracts.GraphRootType;
import com.example.rag.graph.GraphApiContracts.GraphSubgraphEdgeView;
import com.example.rag.graph.GraphApiContracts.GraphSubgraphNodeView;
import com.example.rag.graph.GraphApiContracts.GraphSubgraphView;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

@Service
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GraphVisualizationService {

    private static final int NODE_LIMIT = 20;
    private static final int EDGE_LIMIT = 40;
    private static final int STATEMENT_TIMEOUT_MS = 750;
    private static final Set<String> BROWSABLE_STATUSES = Set.of(
            "ACTIVE", "READY", "RETIRED"
    );

    private static final String VISIBLE_ENTITY = """
            EXISTS (
              SELECT 1
              FROM graph_entity_mentions visible_mention
              JOIN documents visible_document
                ON visible_document.id = visible_mention.document_id
               AND visible_document.current_revision_id =
                   visible_mention.revision_id
               AND visible_document.deleted_at IS NULL
              JOIN document_revisions visible_revision
                ON visible_revision.id = visible_mention.revision_id
               AND visible_revision.document_id =
                   visible_mention.document_id
               AND visible_revision.status = 'READY'
              JOIN graph_projection_states visible_projection
                ON visible_projection.graph_generation =
                   visible_mention.graph_generation
               AND visible_projection.document_id =
                   visible_mention.document_id
               AND visible_projection.revision_id =
                   visible_mention.revision_id
               AND visible_projection.acl_version =
                   visible_document.acl_version
               AND visible_projection.state = 'PROJECTED'
              WHERE visible_mention.graph_generation =
                    entity.graph_generation
                AND visible_mention.entity_id = entity.id
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGenerationRepository generations;
    private final GraphQueryService queries;

    GraphVisualizationService(
            NamedParameterJdbcTemplate jdbc,
            GraphGenerationRepository generations,
            GraphQueryService queries
    ) {
        this.jdbc = jdbc;
        this.generations = generations;
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    GraphSubgraphView subgraph(
            PlatformUserPrincipal user,
            long generation,
            GraphRootType rootType,
            UUID rootId,
            int requestedHops
    ) {
        queries.requireAdmin(user);
        requireBrowsable(generation);
        setStatementTimeout();
        return rootType == GraphRootType.ENTITY
                ? entitySubgraph(
                        generation,
                        rootId,
                        Math.clamp(requestedHops, 1, 2)
                )
                : communitySubgraph(generation, rootId);
    }

    @Transactional(readOnly = true)
    GraphRelationshipView relationship(
            PlatformUserPrincipal user,
            long generation,
            UUID relationshipId
    ) {
        queries.requireAdmin(user);
        requireBrowsable(generation);
        setStatementTimeout();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("generation", generation)
                .addValue("relationshipId", relationshipId);
        Map<UUID, RelationshipRows> rows = new LinkedHashMap<>();
        jdbc.query(
                relationshipEvidenceSql(),
                parameters,
                resultSet -> {
                    UUID id = resultSet.getObject("id", UUID.class);
                    RelationshipRows relationship = rows.computeIfAbsent(
                            id,
                            ignored -> relationshipRows(resultSet)
                    );
                    relationship.evidence().add(evidence(resultSet));
                }
        );
        return rows.values().stream()
                .findFirst()
                .map(RelationshipRows::view)
                .orElseThrow(() -> notFound("关系"));
    }

    private GraphSubgraphView entitySubgraph(
            long generation,
            UUID rootId,
            int hops
    ) {
        LinkedHashMap<UUID, Integer> depths = new LinkedHashMap<>();
        depths.put(rootId, 0);
        Map<UUID, GraphSubgraphNodeView> root = nodes(generation, depths);
        GraphSubgraphNodeView rootNode = root.get(rootId);
        if (rootNode == null) {
            throw notFound("实体");
        }

        LinkedHashMap<UUID, EdgeCandidate> selectedEdges =
                new LinkedHashMap<>();
        boolean truncated = addLayer(
                generation,
                Set.of(rootId),
                1,
                depths,
                selectedEdges
        );
        if (hops == 2 && depths.size() < NODE_LIMIT
                && selectedEdges.size() < EDGE_LIMIT) {
            Set<UUID> firstHop = depths.entrySet().stream()
                    .filter(entry -> entry.getValue() == 1)
                    .map(Map.Entry::getKey)
                    .collect(
                            LinkedHashSet::new,
                            LinkedHashSet::add,
                            LinkedHashSet::addAll
                    );
            if (!firstHop.isEmpty()) {
                truncated |= addLayer(
                        generation,
                        firstHop,
                        2,
                        depths,
                        selectedEdges
                );
            }
        }

        Map<UUID, GraphSubgraphNodeView> visibleNodes = nodes(
                generation,
                depths
        );
        List<GraphSubgraphNodeView> orderedNodes = depths.entrySet().stream()
                .map(entry -> withDepth(
                        visibleNodes.get(entry.getKey()),
                        entry.getValue(),
                        entry.getKey().equals(rootId)
                ))
                .filter(java.util.Objects::nonNull)
                .toList();
        Set<UUID> nodeIds = visibleNodes.keySet();
        List<GraphSubgraphEdgeView> edges = selectedEdges.values().stream()
                .filter(edge -> nodeIds.contains(edge.sourceEntityId())
                        && nodeIds.contains(edge.targetEntityId()))
                .limit(EDGE_LIMIT)
                .map(EdgeCandidate::view)
                .toList();
        return new GraphSubgraphView(
                generation,
                GraphRootType.ENTITY,
                rootId,
                rootNode.name(),
                hops,
                truncated,
                orderedNodes,
                edges
        );
    }

    private GraphSubgraphView communitySubgraph(
            long generation,
            UUID communityId
    ) {
        String rootLabel = communityLabel(generation, communityId);
        List<UUID> members = communityMembers(generation, communityId);
        boolean truncated = members.size() > NODE_LIMIT;
        List<UUID> selectedMembers = members.stream().limit(NODE_LIMIT).toList();
        Map<UUID, Integer> depths = new LinkedHashMap<>();
        selectedMembers.forEach(id -> depths.put(id, 0));
        Map<UUID, GraphSubgraphNodeView> nodeMap = nodes(generation, depths);
        List<EdgeCandidate> edgeCandidates = edges(
                generation,
                new LinkedHashSet<>(selectedMembers),
                new LinkedHashSet<>(selectedMembers)
        );
        truncated |= edgeCandidates.size() > EDGE_LIMIT;
        List<GraphSubgraphNodeView> orderedNodes = selectedMembers.stream()
                .map(id -> withDepth(nodeMap.get(id), 0, false))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator
                        .comparingInt(GraphSubgraphNodeView::relationshipCount)
                        .reversed()
                        .thenComparing(node -> node.id().toString()))
                .toList();
        List<GraphSubgraphEdgeView> selectedEdges = edgeCandidates.stream()
                .limit(EDGE_LIMIT)
                .map(EdgeCandidate::view)
                .toList();
        return new GraphSubgraphView(
                generation,
                GraphRootType.COMMUNITY,
                communityId,
                rootLabel,
                1,
                truncated,
                orderedNodes,
                selectedEdges
        );
    }

    private boolean addLayer(
            long generation,
            Set<UUID> frontier,
            int depth,
            LinkedHashMap<UUID, Integer> depths,
            LinkedHashMap<UUID, EdgeCandidate> selectedEdges
    ) {
        List<EdgeCandidate> candidates = edges(generation, frontier, null);
        boolean truncated = candidates.size() > EDGE_LIMIT;
        for (EdgeCandidate edge : candidates) {
            if (selectedEdges.containsKey(edge.id())) {
                continue;
            }
            boolean sourceKnown = depths.containsKey(edge.sourceEntityId());
            boolean targetKnown = depths.containsKey(edge.targetEntityId());
            UUID next = sourceKnown ? edge.targetEntityId()
                    : targetKnown ? edge.sourceEntityId() : null;
            if (next == null) {
                continue;
            }
            if (!depths.containsKey(next) && depths.size() >= NODE_LIMIT) {
                truncated = true;
                continue;
            }
            if (selectedEdges.size() >= EDGE_LIMIT) {
                return true;
            }
            depths.putIfAbsent(next, depth);
            selectedEdges.put(edge.id(), edge);
        }
        return truncated;
    }

    private Map<UUID, GraphSubgraphNodeView> nodes(
            long generation,
            Map<UUID, Integer> depths
    ) {
        if (depths.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("generation", generation)
                .addValue("entityIds", depths.keySet());
        Map<UUID, GraphSubgraphNodeView> result = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT entity.id,
                       entity.canonical_name,
                       entity.entity_type,
                       (
                         SELECT community.community_key
                         FROM graph_community_members member
                         JOIN graph_communities community
                           ON community.graph_generation =
                              member.graph_generation
                          AND community.id = member.community_id
                         WHERE member.graph_generation =
                               entity.graph_generation
                           AND member.entity_id = entity.id
                         ORDER BY community.community_key
                         LIMIT 1
                       ) AS community_key,
                       (
                         SELECT count(*)
                         FROM graph_entity_mentions mention
                         JOIN documents document
                           ON document.id = mention.document_id
                          AND document.current_revision_id =
                              mention.revision_id
                          AND document.deleted_at IS NULL
                         JOIN document_revisions revision
                           ON revision.id = mention.revision_id
                          AND revision.document_id = mention.document_id
                          AND revision.status = 'READY'
                         JOIN graph_projection_states projection
                           ON projection.graph_generation =
                              mention.graph_generation
                          AND projection.document_id = mention.document_id
                          AND projection.revision_id = mention.revision_id
                          AND projection.acl_version = document.acl_version
                          AND projection.state = 'PROJECTED'
                         WHERE mention.graph_generation =
                               entity.graph_generation
                           AND mention.entity_id = entity.id
                       ) AS mention_count,
                       (
                         SELECT count(DISTINCT relationship.id)
                         FROM graph_relationships relationship
                         JOIN graph_relationship_evidence evidence
                           ON evidence.graph_generation =
                              relationship.graph_generation
                          AND evidence.relationship_id = relationship.id
                         JOIN documents document
                           ON document.id = evidence.document_id
                          AND document.current_revision_id =
                              evidence.revision_id
                          AND document.deleted_at IS NULL
                         JOIN document_revisions revision
                           ON revision.id = evidence.revision_id
                          AND revision.document_id = evidence.document_id
                          AND revision.status = 'READY'
                         JOIN graph_projection_states projection
                           ON projection.graph_generation =
                              evidence.graph_generation
                          AND projection.document_id = evidence.document_id
                          AND projection.revision_id = evidence.revision_id
                          AND projection.acl_version = document.acl_version
                          AND projection.state = 'PROJECTED'
                         WHERE relationship.graph_generation =
                               entity.graph_generation
                           AND (
                             relationship.source_entity_id = entity.id
                             OR relationship.target_entity_id = entity.id
                           )
                       ) AS relationship_count
                FROM graph_entities entity
                WHERE entity.graph_generation = :generation
                  AND entity.id IN (:entityIds)
                  AND
                """ + VISIBLE_ENTITY + """
                ORDER BY entity.id
                """,
                parameters,
                resultSet -> {
                    UUID id = resultSet.getObject("id", UUID.class);
                    Integer communityKey = resultSet.getObject(
                            "community_key", Integer.class
                    );
                    result.put(id, new GraphSubgraphNodeView(
                            id,
                            resultSet.getString("canonical_name"),
                            resultSet.getString("entity_type"),
                            communityKey,
                            depths.getOrDefault(id, 0),
                            resultSet.getInt("mention_count"),
                            resultSet.getInt("relationship_count"),
                            false
                    ));
                }
        );
        return result;
    }

    private List<EdgeCandidate> edges(
            long generation,
            Set<UUID> incidentIds,
            Set<UUID> allowedIds
    ) {
        if (incidentIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("generation", generation)
                .addValue("incidentIds", incidentIds)
                .addValue("limit", EDGE_LIMIT + 1);
        String allowed = "";
        if (allowedIds != null) {
            parameters.addValue("allowedIds", allowedIds);
            allowed = """
                    AND relationship.source_entity_id IN (:allowedIds)
                    AND relationship.target_entity_id IN (:allowedIds)
                    """;
        }
        return jdbc.query(
                """
                SELECT relationship.id,
                       relationship.source_entity_id,
                       relationship.target_entity_id,
                       relationship.relationship_type,
                       left(relationship.description, 300) AS description,
                       count(DISTINCT evidence.id) AS evidence_count
                FROM graph_relationships relationship
                JOIN graph_relationship_evidence evidence
                  ON evidence.graph_generation =
                     relationship.graph_generation
                 AND evidence.relationship_id = relationship.id
                JOIN documents document
                  ON document.id = evidence.document_id
                 AND document.current_revision_id = evidence.revision_id
                 AND document.deleted_at IS NULL
                JOIN document_revisions revision
                  ON revision.id = evidence.revision_id
                 AND revision.document_id = evidence.document_id
                 AND revision.status = 'READY'
                JOIN graph_projection_states projection
                  ON projection.graph_generation = evidence.graph_generation
                 AND projection.document_id = evidence.document_id
                 AND projection.revision_id = evidence.revision_id
                 AND projection.acl_version = document.acl_version
                 AND projection.state = 'PROJECTED'
                JOIN graph_entities source
                  ON source.graph_generation = relationship.graph_generation
                 AND source.id = relationship.source_entity_id
                JOIN graph_entities target
                  ON target.graph_generation = relationship.graph_generation
                 AND target.id = relationship.target_entity_id
                WHERE relationship.graph_generation = :generation
                  AND (
                    relationship.source_entity_id IN (:incidentIds)
                    OR relationship.target_entity_id IN (:incidentIds)
                  )
                  AND
                """ + VISIBLE_ENTITY.replace("entity.", "source.")
                        + " AND\n"
                        + VISIBLE_ENTITY.replace("entity.", "target.")
                        + allowed + """
                GROUP BY relationship.id,
                         relationship.source_entity_id,
                         relationship.target_entity_id,
                         relationship.relationship_type,
                         relationship.description
                ORDER BY count(DISTINCT evidence.id) DESC,
                         relationship.id
                LIMIT :limit
                """,
                parameters,
                (resultSet, rowNumber) -> new EdgeCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject(
                                "source_entity_id", UUID.class
                        ),
                        resultSet.getObject(
                                "target_entity_id", UUID.class
                        ),
                        resultSet.getString("relationship_type"),
                        resultSet.getString("description"),
                        resultSet.getInt("evidence_count")
                )
        );
    }

    private String communityLabel(long generation, UUID communityId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("generation", generation)
                .addValue("communityId", communityId);
        return jdbc.query(
                """
                SELECT community.title
                FROM graph_communities community
                WHERE community.graph_generation = :generation
                  AND community.id = :communityId
                  AND EXISTS (
                    SELECT 1
                    FROM graph_community_members member
                    JOIN graph_entities entity
                      ON entity.graph_generation = member.graph_generation
                     AND entity.id = member.entity_id
                    WHERE member.graph_generation =
                          community.graph_generation
                      AND member.community_id = community.id
                      AND
                """ + VISIBLE_ENTITY + ")",
                parameters,
                (resultSet, rowNumber) -> resultSet.getString("title")
        ).stream().findFirst().orElseThrow(() -> notFound("Community"));
    }

    private List<UUID> communityMembers(
            long generation,
            UUID communityId
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("generation", generation)
                .addValue("communityId", communityId)
                .addValue("limit", NODE_LIMIT + 1);
        return jdbc.queryForList(
                """
                SELECT member.entity_id
                FROM graph_community_members member
                JOIN graph_entities entity
                  ON entity.graph_generation = member.graph_generation
                 AND entity.id = member.entity_id
                WHERE member.graph_generation = :generation
                  AND member.community_id = :communityId
                  AND
                """ + VISIBLE_ENTITY + """
                ORDER BY (
                  SELECT count(DISTINCT relationship.id)
                  FROM graph_relationships relationship
                  JOIN graph_relationship_evidence evidence
                    ON evidence.graph_generation =
                       relationship.graph_generation
                   AND evidence.relationship_id = relationship.id
                  JOIN documents document
                    ON document.id = evidence.document_id
                   AND document.current_revision_id = evidence.revision_id
                   AND document.deleted_at IS NULL
                  JOIN graph_projection_states projection
                    ON projection.graph_generation =
                       evidence.graph_generation
                   AND projection.document_id = evidence.document_id
                   AND projection.revision_id = evidence.revision_id
                   AND projection.acl_version = document.acl_version
                   AND projection.state = 'PROJECTED'
                  WHERE relationship.graph_generation =
                        member.graph_generation
                    AND (
                      relationship.source_entity_id = member.entity_id
                      OR relationship.target_entity_id = member.entity_id
                    )
                ) DESC,
                member.entity_id
                LIMIT :limit
                """,
                parameters,
                UUID.class
        );
    }

    private String relationshipEvidenceSql() {
        return """
                SELECT relationship.id,
                       relationship.source_entity_id,
                       source.canonical_name AS source_name,
                       relationship.target_entity_id,
                       target.canonical_name AS target_name,
                       relationship.relationship_type,
                       relationship.description,
                       evidence.id AS evidence_id,
                       evidence.document_id,
                       document.title AS document_title,
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
                FROM graph_relationships relationship
                JOIN graph_entities source
                  ON source.id = relationship.source_entity_id
                 AND source.graph_generation = relationship.graph_generation
                JOIN graph_entities target
                  ON target.id = relationship.target_entity_id
                 AND target.graph_generation = relationship.graph_generation
                JOIN graph_relationship_evidence evidence
                  ON evidence.relationship_id = relationship.id
                 AND evidence.graph_generation = relationship.graph_generation
                JOIN documents document
                  ON document.id = evidence.document_id
                 AND document.current_revision_id = evidence.revision_id
                 AND document.deleted_at IS NULL
                JOIN document_revisions revision
                  ON revision.id = evidence.revision_id
                 AND revision.document_id = evidence.document_id
                 AND revision.status = 'READY'
                JOIN graph_projection_states projection
                  ON projection.graph_generation = evidence.graph_generation
                 AND projection.document_id = evidence.document_id
                 AND projection.revision_id = evidence.revision_id
                 AND projection.acl_version = document.acl_version
                 AND projection.state = 'PROJECTED'
                JOIN source_spans span
                  ON span.id = evidence.source_span_id
                 AND span.chunk_id = evidence.child_chunk_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                WHERE relationship.graph_generation = :generation
                  AND relationship.id = :relationshipId
                  AND
                """ + VISIBLE_ENTITY.replace("entity.", "source.")
                        + " AND\n"
                        + VISIBLE_ENTITY.replace("entity.", "target.")
                        + """
                ORDER BY evidence.id
                LIMIT 500
                """;
    }

    private void requireBrowsable(long generation) {
        GraphGenerationRepository.ManifestRow manifest =
                generations.manifest(generation);
        if (!BROWSABLE_STATUSES.contains(manifest.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "GRAPH_GENERATION_NOT_BROWSABLE",
                    "该 Graph Generation 当前不可浏览"
            );
        }
    }

    private void setStatementTimeout() {
        jdbc.queryForObject(
                """
                SELECT set_config(
                    'statement_timeout', :timeout, TRUE
                )
                """,
                new MapSqlParameterSource(
                        "timeout", STATEMENT_TIMEOUT_MS + "ms"
                ),
                String.class
        );
    }

    private static GraphSubgraphNodeView withDepth(
            GraphSubgraphNodeView node,
            int depth,
            boolean root
    ) {
        if (node == null) {
            return null;
        }
        return new GraphSubgraphNodeView(
                node.id(),
                node.name(),
                node.entityType(),
                node.communityKey(),
                depth,
                node.mentionCount(),
                node.relationshipCount(),
                root
        );
    }

    private static RelationshipRows relationshipRows(ResultSet resultSet) {
        try {
            return new RelationshipRows(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("source_entity_id", UUID.class),
                    resultSet.getString("source_name"),
                    resultSet.getObject("target_entity_id", UUID.class),
                    resultSet.getString("target_name"),
                    resultSet.getString("relationship_type"),
                    resultSet.getString("description"),
                    new ArrayList<>()
            );
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static GraphRelationshipEvidenceView evidence(
            ResultSet resultSet
    ) throws SQLException {
        SourceLocatorResponse locator = new SourceLocatorResponse(
                resultSet.getString("locator_kind"),
                resultSet.getObject("start_source_unit_id", UUID.class),
                resultSet.getObject("end_source_unit_id", UUID.class),
                resultSet.getInt("start_offset"),
                resultSet.getInt("end_offset"),
                resultSet.getString("locator_address"),
                resultSet.getString("source_text_hash"),
                resultSet.getString("normalization_version"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class),
                resultSet.getString("source_label")
        );
        return new GraphRelationshipEvidenceView(
                resultSet.getObject("evidence_id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getString("document_title"),
                resultSet.getObject("revision_id", UUID.class),
                resultSet.getInt("revision_number"),
                resultSet.getObject("child_chunk_id", UUID.class),
                resultSet.getObject("source_span_id", UUID.class),
                resultSet.getString("evidence_text"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class),
                resultSet.getString("document_format"),
                locator,
                locator.sourceLabel()
        );
    }

    private static ApiException notFound(String resource) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_RESOURCE_NOT_FOUND",
                "找不到" + resource
        );
    }

    private record EdgeCandidate(
            UUID id,
            UUID sourceEntityId,
            UUID targetEntityId,
            String relationshipType,
            String description,
            int evidenceCount
    ) {
        GraphSubgraphEdgeView view() {
            return new GraphSubgraphEdgeView(
                    id,
                    sourceEntityId,
                    targetEntityId,
                    relationshipType,
                    description,
                    evidenceCount
            );
        }
    }

    private record RelationshipRows(
            UUID id,
            UUID sourceEntityId,
            String sourceName,
            UUID targetEntityId,
            String targetName,
            String relationshipType,
            String description,
            List<GraphRelationshipEvidenceView> evidence
    ) {
        GraphRelationshipView view() {
            return new GraphRelationshipView(
                    id,
                    sourceEntityId,
                    sourceName,
                    targetEntityId,
                    targetName,
                    relationshipType,
                    description,
                    evidence
            );
        }
    }
}
