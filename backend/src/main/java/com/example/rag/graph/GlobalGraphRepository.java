package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GlobalGraphContracts.ArtifactSource;
import com.example.rag.graph.GlobalGraphContracts.BuildResult;
import com.example.rag.graph.GlobalGraphContracts.ClaimFact;
import com.example.rag.graph.GlobalGraphContracts.ClaimedGeneration;
import com.example.rag.graph.GlobalGraphContracts.EvidenceAnchor;
import com.example.rag.graph.GlobalGraphContracts.GlobalConfig;
import com.example.rag.graph.GlobalGraphContracts.ManifestRow;
import com.example.rag.graph.GlobalGraphContracts.ReportFact;
import com.example.rag.graph.GlobalReportProvider.Descriptor;
import com.example.rag.search.GlobalReportIndexService;
import com.example.rag.projection.ProjectionClosureService;
import com.example.rag.projection.ProjectionClosureStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GlobalGraphRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GraphProperties properties;
    private final ProjectionClosureService closures;

    GlobalGraphRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            GraphProperties properties,
            ProjectionClosureService closures
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.closures = closures;
    }

    List<GlobalConfig> configs() {
        return jdbc.query(
                configSelect() + " ORDER BY created_at, version",
                (resultSet, rowNumber) -> config(resultSet)
        );
    }

    GlobalConfig config(String version) {
        return jdbc.query(
                configSelect() + " WHERE version = ?",
                (resultSet, rowNumber) -> config(resultSet),
                version
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GLOBAL_CONFIG_NOT_FOUND",
                "找不到 GlobalGraphConfig " + version
        ));
    }

    GlobalConfig createConfig(
            String version,
            String reason,
            UUID actorId,
            Descriptor descriptor
    ) {
        if (!descriptor.enabled()
                || blank(descriptor.model())
                || blank(descriptor.revision())) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GLOBAL_REPORT_MODEL_DISABLED",
                    "请先配置可用的图谱抽取/报告模型"
            );
        }
        try {
            int inserted = jdbc.update(
                    """
                    INSERT INTO global_graph_configs (
                        version, report_model, report_revision,
                        prompt_version, schema_version,
                        community_algorithm,
                        community_algorithm_version,
                        community_seed, community_resolution,
                        index_config_version,
                        bm25_top_k, vector_top_k, rrf_rank_constant,
                        report_limit, context_token_budget,
                        map_call_limit, model_call_limit,
                        hard_timeout_ms, statement_timeout_ms,
                        reason, created_by
                    )
                    SELECT ?, ?, ?, ?, ?,
                           source_config.community_algorithm,
                           source_config.community_algorithm_version,
                           source_config.community_seed,
                           source_config.community_resolution,
                           active_index.index_config_version,
                           50, 50, 60, 8, 1800, 8, 9,
                           30000, 1500, ?, ?
                    FROM graph_publications publication
                    JOIN graph_manifests manifest
                      ON manifest.graph_generation =
                         publication.graph_generation
                     AND manifest.status = 'ACTIVE'
                    JOIN graph_configs source_config
                      ON source_config.version =
                         manifest.graph_config_version
                    CROSS JOIN LATERAL (
                        SELECT index_config_version
                        FROM index_manifests
                        WHERE status = 'ACTIVE'
                        ORDER BY index_generation DESC
                        LIMIT 1
                    ) active_index
                    WHERE publication.singleton_id = 1
                    """,
                    version,
                    descriptor.model(),
                    descriptor.revision(),
                    descriptor.promptVersion(),
                    descriptor.schemaVersion(),
                    reason,
                    actorId
            );
            if (inserted != 1) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "GLOBAL_CONFIG_SOURCE_UNAVAILABLE",
                        "需要 ACTIVE Graph Generation 和 ACTIVE Index Generation"
                );
            }
            return config(version);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "GLOBAL_CONFIG_EXISTS",
                    "GlobalGraphConfig 版本已存在",
                    exception
            );
        }
    }

    ManifestRow start(
            String configVersion,
            String reason,
            UUID actorId
    ) {
        return transactions.execute(status -> {
            GlobalConfig config = config(configVersion);
            Long sourceGeneration = jdbc.query(
                    """
                    SELECT publication.graph_generation
                    FROM graph_publications publication
                    JOIN graph_manifests manifest
                      ON manifest.graph_generation =
                         publication.graph_generation
                     AND manifest.status = 'ACTIVE'
                    WHERE publication.singleton_id = 1
                    FOR SHARE OF manifest
                    """,
                    (resultSet, rowNumber) ->
                            resultSet.getLong("graph_generation")
            ).stream().findFirst().orElseThrow(() -> new ApiException(
                    HttpStatus.CONFLICT,
                    "GLOBAL_SOURCE_GRAPH_UNAVAILABLE",
                    "当前没有 ACTIVE Graph Generation"
            ));
            List<SourceReference> sources = currentPublicSources(
                    sourceGeneration
            );
            if (sources.isEmpty()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "GLOBAL_PUBLIC_SOURCE_EMPTY",
                        "当前没有可构建 Global 报告的 ALL_USERS 图谱来源"
                );
            }
            UUID id = UUID.randomUUID();
            String indexName = "rag-global-reports-"
                    + id.toString().replace("-", "");
            long generation;
            try {
                Long value = jdbc.queryForObject(
                        """
                        INSERT INTO global_graph_manifests (
                            id, global_config_version,
                            source_graph_generation, index_name,
                            status, source_set_hash,
                            expected_source_count,
                            requested_by, build_reason
                        ) VALUES (?, ?, ?, ?, 'BUILDING', ?, ?, ?, ?)
                        RETURNING global_generation
                        """,
                        Long.class,
                        id,
                        config.version(),
                        sourceGeneration,
                        indexName,
                        sourceSetHash(sources),
                        sources.size(),
                        actorId,
                        reason
                );
                generation = value == null ? 0 : value;
            } catch (DuplicateKeyException exception) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "GLOBAL_BUILD_ALREADY_RUNNING",
                        "该 GlobalGraphConfig 已有构建任务",
                        exception
                );
            }
            jdbc.batchUpdate(
                    """
                    INSERT INTO global_graph_sources (
                        global_generation, source_graph_generation,
                        document_id, revision_id, acl_version,
                        document_title
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    sources,
                    100,
                    (statement, source) -> {
                        statement.setLong(1, generation);
                        statement.setLong(2, sourceGeneration);
                        statement.setObject(3, source.documentId());
                        statement.setObject(4, source.revisionId());
                        statement.setLong(5, source.aclVersion());
                        statement.setString(6, source.title());
                    }
            );
            jdbc.update(
                    """
                    UPDATE graph_rebuild_requests request
                    SET state = 'GLOBAL_BUILDING',
                        candidate_global_generation = ?
                    WHERE request.state = 'GRAPH_READY'
                      AND request.global_rebuild_required
                      AND request.candidate_graph_generation = ?
                      AND EXISTS (
                          SELECT 1
                          FROM documents document
                          WHERE document.id = request.document_id
                            AND document.deleted_at IS NULL
                            AND document.current_revision_id =
                                request.target_revision_id
                            AND document.acl_version =
                                request.target_acl_version
                      )
                    """,
                    generation,
                    sourceGeneration
            );
            return manifest(generation);
        });
    }

    List<ManifestRow> manifests() {
        return jdbc.query(
                manifestSelect()
                        + " ORDER BY global_generation DESC",
                (resultSet, rowNumber) -> manifest(resultSet)
        );
    }

    ManifestRow manifest(long generation) {
        return jdbc.query(
                manifestSelect() + " WHERE global_generation = ?",
                (resultSet, rowNumber) -> manifest(resultSet),
                generation
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GLOBAL_GENERATION_NOT_FOUND",
                "找不到 Global Generation " + generation
        ));
    }

    Optional<ClaimedGeneration> claim() {
        return transactions.execute(status -> {
            closeExhaustedBuilds();
            List<ManifestRow> candidates = jdbc.query(
                    manifestSelect() + """
                     WHERE status = 'BUILDING'
                       AND build_attempt < build_max_attempts
                       AND (
                         lease_owner IS NULL
                         OR lease_expires_at < CURRENT_TIMESTAMP
                       )
                     ORDER BY created_at, global_generation
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                    """,
                    (resultSet, rowNumber) -> manifest(resultSet)
            );
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            ManifestRow row = candidates.getFirst();
            int attempt = row.attempt() + 1;
            String owner = properties.getWorkerId()
                    + "-global-" + UUID.randomUUID();
            int updated = jdbc.update(
                    """
                    UPDATE global_graph_manifests
                    SET build_attempt = ?,
                        lease_owner = ?,
                        lease_expires_at =
                            CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                        heartbeat_at = CURRENT_TIMESTAMP,
                        started_at = COALESCE(
                            started_at, CURRENT_TIMESTAMP
                        ),
                        failure_code = NULL,
                        failure_reason = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE global_generation = ?
                      AND status = 'BUILDING'
                    """,
                    attempt,
                    owner,
                    properties.getLeaseDuration().toMillis(),
                    row.generation()
            );
            if (updated != 1) {
                return Optional.empty();
            }
            return Optional.of(new ClaimedGeneration(
                    row.generation(),
                    row.id(),
                    config(row.configVersion()),
                    row.sourceGraphGeneration(),
                    row.indexName(),
                    attempt,
                    owner
            ));
        });
    }

    boolean heartbeat(ClaimedGeneration claim) {
        return jdbc.update(
                """
                UPDATE global_graph_manifests
                SET lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    heartbeat_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE global_generation = ?
                  AND status = 'BUILDING'
                  AND build_attempt = ?
                  AND lease_owner = ?
                """,
                properties.getLeaseDuration().toMillis(),
                claim.generation(),
                claim.attempt(),
                claim.leaseOwner()
        ) == 1;
    }

    List<ArtifactSource> artifacts(ClaimedGeneration claim) {
        return jdbc.query(
                """
                SELECT artifact.id, source.document_id,
                       source.revision_id, source.acl_version,
                       source.document_title,
                       artifact.output_json::text AS output_json
                FROM global_graph_sources source
                JOIN graph_projection_states projection
                  ON projection.graph_generation =
                     source.source_graph_generation
                 AND projection.document_id = source.document_id
                 AND projection.revision_id = source.revision_id
                 AND projection.acl_version = source.acl_version
                 AND projection.state = 'PROJECTED'
                CROSS JOIN LATERAL jsonb_array_elements_text(
                    projection.artifact_ids
                ) artifact_id
                JOIN graph_extraction_artifacts artifact
                  ON artifact.id = artifact_id::uuid
                 AND artifact.document_id = source.document_id
                 AND artifact.revision_id = source.revision_id
                WHERE source.global_generation = ?
                  AND source.source_graph_generation = ?
                ORDER BY source.document_id,
                         artifact.parent_chunk_id,
                         artifact.id
                """,
                (resultSet, rowNumber) -> new ArtifactSource(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getLong("acl_version"),
                        resultSet.getString("document_title"),
                        resultSet.getString("output_json")
                ),
                claim.generation(),
                claim.sourceGraphGeneration()
        );
    }

    List<EvidenceAnchor> evidence(ClaimedGeneration claim) {
        return jdbc.query(
                """
                SELECT evidence.id, evidence.relationship_id,
                       evidence.document_id, evidence.revision_id,
                       source.acl_version, source.document_title,
                       revision.revision_number,
                       evidence.child_chunk_id, evidence.source_span_id,
                       evidence.evidence_text,
                       evidence.evidence_text_hash,
                       location.start_page, location.end_page
                FROM graph_relationship_evidence evidence
                JOIN global_graph_sources source
                  ON source.source_graph_generation =
                     evidence.graph_generation
                 AND source.document_id = evidence.document_id
                 AND source.revision_id = evidence.revision_id
                JOIN document_revisions revision
                  ON revision.id = evidence.revision_id
                 AND revision.document_id = evidence.document_id
                JOIN source_spans span
                  ON span.id = evidence.source_span_id
                 AND span.chunk_id = evidence.child_chunk_id
                 AND span.document_id = evidence.document_id
                 AND span.revision_id = evidence.revision_id
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = span.id
                WHERE source.global_generation = ?
                  AND evidence.graph_generation = ?
                ORDER BY evidence.document_id,
                         evidence.child_chunk_id,
                         evidence.id
                """,
                (resultSet, rowNumber) -> evidence(resultSet),
                claim.generation(),
                claim.sourceGraphGeneration()
        );
    }

    boolean caughtUp(long generation) {
        return closures.global(generation).ready();
    }

    ProjectionClosureStatus closure(long generation) {
        return closures.global(generation);
    }

    void persistReady(
            ClaimedGeneration claim,
            BuildResult build,
            GlobalReportIndexService.BuildResult index
    ) {
        transactions.executeWithoutResult(status -> {
            requireLease(claim);
            if (!caughtUp(claim.generation())) {
                throw new GlobalReportException(
                        "GLOBAL_SOURCE_STALE",
                        "Global Generation 来源图、Revision 或 ACL 已变化"
                );
            }
            for (ReportFact report : build.reports()) {
                int expectedEvidence = report.claims().stream()
                        .mapToInt(item -> item.evidence().size())
                        .sum();
                jdbc.update(
                        """
                        INSERT INTO global_community_reports (
                            id, global_generation, community_key,
                            title, summary, search_text, content_hash,
                            token_count, expected_evidence_count
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        report.id(),
                        claim.generation(),
                        report.communityKey(),
                        report.title(),
                        report.summary(),
                        report.searchText(),
                        report.contentHash(),
                        report.tokenCount(),
                        expectedEvidence
                );
                for (ClaimFact item : report.claims()) {
                    jdbc.update(
                            """
                            INSERT INTO global_report_claims (
                                id, global_generation, report_id,
                                claim_order, claim_text
                            ) VALUES (?, ?, ?, ?, ?)
                            """,
                            item.id(),
                            claim.generation(),
                            report.id(),
                            item.order(),
                            item.text()
                    );
                    for (EvidenceAnchor evidence : item.evidence()) {
                        jdbc.update(
                                """
                                INSERT INTO global_report_evidence (
                                    id, global_generation,
                                    source_graph_generation,
                                    report_id, claim_id,
                                    relationship_id,
                                    relationship_evidence_id,
                                    document_id, revision_id,
                                    child_chunk_id, source_span_id,
                                    acl_version, evidence_text,
                                    evidence_text_hash
                                ) VALUES (
                                    ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                    ?, ?, ?, ?, ?
                                )
                                """,
                                UUID.randomUUID(),
                                claim.generation(),
                                claim.sourceGraphGeneration(),
                                report.id(),
                                item.id(),
                                evidence.relationshipId(),
                                evidence.id(),
                                evidence.documentId(),
                                evidence.revisionId(),
                                evidence.childChunkId(),
                                evidence.sourceSpanId(),
                                evidence.aclVersion(),
                                evidence.evidenceText(),
                                evidence.evidenceTextHash()
                        );
                    }
                }
            }
            jdbc.update(
                    """
                    INSERT INTO global_report_index_states (
                        global_generation, index_name, state,
                        indexed_report_count, valid_vector_count
                    ) VALUES (?, ?, 'READY', ?, ?)
                    """,
                    claim.generation(),
                    claim.indexName(),
                    index.indexedReportCount(),
                    index.validVectorCount()
            );
            if (index.indexedReportCount() != build.reports().size()
                    || index.validVectorCount() != build.reports().size()) {
                throw new GlobalReportException(
                        "GLOBAL_REPORT_INDEX_CLOSURE_INCOMPLETE",
                        "Global Report 索引或向量覆盖未完成"
                );
            }
            closures.requireGlobal(claim.generation());
            int updated = jdbc.update(
                    """
                    UPDATE global_graph_manifests
                    SET status = 'READY',
                        report_count = ?,
                        claim_count = ?,
                        evidence_count = ?,
                        indexed_report_count = ?,
                        valid_vector_count = ?,
                        model_call_count = ?,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        completed_at = CURRENT_TIMESTAMP,
                        retention_until =
                            CURRENT_TIMESTAMP
                            + (? * INTERVAL '1 millisecond'),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE global_generation = ?
                      AND status = 'BUILDING'
                      AND build_attempt = ?
                      AND lease_owner = ?
                    """,
                    build.reports().size(),
                    build.claimCount(),
                    build.evidenceCount(),
                    index.indexedReportCount(),
                    index.validVectorCount(),
                    build.modelCalls(),
                    properties.getRetention().toMillis(),
                    claim.generation(),
                    claim.attempt(),
                    claim.leaseOwner()
            );
            if (updated != 1) {
                throw leaseLost();
            }
            jdbc.update(
                    """
                    UPDATE graph_rebuild_requests
                    SET state = 'FULFILLED',
                        global_ready_at = CURRENT_TIMESTAMP,
                        completed_at = CURRENT_TIMESTAMP
                    WHERE candidate_global_generation = ?
                      AND state = 'GLOBAL_BUILDING'
                    """,
                    claim.generation()
            );
        });
    }

    void fail(ClaimedGeneration claim, String code, String reason) {
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    UPDATE global_graph_manifests
                    SET status = CASE
                            WHEN build_attempt >= build_max_attempts
                            THEN 'FAILED'
                            ELSE status
                        END,
                        failure_code = ?,
                        failure_reason = ?,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        heartbeat_at = NULL,
                        completed_at = CASE
                            WHEN build_attempt >= build_max_attempts
                            THEN CURRENT_TIMESTAMP
                            ELSE completed_at
                        END,
                        retention_until = CASE
                            WHEN build_attempt >= build_max_attempts
                            THEN CURRENT_TIMESTAMP
                                 + (? * INTERVAL '1 millisecond')
                            ELSE retention_until
                        END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE global_generation = ?
                      AND status = 'BUILDING'
                      AND build_attempt = ?
                      AND lease_owner = ?
                    """,
                    code,
                    concise(reason),
                    properties.getRetention().toMillis(),
                    claim.generation(),
                    claim.attempt(),
                    claim.leaseOwner()
            );
            resetFailedGlobalRequests(claim.generation());
        });
    }

    ManifestRow release(
            long generation,
            UUID actorId,
            String reason,
            String action
    ) {
        return transactions.execute(status -> {
            ManifestRow target = lockManifest(generation);
            lockGraphPublicationBoundary();
            lockDocumentSourceSet();
            closures.requireGlobal(generation);
            Long current = lockActiveGeneration();
            if ("ROLLBACK".equals(action)) {
                if (!"RETIRED".equals(target.status())
                        || current == null
                        || current == generation) {
                    throw invalidRelease();
                }
            } else if (!"READY".equals(target.status())) {
                throw invalidRelease();
            }
            Long eventId = jdbc.queryForObject(
                    """
                    INSERT INTO global_graph_publication_events (
                        previous_global_generation,
                        global_generation, action,
                        actor_user_id, reason
                    ) VALUES (?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    current,
                    generation,
                    action,
                    actorId,
                    reason
            );
            if (current != null) {
                jdbc.update(
                        """
                        UPDATE global_graph_manifests
                        SET status = 'RETIRED',
                            retention_until =
                                CURRENT_TIMESTAMP
                                + (? * INTERVAL '1 millisecond'),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE global_generation = ?
                          AND status = 'ACTIVE'
                        """,
                        properties.getRetention().toMillis(),
                        current
                );
            }
            jdbc.update(
                    """
                    UPDATE global_graph_manifests
                    SET status = 'ACTIVE',
                        retention_until = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE global_generation = ?
                    """,
                    generation
            );
            jdbc.update(
                    """
                    INSERT INTO global_graph_publications (
                        singleton_id, global_generation,
                        publication_event_id
                    ) VALUES (1, ?, ?)
                    ON CONFLICT (singleton_id) DO UPDATE
                    SET global_generation =
                            EXCLUDED.global_generation,
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
                SELECT publication.global_generation
                FROM global_graph_publications publication
                JOIN global_graph_manifests manifest
                  ON manifest.global_generation =
                     publication.global_generation
                 AND manifest.status = 'ACTIVE'
                WHERE publication.singleton_id = 1
                """,
                (resultSet, rowNumber) ->
                        resultSet.getLong("global_generation")
        ).stream().findFirst().orElse(null);
    }

    List<String> expiredIndexes() {
        return jdbc.queryForList(
                """
                SELECT index_name
                FROM global_graph_manifests
                WHERE status IN ('RETIRED', 'FAILED')
                  AND retention_until <= CURRENT_TIMESTAMP
                  AND (
                    SELECT count(*)
                    FROM global_graph_manifests newer
                    WHERE newer.global_generation >
                          global_graph_manifests.global_generation
                      AND newer.status <> 'DELETED'
                  ) >= 2
                ORDER BY global_generation
                """,
                String.class
        );
    }

    void markDeleted(String indexName) {
        jdbc.update(
                """
                UPDATE global_graph_manifests
                SET status = 'DELETED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE index_name = ?
                  AND status IN ('RETIRED', 'FAILED')
                """,
                indexName
        );
    }

    private void requireLease(ClaimedGeneration claim) {
        Boolean valid = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM global_graph_manifests
                    WHERE global_generation = ?
                      AND id = ?
                      AND status = 'BUILDING'
                      AND build_attempt = ?
                      AND lease_owner = ?
                      AND lease_expires_at > CURRENT_TIMESTAMP
                )
                """,
                Boolean.class,
                claim.generation(),
                claim.id(),
                claim.attempt(),
                claim.leaseOwner()
        );
        if (!Boolean.TRUE.equals(valid)) {
            throw leaseLost();
        }
    }

    private List<SourceReference> currentPublicSources(long generation) {
        return jdbc.query(
                """
                SELECT source.document_id, source.revision_id,
                       source.acl_version, source.document_title
                FROM graph_generation_sources source
                JOIN documents document
                  ON document.id = source.document_id
                 AND document.current_revision_id =
                     source.revision_id
                 AND document.deleted_at IS NULL
                 AND document.visibility = 'ALL_USERS'
                 AND document.acl_version = source.acl_version
                JOIN document_revisions revision
                  ON revision.id = source.revision_id
                 AND revision.document_id = source.document_id
                 AND revision.status = 'READY'
                JOIN graph_projection_states projection
                  ON projection.graph_generation =
                     source.graph_generation
                 AND projection.document_id = source.document_id
                 AND projection.revision_id = source.revision_id
                 AND projection.acl_version = source.acl_version
                 AND projection.state = 'PROJECTED'
                WHERE source.graph_generation = ?
                ORDER BY source.document_id
                """,
                (resultSet, rowNumber) -> new SourceReference(
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getLong("acl_version"),
                        resultSet.getString("document_title")
                ),
                generation
        );
    }

    private static String sourceSetHash(List<SourceReference> sources) {
        StringBuilder value = new StringBuilder();
        sources.stream()
                .sorted(Comparator.comparing(SourceReference::documentId))
                .forEach(source -> value
                        .append(source.documentId()).append('|')
                        .append(source.revisionId()).append('|')
                        .append(source.aclVersion()).append('\n'));
        return GraphAssembler.sha256(value.toString());
    }

    private Long lockActiveGeneration() {
        return jdbc.query(
                """
                SELECT manifest.global_generation
                FROM global_graph_manifests manifest
                WHERE manifest.status = 'ACTIVE'
                FOR UPDATE
                """,
                (resultSet, rowNumber) ->
                        resultSet.getLong("global_generation")
        ).stream().findFirst().orElse(null);
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
                        + " WHERE global_generation = ? FOR UPDATE",
                (resultSet, rowNumber) -> manifest(resultSet),
                generation
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GLOBAL_GENERATION_NOT_FOUND",
                "找不到 Global Generation " + generation
        ));
    }

    private void closeExhaustedBuilds() {
        jdbc.update(
                """
                UPDATE global_graph_manifests
                SET status = 'FAILED',
                    failure_code = COALESCE(
                        failure_code, 'GLOBAL_BUILD_RETRIES_EXHAUSTED'
                    ),
                    failure_reason = COALESCE(
                        failure_reason, 'Global report build retries exhausted'
                    ),
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    heartbeat_at = NULL,
                    completed_at = CURRENT_TIMESTAMP,
                    retention_until =
                        CURRENT_TIMESTAMP
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
                SET state = CASE
                        WHEN graph_manifest.status = 'ACTIVE'
                        THEN 'GRAPH_READY'
                        ELSE 'REQUESTED'
                    END,
                    candidate_graph_generation = CASE
                        WHEN graph_manifest.status = 'ACTIVE'
                        THEN request.candidate_graph_generation
                        ELSE NULL
                    END,
                    candidate_global_generation = NULL,
                    graph_ready_at = CASE
                        WHEN graph_manifest.status = 'ACTIVE'
                        THEN request.graph_ready_at
                        ELSE NULL
                    END,
                    global_ready_at = NULL,
                    completed_at = NULL
                FROM graph_manifests graph_manifest
                WHERE request.state = 'GLOBAL_BUILDING'
                  AND graph_manifest.graph_generation =
                        request.candidate_graph_generation
                  AND EXISTS (
                      SELECT 1
                      FROM global_graph_manifests manifest
                      WHERE manifest.global_generation =
                            request.candidate_global_generation
                        AND manifest.status = 'FAILED'
                  )
                """
        );
    }

    private void resetFailedGlobalRequests(long generation) {
        jdbc.update(
                """
                UPDATE graph_rebuild_requests request
                SET state = CASE
                        WHEN graph_manifest.status = 'ACTIVE'
                        THEN 'GRAPH_READY'
                        ELSE 'REQUESTED'
                    END,
                    candidate_graph_generation = CASE
                        WHEN graph_manifest.status = 'ACTIVE'
                        THEN request.candidate_graph_generation
                        ELSE NULL
                    END,
                    candidate_global_generation = NULL,
                    graph_ready_at = CASE
                        WHEN graph_manifest.status = 'ACTIVE'
                        THEN request.graph_ready_at
                        ELSE NULL
                    END,
                    global_ready_at = NULL,
                    completed_at = NULL
                FROM graph_manifests graph_manifest
                WHERE request.candidate_global_generation = ?
                  AND request.state = 'GLOBAL_BUILDING'
                  AND graph_manifest.graph_generation =
                        request.candidate_graph_generation
                  AND EXISTS (
                      SELECT 1
                      FROM global_graph_manifests manifest
                      WHERE manifest.global_generation =
                            request.candidate_global_generation
                        AND manifest.status = 'FAILED'
                  )
                """,
                generation
        );
    }

    private static String configSelect() {
        return """
                SELECT version, report_model, report_revision,
                       prompt_version, schema_version,
                       community_algorithm,
                       community_algorithm_version,
                       community_seed, community_resolution,
                       index_config_version,
                       bm25_top_k, vector_top_k,
                       rrf_rank_constant, report_limit,
                       context_token_budget, map_call_limit,
                       model_call_limit, hard_timeout_ms,
                       statement_timeout_ms, reason, created_at
                FROM global_graph_configs
                """;
    }

    private static GlobalConfig config(ResultSet resultSet)
            throws SQLException {
        return new GlobalConfig(
                resultSet.getString("version"),
                resultSet.getString("report_model"),
                resultSet.getString("report_revision"),
                resultSet.getString("prompt_version"),
                resultSet.getString("schema_version"),
                resultSet.getString("community_algorithm"),
                resultSet.getString("community_algorithm_version"),
                resultSet.getLong("community_seed"),
                resultSet.getDouble("community_resolution"),
                resultSet.getString("index_config_version"),
                resultSet.getInt("bm25_top_k"),
                resultSet.getInt("vector_top_k"),
                resultSet.getInt("rrf_rank_constant"),
                resultSet.getInt("report_limit"),
                resultSet.getInt("context_token_budget"),
                resultSet.getInt("map_call_limit"),
                resultSet.getInt("model_call_limit"),
                resultSet.getInt("hard_timeout_ms"),
                resultSet.getInt("statement_timeout_ms"),
                resultSet.getString("reason"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static String manifestSelect() {
        return """
                SELECT id, global_generation, global_config_version,
                       source_graph_generation, index_name, status,
                       expected_source_count, report_count,
                       claim_count, evidence_count,
                       indexed_report_count, valid_vector_count,
                       model_call_count, build_attempt,
                       heartbeat_at, lease_expires_at,
                       failure_code, failure_reason, build_reason,
                       created_at, started_at, completed_at,
                       retention_until, updated_at
                FROM global_graph_manifests
                """;
    }

    private static ManifestRow manifest(ResultSet resultSet)
            throws SQLException {
        return new ManifestRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("global_generation"),
                resultSet.getString("global_config_version"),
                resultSet.getLong("source_graph_generation"),
                resultSet.getString("index_name"),
                resultSet.getString("status"),
                resultSet.getLong("expected_source_count"),
                resultSet.getLong("report_count"),
                resultSet.getLong("claim_count"),
                resultSet.getLong("evidence_count"),
                resultSet.getLong("indexed_report_count"),
                resultSet.getLong("valid_vector_count"),
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

    private static EvidenceAnchor evidence(ResultSet resultSet)
            throws SQLException {
        return new EvidenceAnchor(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("relationship_id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getObject("revision_id", UUID.class),
                resultSet.getLong("acl_version"),
                resultSet.getString("document_title"),
                resultSet.getInt("revision_number"),
                resultSet.getObject("child_chunk_id", UUID.class),
                resultSet.getObject("source_span_id", UUID.class),
                resultSet.getString("evidence_text"),
                resultSet.getString("evidence_text_hash"),
                resultSet.getObject("start_page", Integer.class),
                resultSet.getObject("end_page", Integer.class)
        );
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static ApiException invalidRelease() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "GLOBAL_RELEASE_TARGET_INVALID",
                "发布目标必须为 READY，回滚目标必须为 RETIRED"
        );
    }

    private static GlobalReportException leaseLost() {
        return new GlobalReportException(
                "GLOBAL_BUILD_LEASE_LOST",
                "Global Generation 构建租约已失效"
        );
    }

    private static String concise(String value) {
        String result = blank(value)
                ? "Global report build failed"
                : value.trim();
        return result.substring(0, Math.min(result.length(), 1000));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record SourceReference(
            UUID documentId,
            UUID revisionId,
            long aclVersion,
            String title
    ) {
    }

    private record SourceSnapshot(
            long sourceGraphGeneration,
            String sourceSetHash
    ) {
    }
}
