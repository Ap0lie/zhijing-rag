package com.example.rag.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class EmbeddingArtifactRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    EmbeddingArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    Map<String, StoredArtifact> find(
            EmbeddingNamespace namespace,
            String purpose,
            List<String> inputHashes
    ) {
        if (inputHashes.isEmpty()) {
            return Map.of();
        }
        var parameters = namespaceParameters(namespace)
                .addValue("purpose", purpose)
                .addValue("inputHashes", inputHashes);
        Map<String, StoredArtifact> result = new LinkedHashMap<>();
        named.query(
                """
                SELECT id, input_hash, vector_bytes, vector_checksum, byte_size
                FROM embedding_artifacts
                WHERE provider_key = :providerKey
                  AND model_id = :model
                  AND model_revision = :revision
                  AND purpose = :purpose
                  AND dimensions = :dimensions
                  AND input_format_version = :inputFormatVersion
                  AND normalization_version = :normalizationVersion
                  AND input_hash IN (:inputHashes)
                """,
                parameters,
                resultSet -> {
                    StoredArtifact artifact = new StoredArtifact(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("input_hash"),
                            resultSet.getBytes("vector_bytes"),
                            resultSet.getString("vector_checksum"),
                            resultSet.getInt("byte_size")
                    );
                    result.put(artifact.inputHash(), artifact);
                }
        );
        return Map.copyOf(result);
    }

    void touch(List<UUID> ids, Instant before) {
        if (ids.isEmpty()) {
            return;
        }
        named.update(
                """
                UPDATE embedding_artifacts
                SET last_used_at = CURRENT_TIMESTAMP
                WHERE id IN (:ids) AND last_used_at < :before
                """,
                new MapSqlParameterSource()
                        .addValue("ids", ids)
                        .addValue("before", Timestamp.from(before))
        );
    }

    void delete(UUID id) {
        jdbc.update("DELETE FROM embedding_artifacts WHERE id = ?", id);
    }

    void save(EmbeddingNamespace namespace, String purpose, List<ArtifactWrite> artifacts) {
        if (artifacts.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                """
                INSERT INTO embedding_artifacts (
                    id, provider_key, model_id, model_revision, purpose,
                    dimensions, input_format_version, normalization_version,
                    content_hash, input_hash, vector_bytes, vector_checksum, byte_size
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    provider_key, model_id, model_revision, purpose, dimensions,
                    input_format_version, normalization_version, input_hash
                ) DO UPDATE
                SET content_hash = EXCLUDED.content_hash,
                    vector_bytes = EXCLUDED.vector_bytes,
                    vector_checksum = EXCLUDED.vector_checksum,
                    byte_size = EXCLUDED.byte_size,
                    last_used_at = CURRENT_TIMESTAMP
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index)
                            throws SQLException {
                        ArtifactWrite artifact = artifacts.get(index);
                        statement.setObject(1, UUID.randomUUID());
                        statement.setString(2, namespace.providerKey());
                        statement.setString(3, namespace.model());
                        statement.setString(4, namespace.revision());
                        statement.setString(5, purpose);
                        statement.setInt(6, namespace.dimensions());
                        statement.setString(7, namespace.inputFormatVersion());
                        statement.setString(8, namespace.normalizationVersion());
                        statement.setString(9, artifact.contentHash());
                        statement.setString(10, artifact.inputHash());
                        statement.setBytes(11, artifact.vectorBytes());
                        statement.setString(12, artifact.vectorChecksum());
                        statement.setInt(13, artifact.vectorBytes().length);
                    }

                    @Override
                    public int getBatchSize() {
                        return artifacts.size();
                    }
                }
        );
    }

    ArtifactSummary summary() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) AS entries,
                       COALESCE(SUM(byte_size), 0) AS bytes
                FROM embedding_artifacts
                """,
                (resultSet, rowNumber) -> new ArtifactSummary(
                        resultSet.getLong("entries"),
                        resultSet.getLong("bytes")
                )
        );
    }

    List<ArtifactModelSummary> modelSummaries() {
        return jdbc.query(
                """
                SELECT provider_key, model_id, model_revision, dimensions,
                       COUNT(*) AS entries,
                       COALESCE(SUM(byte_size), 0) AS bytes
                FROM embedding_artifacts
                GROUP BY provider_key, model_id, model_revision, dimensions
                ORDER BY provider_key, model_id, model_revision, dimensions
                """,
                (resultSet, rowNumber) -> new ArtifactModelSummary(
                        resultSet.getString("provider_key"),
                        resultSet.getString("model_id"),
                        resultSet.getString("model_revision"),
                        resultSet.getInt("dimensions"),
                        resultSet.getLong("entries"),
                        resultSet.getLong("bytes")
                )
        );
    }

    @Transactional
    ClearResult clear(
            String providerKey,
            String model,
            String revision,
            UUID actorId,
            String reason
    ) {
        ClearResult result = jdbc.queryForObject(
                """
                WITH deleted AS (
                    DELETE FROM embedding_artifacts
                    WHERE provider_key = ?
                      AND model_id = ?
                      AND model_revision = ?
                    RETURNING byte_size
                )
                SELECT COUNT(*) AS entries,
                       COALESCE(SUM(byte_size), 0) AS bytes
                FROM deleted
                """,
                (resultSet, rowNumber) -> new ClearResult(
                        resultSet.getLong("entries"),
                        resultSet.getLong("bytes")
                ),
                providerKey,
                model,
                revision
        );
        jdbc.update(
                """
                INSERT INTO embedding_cache_events (
                    action, provider_key, model_id, model_revision,
                    actor_user_id, deleted_artifacts, freed_bytes, reason
                ) VALUES ('CLEAR', ?, ?, ?, ?, ?, ?, ?)
                """,
                providerKey,
                model,
                revision,
                actorId,
                result.deletedArtifacts(),
                result.freedBytes(),
                reason
        );
        return result;
    }

    EvictionResult evictExpiredAndUnreferenced(Instant staleBefore, Instant unreferencedBefore) {
        return jdbc.queryForObject(
                """
                WITH deleted AS (
                    DELETE FROM embedding_artifacts artifact
                    WHERE artifact.last_used_at < ?
                       OR (
                            artifact.created_at < ?
                            AND NOT EXISTS (
                                SELECT 1
                                FROM chunks chunk
                                JOIN documents document ON document.id = chunk.document_id
                                WHERE chunk.chunk_type = 'CHILD'
                                  AND chunk.content_hash = artifact.content_hash
                                  AND document.deleted_at IS NULL
                            )
                            AND NOT EXISTS (
                                SELECT 1
                                FROM global_community_reports report
                                JOIN global_graph_manifests manifest
                                  ON manifest.global_generation =
                                     report.global_generation
                                WHERE report.content_hash =
                                      artifact.content_hash
                                  AND manifest.status <> 'DELETED'
                            )
                       )
                    RETURNING byte_size
                )
                SELECT COUNT(*) AS entries,
                       COALESCE(SUM(byte_size), 0) AS bytes
                FROM deleted
                """,
                (resultSet, rowNumber) -> new EvictionResult(
                        resultSet.getLong("entries"),
                        resultSet.getLong("bytes")
                ),
                Timestamp.from(staleBefore),
                Timestamp.from(unreferencedBefore)
        );
    }

    EvictionResult evictOldest(long bytesToFree) {
        if (bytesToFree <= 0) {
            return new EvictionResult(0, 0);
        }
        return jdbc.queryForObject(
                """
                WITH ordered AS (
                    SELECT id,
                           SUM(byte_size) OVER (
                               ORDER BY last_used_at, id
                               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                           ) AS bytes_before
                    FROM embedding_artifacts
                ),
                deleted AS (
                    DELETE FROM embedding_artifacts artifact
                    USING ordered
                    WHERE artifact.id = ordered.id
                      AND COALESCE(ordered.bytes_before, 0) < ?
                    RETURNING artifact.byte_size
                )
                SELECT COUNT(*) AS entries,
                       COALESCE(SUM(byte_size), 0) AS bytes
                FROM deleted
                """,
                (resultSet, rowNumber) -> new EvictionResult(
                        resultSet.getLong("entries"),
                        resultSet.getLong("bytes")
                ),
                bytesToFree
        );
    }

    private static MapSqlParameterSource namespaceParameters(EmbeddingNamespace namespace) {
        return new MapSqlParameterSource()
                .addValue("providerKey", namespace.providerKey())
                .addValue("model", namespace.model())
                .addValue("revision", namespace.revision())
                .addValue("dimensions", namespace.dimensions())
                .addValue("inputFormatVersion", namespace.inputFormatVersion())
                .addValue("normalizationVersion", namespace.normalizationVersion());
    }

    record StoredArtifact(
            UUID id,
            String inputHash,
            byte[] vectorBytes,
            String vectorChecksum,
            int byteSize
    ) {
    }

    record ArtifactWrite(
            String contentHash,
            String inputHash,
            byte[] vectorBytes,
            String vectorChecksum
    ) {
    }

    record ArtifactSummary(long entries, long bytes) {
    }

    record ArtifactModelSummary(
            String providerKey,
            String model,
            String revision,
            int dimensions,
            long entries,
            long bytes
    ) {
    }

    record ClearResult(long deletedArtifacts, long freedBytes) {
    }

    record EvictionResult(long entries, long bytes) {
    }
}

record EmbeddingNamespace(
        String providerKey,
        String model,
        String revision,
        int dimensions,
        String inputFormatVersion,
        String normalizationVersion
) {
}

record ChildEmbeddingInput(String contentHash, String text) {
}
