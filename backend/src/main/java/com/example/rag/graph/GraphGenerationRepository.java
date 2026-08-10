package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GraphBuildContracts.AdjacencyFact;
import com.example.rag.graph.GraphBuildContracts.AliasFact;
import com.example.rag.graph.GraphBuildContracts.ClaimedGeneration;
import com.example.rag.graph.GraphBuildContracts.CommunityClaimFact;
import com.example.rag.graph.GraphBuildContracts.CommunityFact;
import com.example.rag.graph.GraphBuildContracts.CommunityMemberFact;
import com.example.rag.graph.GraphBuildContracts.EntityFact;
import com.example.rag.graph.GraphBuildContracts.ExtractionArtifact;
import com.example.rag.graph.GraphBuildContracts.GraphBuild;
import com.example.rag.graph.GraphBuildContracts.GraphConfig;
import com.example.rag.graph.GraphBuildContracts.MentionFact;
import com.example.rag.graph.GraphBuildContracts.ParentSource;
import com.example.rag.graph.GraphBuildContracts.ProjectionFact;
import com.example.rag.graph.GraphBuildContracts.RelationshipEvidenceFact;
import com.example.rag.graph.GraphBuildContracts.RelationshipFact;
import com.example.rag.graph.GraphBuildContracts.ResolutionRule;
import com.example.rag.graph.GraphBuildContracts.SourceDocument;
import com.example.rag.graph.GraphBuildContracts.SpanSource;
import com.example.rag.graph.GraphBuildContracts.ChildSource;
import com.example.rag.projection.ProjectionClosureService;
import com.example.rag.projection.ProjectionClosureStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GraphGenerationRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshotTransactions;
    private final ObjectMapper objectMapper;
    private final GraphProperties properties;
    private final ProjectionClosureService closures;

    GraphGenerationRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            GraphProperties properties,
            ProjectionClosureService closures
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.snapshotTransactions = new TransactionTemplate(
                transactions.getTransactionManager()
        );
        this.snapshotTransactions.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ
        );
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.closures = closures;
    }

    List<GraphConfig> configs() {
        return jdbc.query(
                graphConfigSelect() + " ORDER BY config.created_at, config.version",
                (resultSet, rowNumber) -> graphConfig(resultSet)
        );
    }

    GraphConfig config(String version) {
        return jdbc.query(
                graphConfigSelect() + " WHERE config.version = ?",
                (resultSet, rowNumber) -> graphConfig(resultSet),
                version
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_CONFIG_NOT_FOUND",
                "找不到 GraphConfig " + version
        ));
    }

    GraphConfig createConfig(
            String version,
            String model,
            String revision,
            String reason,
            UUID actorId
    ) {
        try {
            jdbc.update(
                    """
                    INSERT INTO graph_configs (
                        version, extraction_model, extraction_revision,
                        prompt_version, schema_version, normalization_version,
                        resolution_rule_set_version,
                        community_algorithm, community_algorithm_version,
                        community_seed, community_resolution,
                        reason, created_by
                    ) VALUES (?, ?, ?, ?, ?, 'unicode-nfkc-lower-v1',
                              'phase8-baseline-rules-v1',
                              'leidenalg', '0.10.2', 42, 1.0, ?, ?)
                    """,
                    version,
                    model,
                    revision,
                    GraphExtractionProvider.PROMPT_VERSION,
                    GraphExtractionProvider.SCHEMA_VERSION,
                    reason,
                    actorId
            );
        } catch (RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "GRAPH_CONFIG_CONFLICT",
                    "GraphConfig 版本已存在或配置无效",
                    exception
            );
        }
        return config(version);
    }

    GraphConfig createResolutionConfig(
            GraphConfig base,
            String newVersion,
            long generation,
            String expectedSourceSetHash,
            String action,
            List<UUID> sourceEntityIds,
            List<String> matchAliases,
            String targetCanonicalName,
            String targetNormalizedName,
            String targetEntityType,
            String reason,
            UUID actorId
    ) {
        return transactions.execute(status -> {
            ManifestRow manifest = lockManifest(generation);
            if (!("ACTIVE".equals(manifest.status())
                    || "READY".equals(manifest.status()))) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "GRAPH_GENERATION_NOT_PREVIEWABLE",
                        "只有 ACTIVE 或 READY Generation 可以创建实体消歧规则"
                );
            }
            if (!manifest.configVersion().equals(base.version())
                    || !manifest.sourceSetHash().equals(expectedSourceSetHash)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "GRAPH_RULE_PREVIEW_STALE",
                        "GraphConfig 或来源集合已变化，请重新预检"
                );
            }
            lockDocumentSourceSet();
            if (!expectedSourceSetHash.equals(currentSourceSetHash())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "GRAPH_RULE_PREVIEW_STALE",
                        "文档 Revision 或权限已变化，请重新预检"
                );
            }
            List<String> sourceKeys = entityKeys(generation, sourceEntityIds);
            if (sourceKeys.size() != sourceEntityIds.size()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "GRAPH_RULE_SOURCE_INVALID",
                        "修正规则包含不存在或不属于当前 ACTIVE Generation 的实体"
                );
            }
            if ("SPLIT".equals(action)
                    && (sourceKeys.size() != 1 || matchAliases.isEmpty())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "GRAPH_SPLIT_RULE_INVALID",
                        "SPLIT 必须选择一个实体并提供需要拆分的别名"
                );
            }
            jdbc.update(
                    """
                    INSERT INTO graph_resolution_rule_sets (
                        version, reason, created_by
                    ) VALUES (?, ?, ?)
                    """,
                    newVersion,
                    reason,
                    actorId
            );
            List<ResolutionRule> existing = rules(
                    base.resolutionRuleSetVersion()
            );
            for (ResolutionRule rule : existing) {
                insertRule(
                        newVersion,
                        rule.order(),
                        rule.action(),
                        rule.sourceEntityKeys(),
                        rule.matchAliases(),
                        rule.targetCanonicalName(),
                        rule.targetNormalizedName(),
                        rule.targetEntityType(),
                        "Copied from " + base.resolutionRuleSetVersion()
                );
            }
            insertRule(
                    newVersion,
                    existing.size(),
                    action,
                    sourceKeys,
                    matchAliases,
                    targetCanonicalName,
                    targetNormalizedName,
                    targetEntityType,
                    reason
            );
            jdbc.update(
                    """
                    INSERT INTO graph_configs (
                        version, extraction_model, extraction_revision,
                        prompt_version, schema_version, normalization_version,
                        resolution_rule_set_version,
                        community_algorithm, community_algorithm_version,
                        community_seed, community_resolution,
                        reason, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    newVersion,
                    base.extractionModel(),
                    base.extractionRevision(),
                    base.promptVersion(),
                    base.schemaVersion(),
                    base.normalizationVersion(),
                    newVersion,
                    base.communityAlgorithm(),
                    base.communityAlgorithmVersion(),
                    base.communitySeed(),
                    base.communityResolution(),
                    reason,
                    actorId
            );
            return config(newVersion);
        });
    }

    ManifestRow start(
            String configVersion,
            String reason,
            UUID actorId
    ) {
        config(configVersion);
        try {
            return snapshotTransactions.execute(status -> {
                List<SourceReference> sources = currentSources();
                String sourceSetHash = sourceSetHash(sources);
                Long generation = jdbc.queryForObject(
                        """
                        INSERT INTO graph_manifests (
                            id, graph_config_version, status,
                            expected_document_count, source_set_hash,
                            requested_by, build_reason
                        ) VALUES (?, ?, 'BUILDING', ?, ?, ?, ?)
                        RETURNING graph_generation
                        """,
                        Long.class,
                        UUID.randomUUID(),
                        configVersion,
                        sources.size(),
                        sourceSetHash,
                        actorId,
                        reason
                );
                if (generation == null) {
                    throw new IllegalStateException(
                            "Graph Generation was not created"
                    );
                }
                batch(
                        """
                        INSERT INTO graph_generation_sources (
                            graph_generation, document_id, revision_id,
                            acl_version, document_title
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                        sources.stream().map(source -> new Object[]{
                                generation,
                                source.documentId(),
                                source.revisionId(),
                                source.aclVersion(),
                                source.title()
                        }).toList()
                );
                jdbc.update(
                        """
                        UPDATE graph_rebuild_requests request
                        SET state = 'GRAPH_BUILDING',
                            candidate_graph_generation = ?
                        WHERE request.state = 'REQUESTED'
                          AND EXISTS (
                              SELECT 1
                              FROM graph_generation_sources source
                              WHERE source.graph_generation = ?
                                AND source.document_id = request.document_id
                                AND source.revision_id =
                                    request.target_revision_id
                                AND source.acl_version =
                                    request.target_acl_version
                          )
                        """,
                        generation,
                        generation
                );
                return manifest(generation);
            });
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "GRAPH_BUILD_ALREADY_RUNNING",
                    "该 GraphConfig 已有正在构建的 Generation",
                    exception
            );
        }
    }

    private List<SourceReference> currentSources() {
        return jdbc.query(
                """
                SELECT document.id, document.current_revision_id,
                       document.acl_version, document.title
                FROM documents document
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                 AND revision.document_id = document.id
                 AND revision.status = 'READY'
                WHERE document.deleted_at IS NULL
                ORDER BY document.id
                """,
                (resultSet, rowNumber) -> new SourceReference(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject(
                                "current_revision_id",
                                UUID.class
                        ),
                        resultSet.getLong("acl_version"),
                        resultSet.getString("title")
                )
        );
    }

    String currentSourceSetHash() {
        return sourceSetHash(currentSources());
    }

    private static String sourceSetHash(List<SourceReference> sources) {
        StringBuilder input = new StringBuilder();
        sources.stream()
                .sorted((left, right) -> left.documentId().compareTo(
                        right.documentId()
                ))
                .forEach(source -> input.append(source.documentId())
                        .append('|')
                        .append(source.revisionId())
                        .append('|')
                        .append(source.aclVersion())
                        .append('\n'));
        return GraphAssembler.sha256(input.toString());
    }

    List<ManifestRow> manifests() {
        return jdbc.query(
                manifestSelect()
                        + " ORDER BY manifest.graph_generation DESC",
                (resultSet, rowNumber) -> manifestRow(resultSet)
        );
    }

    ManifestRow manifest(long generation) {
        return jdbc.query(
                manifestSelect()
                        + " WHERE manifest.graph_generation = ?",
                (resultSet, rowNumber) -> manifestRow(resultSet),
                generation
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_GENERATION_NOT_FOUND",
                "找不到 Graph Generation " + generation
        ));
    }

    Optional<ClaimedGeneration> claim() {
        closeExhaustedBuilds();
        return jdbc.query(
                """
                WITH candidate AS (
                    SELECT graph_generation
                    FROM graph_manifests
                    WHERE status = 'BUILDING'
                      AND build_attempt < build_max_attempts
                      AND (
                        lease_owner IS NULL
                        OR lease_expires_at < CURRENT_TIMESTAMP
                      )
                    ORDER BY created_at, graph_generation
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE graph_manifests manifest
                SET lease_owner = ?,
                    lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    build_attempt = manifest.build_attempt + 1,
                    started_at = COALESCE(manifest.started_at, CURRENT_TIMESTAMP),
                    projected_document_count = 0,
                    entity_count = 0,
                    mention_count = 0,
                    relationship_count = 0,
                    relationship_evidence_count = 0,
                    community_count = 0,
                    community_claim_count = 0,
                    cache_hit_count = 0,
                    model_call_count = 0,
                    failure_code = NULL,
                    failure_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidate
                WHERE manifest.graph_generation = candidate.graph_generation
                RETURNING manifest.id, manifest.graph_generation,
                          manifest.graph_config_version,
                          manifest.build_attempt
                """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ClaimedGeneration(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getLong("graph_generation"),
                            config(resultSet.getString("graph_config_version")),
                            resultSet.getInt("build_attempt")
                    ));
                },
                properties.getWorkerId(),
                properties.getLeaseDuration().toMillis()
        );
    }

    boolean progress(
            ClaimedGeneration claim,
            long processedDocuments,
            long cacheHits,
            long modelCalls
    ) {
        return jdbc.update(
                """
                UPDATE graph_manifests
                SET projected_document_count = ?,
                    cache_hit_count = ?,
                    model_call_count = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE graph_generation = ?
                  AND status = 'BUILDING'
                  AND lease_owner = ?
                  AND build_attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                processedDocuments,
                cacheHits,
                modelCalls,
                claim.generation(),
                properties.getWorkerId(),
                claim.attempt()
        ) == 1;
    }

    boolean heartbeat(ClaimedGeneration claim) {
        return jdbc.update(
                """
                UPDATE graph_manifests
                SET heartbeat_at = CURRENT_TIMESTAMP,
                    lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE graph_generation = ?
                  AND status = 'BUILDING'
                  AND lease_owner = ?
                  AND build_attempt = ?
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                properties.getLeaseDuration().toMillis(),
                claim.generation(),
                properties.getWorkerId(),
                claim.attempt()
        ) == 1;
    }

    void fail(ClaimedGeneration claim, String code, String reason) {
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    UPDATE graph_manifests
                    SET status = CASE
                            WHEN build_attempt >= build_max_attempts
                            THEN 'FAILED'
                            ELSE 'BUILDING'
                        END,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        failure_code = ?,
                        failure_reason = ?,
                        completed_at = CASE
                            WHEN build_attempt >= build_max_attempts
                            THEN CURRENT_TIMESTAMP
                            ELSE NULL
                        END,
                        retention_until = CASE
                            WHEN build_attempt >= build_max_attempts
                            THEN CURRENT_TIMESTAMP
                                 + (? * INTERVAL '1 millisecond')
                            ELSE NULL
                        END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE graph_generation = ?
                      AND status = 'BUILDING'
                      AND lease_owner = ?
                      AND build_attempt = ?
                    """,
                    code,
                    reason,
                    properties.getRetention().toMillis(),
                    claim.generation(),
                    properties.getWorkerId(),
                    claim.attempt()
            );
            resetFailedGraphRequests(claim.generation());
        });
    }

    SourceSize sourceSize(long generation) {
        return jdbc.queryForObject(
                """
                SELECT
                    (
                      SELECT count(*)
                      FROM graph_generation_sources source
                      WHERE source.graph_generation = ?
                    ) AS document_count,
                    count(parent.id) AS parent_count,
                    COALESCE(sum(length(parent.text)), 0) AS character_count
                FROM graph_generation_sources source
                LEFT JOIN chunks parent
                  ON parent.document_id = source.document_id
                 AND parent.revision_id = source.revision_id
                 AND parent.chunk_type = 'PARENT'
                WHERE source.graph_generation = ?
                """,
                (resultSet, rowNumber) -> new SourceSize(
                        resultSet.getLong("document_count"),
                        resultSet.getLong("parent_count"),
                        resultSet.getLong("character_count")
                ),
                generation,
                generation
        );
    }

    List<SourceDocument> sources(long generation) {
        Map<UUID, DocumentBuilder> documents = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT source.document_id, source.document_title AS title,
                       source.revision_id,
                       revision.revision_number, source.acl_version
                FROM graph_generation_sources source
                JOIN document_revisions revision
                  ON revision.id = source.revision_id
                 AND revision.document_id = source.document_id
                 AND revision.status = 'READY'
                WHERE source.graph_generation = ?
                ORDER BY source.document_id
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        UUID documentId = resultSet.getObject(
                                "document_id",
                                UUID.class
                        );
                        documents.put(documentId, new DocumentBuilder(
                                documentId,
                                resultSet.getString("title"),
                                resultSet.getObject("revision_id", UUID.class),
                                resultSet.getInt("revision_number"),
                                resultSet.getLong("acl_version")
                        ));
                    }
                    return null;
                },
                generation
        );
        if (documents.isEmpty()) {
            return List.of();
        }
        jdbc.query(
                """
                SELECT parent.document_id, parent.revision_id,
                       parent.id AS parent_id,
                       parent.chunk_order AS parent_order,
                       parent.text AS parent_text,
                       parent.heading_path AS parent_heading_path,
                       parent.content_hash AS parent_content_hash,
                       child.id AS child_id,
                       child.chunk_order AS child_order,
                       child.text AS child_text,
                       child.heading_path AS child_heading_path,
                       child.content_hash AS child_content_hash,
                       span.id AS span_id, span.span_order,
                       location.start_page, location.end_page,
                       location.start_offset, location.end_offset,
                       span.chunk_start_offset, span.chunk_end_offset,
                       location.source_text_hash
                FROM graph_generation_sources source
                JOIN chunks parent
                  ON parent.document_id = source.document_id
                 AND parent.revision_id = source.revision_id
                JOIN document_revisions revision
                  ON revision.id = parent.revision_id
                 AND revision.document_id = parent.document_id
                 AND revision.status = 'READY'
                JOIN chunks child
                  ON child.parent_chunk_id = parent.id
                 AND child.document_id = parent.document_id
                 AND child.revision_id = parent.revision_id
                 AND child.chunk_type = 'CHILD'
                 AND child.searchable = TRUE
                JOIN source_spans span
                  ON span.chunk_id = child.id
                 AND span.document_id = child.document_id
                 AND span.revision_id = child.revision_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                WHERE source.graph_generation = ?
                  AND parent.chunk_type = 'PARENT'
                ORDER BY parent.document_id, parent.chunk_order,
                         child.chunk_order, span.span_order
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        DocumentBuilder document = documents.get(
                                resultSet.getObject("document_id", UUID.class)
                        );
                        if (document == null
                                || !document.revisionId.equals(
                                resultSet.getObject("revision_id", UUID.class)
                        )) {
                            continue;
                        }
                        document.add(resultSet);
                    }
                    return null;
                },
                generation
        );
        return documents.values().stream()
                .map(DocumentBuilder::build)
                .toList();
    }

    List<ResolutionRule> rules(String ruleSetVersion) {
        return jdbc.query(
                """
                SELECT rule_order, action,
                       source_entity_keys::text,
                       match_aliases::text,
                       target_canonical_name,
                       target_normalized_name,
                       target_entity_type
                FROM graph_resolution_rules
                WHERE rule_set_version = ?
                ORDER BY rule_order
                """,
                (resultSet, rowNumber) -> new ResolutionRule(
                        resultSet.getInt("rule_order"),
                        resultSet.getString("action"),
                        strings(resultSet.getString("source_entity_keys")),
                        strings(resultSet.getString("match_aliases")),
                        resultSet.getString("target_canonical_name"),
                        resultSet.getString("target_normalized_name"),
                        resultSet.getString("target_entity_type")
                ),
                ruleSetVersion
        );
    }

    Optional<ExtractionArtifact> artifact(
            UUID parentChunkId,
            String inputHash
    ) {
        return jdbc.query(
                """
                SELECT id, output_json::text, output_hash,
                       entity_count, relationship_count
                FROM graph_extraction_artifacts
                WHERE parent_chunk_id = ?
                  AND input_hash = ?
                """,
                (resultSet, rowNumber) -> new ExtractionArtifact(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("output_json"),
                        resultSet.getString("output_hash"),
                        resultSet.getInt("entity_count"),
                        resultSet.getInt("relationship_count")
                ),
                parentChunkId,
                inputHash
        ).stream().findFirst();
    }

    ExtractionArtifact saveArtifact(
            GraphConfig config,
            SourceDocument document,
            ParentSource parent,
            String inputHash,
            String outputJson,
            String outputHash,
            int entityCount,
            int relationshipCount
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO graph_extraction_artifacts (
                    id, graph_config_version, document_id, revision_id,
                    parent_chunk_id, input_hash, output_json, output_hash,
                    entity_count, relationship_count
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                ON CONFLICT (
                    parent_chunk_id, input_hash
                ) DO NOTHING
                """,
                id,
                config.version(),
                document.documentId(),
                document.revisionId(),
                parent.id(),
                inputHash,
                outputJson,
                outputHash,
                entityCount,
                relationshipCount
        );
        return artifact(parent.id(), inputHash)
                .orElseThrow(() -> new IllegalStateException(
                        "Graph extraction artifact was not persisted"
                ));
    }

    void persistReady(ClaimedGeneration claim, GraphBuild build) {
        transactions.executeWithoutResult(status -> {
            Integer locked = jdbc.query(
                    """
                    SELECT 1
                    FROM graph_manifests
                    WHERE graph_generation = ?
                      AND status = 'BUILDING'
                      AND lease_owner = ?
                      AND build_attempt = ?
                      AND lease_expires_at > CURRENT_TIMESTAMP
                    FOR UPDATE
                    """,
                    resultSet -> resultSet.next() ? 1 : null,
                    claim.generation(),
                    properties.getWorkerId(),
                    claim.attempt()
            );
            if (locked == null) {
                throw new IllegalStateException(
                        "Graph Generation lease was lost before persistence"
                );
            }
            insertFacts(build);
            closures.requireGraph(claim.generation());
            int updated = jdbc.update(
                    """
                    UPDATE graph_manifests
                    SET status = 'READY',
                        projected_document_count = ?,
                        entity_count = ?,
                        mention_count = ?,
                        relationship_count = ?,
                        relationship_evidence_count = ?,
                        community_count = ?,
                        community_claim_count = ?,
                        cache_hit_count = ?,
                        model_call_count = ?,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        completed_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE graph_generation = ?
                      AND status = 'BUILDING'
                      AND lease_owner = ?
                      AND build_attempt = ?
                    """,
                    build.projections().size(),
                    build.entities().size(),
                    build.mentions().size(),
                    build.relationships().size(),
                    build.relationshipEvidence().size(),
                    build.communities().size(),
                    build.communityClaims().size(),
                    build.cacheHits(),
                    build.modelCalls(),
                    claim.generation(),
                    properties.getWorkerId(),
                    claim.attempt()
            );
            if (updated != 1) {
                throw new IllegalStateException(
                        "Graph Generation lease was lost before READY"
                );
            }
            jdbc.update(
                    """
                    UPDATE graph_rebuild_requests
                    SET state = CASE
                            WHEN global_rebuild_required
                            THEN 'GRAPH_READY'
                            ELSE 'FULFILLED'
                        END,
                        graph_ready_at = CURRENT_TIMESTAMP,
                        completed_at = CASE
                            WHEN global_rebuild_required
                            THEN NULL
                            ELSE CURRENT_TIMESTAMP
                        END
                    WHERE candidate_graph_generation = ?
                      AND state = 'GRAPH_BUILDING'
                    """,
                    claim.generation()
            );
        });
    }

    boolean caughtUp(long generation) {
        return closures.graph(generation).ready();
    }

    ProjectionClosureStatus closure(long generation) {
        return closures.graph(generation);
    }

    ManifestRow release(
            long generation,
            String expectedStatus,
            String action,
            String reason,
            UUID actorId
    ) {
        return transactions.execute(status -> {
            ManifestRow target = lockManifest(generation);
            if (!expectedStatus.equals(target.status())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "GRAPH_GENERATION_NOT_RELEASABLE",
                        "目标 Graph Generation 状态不允许此操作"
                );
            }
            lockGraphPublicationBoundary();
            lockDocumentSourceSet();
            closures.requireGraph(generation);
            ManifestRow current = lockCurrentManifest();
            if (current != null) {
                jdbc.update(
                        """
                        UPDATE graph_manifests
                        SET status = 'RETIRED',
                            retention_until =
                                CURRENT_TIMESTAMP
                                + (? * INTERVAL '1 millisecond'),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE graph_generation = ? AND status = 'ACTIVE'
                        """,
                        properties.getRetention().toMillis(),
                        current.generation()
                );
            }
            jdbc.update(
                    """
                    UPDATE graph_manifests
                    SET status = 'ACTIVE',
                        retention_until = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE graph_generation = ? AND status = ?
                    """,
                    generation,
                    expectedStatus
            );
            Long eventId = jdbc.queryForObject(
                    """
                    INSERT INTO graph_publication_events (
                        previous_graph_generation, graph_generation,
                        action, actor_user_id, reason
                    ) VALUES (?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    current == null ? null : current.generation(),
                    generation,
                    action,
                    actorId,
                    reason
            );
            if (eventId == null) {
                throw new IllegalStateException(
                        "Graph publication event was not created"
                );
            }
            jdbc.update(
                    """
                    INSERT INTO graph_publications (
                        singleton_id, graph_generation,
                        publication_event_id, published_at
                    ) VALUES (1, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (singleton_id) DO UPDATE
                    SET graph_generation = EXCLUDED.graph_generation,
                        publication_event_id =
                            EXCLUDED.publication_event_id,
                        published_at = CURRENT_TIMESTAMP
                    """,
                    generation,
                    eventId
            );
            return manifest(generation);
        });
    }

    Long activeGeneration() {
        return jdbc.query(
                """
                SELECT graph_generation
                FROM graph_publications
                WHERE singleton_id = 1
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getLong("graph_generation")
                        : null
        );
    }

    private void insertFacts(GraphBuild build) {
        long generation = build.generation();
        batch(
                """
                INSERT INTO graph_entities (
                    id, graph_generation, canonical_name,
                    normalized_name, entity_type, description
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                build.entities().stream().map(entity -> new Object[]{
                        entity.id(), generation, entity.canonicalName(),
                        entity.normalizedName(), entity.entityType(),
                        entity.description()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_entity_aliases (
                    graph_generation, entity_id, alias, normalized_alias
                ) VALUES (?, ?, ?, ?)
                """,
                build.aliases().stream().map(alias -> new Object[]{
                        generation, alias.entityId(), alias.alias(),
                        alias.normalizedAlias()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_entity_mentions (
                    id, graph_generation, entity_id, document_id,
                    revision_id, parent_chunk_id, child_chunk_id,
                    source_span_id, surface_text, start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                build.mentions().stream().map(mention -> new Object[]{
                        mention.id(), generation, mention.entityId(),
                        mention.documentId(), mention.revisionId(),
                        mention.parentChunkId(), mention.childChunkId(),
                        mention.sourceSpanId(), mention.surfaceText(),
                        mention.startOffset(), mention.endOffset()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_entity_alias_evidence (
                    graph_generation, entity_id, normalized_alias, mention_id
                ) VALUES (?, ?, ?, ?)
                """,
                build.aliasEvidence().stream().map(alias -> new Object[]{
                        generation,
                        alias.entityId(),
                        alias.normalizedAlias(),
                        alias.mentionId()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_relationships (
                    id, graph_generation, source_entity_id,
                    target_entity_id, relationship_type, description
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                build.relationships().stream().map(relationship -> new Object[]{
                        relationship.id(), generation,
                        relationship.sourceEntityId(),
                        relationship.targetEntityId(),
                        relationship.relationshipType(),
                        relationship.description()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_relationship_evidence (
                    id, graph_generation, relationship_id,
                    document_id, revision_id, parent_chunk_id,
                    child_chunk_id, source_span_id,
                    evidence_text, evidence_text_hash,
                    start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                build.relationshipEvidence().stream().map(evidence -> new Object[]{
                        evidence.id(), generation, evidence.relationshipId(),
                        evidence.documentId(), evidence.revisionId(),
                        evidence.parentChunkId(), evidence.childChunkId(),
                        evidence.sourceSpanId(), evidence.evidenceText(),
                        evidence.evidenceTextHash(), evidence.startOffset(),
                        evidence.endOffset()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_adjacency (
                    graph_generation, source_entity_id, target_entity_id,
                    relationship_id, direction, weight
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                build.adjacency().stream().map(edge -> new Object[]{
                        generation, edge.sourceEntityId(),
                        edge.targetEntityId(), edge.relationshipId(),
                        edge.direction(), edge.weight()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_communities (
                    id, graph_generation, community_key,
                    title, summary, entity_count
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                build.communities().stream().map(community -> new Object[]{
                        community.id(), generation, community.key(),
                        community.title(), community.summary(),
                        community.entityCount()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_community_members (
                    graph_generation, community_id,
                    entity_id, member_order
                ) VALUES (?, ?, ?, ?)
                """,
                build.communityMembers().stream().map(member -> new Object[]{
                        generation, member.communityId(),
                        member.entityId(), member.order()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_community_claims (
                    id, graph_generation, community_id,
                    relationship_id, relationship_evidence_id, claim_text
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                build.communityClaims().stream().map(claim -> new Object[]{
                        claim.id(), generation, claim.communityId(),
                        claim.relationshipId(),
                        claim.relationshipEvidenceId(), claim.claimText()
                }).toList()
        );
        batch(
                """
                INSERT INTO graph_projection_states (
                    graph_generation, document_id, revision_id,
                    acl_version, state, input_hash, artifact_ids
                ) VALUES (?, ?, ?, ?, 'PROJECTED', ?, CAST(? AS jsonb))
                """,
                build.projections().stream().map(projection -> new Object[]{
                        generation, projection.documentId(),
                        projection.revisionId(), projection.aclVersion(),
                        projection.inputHash(),
                        json(projection.artifactIds())
                }).toList()
        );
    }

    private void insertRule(
            String ruleSetVersion,
            int order,
            String action,
            List<String> sourceKeys,
            List<String> matchAliases,
            String targetName,
            String targetNormalizedName,
            String targetType,
            String reason
    ) {
        jdbc.update(
                """
                INSERT INTO graph_resolution_rules (
                    id, rule_set_version, rule_order, action,
                    source_entity_keys, match_aliases,
                    target_canonical_name, target_normalized_name,
                    target_entity_type, reason
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                          ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                ruleSetVersion,
                order,
                action,
                json(sourceKeys),
                json(matchAliases),
                targetName,
                targetNormalizedName,
                targetType,
                reason
        );
    }

    private List<String> entityKeys(long generation, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(
                ",",
                java.util.Collections.nCopies(ids.size(), "?")
        );
        List<Object> parameters = new ArrayList<>();
        parameters.add(generation);
        parameters.addAll(ids);
        return jdbc.query(
                """
                SELECT entity_type || '|' || normalized_name AS entity_key
                FROM graph_entities entity
                WHERE entity.graph_generation = ?
                  AND entity.id IN (%s)
                  AND EXISTS (
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
                    WHERE mention.graph_generation = entity.graph_generation
                      AND mention.entity_id = entity.id
                  )
                ORDER BY entity.entity_type, entity.normalized_name, entity.id
                """.formatted(placeholders),
                (resultSet, rowNumber) ->
                        resultSet.getString("entity_key"),
                parameters.toArray()
        );
    }

    private void lockGraphPublicationBoundary() {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(724247001)",
                resultSet -> null
        );
    }

    private void lockDocumentSourceSet() {
        jdbc.execute("LOCK TABLE documents IN SHARE MODE");
    }

    private ManifestRow lockManifest(long generation) {
        return jdbc.query(
                manifestSelect()
                        + " WHERE manifest.graph_generation = ?"
                        + " FOR UPDATE OF manifest",
                (resultSet, rowNumber) -> manifestRow(resultSet),
                generation
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_GENERATION_NOT_FOUND",
                "找不到 Graph Generation " + generation
        ));
    }

    private ManifestRow lockCurrentManifest() {
        return jdbc.query(
                manifestSelect()
                        + " WHERE manifest.status = 'ACTIVE'"
                        + " FOR UPDATE OF manifest",
                (resultSet, rowNumber) -> manifestRow(resultSet)
        ).stream().findFirst().orElse(null);
    }

    private void closeExhaustedBuilds() {
        jdbc.update(
                """
                UPDATE graph_manifests
                SET status = 'FAILED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    failure_code = 'GRAPH_BUILD_LEASE_EXHAUSTED',
                    failure_reason =
                        'Graph worker stopped before completing the build',
                    completed_at = CURRENT_TIMESTAMP,
                    retention_until = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'BUILDING'
                  AND build_attempt >= build_max_attempts
                  AND (
                    lease_owner IS NULL
                    OR lease_expires_at < CURRENT_TIMESTAMP
                  )
                """,
                properties.getRetention().toMillis()
        );
        jdbc.update(
                """
                UPDATE graph_rebuild_requests request
                SET state = 'REQUESTED',
                    candidate_graph_generation = NULL,
                    candidate_global_generation = NULL,
                    graph_ready_at = NULL,
                    global_ready_at = NULL,
                    completed_at = NULL
                WHERE request.state = 'GRAPH_BUILDING'
                  AND EXISTS (
                      SELECT 1
                      FROM graph_manifests manifest
                      WHERE manifest.graph_generation =
                            request.candidate_graph_generation
                        AND manifest.status = 'FAILED'
                  )
                """
        );
    }

    private void resetFailedGraphRequests(long generation) {
        jdbc.update(
                """
                UPDATE graph_rebuild_requests request
                SET state = 'REQUESTED',
                    candidate_graph_generation = NULL,
                    candidate_global_generation = NULL,
                    graph_ready_at = NULL,
                    global_ready_at = NULL,
                    completed_at = NULL
                WHERE request.candidate_graph_generation = ?
                  AND request.state = 'GRAPH_BUILDING'
                  AND EXISTS (
                      SELECT 1
                      FROM graph_manifests manifest
                      WHERE manifest.graph_generation =
                            request.candidate_graph_generation
                        AND manifest.status = 'FAILED'
                  )
                """,
                generation
        );
    }

    void cleanupExpired() {
        transactions.executeWithoutResult(status -> {
            List<Long> generations = jdbc.queryForList(
                    """
                    SELECT manifest.graph_generation
                    FROM graph_manifests manifest
                    WHERE manifest.status IN ('RETIRED', 'FAILED')
                      AND manifest.retention_until <= CURRENT_TIMESTAMP
                      AND NOT EXISTS (
                        SELECT 1
                        FROM global_graph_manifests global_manifest
                        WHERE global_manifest.source_graph_generation =
                              manifest.graph_generation
                      )
                      AND (
                        SELECT count(*)
                        FROM graph_manifests newer
                        WHERE newer.graph_generation
                              > manifest.graph_generation
                          AND newer.status <> 'DELETED'
                      ) >= 2
                    ORDER BY manifest.graph_generation
                    FOR UPDATE OF manifest SKIP LOCKED
                    """,
                    Long.class
            );
            for (Long generation : generations) {
                jdbc.update(
                        "DELETE FROM graph_projection_states "
                                + "WHERE graph_generation = ?",
                        generation
                );
                jdbc.update(
                        "DELETE FROM graph_generation_sources "
                                + "WHERE graph_generation = ?",
                        generation
                );
                jdbc.update(
                        "DELETE FROM graph_entities "
                                + "WHERE graph_generation = ?",
                        generation
                );
                jdbc.update(
                        "DELETE FROM graph_communities "
                                + "WHERE graph_generation = ?",
                        generation
                );
                jdbc.update(
                        """
                        UPDATE graph_manifests
                        SET status = 'DELETED',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE graph_generation = ?
                          AND status IN ('RETIRED', 'FAILED')
                        """,
                        generation
                );
            }
        });
    }

    private void batch(String sql, List<Object[]> values) {
        if (!values.isEmpty()) {
            jdbc.batchUpdate(sql, values);
        }
    }

    private List<String> strings(String json) {
        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<List<String>>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored graph rule JSON is invalid",
                    exception
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Graph state cannot be serialized",
                    exception
            );
        }
    }

    private static String graphConfigSelect() {
        return """
                SELECT config.version, config.extraction_model,
                       config.extraction_revision, config.prompt_version,
                       config.schema_version, config.normalization_version,
                       config.resolution_rule_set_version,
                       config.community_algorithm,
                       config.community_algorithm_version,
                       config.community_seed, config.community_resolution,
                       config.reason, config.created_at
                FROM graph_configs config
                """;
    }

    private static GraphConfig graphConfig(ResultSet resultSet)
            throws SQLException {
        return new GraphConfig(
                resultSet.getString("version"),
                resultSet.getString("extraction_model"),
                resultSet.getString("extraction_revision"),
                resultSet.getString("prompt_version"),
                resultSet.getString("schema_version"),
                resultSet.getString("normalization_version"),
                resultSet.getString("resolution_rule_set_version"),
                resultSet.getString("community_algorithm"),
                resultSet.getString("community_algorithm_version"),
                resultSet.getLong("community_seed"),
                resultSet.getDouble("community_resolution"),
                resultSet.getString("reason"),
                instant(resultSet, "created_at")
        );
    }

    private static String manifestSelect() {
        return """
                SELECT manifest.id, manifest.graph_generation,
                       manifest.graph_config_version, manifest.status,
                       manifest.expected_document_count,
                       manifest.source_set_hash,
                       manifest.projected_document_count,
                       manifest.entity_count, manifest.mention_count,
                       manifest.relationship_count,
                       manifest.relationship_evidence_count,
                       manifest.community_count,
                       manifest.community_claim_count,
                       manifest.cache_hit_count, manifest.model_call_count,
                       manifest.build_attempt, manifest.failure_code,
                       manifest.failure_reason, manifest.build_reason,
                       manifest.created_at, manifest.started_at,
                       manifest.completed_at, manifest.retention_until,
                       manifest.updated_at, manifest.heartbeat_at,
                       manifest.lease_expires_at
                FROM graph_manifests manifest
                """;
    }

    private static ManifestRow manifestRow(ResultSet resultSet)
            throws SQLException {
        return new ManifestRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("graph_generation"),
                resultSet.getString("graph_config_version"),
                resultSet.getString("status"),
                resultSet.getLong("expected_document_count"),
                resultSet.getString("source_set_hash"),
                resultSet.getLong("projected_document_count"),
                resultSet.getLong("entity_count"),
                resultSet.getLong("mention_count"),
                resultSet.getLong("relationship_count"),
                resultSet.getLong("relationship_evidence_count"),
                resultSet.getLong("community_count"),
                resultSet.getLong("community_claim_count"),
                resultSet.getLong("cache_hit_count"),
                resultSet.getLong("model_call_count"),
                resultSet.getInt("build_attempt"),
                instant(resultSet, "heartbeat_at"),
                instant(resultSet, "lease_expires_at"),
                resultSet.getString("failure_code"),
                resultSet.getString("failure_reason"),
                resultSet.getString("build_reason"),
                instant(resultSet, "created_at"),
                instant(resultSet, "started_at"),
                instant(resultSet, "completed_at"),
                instant(resultSet, "retention_until"),
                instant(resultSet, "updated_at")
        );
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record ManifestRow(
            UUID id,
            long generation,
            String configVersion,
            String status,
            long expectedDocuments,
            String sourceSetHash,
            long projectedDocuments,
            long entities,
            long mentions,
            long relationships,
            long relationshipEvidence,
            long communities,
            long communityClaims,
            long cacheHits,
            long modelCalls,
            int attempt,
            Instant heartbeatAt,
            Instant leaseExpiresAt,
            String failureCode,
            String failureReason,
            String buildReason,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant retentionUntil,
            Instant updatedAt
    ) {
    }

    record SourceSize(
            long documents,
            long parents,
            long characters
    ) {
    }

    private record SourceReference(
            UUID documentId,
            UUID revisionId,
            long aclVersion,
            String title
    ) {
    }

    private static final class DocumentBuilder {

        private final UUID documentId;
        private final String title;
        private final UUID revisionId;
        private final int revisionNumber;
        private final long aclVersion;
        private final Map<UUID, ParentBuilder> parents = new LinkedHashMap<>();

        private DocumentBuilder(
                UUID documentId,
                String title,
                UUID revisionId,
                int revisionNumber,
                long aclVersion
        ) {
            this.documentId = documentId;
            this.title = title;
            this.revisionId = revisionId;
            this.revisionNumber = revisionNumber;
            this.aclVersion = aclVersion;
        }

        private void add(ResultSet resultSet) throws SQLException {
            UUID parentId = resultSet.getObject("parent_id", UUID.class);
            ParentBuilder parent = parents.computeIfAbsent(
                    parentId,
                    ignored -> new ParentBuilder(
                            parentId,
                            integer(resultSet, "parent_order"),
                            string(resultSet, "parent_text"),
                            string(resultSet, "parent_heading_path"),
                            string(resultSet, "parent_content_hash")
                    )
            );
            parent.add(resultSet);
        }

        private SourceDocument build() {
            return new SourceDocument(
                    documentId,
                    title,
                    revisionId,
                    revisionNumber,
                    aclVersion,
                    parents.values().stream().map(ParentBuilder::build).toList()
            );
        }
    }

    private static final class ParentBuilder {

        private final UUID id;
        private final int order;
        private final String text;
        private final String headingPath;
        private final String contentHash;
        private final Map<UUID, ChildBuilder> children = new LinkedHashMap<>();

        private ParentBuilder(
                UUID id,
                int order,
                String text,
                String headingPath,
                String contentHash
        ) {
            this.id = id;
            this.order = order;
            this.text = text;
            this.headingPath = headingPath;
            this.contentHash = contentHash;
        }

        private void add(ResultSet resultSet) throws SQLException {
            UUID childId = resultSet.getObject("child_id", UUID.class);
            ChildBuilder child = children.computeIfAbsent(
                    childId,
                    ignored -> new ChildBuilder(
                            childId,
                            integer(resultSet, "child_order"),
                            string(resultSet, "child_text"),
                            string(resultSet, "child_heading_path"),
                            string(resultSet, "child_content_hash")
                    )
            );
            child.add(resultSet);
        }

        private ParentSource build() {
            return new ParentSource(
                    id,
                    order,
                    text,
                    headingPath,
                    contentHash,
                    children.values().stream().map(ChildBuilder::build).toList()
            );
        }
    }

    private static final class ChildBuilder {

        private final UUID id;
        private final int order;
        private final String text;
        private final String headingPath;
        private final String contentHash;
        private final List<SpanSource> spans = new ArrayList<>();

        private ChildBuilder(
                UUID id,
                int order,
                String text,
                String headingPath,
                String contentHash
        ) {
            this.id = id;
            this.order = order;
            this.text = text;
            this.headingPath = headingPath;
            this.contentHash = contentHash;
        }

        private void add(ResultSet resultSet) throws SQLException {
            spans.add(new SpanSource(
                    resultSet.getObject("span_id", UUID.class),
                    resultSet.getInt("span_order"),
                    resultSet.getObject("start_page", Integer.class),
                    resultSet.getObject("end_page", Integer.class),
                    resultSet.getInt("start_offset"),
                    resultSet.getInt("end_offset"),
                    resultSet.getInt("chunk_start_offset"),
                    resultSet.getInt("chunk_end_offset"),
                    resultSet.getString("source_text_hash")
            ));
        }

        private ChildSource build() {
            return new ChildSource(
                    id,
                    order,
                    text,
                    headingPath,
                    contentHash,
                    List.copyOf(spans)
            );
        }
    }

    private static int integer(ResultSet resultSet, String column) {
        try {
            return resultSet.getInt(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String string(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
