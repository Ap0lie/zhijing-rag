package com.example.rag.search;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GraphRebuildRequestService;
import com.example.rag.persistence.PipelineStage;
import com.example.rag.pipeline.PipelineJobLeaseService;
import com.example.rag.pipeline.PipelineJobLeaseService.ClaimedJob;
import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import com.example.rag.search.SearchContracts.IndexStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
public class SearchIndexService {

    private static final long INDEX_COORDINATION_LOCK = 0x5241475F494E4458L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PipelineJobLeaseService leases;
    private final OpenSearchGateway openSearch;
    private final SearchDocumentProjector projector;
    private final SearchProperties properties;
    private final RetrievalConfigurationRepository configurations;
    private final GraphRebuildRequestService graphRebuilds;

    public SearchIndexService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            PipelineJobLeaseService leases,
            OpenSearchGateway openSearch,
            SearchDocumentProjector projector,
            SearchProperties properties,
            RetrievalConfigurationRepository configurations,
            GraphRebuildRequestService graphRebuilds
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.leases = leases;
        this.openSearch = openSearch;
        this.projector = projector;
        this.properties = properties;
        this.configurations = configurations;
        this.graphRebuilds = graphRebuilds;
    }

    public void index(ClaimedJob job) {
        if (job.stage() != PipelineStage.INDEX) {
            throw new IllegalArgumentException("SearchIndexService only accepts INDEX jobs");
        }
        Boolean completed = withCoordinationLock(true, () -> indexLocked(job));
        if (!Boolean.TRUE.equals(completed)) {
            throw new IllegalStateException("INDEX job lease or publication state is no longer valid");
        }
    }

    Optional<String> activeIndexName() {
        return activeManifest().map(Manifest::indexName);
    }

    Optional<ActiveIndex> activeIndex() {
        return activeManifest().map(manifest -> new ActiveIndex(
                manifest.indexName(),
                manifest.generation(),
                manifest.indexConfigVersion()
        ));
    }

    IndexStatus status() {
        boolean building = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM index_manifests
                    WHERE index_alias = ? AND status = 'BUILDING'
                )
                """,
                Boolean.class,
                properties.getIndexAlias()
        ));
        return activeManifest()
                .map(manifest -> manifest.status(building))
                .orElseGet(() -> {
                    IndexStatus empty = IndexStatus.uninitialized();
                    return new IndexStatus(
                            empty.indexName(), empty.indexGeneration(),
                            empty.documentCount(), empty.chunkCount(),
                            empty.status(), empty.updatedAt(), building
                    );
                });
    }

    void synchronizeProjections() {
        withCoordinationLock(true, () -> {
            reconcileActiveAlias();
            for (Manifest manifest : liveManifests()) {
                IndexConfigView config = configurations.indexConfig(
                        manifest.indexConfigVersion()
                );
                projector.synchronize(
                        manifest.indexName(), manifest.generation(), config
                );
                refreshCounts(manifest, config);
            }
            return null;
        });
    }

    Manifest bootstrapActiveIndex() {
        return withCoordinationLock(true, () -> {
            reconcileActiveAlias();
            return activeManifest().orElseGet(this::bootstrapActiveIndexLocked);
        });
    }

    <T> T withCoordinationLock(boolean wait, Supplier<T> action) {
        return jdbc.execute((ConnectionCallback<T>) connection -> {
            boolean acquired;
            if (wait) {
                try (var statement = connection.prepareStatement(
                        "SELECT pg_advisory_lock(?)"
                )) {
                    statement.setLong(1, INDEX_COORDINATION_LOCK);
                    statement.execute();
                }
                acquired = true;
            } else {
                try (var statement = connection.prepareStatement(
                        "SELECT pg_try_advisory_lock(?)"
                )) {
                    statement.setLong(1, INDEX_COORDINATION_LOCK);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        acquired = result.getBoolean(1);
                    }
                }
            }
            if (!acquired) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "INDEX_OPERATION_IN_PROGRESS",
                        "另一项索引操作正在执行"
                );
            }
            try {
                return action.get();
            } finally {
                try (var statement = connection.prepareStatement(
                        "SELECT pg_advisory_unlock(?)"
                )) {
                    statement.setLong(1, INDEX_COORDINATION_LOCK);
                    statement.execute();
                }
            }
        });
    }

    void reconcileActiveAlias() {
        Manifest active = activeManifest().orElse(null);
        if (active != null
                && !openSearch.aliasPointsTo(properties.getIndexAlias(), active.indexName())) {
            openSearch.switchAlias(properties.getIndexAlias(), active.indexName());
        }
    }

    static Map<String, Object> indexDefinition() {
        return indexDefinition(null);
    }

    static Map<String, Object> indexDefinition(IndexConfigView config) {
        if (config != null
                && !"cjk+english-multifield".equals(config.analyzer())) {
            throw new IllegalArgumentException(
                    "Unsupported index analyzer: " + config.analyzer()
            );
        }
        Map<String, Object> bilingualText = Map.of(
                "type", "text",
                "analyzer", "cjk",
                "fields", Map.of(
                        "english", Map.of("type", "text", "analyzer", "english")
                )
        );
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chunkId", Map.of("type", "keyword"));
        fields.put("documentId", Map.of("type", "keyword"));
        fields.put("documentTitle", Map.of("type", "keyword", "index", false));
        fields.put("revisionId", Map.of("type", "keyword"));
        fields.put("revisionNumber", Map.of("type", "integer"));
        fields.put("parentChunkId", Map.of("type", "keyword"));
        fields.put("chunkOrder", Map.of("type", "integer"));
        fields.put("text", bilingualText);
        fields.put("title", bilingualText);
        fields.put("heading", bilingualText);
        fields.put("headingPath", Map.of("type", "keyword", "index", false));
        fields.put("startPage", Map.of("type", "integer"));
        fields.put("endPage", Map.of("type", "integer"));
        if (config != null
                && config.schemaVersion().startsWith("source-locator-")) {
            fields.put("documentFormat", Map.of("type", "keyword"));
            fields.put("sourceLabel", Map.of(
                    "type", "keyword",
                    "index", false,
                    "doc_values", false
            ));
            fields.put("sourceLocator", Map.of(
                    "type", "object",
                    "dynamic", "strict",
                    "properties", Map.ofEntries(
                            Map.entry("kind", Map.of("type", "keyword")),
                            Map.entry("startUnit", Map.of("type", "keyword")),
                            Map.entry("endUnit", Map.of("type", "keyword")),
                            Map.entry("startOffset", Map.of("type", "integer")),
                            Map.entry("endOffset", Map.of("type", "integer")),
                            Map.entry("address", Map.of(
                                    "type", "keyword",
                                    "index", false,
                                    "doc_values", false
                            )),
                            Map.entry("sourceTextHash", Map.of("type", "keyword")),
                            Map.entry("normalizationVersion", Map.of("type", "keyword")),
                            Map.entry("startPage", Map.of("type", "integer")),
                            Map.entry("endPage", Map.of("type", "integer")),
                            Map.entry("sourceLabel", Map.of(
                                    "type", "keyword",
                                    "index", false,
                                    "doc_values", false
                            ))
                    )
            ));
        }
        fields.put("visibility", Map.of("type", "keyword"));
        fields.put("ownerUserId", Map.of("type", "keyword"));
        fields.put("grantedUserIds", Map.of("type", "keyword"));
        fields.put("aclVersion", Map.of("type", "long"));
        fields.put("accessProjectionKey", Map.of("type", "keyword"));
        fields.put("parserVersion", Map.of("type", "keyword"));
        fields.put("chunkerVersion", Map.of("type", "keyword"));
        fields.put("chunkingProfileVersion", Map.of("type", "keyword"));
        fields.put("schemaVersion", Map.of("type", "keyword"));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        if (config != null && config.vectorEnabled()) {
            settings.put("knn", true);
            fields.put("embedding", Map.of(
                    "type", "knn_vector",
                    "dimension", config.vectorDimensions(),
                    "method", Map.of(
                            "name", "hnsw",
                            "engine", "lucene",
                            "space_type", spaceType(config.distance()),
                            "parameters", Map.of(
                                    "m", config.hnswM(),
                                    "ef_construction", config.hnswEfConstruction()
                            )
                    )
            ));
        }
        return Map.of(
                "settings", Map.of("index", settings),
                "mappings", Map.of("dynamic", "strict", "properties", fields)
        );
    }

    private boolean indexLocked(ClaimedJob job) {
        reconcileActiveAlias();
        if (activeManifest().isEmpty()) {
            bootstrapActiveIndexLocked();
        }
        List<Manifest> targets = liveManifests();
        if (targets.isEmpty()) {
            throw new IllegalStateException("No live search generation is available");
        }
        try {
            for (Manifest manifest : targets) {
                projector.indexRevision(
                        manifest.indexName(),
                        job.revisionId(),
                        configurations.indexConfig(manifest.indexConfigVersion())
                );
            }
            Boolean completed = transactions.execute(status ->
                    publishRevision(job, targets.getFirst()));
            if (!Boolean.TRUE.equals(completed)) {
                cleanupRevision(targets, job.revisionId(), null);
                return false;
            }
            if (!revisionIsCurrent(job.revisionId())) {
                cleanupRevision(targets, job.revisionId(), null);
            }
            return true;
        } catch (RuntimeException exception) {
            cleanupRevision(targets, job.revisionId(), exception);
            throw exception;
        }
    }

    private boolean revisionIsCurrent(UUID revisionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM documents
                    WHERE current_revision_id = ? AND deleted_at IS NULL
                )
                """,
                Boolean.class,
                revisionId
        ));
    }

    private void cleanupRevision(
            List<Manifest> targets,
            UUID revisionId,
            RuntimeException original
    ) {
        for (Manifest manifest : targets) {
            try {
                projector.deleteRevision(manifest.indexName(), revisionId);
            } catch (RuntimeException cleanupFailure) {
                if (original == null) {
                    throw cleanupFailure;
                }
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private boolean publishRevision(ClaimedJob job, Manifest active) {
        if (!leases.lockOwned(job.id(), job.attempt())
                || !manifestIsActive(active.id(), active.indexName())) {
            return false;
        }
        Publication publication = lockPublication(job.id(), job.revisionId());
        if (publication == null
                || !"READY".equals(publication.revisionStatus())
                || publication.deletedAt() != null) {
            return false;
        }
        if (publication.currentRevisionNumber() == null
                || publication.revisionNumber() > publication.currentRevisionNumber()) {
            jdbc.update(
                    """
                    UPDATE documents
                    SET current_revision_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND deleted_at IS NULL
                    """,
                    job.revisionId(),
                    publication.documentId()
            );
            graphRebuilds.revisionPublished(publication.documentId());
        } else {
            jdbc.update(
                    """
                    UPDATE documents
                    SET updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND deleted_at IS NULL
                    """,
                    publication.documentId()
            );
        }
        if (!leases.markSucceeded(job.id(), job.attempt())) {
            throw new IllegalStateException("INDEX job lease was lost before publication");
        }
        return true;
    }

    private Manifest bootstrapActiveIndexLocked() {
        IndexConfigView config = configurations.indexConfigForRebuild();
        if (config.vectorEnabled()) {
            throw new IllegalStateException("The initial search generation must be BM25");
        }
        SearchDocumentProjector.ExpectedCounts expected = projector.expectedCounts();
        String indexName = properties.getIndexPrefix()
                + "-" + config.version()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = UUID.randomUUID();
        Manifest manifest = jdbc.queryForObject(
                """
                INSERT INTO index_manifests (
                    id, index_name, index_alias, index_config_version, status,
                    expected_document_count, expected_chunk_count,
                    build_reason, started_at
                ) VALUES (?, ?, ?, ?, 'BUILDING', ?, ?, ?, CURRENT_TIMESTAMP)
                RETURNING id, index_generation, index_name, index_alias, status,
                          index_config_version, document_count, chunk_count, updated_at
                """,
                (resultSet, rowNumber) -> manifest(resultSet),
                id,
                indexName,
                properties.getIndexAlias(),
                config.version(),
                expected.documents(),
                expected.chunks(),
                "Initial BM25 bootstrap"
        );
        try {
            openSearch.createIndex(indexName, indexDefinition(config));
            SearchDocumentProjector.IndexCounts actual = projector.rebuild(
                    indexName,
                    manifest.generation(),
                    config,
                    (indexed, vectors) -> updateBuildProgress(id, indexed, vectors)
            );
            projector.synchronize(indexName, manifest.generation(), config);
            SearchDocumentProjector.ExpectedCounts currentExpected = projector.expectedCounts();
            actual = projector.actualCounts(indexName, false);
            markReady(id, currentExpected, actual);
            openSearch.switchAlias(properties.getIndexAlias(), indexName);
            jdbc.update(
                    """
                    UPDATE index_manifests
                    SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'READY'
                    """,
                    id
            );
            return activeManifest().orElseThrow();
        } catch (RuntimeException exception) {
            jdbc.update(
                    """
                    UPDATE index_manifests
                    SET status = 'FAILED',
                        failure_code = 'BOOTSTRAP_FAILED',
                        failure_reason = ?,
                        completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'BUILDING'
                    """,
                    concise(exception.getMessage()),
                    id
            );
            throw exception;
        }
    }

    private void markReady(
            UUID id,
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
                WHERE id = ? AND status = 'BUILDING'
                """,
                expected.documents(),
                actual.indexedChunks(),
                expected.documents(),
                expected.chunks(),
                actual.indexedChunks(),
                actual.validVectors(),
                id
        );
        if (updated != 1) {
            throw new IllegalStateException("Search generation could not enter READY");
        }
    }

    private void updateBuildProgress(UUID id, long indexed, long vectors) {
        jdbc.update(
                """
                UPDATE index_manifests
                SET expected_chunk_count = GREATEST(expected_chunk_count, ?),
                    indexed_chunk_count = ?,
                    valid_vector_count = ?,
                    heartbeat_at = CASE
                        WHEN lease_owner IS NULL THEN NULL
                        ELSE CURRENT_TIMESTAMP
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'BUILDING'
                """,
                indexed,
                indexed,
                vectors,
                id
        );
    }

    private void refreshCounts(Manifest manifest, IndexConfigView config) {
        SearchDocumentProjector.ExpectedCounts expected = projector.expectedCounts();
        SearchDocumentProjector.IndexCounts actual = projector.actualCounts(
                manifest.indexName(), config.vectorEnabled()
        );
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
                WHERE id = ? AND status IN ('READY', 'ACTIVE')
                """,
                expected.documents(),
                actual.indexedChunks(),
                expected.documents(),
                expected.chunks(),
                actual.indexedChunks(),
                actual.validVectors(),
                manifest.id()
        );
    }

    private List<Manifest> liveManifests() {
        return jdbc.query(
                """
                SELECT id, index_generation, index_name, index_alias, status,
                       index_config_version, document_count, chunk_count, updated_at
                FROM index_manifests
                WHERE index_alias = ? AND status IN ('ACTIVE', 'READY')
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END, index_generation
                """,
                (resultSet, rowNumber) -> manifest(resultSet),
                properties.getIndexAlias()
        );
    }

    private Optional<Manifest> activeManifest() {
        return jdbc.query(
                """
                SELECT id, index_generation, index_name, index_alias, status,
                       index_config_version, document_count, chunk_count, updated_at
                FROM index_manifests
                WHERE index_alias = ? AND status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> manifest(resultSet),
                properties.getIndexAlias()
        ).stream().findFirst();
    }

    private boolean manifestIsActive(UUID manifestId, String indexName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM index_manifests
                    WHERE id = ?
                      AND index_name = ?
                      AND index_alias = ?
                      AND status = 'ACTIVE'
                )
                """,
                Boolean.class,
                manifestId,
                indexName,
                properties.getIndexAlias()
        ));
    }

    private Publication lockPublication(UUID jobId, UUID revisionId) {
        return jdbc.query(
                """
                SELECT document.id AS document_id,
                       document.deleted_at,
                       revision.status AS revision_status,
                       revision.revision_number,
                       current_revision.revision_number AS current_revision_number
                FROM pipeline_jobs job
                JOIN document_revisions revision ON revision.id = job.revision_id
                JOIN documents document ON document.id = revision.document_id
                JOIN document_runtime_policies format_policy
                  ON format_policy.policy_key = 'FORMAT:' || revision.document_format
                 AND format_policy.status = 'ENABLED'
                JOIN document_runtime_policies parser_policy
                  ON parser_policy.policy_key = 'PARSER:' || revision.document_format
                     || ':' || job.parser_provider
                 AND parser_policy.status = 'ENABLED'
                LEFT JOIN document_revisions current_revision
                  ON current_revision.id = document.current_revision_id
                WHERE job.id = ? AND job.revision_id = ? AND job.stage = 'INDEX'
                FOR UPDATE OF job, revision, document, format_policy, parser_policy
                """,
                resultSet -> resultSet.next() ? new Publication(
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("revision_status"),
                        resultSet.getInt("revision_number"),
                        resultSet.getObject("current_revision_number", Integer.class),
                        resultSet.getTimestamp("deleted_at") == null
                                ? null : resultSet.getTimestamp("deleted_at").toInstant()
                ) : null,
                jobId,
                revisionId
        );
    }

    private static Manifest manifest(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new Manifest(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("index_generation"),
                resultSet.getString("index_name"),
                resultSet.getString("index_alias"),
                resultSet.getString("status"),
                resultSet.getString("index_config_version"),
                resultSet.getLong("document_count"),
                resultSet.getLong("chunk_count"),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static String spaceType(String distance) {
        return switch (distance) {
            case "COSINE" -> "cosinesimil";
            case "L2" -> "l2";
            case "INNER_PRODUCT" -> "innerproduct";
            default -> throw new IllegalArgumentException(
                    "Unsupported vector distance: " + distance
            );
        };
    }

    private static String concise(String value) {
        if (value == null || value.isBlank()) {
            return "Index operation failed";
        }
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private record Publication(
            UUID documentId,
            String revisionStatus,
            int revisionNumber,
            Integer currentRevisionNumber,
            Instant deletedAt
    ) {
    }

    record Manifest(
            UUID id,
            long generation,
            String indexName,
            String alias,
            String state,
            String indexConfigVersion,
            long documentCount,
            long chunkCount,
            Instant updatedAt
    ) {
        IndexStatus status(boolean building) {
            return new IndexStatus(
                    indexName,
                    generation,
                    documentCount,
                    chunkCount,
                    state,
                    updatedAt,
                    building
            );
        }
    }

    record ActiveIndex(
            String indexName,
            long generation,
            String indexConfigVersion
    ) {
    }
}
