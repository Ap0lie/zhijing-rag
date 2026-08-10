package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GraphRetrievalContracts.ConfigurationResponse;
import com.example.rag.graph.GraphRetrievalContracts.CreateProfileRequest;
import com.example.rag.graph.GraphRetrievalContracts.ProfileView;
import com.example.rag.graph.GraphRetrievalContracts.PublicationView;
import com.example.rag.graph.GraphRetrievalContracts.ReleaseProfileRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
class GraphRetrievalConfigurationService {

    private final JdbcTemplate jdbc;

    GraphRetrievalConfigurationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    ConfigurationResponse configuration() {
        return new ConfigurationResponse(
                publication(),
                activeGraphGeneration(),
                profiles()
        );
    }

    @Transactional(readOnly = true)
    ProfileView currentProfile() {
        return profile(publication().profileVersion());
    }

    @Transactional
    ProfileView create(CreateProfileRequest request, UUID actorId) {
        if (request.entityLimit() < request.seedLimit()) {
            throw invalidProfile();
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO graph_retrieval_profiles (
                        version, seed_limit, max_hops, entity_limit,
                        edge_limit, graph_child_limit, graph_weight,
                        graph_context_token_budget, graph_context_percent,
                        statement_timeout_ms, reason, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    request.version().trim(),
                    request.seedLimit(),
                    request.maxHops(),
                    request.entityLimit(),
                    request.edgeLimit(),
                    request.graphChildLimit(),
                    request.graphWeight(),
                    request.graphContextTokenBudget(),
                    request.graphContextPercent(),
                    request.statementTimeoutMs(),
                    request.reason().trim(),
                    actorId
            );
            return profile(request.version().trim());
        } catch (DuplicateKeyException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "GRAPH_RETRIEVAL_PROFILE_EXISTS",
                    "GraphRetrievalProfile 版本已存在",
                    exception
            );
        }
    }

    @Transactional
    PublicationView publish(
            ReleaseProfileRequest request,
            UUID actorId,
            String eventType
    ) {
        String expected = "ROLLBACK".equals(eventType)
                ? "ROLLBACK"
                : "PUBLISH";
        if (!expected.equals(request.confirmation())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GRAPH_RETRIEVAL_CONFIRMATION_INVALID",
                    "确认字段必须为 " + expected
            );
        }
        String target = profile(request.profileVersion().trim()).version();
        PublicationView current = jdbc.query(
                """
                SELECT profile_version, publication_event_id, published_at
                FROM graph_retrieval_publications
                WHERE singleton_id = 1
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> publication(resultSet)
        ).getFirst();
        if ("ROLLBACK".equals(eventType)) {
            requirePublishedRollbackTarget(target, current);
        }
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO graph_retrieval_publication_events (
                    event_type, previous_profile_version,
                    profile_version, reason, actor_user_id
                ) VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                eventType,
                current.profileVersion(),
                target,
                request.reason().trim(),
                actorId
        );
        jdbc.update(
                """
                UPDATE graph_retrieval_publications
                SET profile_version = ?,
                    publication_event_id = ?,
                    published_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1
                """,
                target,
                eventId
        );
        return publication();
    }

    private void requirePublishedRollbackTarget(
            String target,
            PublicationView current
    ) {
        if (target.equals(current.profileVersion())) {
            throw invalidRollbackTarget();
        }
        Boolean wasPublished = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM graph_retrieval_publication_events
                    WHERE profile_version = ?
                      AND id < ?
                )
                """,
                Boolean.class,
                target,
                current.publicationEventId()
        );
        if (!Boolean.TRUE.equals(wasPublished)) {
            throw invalidRollbackTarget();
        }
    }

    private static ApiException invalidRollbackTarget() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "GRAPH_RETRIEVAL_ROLLBACK_TARGET_INVALID",
                "只能回滚到此前已发布且非当前的 GraphRetrievalProfile"
        );
    }

    private PublicationView publication() {
        return jdbc.query(
                """
                SELECT profile_version, publication_event_id, published_at
                FROM graph_retrieval_publications
                WHERE singleton_id = 1
                """,
                (resultSet, rowNumber) -> publication(resultSet)
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GRAPH_RETRIEVAL_CONFIGURATION_UNAVAILABLE",
                "Local GraphRAG 配置不可用"
        ));
    }

    private Long activeGraphGeneration() {
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
                (resultSet, rowNumber) -> resultSet.getLong(
                        "graph_generation"
                )
        ).stream().findFirst().orElse(null);
    }

    private List<ProfileView> profiles() {
        return jdbc.query(
                profileSelect() + " ORDER BY created_at, version",
                (resultSet, rowNumber) -> profile(resultSet)
        );
    }

    ProfileView profile(String version) {
        return jdbc.query(
                profileSelect() + " WHERE version = ?",
                (resultSet, rowNumber) -> profile(resultSet),
                version
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_RETRIEVAL_PROFILE_NOT_FOUND",
                "找不到 GraphRetrievalProfile " + version
        ));
    }

    private static String profileSelect() {
        return """
                SELECT version, seed_limit, max_hops, entity_limit,
                       edge_limit, graph_child_limit, graph_weight,
                       graph_context_token_budget, graph_context_percent,
                       statement_timeout_ms, reason, created_at
                FROM graph_retrieval_profiles
                """;
    }

    private static ProfileView profile(ResultSet resultSet)
            throws SQLException {
        return new ProfileView(
                resultSet.getString("version"),
                resultSet.getInt("seed_limit"),
                resultSet.getInt("max_hops"),
                resultSet.getInt("entity_limit"),
                resultSet.getInt("edge_limit"),
                resultSet.getInt("graph_child_limit"),
                resultSet.getDouble("graph_weight"),
                resultSet.getInt("graph_context_token_budget"),
                resultSet.getInt("graph_context_percent"),
                resultSet.getInt("statement_timeout_ms"),
                resultSet.getString("reason"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static PublicationView publication(ResultSet resultSet)
            throws SQLException {
        return new PublicationView(
                resultSet.getString("profile_version"),
                resultSet.getLong("publication_event_id"),
                resultSet.getTimestamp("published_at").toInstant()
        );
    }

    private static ApiException invalidProfile() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "GRAPH_RETRIEVAL_PROFILE_INVALID",
                "实体上限不能小于种子上限"
        );
    }
}
