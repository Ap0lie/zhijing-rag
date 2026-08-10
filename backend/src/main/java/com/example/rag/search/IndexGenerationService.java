package com.example.rag.search;

import com.example.rag.common.ApiException;
import com.example.rag.projection.GenerationRecoveryProgress;
import com.example.rag.projection.ProjectionClosureService;
import com.example.rag.projection.ProjectionClosureStatus;
import com.example.rag.search.IndexGenerationContracts.IndexGenerationView;
import com.example.rag.search.IndexGenerationContracts.IndexGenerationsResponse;
import com.example.rag.search.IndexGenerationContracts.PublishGenerationRequest;
import com.example.rag.search.IndexGenerationContracts.RollbackGenerationRequest;
import com.example.rag.search.IndexGenerationContracts.StartIndexBuildRequest;
import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalMode;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalProfileView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class IndexGenerationService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SearchProperties properties;
    private final RetrievalConfigurationRepository configurations;
    private final SearchDocumentProjector projector;
    private final SearchIndexService indexes;
    private final OpenSearchGateway openSearch;
    private final EmbeddingProvider embeddings;
    private final ProjectionClosureService closures;

    IndexGenerationService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SearchProperties properties,
            RetrievalConfigurationRepository configurations,
            SearchDocumentProjector projector,
            SearchIndexService indexes,
            OpenSearchGateway openSearch,
            EmbeddingProvider embeddings,
            ProjectionClosureService closures
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.configurations = configurations;
        this.projector = projector;
        this.indexes = indexes;
        this.openSearch = openSearch;
        this.embeddings = embeddings;
        this.closures = closures;
    }

    IndexGenerationsResponse generations() {
        List<IndexGenerationView> generations = jdbc.query(
                generationSelect() + """
                        WHERE manifest.index_alias = ?
                        ORDER BY manifest.index_generation DESC
                        """,
                (resultSet, rowNumber) -> view(resultSet),
                properties.getIndexAlias()
        );
        Long active = generations.stream()
                .filter(item -> "ACTIVE".equals(item.status()))
                .map(IndexGenerationView::indexGeneration)
                .findFirst()
                .orElse(null);
        return new IndexGenerationsResponse(active, generations);
    }

    IndexGenerationView start(StartIndexBuildRequest request, UUID actorId) {
        IndexConfigView config = configurations.indexConfig(
                request.indexConfigVersion().trim()
        );
        requireCurrentDocumentFormatCompatibility(config);
        ensureEmbeddingCompatible(config, true);
        SearchDocumentProjector.ExpectedCounts expected = projector.expectedCounts();
        UUID id = UUID.randomUUID();
        String indexName = properties.getIndexPrefix()
                + "-" + config.version()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            jdbc.update(
                    """
                    INSERT INTO index_manifests (
                        id, index_name, index_alias, index_config_version, status,
                        expected_document_count, expected_chunk_count,
                        requested_by, build_reason
                    ) VALUES (?, ?, ?, ?, 'BUILDING', ?, ?, ?, ?)
                    """,
                    id,
                    indexName,
                    properties.getIndexAlias(),
                    config.version(),
                    expected.documents(),
                    expected.chunks(),
                    actorId,
                    request.reason().trim()
            );
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "INDEX_BUILD_ALREADY_RUNNING",
                    "已有 Generation 正在构建",
                    exception
            );
        }
        return generation(id);
    }

    Optional<ClaimedGeneration> claim() {
        if (!properties.isGenerationWorkerEnabled()) {
            return Optional.empty();
        }
        closeExpiredAttempts();
        List<ClaimedGeneration> claimed = jdbc.query(
                """
                WITH candidate AS (
                    SELECT id
                    FROM index_manifests
                    WHERE index_alias = ?
                      AND status = 'BUILDING'
                      AND build_attempt < build_max_attempts
                      AND (
                        lease_owner IS NULL
                        OR lease_expires_at < CURRENT_TIMESTAMP
                      )
                    ORDER BY created_at, index_generation
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE index_manifests manifest
                SET build_attempt = manifest.build_attempt + 1,
                    lease_owner = ?,
                    lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    started_at = COALESCE(manifest.started_at, CURRENT_TIMESTAMP),
                    failure_code = NULL,
                    failure_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE manifest.id = candidate.id
                RETURNING manifest.id, manifest.index_generation,
                          manifest.index_name, manifest.index_config_version,
                          manifest.build_attempt
                """,
                (resultSet, rowNumber) -> new ClaimedGeneration(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("index_generation"),
                        resultSet.getString("index_name"),
                        resultSet.getString("index_config_version"),
                        resultSet.getInt("build_attempt")
                ),
                properties.getIndexAlias(),
                properties.getGenerationWorkerId(),
                properties.getGenerationLeaseDuration().toMillis()
        );
        return claimed.stream().findFirst();
    }

    void build(ClaimedGeneration claim) {
        IndexConfigView config = configurations.indexConfig(claim.indexConfigVersion());
        ensureEmbeddingCompatible(config, false);
        projector.deleteIndex(claim.indexName());
        openSearch.createIndex(claim.indexName(), SearchIndexService.indexDefinition(config));
        projector.rebuild(
                claim.indexName(),
                claim.generation(),
                config,
                (indexed, vectors) -> heartbeat(claim, indexed, vectors)
        );
        indexes.withCoordinationLock(true, () -> {
            projector.synchronize(claim.indexName(), claim.generation(), config);
            SearchDocumentProjector.ExpectedCounts expected = projector.expectedCounts();
            SearchDocumentProjector.IndexCounts current = projector.actualCounts(
                    claim.indexName(), config.vectorEnabled()
            );
            if (!projector.isCaughtUp(claim.generation())
                    || current.indexedChunks() != expected.chunks()
                    || (config.vectorEnabled()
                    && current.validVectors() != expected.chunks())) {
                throw new IllegalStateException(
                        "Generation did not reach complete Revision/ACL/vector coverage"
                );
            }
            closures.requireIndex(
                    claim.generation(),
                    sourceLocatorCompatible(config)
            );
            completeReady(claim, expected, current);
            return null;
        });
    }

    void fail(ClaimedGeneration claim, Throwable failure) {
        jdbc.update(
                """
                UPDATE index_manifests
                SET status = 'FAILED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    failure_code = 'INDEX_BUILD_FAILED',
                    failure_reason = ?,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'BUILDING'
                  AND lease_owner = ?
                  AND build_attempt = ?
                """,
                concise(failure.getMessage()),
                claim.id(),
                properties.getGenerationWorkerId(),
                claim.attempt()
        );
    }

    IndexGenerationView publish(PublishGenerationRequest request, UUID actorId) {
        return release(
                request.indexGeneration(),
                request.profileVersion(),
                request.reason(),
                actorId,
                "READY",
                "PUBLISH"
        );
    }

    IndexGenerationView rollback(RollbackGenerationRequest request, UUID actorId) {
        return release(
                request.indexGeneration(),
                request.profileVersion(),
                request.reason(),
                actorId,
                "RETIRED",
                "ROLLBACK"
        );
    }

    void maintain() {
        indexes.reconcileActiveAlias();
        List<GenerationRow> expired = jdbc.query(
                generationRowSelect() + """
                        WHERE manifest.index_alias = ?
                          AND manifest.status = 'RETIRED'
                          AND manifest.retention_until <= CURRENT_TIMESTAMP
                          AND manifest.index_generation NOT IN (
                              SELECT recent.index_generation
                              FROM index_manifests recent
                              WHERE recent.index_alias = ?
                                AND recent.status IN ('ACTIVE', 'READY', 'RETIRED')
                              ORDER BY recent.index_generation DESC
                              LIMIT 2
                          )
                        ORDER BY manifest.index_generation
                        """,
                (resultSet, rowNumber) -> row(resultSet),
                properties.getIndexAlias(),
                properties.getIndexAlias()
        );
        for (GenerationRow generation : expired) {
            projector.deleteIndex(generation.indexName());
            transactions.executeWithoutResult(status -> {
                jdbc.update(
                        "DELETE FROM search_projection_states WHERE index_generation = ?",
                        generation.generation()
                );
                jdbc.update(
                        """
                        UPDATE index_manifests
                        SET status = 'DELETED', updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'RETIRED'
                        """,
                        generation.id()
                );
            });
        }
    }

    private IndexGenerationView release(
            long generation,
            String profileVersion,
            String reason,
            UUID actorId,
            String expectedStatus,
            String action
    ) {
        return indexes.withCoordinationLock(false, () -> {
            GenerationRow target = generationRow(generation);
            if (!expectedStatus.equals(target.status())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "INDEX_GENERATION_NOT_RELEASABLE",
                        "目标 Generation 状态不允许此操作"
                );
            }
            IndexConfigView config = configurations.indexConfig(
                    target.indexConfigVersion()
            );
            RetrievalProfileView profile = configurations.profile(profileVersion.trim());
            validateCompatibility(profile, config);
            requireCurrentDocumentFormatCompatibility(config);
            projector.synchronize(target.indexName(), target.generation(), config);
            SearchDocumentProjector.ExpectedCounts expected = projector.expectedCounts();
            SearchDocumentProjector.IndexCounts actual = projector.actualCounts(
                    target.indexName(), config.vectorEnabled()
            );
            if (!projector.isCaughtUp(target.generation())
                    || actual.indexedChunks() != expected.chunks()
                    || (config.vectorEnabled()
                    && actual.validVectors() != expected.chunks())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "INDEX_GENERATION_NOT_CAUGHT_UP",
                        "目标 Generation 尚未追平当前 Revision、ACL 或向量覆盖"
                );
            }
            closures.requireIndex(
                    target.generation(),
                    sourceLocatorCompatible(config)
            );
            updateCounts(target.id(), expected, actual);
            openSearch.switchAlias(properties.getIndexAlias(), target.indexName());
            try {
                transactions.executeWithoutResult(status ->
                        recordRelease(
                                target, profile, actorId, reason.trim(),
                                expectedStatus, action
                        ));
            } catch (RuntimeException exception) {
                try {
                    indexes.reconcileActiveAlias();
                } catch (RuntimeException recoveryFailure) {
                    exception.addSuppressed(recoveryFailure);
                }
                throw exception;
            }
            return generation(target.id());
        });
    }

    private void requireCurrentDocumentFormatCompatibility(IndexConfigView config) {
        if (sourceLocatorCompatible(config)) {
            return;
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "INDEX_SCHEMA_SOURCE_LOCATOR_REQUIRED",
                "Phase 18C 只允许构建、发布或回滚支持 SourceLocator 的索引"
        );
    }

    private static boolean sourceLocatorCompatible(IndexConfigView config) {
        return config.schemaVersion().startsWith("source-locator-");
    }

    private void recordRelease(
            GenerationRow target,
            RetrievalProfileView profile,
            UUID actorId,
            String reason,
            String expectedStatus,
            String action
    ) {
        GenerationRow current = jdbc.query(
                generationRowSelect() + """
                        WHERE manifest.index_alias = ?
                          AND manifest.status = 'ACTIVE'
                        FOR UPDATE OF manifest
                        """,
                (resultSet, rowNumber) -> row(resultSet),
                properties.getIndexAlias()
        ).stream().findFirst().orElse(null);
        GenerationRow lockedTarget = jdbc.query(
                generationRowSelect() + """
                        WHERE manifest.id = ?
                        FOR UPDATE OF manifest
                        """,
                (resultSet, rowNumber) -> row(resultSet),
                target.id()
        ).stream().findFirst().orElseThrow();
        if (!expectedStatus.equals(lockedTarget.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "INDEX_GENERATION_CHANGED",
                    "目标 Generation 状态已经变化"
            );
        }
        if (current != null) {
            jdbc.update(
                    """
                    UPDATE index_manifests
                    SET status = 'RETIRED',
                        retention_until =
                            CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'ACTIVE'
                    """,
                    properties.getGenerationRetention().toMillis(),
                    current.id()
            );
        }
        jdbc.update(
                """
                UPDATE index_manifests
                SET status = 'ACTIVE',
                    retention_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """,
                target.id(),
                expectedStatus
        );
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO retrieval_publication_events (
                    previous_profile_version, profile_version,
                    previous_index_generation, index_generation,
                    action, actor_user_id, reason
                )
                SELECT publication.profile_version, ?,
                       ?, ?, ?, ?, ?
                FROM retrieval_publications publication
                WHERE publication.singleton_id = 1
                RETURNING id
                """,
                Long.class,
                profile.version(),
                current == null ? null : current.generation(),
                target.generation(),
                action,
                actorId,
                reason
        );
        if (eventId == null) {
            throw new IllegalStateException("Retrieval publication event was not created");
        }
        jdbc.update(
                """
                UPDATE retrieval_publications
                SET profile_version = ?,
                    publication_event_id = ?,
                    published_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1
                """,
                profile.version(),
                eventId
        );
    }

    private void completeReady(
            ClaimedGeneration claim,
            SearchDocumentProjector.ExpectedCounts expected,
            SearchDocumentProjector.IndexCounts actual
    ) {
        int updated = jdbc.update(
                """
                UPDATE index_manifests
                SET status = 'READY',
                    document_count = ?,
                    chunk_count = ?,
                    expected_document_count = ?,
                    expected_chunk_count = ?,
                    indexed_chunk_count = ?,
                    valid_vector_count = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'BUILDING'
                  AND lease_owner = ?
                  AND build_attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                expected.documents(),
                actual.indexedChunks(),
                expected.documents(),
                expected.chunks(),
                actual.indexedChunks(),
                actual.validVectors(),
                claim.id(),
                properties.getGenerationWorkerId(),
                claim.attempt()
        );
        if (updated != 1) {
            throw new IllegalStateException("Generation lease was lost before READY");
        }
    }

    private void heartbeat(ClaimedGeneration claim, long indexed, long vectors) {
        int updated = jdbc.update(
                """
                UPDATE index_manifests
                SET expected_chunk_count = GREATEST(expected_chunk_count, ?),
                    indexed_chunk_count = ?,
                    valid_vector_count = ?,
                    heartbeat_at = CURRENT_TIMESTAMP,
                    lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'BUILDING'
                  AND lease_owner = ?
                  AND build_attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                indexed,
                indexed,
                vectors,
                properties.getGenerationLeaseDuration().toMillis(),
                claim.id(),
                properties.getGenerationWorkerId(),
                claim.attempt()
        );
        if (updated != 1) {
            throw new IllegalStateException("Generation build lease was lost");
        }
    }

    private void closeExpiredAttempts() {
        jdbc.update(
                """
                UPDATE index_manifests
                SET status = 'FAILED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    failure_code = 'BUILD_LEASE_EXHAUSTED',
                    failure_reason = 'Generation worker stopped before completing the build',
                    completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'BUILDING'
                  AND build_attempt >= build_max_attempts
                  AND lease_expires_at < CURRENT_TIMESTAMP
                """
        );
    }

    private void updateCounts(
            UUID id,
            SearchDocumentProjector.ExpectedCounts expected,
            SearchDocumentProjector.IndexCounts actual
    ) {
        jdbc.update(
                """
                UPDATE index_manifests
                SET document_count = ?,
                    chunk_count = ?,
                    expected_document_count = ?,
                    expected_chunk_count = ?,
                    indexed_chunk_count = ?,
                    valid_vector_count = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('READY', 'RETIRED')
                """,
                expected.documents(),
                actual.indexedChunks(),
                expected.documents(),
                expected.chunks(),
                actual.indexedChunks(),
                actual.validVectors(),
                id
        );
    }

    private void ensureEmbeddingCompatible(IndexConfigView config, boolean checkHealth) {
        if (!config.vectorEnabled()) {
            return;
        }
        ModelDescriptor model = embeddings.descriptor();
        if (!model.enabled()
                || !config.embeddingModel().equals(model.model())
                || !config.embeddingRevision().equals(model.revision())
                || !config.vectorDimensions().equals(model.dimensions())) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EMBEDDING_CONFIGURATION_MISMATCH",
                    "Embedding 服务未启用或与 IndexConfig 不匹配"
            );
        }
        if (checkHealth) {
            try {
                embeddings.health();
            } catch (RuntimeException exception) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "EMBEDDING_UNAVAILABLE",
                        "Embedding 服务不可用",
                        exception
                );
            }
        }
    }

    private static void validateCompatibility(
            RetrievalProfileView profile,
            IndexConfigView config
    ) {
        if (profile.mode() == RetrievalMode.HYBRID && !config.vectorEnabled()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "RETRIEVAL_RELEASE_INCOMPATIBLE",
                    "Hybrid Profile 必须发布到 Vector Generation"
            );
        }
    }

    private IndexGenerationView generation(UUID id) {
        return jdbc.query(
                generationSelect() + " WHERE manifest.id = ?",
                (resultSet, rowNumber) -> view(resultSet),
                id
        ).stream().findFirst().orElseThrow(() -> generationNotFound(id.toString()));
    }

    private GenerationRow generationRow(long generation) {
        return jdbc.query(
                generationRowSelect() + " WHERE manifest.index_generation = ?",
                (resultSet, rowNumber) -> row(resultSet),
                generation
        ).stream().findFirst().orElseThrow(() ->
                generationNotFound(Long.toString(generation)));
    }

    private static String generationSelect() {
        return """
                SELECT manifest.id, manifest.index_generation, manifest.index_name,
                       manifest.index_config_version, manifest.status,
                       manifest.expected_document_count,
                       manifest.expected_chunk_count,
                       manifest.indexed_chunk_count,
                       manifest.valid_vector_count,
                       manifest.build_attempt,
                       manifest.failure_code, manifest.failure_reason,
                       manifest.created_at, manifest.started_at,
                       manifest.completed_at, manifest.retention_until,
                       manifest.updated_at, manifest.heartbeat_at,
                       manifest.lease_expires_at, config.schema_version,
                       config.vector_dimensions IS NOT NULL AS vector_enabled
                FROM index_manifests manifest
                JOIN index_configs config
                  ON config.version = manifest.index_config_version
                """;
    }

    private static String generationRowSelect() {
        return """
                SELECT manifest.id, manifest.index_generation, manifest.index_name,
                       manifest.index_config_version, manifest.status
                FROM index_manifests manifest
                """;
    }

    private IndexGenerationView view(ResultSet resultSet) throws SQLException {
        long expected = resultSet.getLong("expected_chunk_count");
        long vectors = resultSet.getLong("valid_vector_count");
        boolean vector = resultSet.getBoolean("vector_enabled");
        long generation = resultSet.getLong("index_generation");
        int attempt = resultSet.getInt("build_attempt");
        String status = resultSet.getString("status");
        Instant heartbeatAt = instant(resultSet, "heartbeat_at");
        Instant leaseExpiresAt = instant(resultSet, "lease_expires_at");
        ProjectionClosureStatus closure = closures.index(
                generation,
                resultSet.getString("schema_version").startsWith("source-locator-")
        );
        boolean complete = resultSet.getLong("indexed_chunk_count") == expected
                && (!vector || vectors == expected)
                && closure.ready();
        double coverage = !vector
                ? 0.0
                : expected == 0 ? 1.0 : (double) vectors / expected;
        return new IndexGenerationView(
                resultSet.getObject("id", UUID.class),
                generation,
                resultSet.getString("index_name"),
                resultSet.getString("index_config_version"),
                status,
                resultSet.getLong("expected_document_count"),
                expected,
                resultSet.getLong("indexed_chunk_count"),
                vectors,
                coverage,
                complete,
                closure,
                GenerationRecoveryProgress.of(
                        status, attempt, heartbeatAt, leaseExpiresAt
                ),
                attempt,
                resultSet.getString("failure_code"),
                resultSet.getString("failure_reason"),
                instant(resultSet, "created_at"),
                instant(resultSet, "started_at"),
                instant(resultSet, "completed_at"),
                instant(resultSet, "retention_until"),
                instant(resultSet, "updated_at")
        );
    }

    private static GenerationRow row(ResultSet resultSet) throws SQLException {
        return new GenerationRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("index_generation"),
                resultSet.getString("index_name"),
                resultSet.getString("index_config_version"),
                resultSet.getString("status")
        );
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static ApiException generationNotFound(String value) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "INDEX_GENERATION_NOT_FOUND",
                "找不到 Generation " + value
        );
    }

    private static String concise(String value) {
        if (value == null || value.isBlank()) {
            return "Generation build failed";
        }
        return value.substring(0, Math.min(value.length(), 1000));
    }

    record ClaimedGeneration(
            UUID id,
            long generation,
            String indexName,
            String indexConfigVersion,
            int attempt
    ) {
    }

    private record GenerationRow(
            UUID id,
            long generation,
            String indexName,
            String indexConfigVersion,
            String status
    ) {
    }
}
