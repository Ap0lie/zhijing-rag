package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.graph.GraphApiContracts.GraphCommunityClaimView;
import com.example.rag.graph.GraphApiContracts.GraphCommunityDetail;
import com.example.rag.graph.GraphApiContracts.GraphCommunityPage;
import com.example.rag.graph.GraphApiContracts.GraphCommunitySummary;
import com.example.rag.graph.GraphApiContracts.GraphEntityDetail;
import com.example.rag.graph.GraphApiContracts.GraphEntityPage;
import com.example.rag.graph.GraphApiContracts.GraphEntitySummary;
import com.example.rag.graph.GraphApiContracts.GraphMentionView;
import com.example.rag.graph.GraphApiContracts.GraphRelationshipEvidenceView;
import com.example.rag.graph.GraphApiContracts.GraphRelationshipView;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
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
class GraphQueryService {

    private static final String VISIBLE_MENTION = """
            SELECT 1
            FROM graph_entity_mentions mention
            JOIN documents document
              ON document.id = mention.document_id
             AND document.current_revision_id = mention.revision_id
             AND document.deleted_at IS NULL
            JOIN document_revisions revision
              ON revision.id = mention.revision_id
             AND revision.document_id = mention.document_id
             AND revision.status = 'READY'
            JOIN graph_projection_states projection
              ON projection.graph_generation = mention.graph_generation
             AND projection.document_id = mention.document_id
             AND projection.revision_id = mention.revision_id
             AND projection.acl_version = document.acl_version
             AND projection.state = 'PROJECTED'
            JOIN users request_user
              ON request_user.id = :userId
             AND request_user.enabled = TRUE
             AND request_user.role = 'ADMIN'
             AND request_user.role = :sessionRole
             AND request_user.password_hash = :sessionPasswordHash
            WHERE mention.graph_generation = entity.graph_generation
              AND mention.entity_id = entity.id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final GraphGenerationRepository generations;

    GraphQueryService(
            NamedParameterJdbcTemplate jdbc,
            GraphGenerationRepository generations
    ) {
        this.jdbc = jdbc;
        this.generations = generations;
    }

    @Transactional(readOnly = true)
    void requireAdmin(PlatformUserPrincipal user) {
        if (user == null) {
            throw forbidden();
        }
        Integer valid = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM users
                WHERE id = :userId
                  AND enabled = TRUE
                  AND role = 'ADMIN'
                  AND role = :sessionRole
                  AND password_hash = :sessionPasswordHash
                """,
                parameters(user),
                Integer.class
        );
        if (valid == null || valid != 1) {
            throw forbidden();
        }
    }

    @Transactional
    void requireAdminForUpdate(PlatformUserPrincipal user) {
        if (user == null || jdbc.queryForList(
                """
                SELECT id
                FROM users
                WHERE id = :userId
                  AND enabled = TRUE
                  AND role = 'ADMIN'
                  AND role = :sessionRole
                  AND password_hash = :sessionPasswordHash
                FOR SHARE
                """,
                parameters(user),
                UUID.class
        ).size() != 1) {
            throw forbidden();
        }
    }

    @Transactional(readOnly = true)
    GraphEntityPage entities(
            PlatformUserPrincipal user,
            Long requestedGeneration,
            String query,
            String entityType,
            String cursor,
            int page,
            int size
    ) {
        long generation = generation(requestedGeneration);
        EntityCursor decodedCursor = decodeCursor(cursor);
        int safePage = decodedCursor == null ? Math.max(0, page) : 0;
        int safeSize = Math.clamp(size, 1, 100);
        String filter = GraphAssembler.normalize(query);
        String typeFilter = GraphAssembler.normalizeType(entityType);
        MapSqlParameterSource parameters = parameters(user)
                .addValue("generation", generation)
                .addValue("query", filter)
                .addValue("entityType", typeFilter)
                .addValue("hasCursor", decodedCursor != null)
                .addValue("cursorType", decodedCursor == null
                        ? "" : decodedCursor.entityType())
                .addValue("cursorName", decodedCursor == null
                        ? "" : decodedCursor.normalizedName())
                .addValue("cursorId", decodedCursor == null
                        ? new UUID(0, 0) : decodedCursor.id())
                .addValue("limit", safeSize + 1)
                .addValue("offset", safePage * safeSize);
        String baseWhere = """
                entity.graph_generation = :generation
                AND (:entityType = '' OR entity.entity_type = :entityType)
                AND (
                  :query = ''
                  OR entity.normalized_name LIKE :query || '%'
                  OR EXISTS (
                    SELECT 1
                    FROM graph_entity_aliases alias
                    JOIN graph_entity_alias_evidence alias_evidence
                      ON alias_evidence.graph_generation =
                         alias.graph_generation
                     AND alias_evidence.entity_id = alias.entity_id
                     AND alias_evidence.normalized_alias =
                         alias.normalized_alias
                    JOIN graph_entity_mentions alias_mention
                      ON alias_mention.id = alias_evidence.mention_id
                     AND alias_mention.graph_generation =
                         alias_evidence.graph_generation
                    JOIN documents alias_document
                      ON alias_document.id = alias_mention.document_id
                     AND alias_document.current_revision_id =
                         alias_mention.revision_id
                     AND alias_document.deleted_at IS NULL
                    JOIN document_revisions alias_revision
                      ON alias_revision.id = alias_mention.revision_id
                     AND alias_revision.document_id = alias_mention.document_id
                     AND alias_revision.status = 'READY'
                    JOIN graph_projection_states alias_projection
                      ON alias_projection.graph_generation =
                         alias_mention.graph_generation
                     AND alias_projection.document_id =
                         alias_mention.document_id
                     AND alias_projection.revision_id =
                         alias_mention.revision_id
                     AND alias_projection.acl_version =
                         alias_document.acl_version
                     AND alias_projection.state = 'PROJECTED'
                    WHERE alias.graph_generation = entity.graph_generation
                      AND alias.entity_id = entity.id
                      AND alias.normalized_alias LIKE :query || '%'
                  )
                )
                AND EXISTS (
                """ + VISIBLE_MENTION + ")";
        long total = value(jdbc.queryForObject(
                "SELECT count(*) FROM graph_entities entity WHERE "
                        + baseWhere,
                parameters,
                Long.class
        ));
        List<SearchEntity> rows = jdbc.query(
                """
                SELECT entity.id,
                       entity.canonical_name,
                       entity.normalized_name,
                       entity.entity_type,
                       entity.description,
                       (
                         SELECT count(*)
                         FROM graph_entity_mentions mention
                         JOIN documents document
                           ON document.id = mention.document_id
                          AND document.current_revision_id = mention.revision_id
                          AND document.deleted_at IS NULL
                         JOIN graph_projection_states projection
                           ON projection.graph_generation =
                              mention.graph_generation
                          AND projection.document_id = mention.document_id
                          AND projection.revision_id = mention.revision_id
                          AND projection.acl_version = document.acl_version
                          AND projection.state = 'PROJECTED'
                         WHERE mention.graph_generation = entity.graph_generation
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
                       ) AS relationship_count,
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
                         LIMIT 1
                       ) AS community_key,
                       COALESCE((
                         SELECT array_agg(DISTINCT alias.alias
                                          ORDER BY alias.alias)
                         FROM graph_entity_aliases alias
                         JOIN graph_entity_alias_evidence alias_evidence
                           ON alias_evidence.graph_generation =
                              alias.graph_generation
                          AND alias_evidence.entity_id = alias.entity_id
                          AND alias_evidence.normalized_alias =
                              alias.normalized_alias
                         JOIN graph_entity_mentions alias_mention
                           ON alias_mention.id = alias_evidence.mention_id
                          AND alias_mention.graph_generation =
                              alias_evidence.graph_generation
                         JOIN documents alias_document
                           ON alias_document.id = alias_mention.document_id
                          AND alias_document.current_revision_id =
                              alias_mention.revision_id
                          AND alias_document.deleted_at IS NULL
                         JOIN graph_projection_states alias_projection
                           ON alias_projection.graph_generation =
                              alias_mention.graph_generation
                          AND alias_projection.document_id =
                              alias_mention.document_id
                          AND alias_projection.revision_id =
                              alias_mention.revision_id
                          AND alias_projection.acl_version =
                              alias_document.acl_version
                          AND alias_projection.state = 'PROJECTED'
                         WHERE alias.graph_generation = entity.graph_generation
                           AND alias.entity_id = entity.id
                       ), ARRAY[]::text[]) AS aliases,
                       (
                         SELECT alias.alias
                         FROM graph_entity_aliases alias
                         JOIN graph_entity_alias_evidence alias_evidence
                           ON alias_evidence.graph_generation =
                              alias.graph_generation
                          AND alias_evidence.entity_id = alias.entity_id
                          AND alias_evidence.normalized_alias =
                              alias.normalized_alias
                         JOIN graph_entity_mentions alias_mention
                           ON alias_mention.id = alias_evidence.mention_id
                          AND alias_mention.graph_generation =
                              alias_evidence.graph_generation
                         JOIN documents alias_document
                           ON alias_document.id = alias_mention.document_id
                          AND alias_document.current_revision_id =
                              alias_mention.revision_id
                          AND alias_document.deleted_at IS NULL
                         JOIN graph_projection_states alias_projection
                           ON alias_projection.graph_generation =
                              alias_mention.graph_generation
                          AND alias_projection.document_id =
                              alias_mention.document_id
                          AND alias_projection.revision_id =
                              alias_mention.revision_id
                          AND alias_projection.acl_version =
                              alias_document.acl_version
                          AND alias_projection.state = 'PROJECTED'
                         WHERE alias.graph_generation = entity.graph_generation
                           AND alias.entity_id = entity.id
                           AND :query <> ''
                           AND alias.normalized_alias LIKE :query || '%'
                         ORDER BY alias.normalized_alias, alias.alias
                         LIMIT 1
                       ) AS matched_alias
                FROM graph_entities entity
                WHERE
                """ + baseWhere + """
                  AND (
                    :hasCursor = FALSE
                    OR (entity.entity_type,
                        entity.normalized_name,
                        entity.id) >
                       (:cursorType, :cursorName, :cursorId)
                  )
                ORDER BY entity.entity_type,
                         entity.normalized_name,
                         entity.id
                LIMIT :limit OFFSET :offset
                """,
                parameters,
                (resultSet, rowNumber) -> searchEntity(resultSet, filter)
        );
        boolean hasNext = rows.size() > safeSize;
        if (hasNext) {
            rows = new ArrayList<>(rows.subList(0, safeSize));
        }
        String nextCursor = hasNext && !rows.isEmpty()
                ? encodeCursor(rows.getLast())
                : null;
        List<GraphEntitySummary> items = rows.stream()
                .map(SearchEntity::summary)
                .toList();
        return new GraphEntityPage(
                generation,
                safePage,
                safeSize,
                total,
                nextCursor,
                items
        );
    }

    @Transactional(readOnly = true)
    GraphEntityDetail entity(
            PlatformUserPrincipal user,
            Long requestedGeneration,
            UUID entityId
    ) {
        long generation = generation(requestedGeneration);
        MapSqlParameterSource parameters = parameters(user)
                .addValue("generation", generation)
                .addValue("entityId", entityId);
        GraphEntitySummary summary = jdbc.query(
                """
                SELECT entity.id,
                       entity.canonical_name,
                       entity.entity_type,
                       entity.description,
                       (
                         SELECT count(*)
                         FROM graph_entity_mentions mention
                         JOIN documents document
                           ON document.id = mention.document_id
                          AND document.current_revision_id = mention.revision_id
                          AND document.deleted_at IS NULL
                         WHERE mention.graph_generation = entity.graph_generation
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
                         WHERE relationship.graph_generation =
                               entity.graph_generation
                           AND (
                             relationship.source_entity_id = entity.id
                             OR relationship.target_entity_id = entity.id
                           )
                       ) AS relationship_count,
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
                         LIMIT 1
                       ) AS community_key
                FROM graph_entities entity
                WHERE entity.graph_generation = :generation
                  AND entity.id = :entityId
                  AND EXISTS (
                """ + VISIBLE_MENTION + ")",
                parameters,
                (resultSet, rowNumber) -> entity(resultSet)
        ).stream().findFirst().orElseThrow(() -> notFound("实体"));
        List<String> aliases = jdbc.queryForList(
                """
                SELECT DISTINCT alias.alias
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
                JOIN documents document
                  ON document.id = mention.document_id
                 AND document.current_revision_id = mention.revision_id
                 AND document.deleted_at IS NULL
                WHERE alias.graph_generation = :generation
                  AND alias.entity_id = :entityId
                ORDER BY alias.alias
                LIMIT 200
                """,
                parameters,
                String.class
        );
        List<GraphMentionView> mentions = jdbc.query(
                """
                SELECT mention.id,
                       mention.document_id,
                       document.title AS document_title,
                       mention.revision_id,
                       revision.revision_number,
                       mention.child_chunk_id,
                       mention.source_span_id,
                       mention.surface_text,
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
                FROM graph_entity_mentions mention
                JOIN documents document
                  ON document.id = mention.document_id
                 AND document.current_revision_id = mention.revision_id
                 AND document.deleted_at IS NULL
                JOIN document_revisions revision
                  ON revision.id = mention.revision_id
                 AND revision.document_id = mention.document_id
                 AND revision.status = 'READY'
                JOIN source_spans span
                  ON span.id = mention.source_span_id
                 AND span.chunk_id = mention.child_chunk_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                JOIN users request_user
                  ON request_user.id = :userId
                 AND request_user.enabled = TRUE
                 AND request_user.role = 'ADMIN'
                 AND request_user.role = :sessionRole
                 AND request_user.password_hash = :sessionPasswordHash
                WHERE mention.graph_generation = :generation
                  AND mention.entity_id = :entityId
                ORDER BY document.title,
                         revision.revision_number,
                         location.start_unit_order,
                         mention.id
                LIMIT 200
                """,
                parameters,
                (resultSet, rowNumber) -> mention(resultSet)
        );
        return new GraphEntityDetail(
                summary,
                aliases,
                mentions,
                relationships(parameters)
        );
    }

    @Transactional(readOnly = true)
    GraphCommunityPage communities(
            PlatformUserPrincipal user,
            Long requestedGeneration,
            int page,
            int size
    ) {
        long generation = generation(requestedGeneration);
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        MapSqlParameterSource parameters = parameters(user)
                .addValue("generation", generation)
                .addValue("limit", safeSize)
                .addValue("offset", safePage * safeSize);
        String visible = """
                EXISTS (
                  SELECT 1
                  FROM graph_community_members member
                  JOIN graph_entities entity
                    ON entity.id = member.entity_id
                   AND entity.graph_generation = member.graph_generation
                  WHERE member.graph_generation =
                        community.graph_generation
                    AND member.community_id = community.id
                    AND EXISTS (
                """ + VISIBLE_MENTION + "))";
        long total = value(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM graph_communities community
                WHERE community.graph_generation = :generation
                  AND
                """ + visible,
                parameters,
                Long.class
        ));
        List<GraphCommunitySummary> items = jdbc.query(
                """
                SELECT community.id,
                       community.community_key,
                       community.title,
                       community.summary,
                       community.entity_count,
                       (
                         SELECT count(*)
                         FROM graph_community_claims claim
                         JOIN graph_relationship_evidence evidence
                           ON evidence.id =
                              claim.relationship_evidence_id
                          AND evidence.graph_generation =
                              claim.graph_generation
                         JOIN documents document
                           ON document.id = evidence.document_id
                          AND document.current_revision_id =
                              evidence.revision_id
                          AND document.deleted_at IS NULL
                         WHERE claim.graph_generation =
                               community.graph_generation
                           AND claim.community_id = community.id
                       ) AS claim_count
                FROM graph_communities community
                WHERE community.graph_generation = :generation
                  AND
                """ + visible + """
                ORDER BY community.community_key
                LIMIT :limit OFFSET :offset
                """,
                parameters,
                (resultSet, rowNumber) -> community(resultSet)
        );
        return new GraphCommunityPage(
                generation,
                safePage,
                safeSize,
                total,
                items
        );
    }

    @Transactional(readOnly = true)
    GraphCommunityDetail community(
            PlatformUserPrincipal user,
            Long requestedGeneration,
            UUID communityId
    ) {
        long generation = generation(requestedGeneration);
        MapSqlParameterSource parameters = parameters(user)
                .addValue("generation", generation)
                .addValue("communityId", communityId);
        GraphCommunitySummary summary = jdbc.query(
                """
                SELECT community.id,
                       community.community_key,
                       community.title,
                       community.summary,
                       community.entity_count,
                       (
                         SELECT count(*)
                         FROM graph_community_claims claim
                         WHERE claim.graph_generation =
                               community.graph_generation
                           AND claim.community_id = community.id
                       ) AS claim_count
                FROM graph_communities community
                WHERE community.graph_generation = :generation
                  AND community.id = :communityId
                  AND EXISTS (
                    SELECT 1
                    FROM graph_community_members member
                    JOIN graph_entities entity
                      ON entity.id = member.entity_id
                     AND entity.graph_generation =
                         member.graph_generation
                    WHERE member.graph_generation =
                          community.graph_generation
                      AND member.community_id = community.id
                      AND EXISTS (
                """ + VISIBLE_MENTION + "))",
                parameters,
                (resultSet, rowNumber) -> community(resultSet)
        ).stream().findFirst().orElseThrow(() -> notFound("Community"));
        List<GraphEntitySummary> entities = jdbc.query(
                """
                SELECT entity.id,
                       entity.canonical_name,
                       entity.entity_type,
                       entity.description,
                       (
                         SELECT count(*)
                         FROM graph_entity_mentions mention
                         JOIN documents document
                           ON document.id = mention.document_id
                          AND document.current_revision_id =
                              mention.revision_id
                          AND document.deleted_at IS NULL
                         WHERE mention.graph_generation =
                               entity.graph_generation
                           AND mention.entity_id = entity.id
                       ) AS mention_count,
                       (
                         SELECT count(DISTINCT relationship.id)
                         FROM graph_relationships relationship
                         JOIN graph_relationship_evidence evidence
                           ON evidence.relationship_id = relationship.id
                          AND evidence.graph_generation =
                              relationship.graph_generation
                         JOIN documents document
                           ON document.id = evidence.document_id
                          AND document.current_revision_id =
                              evidence.revision_id
                          AND document.deleted_at IS NULL
                         WHERE relationship.graph_generation =
                               entity.graph_generation
                           AND (
                             relationship.source_entity_id = entity.id
                             OR relationship.target_entity_id = entity.id
                           )
                       ) AS relationship_count,
                       community.community_key
                FROM graph_community_members member
                JOIN graph_entities entity
                  ON entity.id = member.entity_id
                 AND entity.graph_generation = member.graph_generation
                JOIN graph_communities community
                  ON community.id = member.community_id
                 AND community.graph_generation =
                     member.graph_generation
                WHERE member.graph_generation = :generation
                  AND member.community_id = :communityId
                  AND EXISTS (
                """ + VISIBLE_MENTION + """
                  )
                ORDER BY member.member_order
                LIMIT 200
                """,
                parameters,
                (resultSet, rowNumber) -> entity(resultSet)
        );
        List<GraphCommunityClaimView> claims = jdbc.query(
                """
                SELECT claim.id,
                       claim.claim_text,
                       claim.relationship_id,
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
                FROM graph_community_claims claim
                JOIN graph_relationship_evidence evidence
                  ON evidence.id = claim.relationship_evidence_id
                 AND evidence.graph_generation = claim.graph_generation
                JOIN documents document
                  ON document.id = evidence.document_id
                 AND document.current_revision_id = evidence.revision_id
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
                JOIN users request_user
                  ON request_user.id = :userId
                 AND request_user.enabled = TRUE
                 AND request_user.role = 'ADMIN'
                 AND request_user.role = :sessionRole
                 AND request_user.password_hash =
                     :sessionPasswordHash
                WHERE claim.graph_generation = :generation
                  AND claim.community_id = :communityId
                ORDER BY claim.id
                LIMIT 200
                """,
                parameters,
                (resultSet, rowNumber) -> new GraphCommunityClaimView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("claim_text"),
                        resultSet.getObject(
                                "relationship_id",
                                UUID.class
                        ),
                        evidence(resultSet)
                )
        );
        return new GraphCommunityDetail(summary, entities, claims);
    }

    private List<GraphRelationshipView> relationships(
            MapSqlParameterSource parameters
    ) {
        Map<UUID, RelationshipRows> relationships = new LinkedHashMap<>();
        jdbc.query(
                """
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
                 AND source.graph_generation =
                     relationship.graph_generation
                JOIN graph_entities target
                  ON target.id = relationship.target_entity_id
                 AND target.graph_generation =
                     relationship.graph_generation
                JOIN graph_relationship_evidence evidence
                  ON evidence.relationship_id = relationship.id
                 AND evidence.graph_generation =
                     relationship.graph_generation
                JOIN documents document
                  ON document.id = evidence.document_id
                 AND document.current_revision_id = evidence.revision_id
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
                JOIN users request_user
                  ON request_user.id = :userId
                 AND request_user.enabled = TRUE
                 AND request_user.role = 'ADMIN'
                 AND request_user.role = :sessionRole
                 AND request_user.password_hash =
                     :sessionPasswordHash
                WHERE relationship.graph_generation = :generation
                  AND (
                    relationship.source_entity_id = :entityId
                    OR relationship.target_entity_id = :entityId
                  )
                ORDER BY relationship.relationship_type,
                         relationship.id,
                         evidence.id
                LIMIT 500
                """,
                parameters,
                resultSet -> {
                    UUID id = resultSet.getObject("id", UUID.class);
                    RelationshipRows rows = relationships.computeIfAbsent(
                            id,
                            ignored -> relationship(resultSet)
                    );
                    rows.evidence().add(evidence(resultSet));
                }
        );
        return relationships.values().stream()
                .map(RelationshipRows::view)
                .toList();
    }

    private long generation(Long requested) {
        Long active = requested == null
                ? generations.activeGeneration()
                : requested;
        if (active == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "GRAPH_NOT_PUBLISHED",
                    "当前没有已发布的 Graph Generation"
            );
        }
        long generation = active;
        generations.manifest(generation);
        return generation;
    }

    private static MapSqlParameterSource parameters(
            PlatformUserPrincipal user
    ) {
        if (user == null) {
            throw forbidden();
        }
        return new MapSqlParameterSource()
                .addValue("userId", user.id())
                .addValue("sessionRole", user.role().name())
                .addValue("sessionPasswordHash", user.getPassword());
    }

    private static GraphEntitySummary entity(
            ResultSet resultSet
    ) throws SQLException {
        int communityKey = resultSet.getInt("community_key");
        return new GraphEntitySummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("canonical_name"),
                resultSet.getString("entity_type"),
                resultSet.getString("description"),
                resultSet.getInt("mention_count"),
                resultSet.getInt("relationship_count"),
                resultSet.wasNull() ? null : communityKey,
                List.of(),
                null,
                null
        );
    }

    private static SearchEntity searchEntity(
            ResultSet resultSet,
            String normalizedQuery
    ) throws SQLException {
        int communityKey = resultSet.getInt("community_key");
        Integer community = resultSet.wasNull() ? null : communityKey;
        String normalizedName = resultSet.getString("normalized_name");
        String matchedAlias = resultSet.getString("matched_alias");
        String matchSource = normalizedQuery.isEmpty()
                ? null
                : normalizedName.startsWith(normalizedQuery)
                ? "CANONICAL_NAME"
                : matchedAlias == null ? null : "ALIAS";
        return new SearchEntity(
                new GraphEntitySummary(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("canonical_name"),
                        resultSet.getString("entity_type"),
                        resultSet.getString("description"),
                        resultSet.getInt("mention_count"),
                        resultSet.getInt("relationship_count"),
                        community,
                        strings(resultSet.getArray("aliases")),
                        matchSource,
                        matchedAlias
                ),
                normalizedName
        );
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    private static EntityCursor decodeCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8
            );
            String[] parts = decoded.split("\\u001f", -1);
            if (parts.length != 3
                    || parts[0].isBlank()
                    || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new EntityCursor(
                    parts[0], parts[1], UUID.fromString(parts[2])
            );
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GRAPH_ENTITY_CURSOR_INVALID",
                    "实体搜索游标无效，请重新搜索"
            );
        }
    }

    private static String encodeCursor(SearchEntity entity) {
        String value = entity.summary().entityType()
                + '\u001f' + entity.normalizedName()
                + '\u001f' + entity.summary().id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record SearchEntity(
            GraphEntitySummary summary,
            String normalizedName
    ) {
    }

    private record EntityCursor(
            String entityType,
            String normalizedName,
            UUID id
    ) {
    }

    private static GraphMentionView mention(
            ResultSet resultSet
    ) throws SQLException {
        SourceLocatorResponse locator = locator(resultSet);
        return new GraphMentionView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getString("document_title"),
                resultSet.getObject("revision_id", UUID.class),
                resultSet.getInt("revision_number"),
                resultSet.getObject("child_chunk_id", UUID.class),
                resultSet.getObject("source_span_id", UUID.class),
                resultSet.getString("surface_text"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class),
                resultSet.getString("document_format"),
                locator,
                locator.sourceLabel()
        );
    }

    private static GraphRelationshipEvidenceView evidence(
            ResultSet resultSet
    ) throws SQLException {
        SourceLocatorResponse locator = locator(resultSet);
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

    private static SourceLocatorResponse locator(
            ResultSet resultSet
    ) throws SQLException {
        return new SourceLocatorResponse(
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
    }

    private static RelationshipRows relationship(
            ResultSet resultSet
    ) {
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

    private static GraphCommunitySummary community(
            ResultSet resultSet
    ) throws SQLException {
        return new GraphCommunitySummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getInt("community_key"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                resultSet.getInt("entity_count"),
                resultSet.getInt("claim_count")
        );
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static ApiException forbidden() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "GRAPH_ADMIN_REQUIRED",
                "当前管理员会话已失效"
        );
    }

    private static ApiException notFound(String resource) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_RESOURCE_NOT_FOUND",
                "找不到" + resource
        );
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
                    List.copyOf(evidence)
            );
        }
    }
}
