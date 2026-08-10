package com.example.rag.search;

import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

@Service
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class SearchDocumentProjector {

    private final JdbcTemplate jdbc;
    private final OpenSearchGateway openSearch;
    private final EmbeddingCacheService embeddings;
    private final SearchProperties properties;

    SearchDocumentProjector(
            JdbcTemplate jdbc,
            OpenSearchGateway openSearch,
            EmbeddingCacheService embeddings,
            SearchProperties properties
    ) {
        this.jdbc = jdbc;
        this.openSearch = openSearch;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    int indexRevision(String indexName, UUID revisionId, IndexConfigView config) {
        List<IndexedDocument> documents = revisionDocuments(revisionId, config);
        if (documents.isEmpty()) {
            throw new IllegalStateException("INDEX job has no searchable Child chunks");
        }
        write(indexName, documents, config);
        openSearch.refresh(indexName);
        return documents.size();
    }

    IndexCounts rebuild(
            String indexName,
            long generation,
            IndexConfigView config,
            BiConsumer<Long, Long> progress
    ) {
        UUID after = null;
        long indexed = 0;
        long vectors = 0;
        while (true) {
            List<IndexedDocument> batch = publishedBatch(after, properties.getBulkSize(), config);
            if (batch.isEmpty()) {
                break;
            }
            write(indexName, batch, config);
            saveProjectionStates(generation, batch);
            indexed += batch.size();
            if (config.vectorEnabled()) {
                vectors += batch.size();
            }
            progress.accept(indexed, vectors);
            after = batch.getLast().chunkId();
        }
        openSearch.refresh(indexName);
        return actualCounts(indexName, config.vectorEnabled());
    }

    void synchronize(String indexName, long generation, IndexConfigView config) {
        while (true) {
            List<Projection> stale = staleProjections(generation);
            if (stale.isEmpty()) {
                purgeDeletedDocuments(indexName, generation);
                return;
            }
            for (Projection projection : stale) {
                openSearch.deleteDocument(indexName, projection.documentId().toString());
                if (projection.deletedAt() == null && projection.revisionId() != null) {
                    List<IndexedDocument> documents = revisionDocuments(
                            projection.revisionId(), config
                    );
                    if (documents.isEmpty()) {
                        throw new IllegalStateException(
                                "Published document is missing searchable Child chunks"
                        );
                    }
                    write(indexName, documents, config);
                    openSearch.refresh(indexName);
                    saveProjectionState(projection, generation, "ACTIVE");
                } else {
                    saveProjectionState(projection, generation, "DELETED");
                }
            }
        }
    }

    boolean isCaughtUp(long generation) {
        return !Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM documents document
                    LEFT JOIN search_projection_states state
                      ON state.document_id = document.id
                     AND state.index_generation = ?
                    WHERE state.document_id IS NULL
                       OR state.document_updated_at < document.updated_at
                       OR state.acl_version <> document.acl_version
                       OR (
                            document.deleted_at IS NULL
                            AND document.current_revision_id IS NOT NULL
                            AND (
                                state.state <> 'ACTIVE'
                                OR state.revision_id IS DISTINCT FROM document.current_revision_id
                            )
                       )
                       OR (
                            (
                                document.deleted_at IS NOT NULL
                                OR document.current_revision_id IS NULL
                            )
                            AND state.state <> 'DELETED'
                       )
                )
                """,
                Boolean.class,
                generation
        ));
    }

    ExpectedCounts expectedCounts() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT document.id) AS document_count,
                       COUNT(chunk.id) AS chunk_count
                FROM documents document
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                 AND revision.status = 'READY'
                LEFT JOIN chunks chunk
                  ON chunk.revision_id = revision.id
                 AND chunk.chunk_type = 'CHILD'
                 AND chunk.searchable = TRUE
                WHERE document.deleted_at IS NULL
                """,
                (resultSet, rowNumber) -> new ExpectedCounts(
                        resultSet.getLong("document_count"),
                        resultSet.getLong("chunk_count")
                )
        );
    }

    IndexCounts actualCounts(String indexName, boolean vectorEnabled) {
        long indexed = openSearch.count(indexName, Map.of("match_all", Map.of()));
        long vectors = vectorEnabled
                ? openSearch.count(indexName, Map.of("exists", Map.of("field", "embedding")))
                : 0;
        return new IndexCounts(indexed, vectors);
    }

    void deleteRevision(String indexName, UUID revisionId) {
        openSearch.deleteRevision(indexName, revisionId.toString());
    }

    void deleteIndex(String indexName) {
        openSearch.deleteIndex(indexName);
    }

    private void purgeDeletedDocuments(String indexName, long generation) {
        jdbc.queryForList(
                """
                SELECT state.document_id
                FROM search_projection_states state
                JOIN documents document ON document.id = state.document_id
                WHERE state.index_generation = ?
                  AND state.state = 'DELETED'
                  AND document.deleted_at IS NOT NULL
                ORDER BY state.document_id
                """,
                UUID.class,
                generation
        ).forEach(documentId ->
                openSearch.deleteDocument(indexName, documentId.toString()));
    }

    private List<IndexedDocument> publishedBatch(
            UUID after,
            int limit,
            IndexConfigView config
    ) {
        return indexDocuments(
                """
                WHERE document.deleted_at IS NULL
                  AND document.current_revision_id = chunk.revision_id
                  AND revision.status = 'READY'
                  AND chunk.chunk_type = 'CHILD'
                  AND chunk.searchable = TRUE
                  AND (CAST(? AS UUID) IS NULL OR chunk.id > CAST(? AS UUID))
                ORDER BY chunk.id
                LIMIT ?
                """,
                config,
                after,
                after,
                limit
        );
    }

    private List<IndexedDocument> revisionDocuments(
            UUID revisionId,
            IndexConfigView config
    ) {
        return indexDocuments(
                """
                WHERE chunk.revision_id = ?
                  AND revision.status = 'READY'
                  AND document.deleted_at IS NULL
                  AND chunk.chunk_type = 'CHILD'
                  AND chunk.searchable = TRUE
                ORDER BY chunk.chunk_order
                """,
                config,
                revisionId
        );
    }

    private List<IndexedDocument> indexDocuments(
            String where,
            IndexConfigView config,
            Object... arguments
    ) {
        String sql = """
                SELECT chunk.id AS chunk_id,
                       chunk.document_id,
                       document.title,
                       document.visibility,
                       document.owner_user_id,
                       document.acl_version,
                       document.deleted_at,
                       document.updated_at,
                       chunk.revision_id,
                       revision.revision_number,
                       chunk.parent_chunk_id,
                       chunk.chunk_order,
                       chunk.text,
                       chunk.heading_path,
                       chunk.parser_version,
                       chunk.chunker_version,
                       chunk.chunking_profile_version,
                       chunk.content_hash,
                       revision.document_format,
                       span.locator_kind,
                       span.start_source_unit_id,
                       span.end_source_unit_id,
                       span.start_unit_order,
                       span.end_unit_order,
                       span.start_locator_address,
                       span.end_locator_address,
                       span.start_offset,
                       span.end_offset,
                       span.normalization_version,
                       span.start_page,
                       span.end_page
                FROM chunks chunk
                JOIN documents document ON document.id = chunk.document_id
                JOIN document_revisions revision ON revision.id = chunk.revision_id
                LEFT JOIN LATERAL (
                    SELECT
                           (array_agg(
                               location.locator_kind
                               ORDER BY source.span_order
                           ))[1] AS locator_kind,
                           (array_agg(
                               location.start_source_unit_id
                               ORDER BY source.span_order
                           ))[1] AS start_source_unit_id,
                           (array_agg(
                               location.end_source_unit_id
                               ORDER BY source.span_order DESC
                           ))[1] AS end_source_unit_id,
                           (array_agg(
                               location.start_unit_order
                               ORDER BY source.span_order
                           ))[1] AS start_unit_order,
                           (array_agg(
                               location.end_unit_order
                               ORDER BY source.span_order DESC
                           ))[1] AS end_unit_order,
                           COALESCE(
                               (array_agg(
                                   split_part(
                                       location.address ->> 'address', '#', 1
                                   )
                                   ORDER BY source.span_order
                               ) FILTER (
                                   WHERE location.locator_kind <> 'CELL_RANGE'
                                      OR location.start_unit_address NOT LIKE '%:heading'
                               ))[1],
                               (array_agg(
                                   split_part(
                                       location.address ->> 'address', '#', 1
                                   )
                                   ORDER BY source.span_order
                               ))[1],
                               (array_agg(
                                   location.start_unit_address
                                   ORDER BY source.span_order
                               ))[1]
                           ) AS start_locator_address,
                           COALESCE(
                               (array_agg(
                                   split_part(
                                       location.address ->> 'address', '#', 1
                                   )
                                   ORDER BY source.span_order DESC
                               ) FILTER (
                                   WHERE location.locator_kind <> 'CELL_RANGE'
                                      OR location.end_unit_address NOT LIKE '%:heading'
                               ))[1],
                               (array_agg(
                                   split_part(
                                       location.address ->> 'address', '#', 1
                                   )
                                   ORDER BY source.span_order DESC
                               ))[1],
                               (array_agg(
                                   location.end_unit_address
                                   ORDER BY source.span_order DESC
                               ))[1]
                           ) AS end_locator_address,
                           (array_agg(
                               location.start_offset
                               ORDER BY source.span_order
                           ))[1] AS start_offset,
                           (array_agg(
                               location.end_offset
                               ORDER BY source.span_order DESC
                           ))[1] AS end_offset,
                           (array_agg(
                               location.normalization_version
                               ORDER BY source.span_order
                           ))[1] AS normalization_version,
                           MIN(location.start_page) AS start_page,
                           MAX(location.end_page) AS end_page
                    FROM source_spans source
                    JOIN source_locator_projection location
                      ON location.source_kind = 'SOURCE_SPAN'
                     AND location.source_id = source.id
                    WHERE source.chunk_id = chunk.id
                ) span ON TRUE
                """ + where;
        return jdbc.query(sql, (resultSet, rowNumber) -> {
            UUID chunkId = resultSet.getObject("chunk_id", UUID.class);
            UUID documentId = resultSet.getObject("document_id", UUID.class);
            UUID revisionId = resultSet.getObject("revision_id", UUID.class);
            long aclVersion = resultSet.getLong("acl_version");
            String headingPath = resultSet.getString("heading_path");
            Integer startPage = resultSet.getObject(
                    "start_page", Integer.class
            );
            Integer endPage = resultSet.getObject(
                    "end_page", Integer.class
            );
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("chunkId", chunkId.toString());
            source.put("documentId", documentId.toString());
            source.put("documentTitle", resultSet.getString("title"));
            source.put("revisionId", revisionId.toString());
            source.put("revisionNumber", resultSet.getInt("revision_number"));
            source.put("parentChunkId",
                    resultSet.getObject("parent_chunk_id", UUID.class).toString());
            source.put("chunkOrder", resultSet.getInt("chunk_order"));
            source.put("text", resultSet.getString("text"));
            source.put("title", resultSet.getString("title"));
            source.put("heading", headingPath == null ? "" : headingPath.replace('\n', ' '));
            source.put("headingPath", headingPath == null ? "" : headingPath);
            source.put("startPage", startPage);
            source.put("endPage", endPage);
            source.put("visibility", resultSet.getString("visibility"));
            source.put("ownerUserId",
                    resultSet.getObject("owner_user_id", UUID.class).toString());
            source.put("grantedUserIds", grantedUserIds(documentId));
            source.put("aclVersion", aclVersion);
            source.put("accessProjectionKey",
                    SearchAccessService.projectionKey(documentId, revisionId, aclVersion));
            source.put("parserVersion", resultSet.getString("parser_version"));
            source.put("chunkerVersion", resultSet.getString("chunker_version"));
            source.put("chunkingProfileVersion",
                    resultSet.getString("chunking_profile_version"));
            source.put("schemaVersion", config.schemaVersion());
            if (config.schemaVersion().startsWith("source-locator-")) {
                String format = resultSet.getString("document_format");
                String locatorKind = resultSet.getString("locator_kind");
                String startAddress = resultSet.getString(
                        "start_locator_address"
                );
                String endAddress = resultSet.getString(
                        "end_locator_address"
                );
                String label = sourceLabel(
                        format,
                        startAddress,
                        endAddress,
                        startPage,
                        endPage
                );
                source.put("documentFormat", format);
                source.put("sourceLabel", label);
                Map<String, Object> locator = new LinkedHashMap<>();
                locator.put("kind", locatorKind);
                putUuid(
                        locator,
                        "startUnit",
                        resultSet.getObject(
                                "start_source_unit_id", UUID.class
                        )
                );
                putUuid(
                        locator,
                        "endUnit",
                        resultSet.getObject(
                                "end_source_unit_id", UUID.class
                        )
                );
                locator.put(
                        "startOffset",
                        resultSet.getInt("start_offset")
                );
                locator.put("endOffset", resultSet.getInt("end_offset"));
                locator.put(
                        "address",
                        locatorAddress(
                                locatorKind,
                                startAddress,
                                endAddress,
                                startPage,
                                endPage
                        )
                );
                locator.put(
                        "normalizationVersion",
                        resultSet.getString("normalization_version")
                );
                locator.put("startPage", startPage);
                locator.put("endPage", endPage);
                locator.put("sourceLabel", label);
                source.put("sourceLocator", locator);
            }
            Projection projection = new Projection(
                    documentId,
                    revisionId,
                    aclVersion,
                    resultSet.getTimestamp("deleted_at") == null
                            ? null : resultSet.getTimestamp("deleted_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant()
            );
            return new IndexedDocument(
                    chunkId,
                    resultSet.getString("content_hash"),
                    source,
                    projection
            );
        }, arguments);
    }

    private static void putUuid(
            Map<String, Object> target,
            String key,
            UUID value
    ) {
        if (value != null) {
            target.put(key, value.toString());
        }
    }

    private static String locatorAddress(
            String locatorKind,
            String startAddress,
            String endAddress,
            Integer startPage,
            Integer endPage
    ) {
        if ("PAGE".equals(locatorKind)
                && startPage != null && endPage != null) {
            return startPage.equals(endPage)
                    ? "page:" + startPage
                    : "page:" + startPage + "-" + endPage;
        }
        if (startAddress == null) {
            return "source";
        }
        return startAddress.equals(endAddress) || endAddress == null
                ? startAddress : startAddress + ".." + endAddress;
    }

    private static String sourceLabel(
            String format,
            String startAddress,
            String endAddress,
            Integer startPage,
            Integer endPage
    ) {
        if (!"PDF".equals(format)
                || startPage == null
                || endPage == null) {
            return locatorAddress(
                    null,
                    startAddress,
                    endAddress,
                    startPage,
                    endPage
            );
        }
        return startPage.equals(endPage)
                ? "第 " + startPage + " 页"
                : "第 " + startPage + "–" + endPage + " 页";
    }

    private void write(
            String indexName,
            List<IndexedDocument> documents,
            IndexConfigView config
    ) {
        if (config.vectorEnabled()) {
            int cacheBatchSize = properties.getBulkSize();
            for (int start = 0; start < documents.size(); start += cacheBatchSize) {
                List<IndexedDocument> batch = documents.subList(
                        start,
                        Math.min(start + cacheBatchSize, documents.size())
                );
                List<List<Double>> vectors = embeddings.embedChildren(
                        config,
                        batch.stream()
                                .map(item -> new ChildEmbeddingInput(
                                        item.contentHash(),
                                        (String) item.source().get("text")
                                ))
                                .toList()
                );
                for (int index = 0; index < batch.size(); index++) {
                    batch.get(index).source().put("embedding", vectors.get(index));
                }
            }
        }
        for (int start = 0; start < documents.size(); start += properties.getBulkSize()) {
            openSearch.bulk(
                    indexName,
                    documents.subList(start, Math.min(start + properties.getBulkSize(), documents.size()))
                            .stream()
                            .map(IndexedDocument::source)
                            .toList()
            );
        }
    }

    private List<Projection> staleProjections(long generation) {
        return jdbc.query(
                """
                SELECT document.id,
                       document.current_revision_id,
                       document.acl_version,
                       document.deleted_at,
                       document.updated_at
                FROM documents document
                LEFT JOIN search_projection_states state
                  ON state.document_id = document.id
                 AND state.index_generation = ?
                WHERE state.document_id IS NULL
                   OR state.document_updated_at < document.updated_at
                   OR state.acl_version <> document.acl_version
                   OR (
                        document.deleted_at IS NULL
                        AND document.current_revision_id IS NOT NULL
                        AND (
                            state.state <> 'ACTIVE'
                            OR state.revision_id IS DISTINCT FROM document.current_revision_id
                        )
                   )
                   OR (
                        (
                            document.deleted_at IS NOT NULL
                            OR document.current_revision_id IS NULL
                        )
                        AND state.state <> 'DELETED'
                   )
                ORDER BY document.id
                LIMIT 100
                """,
                (resultSet, rowNumber) -> new Projection(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("current_revision_id", UUID.class),
                        resultSet.getLong("acl_version"),
                        resultSet.getTimestamp("deleted_at") == null
                                ? null : resultSet.getTimestamp("deleted_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                ),
                generation
        );
    }

    private void saveProjectionStates(long generation, List<IndexedDocument> documents) {
        Set<UUID> saved = new LinkedHashSet<>();
        for (IndexedDocument document : documents) {
            if (saved.add(document.projection().documentId())) {
                saveProjectionState(document.projection(), generation, "ACTIVE");
            }
        }
    }

    private void saveProjectionState(Projection projection, long generation, String state) {
        jdbc.update(
                """
                INSERT INTO search_projection_states (
                    document_id, revision_id, acl_version, index_generation,
                    state, document_updated_at, projected_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (document_id, index_generation) DO UPDATE
                SET revision_id = EXCLUDED.revision_id,
                    acl_version = EXCLUDED.acl_version,
                    state = EXCLUDED.state,
                    document_updated_at = EXCLUDED.document_updated_at,
                    projected_at = CURRENT_TIMESTAMP
                """,
                projection.documentId(),
                "ACTIVE".equals(state) ? projection.revisionId() : null,
                projection.aclVersion(),
                generation,
                state,
                Timestamp.from(projection.updatedAt())
        );
    }

    private List<String> grantedUserIds(UUID documentId) {
        return jdbc.query(
                """
                SELECT user_id
                FROM document_acl_entries
                WHERE document_id = ?
                ORDER BY user_id
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("user_id", UUID.class).toString(),
                documentId
        );
    }

    record ExpectedCounts(long documents, long chunks) {
    }

    record IndexCounts(long indexedChunks, long validVectors) {
    }

    private record Projection(
            UUID documentId,
            UUID revisionId,
            long aclVersion,
            Instant deletedAt,
            Instant updatedAt
    ) {
    }

    private record IndexedDocument(
            UUID chunkId,
            String contentHash,
            Map<String, Object> source,
            Projection projection
    ) {
    }
}
