package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.document.SourceLocatorResponse;
import com.example.rag.governance.GovernanceEventService;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateDetail;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateEventView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateEvidenceView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateNeighborView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidatePage;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateSignalView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateSnapshotView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateSummary;
import com.example.rag.graph.GraphApiContracts.RefreshResolutionCandidatesRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionEntityView;
import com.example.rag.graph.GraphApiContracts.UpdateResolutionCandidateRequest;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GraphResolutionCandidateService {

    static final String ALGORITHM_VERSION = "phase21b-deterministic-v1";
    private static final String REFRESH_ACTION = "GRAPH_CANDIDATES_REFRESH";
    private static final int MAX_ENTITIES = 5_000;
    private static final int MAX_CANDIDATES_PER_TYPE = 500;
    private static final int MAX_EVIDENCE = 8;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final GraphGenerationRepository generations;
    private final GovernanceEventService governance;

    GraphResolutionCandidateService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            GraphGenerationRepository generations,
            GovernanceEventService governance
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.generations = generations;
        this.governance = governance;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    GraphResolutionCandidateSnapshotView refresh(
            RefreshResolutionCandidatesRequest request,
            PlatformUserPrincipal actor
    ) {
        String reason = GovernanceEventService.normalizeReason(request.reason());
        String idempotencyKey = required(request.idempotencyKey(), "idempotencyKey");
        String requestHash = governance.requestHash(
                request.graphGeneration() + "|" + ALGORITHM_VERSION + "|" + reason
        );
        governance.lockIdempotency(actor, REFRESH_ACTION, idempotencyKey);
        String existingHash = governance.existingRequestHash(
                actor, REFRESH_ACTION, idempotencyKey
        );
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw conflict(
                        "GRAPH_RESOLUTION_CANDIDATE_IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的候选刷新请求"
                );
            }
            return view(snapshot(UUID.fromString(governance.existingObjectId(
                    actor, REFRESH_ACTION, idempotencyKey
            ))));
        }

        jdbc.execute("SET LOCAL statement_timeout = '8s'");
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null,
                "graph-resolution-candidates:" + request.graphGeneration()
        );
        reconcileStaleSnapshots();
        Manifest manifest = manifest(request.graphGeneration(), true);
        requireRefreshable(manifest);

        InputModel input = loadInput(manifest.generation());
        if (input.entities().size() > MAX_ENTITIES) {
            throw conflict(
                    "GRAPH_RESOLUTION_CANDIDATE_ENTITY_LIMIT",
                    "当前 Generation 实体数量超过首版候选发现上限"
            );
        }
        String inputHash = inputHash(manifest, input);
        Optional<SnapshotRow> existing = findSnapshot(
                manifest.generation(), inputHash
        );
        SnapshotRow row;
        if (existing.isPresent()) {
            row = existing.get();
        } else {
            List<CandidateDraft> candidates = detect(input);
            row = insertSnapshot(manifest, inputHash, candidates, actor, reason);
        }
        governance.append(
                "GRAPH",
                REFRESH_ACTION,
                actor,
                "GRAPH_CANDIDATE_SNAPSHOT",
                row.id().toString(),
                "G" + row.generation() + " · " + row.algorithmVersion(),
                Map.of(),
                Map.of(
                        "generation", row.generation(),
                        "algorithmVersion", row.algorithmVersion(),
                        "inputHash", row.inputHash(),
                        "duplicateCandidates", row.duplicateCount(),
                        "splitCandidates", row.splitCount(),
                        "reused", existing.isPresent()
                ),
                reason,
                idempotencyKey,
                requestHash
        );
        return view(row);
    }

    @Transactional
    GraphResolutionCandidatePage candidates(
            long generation,
            String candidateType,
            String status,
            String signal,
            String entityQuery,
            String cursor,
            int limit
    ) {
        reconcileStaleSnapshots();
        SnapshotRow snapshot = latestSnapshot(generation).orElse(null);
        if (snapshot == null) {
            return new GraphResolutionCandidatePage(null, null, List.of());
        }
        int safeLimit = Math.clamp(limit, 1, 50);
        CandidateCursor decoded = decodeCursor(cursor);
        List<GraphResolutionCandidateSummary> filtered = loadSummaries(snapshot)
                .stream()
                .filter(item -> candidateType == null || candidateType.isBlank()
                        || item.candidateType().equals(candidateType))
                .filter(item -> status == null || status.isBlank()
                        || item.status().equals(status))
                .filter(item -> signal == null || signal.isBlank()
                        || item.signals().stream().anyMatch(value ->
                        value.code().equals(signal)))
                .filter(item -> matchesEntity(item, entityQuery))
                .filter(item -> decoded == null
                        || item.stableRank() > decoded.rank()
                        || (item.stableRank() == decoded.rank()
                        && item.id().compareTo(decoded.id()) > 0))
                .sorted(summaryOrder())
                .limit(safeLimit + 1L)
                .toList();
        String next = null;
        if (filtered.size() > safeLimit) {
            GraphResolutionCandidateSummary last = filtered.get(safeLimit - 1);
            next = encodeCursor(last.stableRank(), last.id());
            filtered = filtered.subList(0, safeLimit);
        }
        return new GraphResolutionCandidatePage(view(snapshot), next, filtered);
    }

    @Transactional
    GraphResolutionCandidateDetail detail(UUID candidateId) {
        reconcileStaleSnapshots();
        CandidateContext context = context(candidateId, false);
        GraphResolutionCandidateSummary summary = loadSummaries(context.snapshot())
                .stream()
                .filter(item -> item.id().equals(candidateId))
                .findFirst()
                .orElseThrow(GraphResolutionCandidateService::notFound);
        if ("STALE".equals(summary.status())) {
            return new GraphResolutionCandidateDetail(
                    summary, List.of(), List.of(), events(candidateId)
            );
        }
        return new GraphResolutionCandidateDetail(
                summary,
                evidence(candidateId, context.snapshot().generation()),
                neighbors(candidateId, context.snapshot().generation()),
                events(candidateId)
        );
    }

    @Transactional
    GraphResolutionCandidateSummary changeState(
            UUID candidateId,
            String operation,
            UpdateResolutionCandidateRequest request,
            PlatformUserPrincipal actor
    ) {
        String nextStatus = switch (operation) {
            case "IGNORE" -> "IGNORED";
            case "RESTORE" -> "ACTIVE";
            default -> throw new IllegalArgumentException("Unsupported candidate operation");
        };
        String expectedConfirmation = operation + "_RESOLUTION_CANDIDATE";
        if (!expectedConfirmation.equals(request.confirmation())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GRAPH_RESOLUTION_CANDIDATE_CONFIRMATION_INVALID",
                    "确认字段不正确"
            );
        }
        String action = "GRAPH_CANDIDATE_" + operation;
        String reason = GovernanceEventService.normalizeReason(request.reason());
        String idempotencyKey = required(request.idempotencyKey(), "idempotencyKey");
        String requestHash = governance.requestHash(
                candidateId + "|" + request.expectedVersion() + "|"
                        + nextStatus + "|" + reason
        );
        governance.lockIdempotency(actor, action, idempotencyKey);
        String existingHash = governance.existingRequestHash(
                actor, action, idempotencyKey
        );
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw conflict(
                        "GRAPH_RESOLUTION_CANDIDATE_IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的候选操作"
                );
            }
            CandidateContext replay = context(candidateId, false);
            return summary(candidateId, replay.snapshot());
        }

        reconcileStaleSnapshots();
        CandidateContext context = context(candidateId, true);
        if (!"READY".equals(context.snapshot().status())) {
            throw conflict(
                    "GRAPH_RESOLUTION_CANDIDATE_STALE",
                    "候选事实已过期，请刷新候选后重新核对"
            );
        }
        StateRow state = state(candidateId, true);
        if (state.version() != request.expectedVersion()) {
            throw conflict(
                    "GRAPH_RESOLUTION_CANDIDATE_VERSION_CONFLICT",
                    "候选状态已变化，请刷新后重试"
            );
        }
        if (state.status().equals(nextStatus)) {
            throw conflict(
                    "GRAPH_RESOLUTION_CANDIDATE_STATE_CONFLICT",
                    "候选已经处于目标状态"
            );
        }
        int updated = jdbc.update(
                """
                UPDATE graph_resolution_candidate_states
                   SET status = ?, version = version + 1,
                       updated_by = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE candidate_id = ? AND version = ? AND status = ?
                """,
                nextStatus, actor.id(), candidateId,
                state.version(), state.status()
        );
        if (updated != 1) {
            throw conflict(
                    "GRAPH_RESOLUTION_CANDIDATE_VERSION_CONFLICT",
                    "候选状态已变化，请刷新后重试"
            );
        }
        int nextVersion = state.version() + 1;
        jdbc.update(
                """
                INSERT INTO graph_resolution_candidate_events (
                    candidate_id, event_type, actor_user_id,
                    previous_status, next_status, version, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                candidateId, operation + "D", actor.id(), state.status(),
                nextStatus, nextVersion, reason
        );
        governance.append(
                "GRAPH",
                action,
                actor,
                "GRAPH_RESOLUTION_CANDIDATE",
                candidateId.toString(),
                candidateId.toString(),
                Map.of("status", state.status(), "version", state.version()),
                Map.of("status", nextStatus, "version", nextVersion),
                reason,
                idempotencyKey,
                requestHash
        );
        return summary(candidateId, context.snapshot());
    }

    private void reconcileStaleSnapshots() {
        String currentHash = generations.currentSourceSetHash();
        jdbc.update(
                """
                UPDATE graph_resolution_candidate_snapshots snapshot
                   SET status = 'STALE', stale_at = CURRENT_TIMESTAMP,
                       stale_reason = CASE
                         WHEN manifest.status NOT IN ('ACTIVE', 'READY')
                           THEN 'GRAPH_GENERATION_NO_LONGER_BROWSABLE'
                         WHEN snapshot.source_set_hash <> ?
                           THEN 'GRAPH_SOURCE_SET_CHANGED'
                         ELSE 'GRAPH_PROJECTION_STALE'
                       END
                  FROM graph_manifests manifest
                 WHERE manifest.graph_generation = snapshot.graph_generation
                   AND snapshot.status = 'READY'
                   AND (
                     manifest.status NOT IN ('ACTIVE', 'READY')
                     OR snapshot.source_set_hash <> ?
                     OR EXISTS (
                       SELECT 1
                       FROM documents document
                       JOIN document_revisions revision
                         ON revision.id = document.current_revision_id
                        AND revision.document_id = document.id
                        AND revision.status = 'READY'
                       LEFT JOIN graph_projection_states projection
                         ON projection.graph_generation = snapshot.graph_generation
                        AND projection.document_id = document.id
                        AND projection.revision_id = document.current_revision_id
                        AND projection.acl_version = document.acl_version
                        AND projection.state = 'PROJECTED'
                       WHERE document.deleted_at IS NULL
                         AND projection.document_id IS NULL
                     )
                   )
                """,
                currentHash, currentHash
        );
    }

    private Manifest manifest(long generation, boolean lock) {
        String suffix = lock ? " FOR SHARE" : "";
        return jdbc.query(
                """
                SELECT graph_generation, graph_config_version, status,
                       source_set_hash
                FROM graph_manifests
                WHERE graph_generation = ?
                """ + suffix,
                (resultSet, rowNumber) -> new Manifest(
                        resultSet.getLong("graph_generation"),
                        resultSet.getString("graph_config_version"),
                        resultSet.getString("status"),
                        resultSet.getString("source_set_hash")
                ),
                generation
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_GENERATION_NOT_FOUND",
                "找不到 Graph Generation " + generation
        ));
    }

    private void requireRefreshable(Manifest manifest) {
        if (!Set.of("ACTIVE", "READY").contains(manifest.status())) {
            throw conflict(
                    "GRAPH_RESOLUTION_CANDIDATE_GENERATION_INVALID",
                    "只有 ACTIVE 或 READY Generation 可以刷新候选"
            );
        }
        String currentHash = generations.currentSourceSetHash();
        if (!manifest.sourceSetHash().equals(currentHash)) {
            throw conflict(
                    "GRAPH_RESOLUTION_CANDIDATE_GENERATION_STALE",
                    "当前 Revision 或 ACL 已变化，请先重建 Graph Generation"
            );
        }
        Integer missing = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM documents document
                JOIN document_revisions revision
                  ON revision.id = document.current_revision_id
                 AND revision.document_id = document.id
                 AND revision.status = 'READY'
                LEFT JOIN graph_projection_states projection
                  ON projection.graph_generation = ?
                 AND projection.document_id = document.id
                 AND projection.revision_id = document.current_revision_id
                 AND projection.acl_version = document.acl_version
                 AND projection.state = 'PROJECTED'
                WHERE document.deleted_at IS NULL
                  AND projection.document_id IS NULL
                """,
                Integer.class,
                manifest.generation()
        );
        if (missing == null || missing > 0) {
            throw conflict(
                    "GRAPH_RESOLUTION_CANDIDATE_PROJECTION_STALE",
                    "Graph Projection 尚未追平，不能生成治理候选"
            );
        }
    }

    private InputModel loadInput(long generation) {
        Map<UUID, EntityProfile> profiles = new TreeMap<>();
        jdbc.query(
                """
                SELECT entity.id, entity.canonical_name,
                       entity.normalized_name, entity.entity_type
                FROM graph_entities entity
                WHERE entity.graph_generation = ?
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
                ORDER BY entity.id
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        UUID id = resultSet.getObject("id", UUID.class);
                        profiles.put(id, new EntityProfile(
                                id,
                                resultSet.getString("canonical_name"),
                                resultSet.getString("normalized_name"),
                                resultSet.getString("entity_type"),
                                new TreeMap<>(),
                                new ArrayList<>(),
                                new TreeMap<>(),
                                new ArrayList<>()
                        ));
                    }
                    return null;
                },
                generation
        );
        if (profiles.isEmpty()) {
            return new InputModel(profiles, List.of());
        }

        jdbc.query(
                """
                SELECT alias.entity_id, alias.normalized_alias, min(alias.alias) AS alias
                FROM graph_entity_aliases alias
                JOIN graph_entity_alias_evidence alias_evidence
                  ON alias_evidence.graph_generation = alias.graph_generation
                 AND alias_evidence.entity_id = alias.entity_id
                 AND alias_evidence.normalized_alias = alias.normalized_alias
                JOIN graph_entity_mentions mention
                  ON mention.id = alias_evidence.mention_id
                 AND mention.graph_generation = alias.graph_generation
                JOIN documents document
                  ON document.id = mention.document_id
                 AND document.current_revision_id = mention.revision_id
                 AND document.deleted_at IS NULL
                JOIN graph_projection_states projection
                  ON projection.graph_generation = mention.graph_generation
                 AND projection.document_id = mention.document_id
                 AND projection.revision_id = mention.revision_id
                 AND projection.acl_version = document.acl_version
                 AND projection.state = 'PROJECTED'
                WHERE alias.graph_generation = ?
                GROUP BY alias.entity_id, alias.normalized_alias
                ORDER BY alias.entity_id, alias.normalized_alias
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        EntityProfile profile = profiles.get(
                                resultSet.getObject("entity_id", UUID.class)
                        );
                        if (profile != null) {
                            profile.aliases().put(
                                    resultSet.getString("normalized_alias"),
                                    resultSet.getString("alias")
                            );
                        }
                    }
                    return null;
                },
                generation
        );

        jdbc.query(
                """
                SELECT mention.id, mention.entity_id, mention.document_id,
                       mention.revision_id, mention.child_chunk_id,
                       mention.source_span_id, mention.surface_text
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
                WHERE mention.graph_generation = ?
                ORDER BY mention.entity_id, mention.document_id,
                         mention.child_chunk_id, mention.id
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        UUID entityId = resultSet.getObject("entity_id", UUID.class);
                        EntityProfile profile = profiles.get(entityId);
                        if (profile != null) {
                            profile.mentions().add(new EvidenceAnchor(
                                    "MENTION",
                                    resultSet.getObject("id", UUID.class),
                                    entityId,
                                    resultSet.getObject("document_id", UUID.class),
                                    resultSet.getObject("revision_id", UUID.class),
                                    resultSet.getObject("child_chunk_id", UUID.class),
                                    resultSet.getObject("source_span_id", UUID.class),
                                    resultSet.getString("surface_text")
                            ));
                        }
                    }
                    return null;
                },
                generation
        );

        List<RelationshipAnchor> relationships = jdbc.query(
                """
                SELECT evidence.id, relationship.id AS relationship_id,
                       relationship.source_entity_id,
                       relationship.target_entity_id,
                       evidence.document_id, evidence.revision_id,
                       evidence.child_chunk_id, evidence.source_span_id,
                       evidence.evidence_text
                FROM graph_relationships relationship
                JOIN graph_relationship_evidence evidence
                  ON evidence.graph_generation = relationship.graph_generation
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
                WHERE relationship.graph_generation = ?
                ORDER BY relationship.id, evidence.document_id, evidence.id
                """,
                (resultSet, rowNumber) -> new RelationshipAnchor(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("relationship_id", UUID.class),
                        resultSet.getObject("source_entity_id", UUID.class),
                        resultSet.getObject("target_entity_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getObject("revision_id", UUID.class),
                        resultSet.getObject("child_chunk_id", UUID.class),
                        resultSet.getObject("source_span_id", UUID.class),
                        resultSet.getString("evidence_text")
                ),
                generation
        );
        for (RelationshipAnchor relationship : relationships) {
            EntityProfile source = profiles.get(relationship.sourceEntityId());
            EntityProfile target = profiles.get(relationship.targetEntityId());
            if (source == null || target == null) continue;
            addNeighbor(source, target.id(), relationship);
            addNeighbor(target, source.id(), relationship);
            source.relationshipEvidence().add(relationship);
            target.relationshipEvidence().add(relationship);
        }
        return new InputModel(profiles, relationships);
    }

    private static void addNeighbor(
            EntityProfile profile,
            UUID neighborId,
            RelationshipAnchor relationship
    ) {
        NeighborStats stats = profile.neighbors().computeIfAbsent(
                neighborId, ignored -> new NeighborStats(new TreeSet<>(), 0)
        );
        stats.documents().add(relationship.documentId());
        stats.increment();
    }

    private List<CandidateDraft> detect(InputModel input) {
        List<CandidateDraft> duplicates = duplicateCandidates(input);
        List<CandidateDraft> splits = splitCandidates(input);
        rank(duplicates);
        rank(splits);
        return java.util.stream.Stream.concat(
                duplicates.stream(), splits.stream()
        ).toList();
    }

    private List<CandidateDraft> duplicateCandidates(InputModel input) {
        List<EntityProfile> entities = new ArrayList<>(input.entities().values());
        Set<Pair> pairs = new TreeSet<>();
        groupedPairs(entities, EntityProfile::normalizedName, pairs);
        Map<String, List<EntityProfile>> aliases = new TreeMap<>();
        for (EntityProfile entity : entities) {
            for (String alias : entity.aliases().keySet()) {
                aliases.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(entity);
            }
        }
        aliases.values().forEach(group -> addPairs(group, pairs));
        for (int leftIndex = 0; leftIndex < entities.size(); leftIndex++) {
            EntityProfile left = entities.get(leftIndex);
            String leftName = compact(left.normalizedName());
            if (leftName.length() < 5) continue;
            for (int rightIndex = leftIndex + 1; rightIndex < entities.size(); rightIndex++) {
                EntityProfile right = entities.get(rightIndex);
                String rightName = compact(right.normalizedName());
                if (rightName.length() < 5
                        || leftName.charAt(0) != rightName.charAt(0)
                        || Math.abs(leftName.length() - rightName.length()) > 3) {
                    continue;
                }
                if (similarity(leftName, rightName) >= 0.90d) {
                    pairs.add(new Pair(left.id(), right.id()));
                }
            }
        }

        List<CandidateDraft> result = new ArrayList<>();
        for (Pair pair : pairs) {
            EntityProfile left = input.entities().get(pair.left());
            EntityProfile right = input.entities().get(pair.right());
            List<Signal> signals = new ArrayList<>();
            if (left.normalizedName().equals(right.normalizedName())) {
                signals.add(new Signal(
                        "NORMALIZED_NAME_EQUAL", "HARD",
                        "两个实体的规范化名称完全相同", 1d
                ));
            }
            Set<String> sharedAliases = intersection(
                    left.aliases().keySet(), right.aliases().keySet()
            );
            if (!sharedAliases.isEmpty()) {
                signals.add(new Signal(
                        "ALIAS_OVERLAP", "HARD",
                        "两个实体共享 " + sharedAliases.size() + " 个有 Evidence 的 Alias",
                        (double) sharedAliases.size()
                ));
            }
            double stringSimilarity = similarity(
                    compact(left.normalizedName()), compact(right.normalizedName())
            );
            if (stringSimilarity >= 0.90d
                    && !left.normalizedName().equals(right.normalizedName())) {
                signals.add(new Signal(
                        "STRING_SIMILARITY", "HARD",
                        "规范化名称具有较高的可解释字符串相似度",
                        stringSimilarity
                ));
            }
            if (signals.stream().noneMatch(signal -> "HARD".equals(signal.strength()))) {
                continue;
            }
            signals.add(new Signal(
                    left.entityType().equals(right.entityType())
                            ? "ENTITY_TYPE_COMPATIBLE" : "ENTITY_TYPE_CONFLICT",
                    left.entityType().equals(right.entityType())
                            ? "SUPPORTING" : "WARNING",
                    left.entityType().equals(right.entityType())
                            ? "两个实体类型一致"
                            : "两个实体类型不同，需要人工确认是否为同名歧义",
                    null
            ));
            int commonDocuments = intersection(
                    documents(left.mentions()), documents(right.mentions())
            ).size();
            if (commonDocuments > 0) {
                signals.add(new Signal(
                        "MENTION_CO_OCCURRENCE", "SUPPORTING",
                        "两个实体在 " + commonDocuments + " 个当前文档中共同出现",
                        (double) commonDocuments
                ));
            }
            Set<UUID> sharedNeighbors = intersection(
                    left.neighbors().keySet(), right.neighbors().keySet()
            );
            if (!sharedNeighbors.isEmpty()) {
                int union = unionSize(
                        left.neighbors().keySet(), right.neighbors().keySet()
                );
                signals.add(new Signal(
                        "NEIGHBOR_OVERLAP", "SUPPORTING",
                        "两个实体共享 " + sharedNeighbors.size() + " 个当前关系邻居",
                        union == 0 ? 0d : (double) sharedNeighbors.size() / union
                ));
            }
            List<EvidenceAnchor> evidence = evidenceFor(left, right);
            if (evidence.isEmpty()) continue;
            EntityProfile target = preferred(left, right);
            result.add(new CandidateDraft(
                    "SUSPECTED_DUPLICATE", "MERGE",
                    List.of(left.id(), right.id()),
                    target.canonicalName(), target.entityType(), List.of(),
                    signals, evidence,
                    candidateKey("SUSPECTED_DUPLICATE", List.of(left.id(), right.id()), List.of())
            ));
        }
        return result.stream()
                .sorted(draftOrder())
                .limit(MAX_CANDIDATES_PER_TYPE)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<CandidateDraft> splitCandidates(InputModel input) {
        Map<String, Set<UUID>> aliasOwners = new TreeMap<>();
        Map<String, Set<String>> canonicalTypes = new TreeMap<>();
        for (EntityProfile profile : input.entities().values()) {
            canonicalTypes.computeIfAbsent(
                    profile.normalizedName(), ignored -> new TreeSet<>()
            ).add(profile.entityType());
            for (String alias : profile.aliases().keySet()) {
                aliasOwners.computeIfAbsent(alias, ignored -> new TreeSet<>())
                        .add(profile.id());
            }
        }

        List<CandidateDraft> result = new ArrayList<>();
        for (EntityProfile profile : input.entities().values()) {
            Map<String, List<EvidenceAnchor>> groups = profile.mentions().stream()
                    .collect(Collectors.groupingBy(
                            anchor -> normalize(anchor.excerpt()),
                            TreeMap::new,
                            Collectors.toList()
                    ));
            List<Map.Entry<String, List<EvidenceAnchor>>> ordered = groups.entrySet()
                    .stream()
                    .sorted(Comparator
                            .<Map.Entry<String, List<EvidenceAnchor>>>comparingInt(
                                    entry -> entry.getValue().size()
                            ).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .toList();
            ClusterPair separated = separatedClusters(ordered);
            if (separated == null) continue;

            List<String> suggestedAliases = ordered.stream()
                    .map(Map.Entry::getKey)
                    .filter(value -> !value.equals(profile.normalizedName()))
                    .filter(profile.aliases()::containsKey)
                    .map(profile.aliases()::get)
                    .distinct()
                    .limit(20)
                    .toList();
            if (suggestedAliases.isEmpty()) continue;

            boolean ambiguous = profile.aliases().keySet().stream()
                    .anyMatch(alias -> aliasOwners.getOrDefault(alias, Set.of()).size() > 1);
            boolean typeConflict = profile.aliases().keySet().stream().anyMatch(alias ->
                    canonicalTypes.getOrDefault(alias, Set.of()).stream()
                            .anyMatch(type -> !type.equals(profile.entityType()))
            );
            boolean neighborSeparated = neighborSourcesSeparated(profile);
            if (!ambiguous && !typeConflict && !neighborSeparated) continue;

            List<Signal> signals = new ArrayList<>();
            signals.add(new Signal(
                    "LOW_OVERLAP_SOURCE_CLUSTERS", "HARD",
                    "该实体的 Mention 形成至少两个来源不重叠的表述簇",
                    0d
            ));
            if (ambiguous) {
                signals.add(new Signal(
                        "AMBIGUOUS_PROVENANCE", "HARD",
                        "部分 Alias 同时由其他实体的当前 Mention 支撑",
                        null
                ));
            }
            if (typeConflict) {
                signals.add(new Signal(
                        "TYPE_HINT_CONFLICT", "HARD",
                        "部分 Alias 与不同实体类型的规范化名称冲突",
                        null
                ));
            }
            if (neighborSeparated) {
                signals.add(new Signal(
                        "NEIGHBOR_SOURCE_SEPARATION", "SUPPORTING",
                        "关系邻居由明显分离的文档来源支撑",
                        null
                ));
            }
            List<EvidenceAnchor> evidence = new ArrayList<>();
            evidence.addAll(separated.left().getValue().stream().limit(3).toList());
            evidence.addAll(separated.right().getValue().stream().limit(3).toList());
            evidence = deduplicateEvidence(evidence).stream()
                    .limit(MAX_EVIDENCE).toList();
            if (evidence.isEmpty()) continue;
            String targetAlias = suggestedAliases.getFirst();
            result.add(new CandidateDraft(
                    "SUSPECTED_MERGE", "SPLIT", List.of(profile.id()),
                    targetAlias, profile.entityType(), suggestedAliases,
                    signals, evidence,
                    candidateKey("SUSPECTED_MERGE", List.of(profile.id()), suggestedAliases)
            ));
        }
        return result.stream()
                .sorted(draftOrder())
                .limit(MAX_CANDIDATES_PER_TYPE)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private SnapshotRow insertSnapshot(
            Manifest manifest,
            String inputHash,
            List<CandidateDraft> candidates,
            PlatformUserPrincipal actor,
            String reason
    ) {
        UUID snapshotId = deterministicUuid(
                "snapshot|" + manifest.generation() + "|"
                        + ALGORITHM_VERSION + "|" + inputHash
        );
        int duplicateCount = (int) candidates.stream().filter(candidate ->
                "SUSPECTED_DUPLICATE".equals(candidate.type())).count();
        int splitCount = candidates.size() - duplicateCount;
        jdbc.update(
                """
                INSERT INTO graph_resolution_candidate_snapshots (
                    id, graph_generation, graph_config_version,
                    source_set_hash, algorithm_version, input_hash,
                    status, duplicate_candidate_count,
                    split_candidate_count, created_by, reason
                ) VALUES (?, ?, ?, ?, ?, ?, 'READY', ?, ?, ?, ?)
                """,
                snapshotId, manifest.generation(), manifest.configVersion(),
                manifest.sourceSetHash(), ALGORITHM_VERSION, inputHash,
                duplicateCount, splitCount, actor.id(), reason
        );
        for (CandidateDraft candidate : candidates) {
            UUID candidateId = deterministicUuid(
                    snapshotId + "|" + candidate.key()
            );
            jdbc.update(
                    """
                    INSERT INTO graph_resolution_candidates (
                        id, snapshot_id, candidate_key, candidate_type,
                        suggested_action, suggested_target_name,
                        suggested_target_type, suggested_aliases,
                        hard_signal_count, signal_count, evidence_count,
                        source_document_count, stable_rank
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                              ?, ?, ?, ?, ?)
                    """,
                    candidateId, snapshotId, candidate.key(), candidate.type(),
                    candidate.action(), candidate.targetName(), candidate.targetType(),
                    json(candidate.suggestedAliases()), candidate.hardSignals(),
                    candidate.signals().size(), candidate.evidence().size(),
                    documents(candidate.evidence()).size(), candidate.rank()
            );
            for (int index = 0; index < candidate.entityIds().size(); index++) {
                jdbc.update(
                        """
                        INSERT INTO graph_resolution_candidate_entities (
                            candidate_id, graph_generation, entity_id, entity_order
                        ) VALUES (?, ?, ?, ?)
                        """,
                        candidateId, manifest.generation(),
                        candidate.entityIds().get(index), index
                );
            }
            for (int index = 0; index < candidate.signals().size(); index++) {
                Signal signal = candidate.signals().get(index);
                jdbc.update(
                        """
                        INSERT INTO graph_resolution_candidate_signals (
                            candidate_id, signal_order, signal_code, strength,
                            explanation, numeric_value, details
                        ) VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb)
                        """,
                        candidateId, index, signal.code(), signal.strength(),
                        signal.explanation(), signal.numericValue()
                );
            }
            for (int index = 0; index < candidate.evidence().size(); index++) {
                EvidenceAnchor evidence = candidate.evidence().get(index);
                jdbc.update(
                        """
                        INSERT INTO graph_resolution_candidate_evidence (
                            candidate_id, evidence_order, graph_generation,
                            entity_id, anchor_type, anchor_id, document_id,
                            revision_id, child_chunk_id, source_span_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        candidateId, index, manifest.generation(),
                        evidence.entityId(), evidence.anchorType(), evidence.anchorId(),
                        evidence.documentId(), evidence.revisionId(),
                        evidence.childChunkId(), evidence.sourceSpanId()
                );
            }
            jdbc.update(
                    "INSERT INTO graph_resolution_candidate_states (candidate_id) VALUES (?)",
                    candidateId
            );
        }
        return snapshot(snapshotId);
    }

    private List<GraphResolutionCandidateSummary> loadSummaries(SnapshotRow snapshot) {
        List<BaseCandidate> bases = jdbc.query(
                """
                SELECT candidate.id, candidate.candidate_type,
                       candidate.suggested_action,
                       candidate.suggested_target_name,
                       candidate.suggested_target_type,
                       candidate.suggested_aliases::text,
                       candidate.evidence_count,
                       candidate.source_document_count,
                       candidate.stable_rank, candidate.created_at,
                       state.status, state.version, state.updated_at
                FROM graph_resolution_candidates candidate
                JOIN graph_resolution_candidate_states state
                  ON state.candidate_id = candidate.id
                WHERE candidate.snapshot_id = ?
                ORDER BY candidate.stable_rank, candidate.id
                """,
                (resultSet, rowNumber) -> new BaseCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("candidate_type"),
                        resultSet.getString("suggested_action"),
                        resultSet.getString("suggested_target_name"),
                        resultSet.getString("suggested_target_type"),
                        strings(resultSet.getString("suggested_aliases")),
                        resultSet.getInt("evidence_count"),
                        resultSet.getInt("source_document_count"),
                        resultSet.getInt("stable_rank"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getString("status"),
                        resultSet.getInt("version"),
                        resultSet.getTimestamp("updated_at").toInstant()
                ),
                snapshot.id()
        );
        if (bases.isEmpty()) return List.of();
        List<UUID> ids = bases.stream().map(BaseCandidate::id).toList();
        Map<UUID, List<GraphResolutionCandidateSignalView>> signals = signals(ids);
        boolean stale = !"READY".equals(snapshot.status());
        Map<UUID, List<ResolutionEntityView>> entities = stale
                ? Map.of() : entityViews(ids, snapshot.generation());
        return bases.stream().map(base -> new GraphResolutionCandidateSummary(
                base.id(), base.type(), base.action(),
                stale ? "STALE" : base.status(), base.version(),
                stale ? List.of() : entities.getOrDefault(base.id(), List.of()),
                stale ? null : base.targetName(),
                stale ? null : base.targetType(),
                stale ? List.of() : base.suggestedAliases(),
                stale ? signals.getOrDefault(base.id(), List.of()).stream()
                        .map(signal -> new GraphResolutionCandidateSignalView(
                                signal.code(), signal.strength(),
                                "候选事实已过期，请刷新后重新核对", null
                        )).toList()
                        : signals.getOrDefault(base.id(), List.of()),
                stale ? 0 : base.evidenceCount(),
                stale ? 0 : base.sourceDocumentCount(),
                base.rank(), base.createdAt(), base.updatedAt()
        )).toList();
    }

    private Map<UUID, List<ResolutionEntityView>> entityViews(
            List<UUID> candidateIds,
            long generation
    ) {
        String placeholders = placeholders(candidateIds.size());
        List<EntityCandidateRow> rows = jdbc.query(
                """
                WITH valid_mentions AS (
                    SELECT mention.*
                    FROM graph_entity_mentions mention
                    JOIN documents document
                      ON document.id = mention.document_id
                     AND document.current_revision_id = mention.revision_id
                     AND document.deleted_at IS NULL
                    JOIN graph_projection_states projection
                      ON projection.graph_generation = mention.graph_generation
                     AND projection.document_id = mention.document_id
                     AND projection.revision_id = mention.revision_id
                     AND projection.acl_version = document.acl_version
                     AND projection.state = 'PROJECTED'
                    WHERE mention.graph_generation = ?
                )
                SELECT selected.candidate_id, selected.entity_order,
                       entity.id, entity.canonical_name, entity.entity_type,
                       COALESCE((
                         SELECT array_agg(DISTINCT alias.alias ORDER BY alias.alias)
                         FROM graph_entity_aliases alias
                         JOIN graph_entity_alias_evidence alias_evidence
                           ON alias_evidence.graph_generation = alias.graph_generation
                          AND alias_evidence.entity_id = alias.entity_id
                          AND alias_evidence.normalized_alias = alias.normalized_alias
                         JOIN valid_mentions mention
                           ON mention.id = alias_evidence.mention_id
                         WHERE alias.graph_generation = entity.graph_generation
                           AND alias.entity_id = entity.id
                       ), ARRAY[]::text[]) AS aliases,
                       (SELECT count(*) FROM valid_mentions mention
                         WHERE mention.entity_id = entity.id) AS mention_count,
                       (SELECT count(DISTINCT relationship.id)
                          FROM graph_relationships relationship
                          JOIN graph_relationship_evidence evidence
                            ON evidence.graph_generation = relationship.graph_generation
                           AND evidence.relationship_id = relationship.id
                          JOIN documents document
                            ON document.id = evidence.document_id
                           AND document.current_revision_id = evidence.revision_id
                           AND document.deleted_at IS NULL
                          JOIN graph_projection_states projection
                            ON projection.graph_generation = evidence.graph_generation
                           AND projection.document_id = evidence.document_id
                           AND projection.revision_id = evidence.revision_id
                           AND projection.acl_version = document.acl_version
                           AND projection.state = 'PROJECTED'
                         WHERE relationship.graph_generation = entity.graph_generation
                           AND (relationship.source_entity_id = entity.id
                             OR relationship.target_entity_id = entity.id)) AS relationship_count,
                       (SELECT count(*)
                          FROM graph_relationships relationship
                          JOIN graph_relationship_evidence evidence
                            ON evidence.graph_generation = relationship.graph_generation
                           AND evidence.relationship_id = relationship.id
                          JOIN documents document
                            ON document.id = evidence.document_id
                           AND document.current_revision_id = evidence.revision_id
                           AND document.deleted_at IS NULL
                          JOIN graph_projection_states projection
                            ON projection.graph_generation = evidence.graph_generation
                           AND projection.document_id = evidence.document_id
                           AND projection.revision_id = evidence.revision_id
                           AND projection.acl_version = document.acl_version
                           AND projection.state = 'PROJECTED'
                         WHERE relationship.graph_generation = entity.graph_generation
                           AND (relationship.source_entity_id = entity.id
                             OR relationship.target_entity_id = entity.id)) AS evidence_count
                FROM graph_resolution_candidate_entities selected
                JOIN graph_entities entity
                  ON entity.id = selected.entity_id
                 AND entity.graph_generation = selected.graph_generation
                WHERE selected.candidate_id IN (%s)
                ORDER BY selected.candidate_id, selected.entity_order
                """.formatted(placeholders),
                (resultSet, rowNumber) -> new EntityCandidateRow(
                        resultSet.getObject("candidate_id", UUID.class),
                        new ResolutionEntityView(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("canonical_name"),
                                resultSet.getString("entity_type"),
                                textArray(resultSet.getArray("aliases")),
                                resultSet.getInt("mention_count"),
                                resultSet.getInt("relationship_count"),
                                resultSet.getInt("evidence_count")
                        )
                ),
                prepend(generation, candidateIds)
        );
        return rows.stream().collect(Collectors.groupingBy(
                EntityCandidateRow::candidateId,
                LinkedHashMap::new,
                Collectors.mapping(EntityCandidateRow::view, Collectors.toList())
        ));
    }

    private Map<UUID, List<GraphResolutionCandidateSignalView>> signals(
            List<UUID> candidateIds
    ) {
        String placeholders = placeholders(candidateIds.size());
        List<SignalRow> rows = jdbc.query(
                """
                SELECT candidate_id, signal_code, strength, explanation,
                       numeric_value
                FROM graph_resolution_candidate_signals
                WHERE candidate_id IN (%s)
                ORDER BY candidate_id, signal_order
                """.formatted(placeholders),
                (resultSet, rowNumber) -> new SignalRow(
                        resultSet.getObject("candidate_id", UUID.class),
                        new GraphResolutionCandidateSignalView(
                                resultSet.getString("signal_code"),
                                resultSet.getString("strength"),
                                resultSet.getString("explanation"),
                                resultSet.getObject("numeric_value") == null
                                        ? null : resultSet.getDouble("numeric_value")
                        )
                ),
                candidateIds.toArray()
        );
        return rows.stream().collect(Collectors.groupingBy(
                SignalRow::candidateId,
                LinkedHashMap::new,
                Collectors.mapping(SignalRow::view, Collectors.toList())
        ));
    }

    private List<GraphResolutionCandidateEvidenceView> evidence(
            UUID candidateId,
            long generation
    ) {
        return jdbc.query(
                """
                SELECT anchor.anchor_type, anchor.anchor_id, anchor.entity_id,
                       entity.canonical_name AS entity_name,
                       anchor.document_id, document.title AS document_title,
                       anchor.revision_id, revision.revision_number,
                       anchor.child_chunk_id, anchor.source_span_id,
                       CASE WHEN anchor.anchor_type = 'MENTION'
                         THEN mention.surface_text ELSE evidence.evidence_text END AS excerpt,
                       revision.document_format,
                       location.locator_kind, location.start_source_unit_id,
                       location.end_source_unit_id, location.start_offset,
                       location.end_offset, location.address::text AS locator_address,
                       location.source_text_hash, location.normalization_version,
                       location.start_page, location.end_page, location.source_label
                FROM graph_resolution_candidate_evidence anchor
                JOIN graph_entities entity
                  ON entity.id = anchor.entity_id
                 AND entity.graph_generation = anchor.graph_generation
                JOIN documents document
                  ON document.id = anchor.document_id
                 AND document.current_revision_id = anchor.revision_id
                 AND document.deleted_at IS NULL
                JOIN document_revisions revision
                  ON revision.id = anchor.revision_id
                 AND revision.document_id = anchor.document_id
                 AND revision.status = 'READY'
                JOIN graph_projection_states projection
                  ON projection.graph_generation = anchor.graph_generation
                 AND projection.document_id = anchor.document_id
                 AND projection.revision_id = anchor.revision_id
                 AND projection.acl_version = document.acl_version
                 AND projection.state = 'PROJECTED'
                JOIN source_locator_projection location
                  ON location.source_kind = 'SOURCE_SPAN'
                 AND location.source_id = anchor.source_span_id
                LEFT JOIN graph_entity_mentions mention
                  ON anchor.anchor_type = 'MENTION'
                 AND mention.id = anchor.anchor_id
                 AND mention.graph_generation = anchor.graph_generation
                LEFT JOIN graph_relationship_evidence evidence
                  ON anchor.anchor_type = 'RELATIONSHIP_EVIDENCE'
                 AND evidence.id = anchor.anchor_id
                 AND evidence.graph_generation = anchor.graph_generation
                WHERE anchor.candidate_id = ?
                  AND anchor.graph_generation = ?
                  AND ((anchor.anchor_type = 'MENTION' AND mention.id IS NOT NULL)
                    OR (anchor.anchor_type = 'RELATIONSHIP_EVIDENCE' AND evidence.id IS NOT NULL))
                ORDER BY anchor.evidence_order
                """,
                (resultSet, rowNumber) -> candidateEvidence(resultSet),
                candidateId, generation
        );
    }

    private List<GraphResolutionCandidateNeighborView> neighbors(
            UUID candidateId,
            long generation
    ) {
        return jdbc.query(
                """
                WITH selected AS (
                    SELECT entity_id
                    FROM graph_resolution_candidate_entities
                    WHERE candidate_id = ?
                ),
                visible_edges AS (
                    SELECT selected.entity_id,
                           CASE WHEN relationship.source_entity_id = selected.entity_id
                             THEN relationship.target_entity_id
                             ELSE relationship.source_entity_id END AS neighbor_id,
                           count(*) AS evidence_count
                    FROM selected
                    JOIN graph_relationships relationship
                      ON relationship.graph_generation = ?
                     AND (relationship.source_entity_id = selected.entity_id
                       OR relationship.target_entity_id = selected.entity_id)
                    JOIN graph_relationship_evidence evidence
                      ON evidence.graph_generation = relationship.graph_generation
                     AND evidence.relationship_id = relationship.id
                    JOIN documents document
                      ON document.id = evidence.document_id
                     AND document.current_revision_id = evidence.revision_id
                     AND document.deleted_at IS NULL
                    JOIN graph_projection_states projection
                      ON projection.graph_generation = evidence.graph_generation
                     AND projection.document_id = evidence.document_id
                     AND projection.revision_id = evidence.revision_id
                     AND projection.acl_version = document.acl_version
                     AND projection.state = 'PROJECTED'
                    GROUP BY selected.entity_id,
                             CASE WHEN relationship.source_entity_id = selected.entity_id
                               THEN relationship.target_entity_id
                               ELSE relationship.source_entity_id END
                ),
                shared AS (
                    SELECT neighbor_id, count(DISTINCT entity_id) > 1 AS shared
                    FROM visible_edges GROUP BY neighbor_id
                )
                SELECT edge.entity_id, source.canonical_name AS entity_name,
                       edge.neighbor_id, neighbor.canonical_name AS neighbor_name,
                       neighbor.entity_type AS neighbor_type, shared.shared,
                       edge.evidence_count
                FROM visible_edges edge
                JOIN shared ON shared.neighbor_id = edge.neighbor_id
                JOIN graph_entities source
                  ON source.graph_generation = ? AND source.id = edge.entity_id
                JOIN graph_entities neighbor
                  ON neighbor.graph_generation = ? AND neighbor.id = edge.neighbor_id
                ORDER BY shared.shared DESC, neighbor.normalized_name,
                         edge.entity_id, edge.neighbor_id
                LIMIT 100
                """,
                (resultSet, rowNumber) -> new GraphResolutionCandidateNeighborView(
                        resultSet.getObject("entity_id", UUID.class),
                        resultSet.getString("entity_name"),
                        resultSet.getObject("neighbor_id", UUID.class),
                        resultSet.getString("neighbor_name"),
                        resultSet.getString("neighbor_type"),
                        resultSet.getBoolean("shared"),
                        resultSet.getInt("evidence_count")
                ),
                candidateId, generation, generation, generation
        );
    }

    private List<GraphResolutionCandidateEventView> events(UUID candidateId) {
        return jdbc.query(
                """
                SELECT id, event_type, previous_status, next_status,
                       version, reason, created_at
                FROM graph_resolution_candidate_events
                WHERE candidate_id = ?
                ORDER BY id DESC
                LIMIT 50
                """,
                (resultSet, rowNumber) -> new GraphResolutionCandidateEventView(
                        resultSet.getLong("id"),
                        resultSet.getString("event_type"),
                        resultSet.getString("previous_status"),
                        resultSet.getString("next_status"),
                        resultSet.getInt("version"),
                        resultSet.getString("reason"),
                        resultSet.getTimestamp("created_at").toInstant()
                ),
                candidateId
        );
    }

    private GraphResolutionCandidateSummary summary(
            UUID candidateId,
            SnapshotRow snapshot
    ) {
        return loadSummaries(snapshot).stream()
                .filter(item -> item.id().equals(candidateId))
                .findFirst()
                .orElseThrow(GraphResolutionCandidateService::notFound);
    }

    private CandidateContext context(UUID candidateId, boolean lock) {
        String suffix = lock ? " FOR SHARE OF snapshot" : "";
        return jdbc.query(
                """
                SELECT snapshot.id, snapshot.graph_generation,
                       snapshot.graph_config_version, snapshot.source_set_hash,
                       snapshot.algorithm_version, snapshot.input_hash,
                       snapshot.status, snapshot.duplicate_candidate_count,
                       snapshot.split_candidate_count, snapshot.created_at,
                       snapshot.stale_at, snapshot.stale_reason
                FROM graph_resolution_candidates candidate
                JOIN graph_resolution_candidate_snapshots snapshot
                  ON snapshot.id = candidate.snapshot_id
                WHERE candidate.id = ?
                """ + suffix,
                (resultSet, rowNumber) -> new CandidateContext(snapshotRow(resultSet)),
                candidateId
        ).stream().findFirst().orElseThrow(GraphResolutionCandidateService::notFound);
    }

    private StateRow state(UUID candidateId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query(
                "SELECT status, version FROM graph_resolution_candidate_states WHERE candidate_id = ?" + suffix,
                (resultSet, rowNumber) -> new StateRow(
                        resultSet.getString("status"), resultSet.getInt("version")
                ),
                candidateId
        ).stream().findFirst().orElseThrow(GraphResolutionCandidateService::notFound);
    }

    private Optional<SnapshotRow> latestSnapshot(long generation) {
        return jdbc.query(
                snapshotSelect() + " WHERE graph_generation = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                (resultSet, rowNumber) -> snapshotRow(resultSet),
                generation
        ).stream().findFirst();
    }

    private Optional<SnapshotRow> findSnapshot(long generation, String inputHash) {
        return jdbc.query(
                snapshotSelect() + " WHERE graph_generation = ? AND algorithm_version = ? AND input_hash = ?",
                (resultSet, rowNumber) -> snapshotRow(resultSet),
                generation, ALGORITHM_VERSION, inputHash
        ).stream().findFirst();
    }

    private SnapshotRow snapshot(UUID id) {
        return jdbc.query(
                snapshotSelect() + " WHERE id = ?",
                (resultSet, rowNumber) -> snapshotRow(resultSet),
                id
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_RESOLUTION_CANDIDATE_SNAPSHOT_NOT_FOUND",
                "候选 Snapshot 不存在"
        ));
    }

    private static String snapshotSelect() {
        return """
                SELECT id, graph_generation, graph_config_version,
                       source_set_hash, algorithm_version, input_hash,
                       status, duplicate_candidate_count,
                       split_candidate_count, created_at, stale_at, stale_reason
                FROM graph_resolution_candidate_snapshots
                """;
    }

    private static SnapshotRow snapshotRow(ResultSet resultSet) throws SQLException {
        return new SnapshotRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("graph_generation"),
                resultSet.getString("graph_config_version"),
                resultSet.getString("source_set_hash"),
                resultSet.getString("algorithm_version"),
                resultSet.getString("input_hash"),
                resultSet.getString("status"),
                resultSet.getInt("duplicate_candidate_count"),
                resultSet.getInt("split_candidate_count"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("stale_at") == null
                        ? null : resultSet.getTimestamp("stale_at").toInstant(),
                resultSet.getString("stale_reason")
        );
    }

    private static GraphResolutionCandidateSnapshotView view(SnapshotRow row) {
        return new GraphResolutionCandidateSnapshotView(
                row.id(), row.generation(), row.configVersion(),
                row.sourceSetHash(), row.algorithmVersion(), row.inputHash(),
                row.status(), row.duplicateCount(), row.splitCount(),
                row.createdAt(), row.staleAt(), row.staleReason()
        );
    }

    private String inputHash(Manifest manifest, InputModel input) {
        StringBuilder value = new StringBuilder()
                .append(manifest.generation()).append('|')
                .append(manifest.configVersion()).append('|')
                .append(manifest.sourceSetHash()).append('|')
                .append(ALGORITHM_VERSION).append('\n');
        for (EntityProfile profile : input.entities().values()) {
            value.append("E|").append(profile.id()).append('|')
                    .append(profile.normalizedName()).append('|')
                    .append(profile.entityType()).append('|')
                    .append(String.join(",", profile.aliases().keySet())).append('\n');
            for (EvidenceAnchor mention : profile.mentions()) {
                value.append("M|").append(mention.anchorId()).append('|')
                        .append(mention.documentId()).append('|')
                        .append(mention.revisionId()).append('|')
                        .append(mention.sourceSpanId()).append('|')
                        .append(normalize(mention.excerpt())).append('\n');
            }
        }
        for (RelationshipAnchor relationship : input.relationships()) {
            value.append("R|").append(relationship.id()).append('|')
                    .append(relationship.relationshipId()).append('|')
                    .append(relationship.sourceEntityId()).append('|')
                    .append(relationship.targetEntityId()).append('|')
                    .append(relationship.documentId()).append('|')
                    .append(relationship.sourceSpanId()).append('\n');
        }
        return GraphAssembler.sha256(value.toString());
    }

    private static List<EvidenceAnchor> evidenceFor(
            EntityProfile left,
            EntityProfile right
    ) {
        List<EvidenceAnchor> selected = new ArrayList<>();
        selected.addAll(left.mentions().stream().limit(3).toList());
        selected.addAll(right.mentions().stream().limit(3).toList());
        for (RelationshipAnchor relationship : left.relationshipEvidence()) {
            if (selected.size() >= MAX_EVIDENCE) break;
            selected.add(relationship.asEvidence(left.id()));
        }
        return deduplicateEvidence(selected).stream()
                .limit(MAX_EVIDENCE).toList();
    }

    private static List<EvidenceAnchor> deduplicateEvidence(
            List<EvidenceAnchor> values
    ) {
        Map<String, EvidenceAnchor> result = new LinkedHashMap<>();
        values.forEach(value -> result.putIfAbsent(
                value.anchorType() + "|" + value.anchorId(), value
        ));
        return new ArrayList<>(result.values());
    }

    private static void groupedPairs(
            List<EntityProfile> profiles,
            Function<EntityProfile, String> key,
            Set<Pair> pairs
    ) {
        profiles.stream().collect(Collectors.groupingBy(
                key, TreeMap::new, Collectors.toList()
        )).values().forEach(group -> addPairs(group, pairs));
    }

    private static void addPairs(List<EntityProfile> group, Set<Pair> pairs) {
        for (int left = 0; left < group.size(); left++) {
            for (int right = left + 1; right < group.size(); right++) {
                pairs.add(new Pair(group.get(left).id(), group.get(right).id()));
            }
        }
    }

    private static ClusterPair separatedClusters(
            List<Map.Entry<String, List<EvidenceAnchor>>> groups
    ) {
        for (int left = 0; left < groups.size(); left++) {
            Set<UUID> leftDocuments = documents(groups.get(left).getValue());
            for (int right = left + 1; right < groups.size(); right++) {
                if (intersection(leftDocuments, documents(groups.get(right).getValue())).isEmpty()) {
                    return new ClusterPair(groups.get(left), groups.get(right));
                }
            }
        }
        return null;
    }

    private static boolean neighborSourcesSeparated(EntityProfile profile) {
        List<NeighborStats> neighbors = new ArrayList<>(profile.neighbors().values());
        for (int left = 0; left < neighbors.size(); left++) {
            for (int right = left + 1; right < neighbors.size(); right++) {
                if (intersection(
                        neighbors.get(left).documents(),
                        neighbors.get(right).documents()
                ).isEmpty()) return true;
            }
        }
        return false;
    }

    private static void rank(List<CandidateDraft> candidates) {
        candidates.sort(draftOrder());
        for (int index = 0; index < candidates.size(); index++) {
            candidates.get(index).rank(index);
        }
    }

    private static Comparator<CandidateDraft> draftOrder() {
        return Comparator.comparingInt(CandidateDraft::hardSignals).reversed()
                .thenComparing(Comparator.comparingInt(
                        (CandidateDraft value) -> value.signals().size()
                ).reversed())
                .thenComparing(Comparator.comparingInt(
                        (CandidateDraft value) -> value.evidence().size()
                ).reversed())
                .thenComparing(CandidateDraft::key);
    }

    private static Comparator<GraphResolutionCandidateSummary> summaryOrder() {
        return Comparator.comparingInt(GraphResolutionCandidateSummary::stableRank)
                .thenComparing(GraphResolutionCandidateSummary::id);
    }

    private static EntityProfile preferred(EntityProfile left, EntityProfile right) {
        if (left.mentions().size() != right.mentions().size()) {
            return left.mentions().size() > right.mentions().size() ? left : right;
        }
        int byName = left.normalizedName().compareTo(right.normalizedName());
        return byName < 0 || (byName == 0 && left.id().compareTo(right.id()) < 0)
                ? left : right;
    }

    private static String candidateKey(
            String type,
            List<UUID> entityIds,
            List<String> aliases
    ) {
        return GraphAssembler.sha256(
                type + "|" + entityIds.stream().sorted().map(UUID::toString)
                        .collect(Collectors.joining(","))
                        + "|" + aliases.stream().map(GraphResolutionCandidateService::normalize)
                        .sorted().collect(Collectors.joining(","))
        );
    }

    private static boolean matchesEntity(
            GraphResolutionCandidateSummary item,
            String query
    ) {
        if (query == null || query.isBlank()) return true;
        String needle = normalize(query);
        return item.entities().stream().anyMatch(entity ->
                normalize(entity.canonicalName()).contains(needle)
                        || entity.aliases().stream().anyMatch(alias ->
                        normalize(alias).contains(needle))
        );
    }

    private static double similarity(String left, String right) {
        if (left.equals(right)) return 1d;
        if (left.isBlank() || right.isBlank()) return 0d;
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) previous[index] = index;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int substitution = previous[rightIndex - 1]
                        + (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1);
                current[rightIndex] = Math.min(
                        Math.min(previous[rightIndex] + 1, current[rightIndex - 1] + 1),
                        substitution
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1d - ((double) previous[right.length()]
                / Math.max(left.length(), right.length()));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static String compact(String value) {
        return normalize(value).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static <T> Set<T> intersection(Collection<T> left, Collection<T> right) {
        Set<T> result = new LinkedHashSet<>(left);
        result.retainAll(new HashSet<>(right));
        return result;
    }

    private static int unionSize(Collection<?> left, Collection<?> right) {
        Set<Object> result = new HashSet<>(left);
        result.addAll(right);
        return result.size();
    }

    private static Set<UUID> documents(Collection<EvidenceAnchor> evidence) {
        return evidence.stream().map(EvidenceAnchor::documentId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GRAPH_RESOLUTION_CANDIDATE_REQUEST_INVALID",
                    field + " 不能为空"
            );
        }
        return normalized;
    }

    private static UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Candidate value cannot be serialized", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored candidate aliases are invalid", exception);
        }
    }

    private static List<String> textArray(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Object[] prepend(Object first, List<?> rest) {
        Object[] values = new Object[rest.size() + 1];
        values[0] = first;
        for (int index = 0; index < rest.size(); index++) values[index + 1] = rest.get(index);
        return values;
    }

    private static String placeholders(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(ignored -> "?").collect(Collectors.joining(","));
    }

    private static CandidateCursor decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8
            );
            String[] parts = decoded.split("\\u001f", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new CandidateCursor(Integer.parseInt(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GRAPH_RESOLUTION_CANDIDATE_CURSOR_INVALID",
                    "候选分页游标无效，请重新加载"
            );
        }
    }

    private static String encodeCursor(int rank, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (rank + "\u001f" + id).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static GraphResolutionCandidateEvidenceView candidateEvidence(
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
        return new GraphResolutionCandidateEvidenceView(
                resultSet.getString("anchor_type"),
                resultSet.getObject("anchor_id", UUID.class),
                resultSet.getObject("entity_id", UUID.class),
                resultSet.getString("entity_name"),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getString("document_title"),
                resultSet.getObject("revision_id", UUID.class),
                resultSet.getInt("revision_number"),
                resultSet.getObject("child_chunk_id", UUID.class),
                resultSet.getObject("source_span_id", UUID.class),
                resultSet.getString("excerpt"),
                resultSet.getString("document_format"),
                locator,
                locator.sourceLabel()
        );
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "GRAPH_RESOLUTION_CANDIDATE_NOT_FOUND",
                "候选不存在或已被重建删除"
        );
    }

    private record Manifest(
            long generation,
            String configVersion,
            String status,
            String sourceSetHash
    ) { }

    private record SnapshotRow(
            UUID id,
            long generation,
            String configVersion,
            String sourceSetHash,
            String algorithmVersion,
            String inputHash,
            String status,
            int duplicateCount,
            int splitCount,
            Instant createdAt,
            Instant staleAt,
            String staleReason
    ) { }

    private record InputModel(
            Map<UUID, EntityProfile> entities,
            List<RelationshipAnchor> relationships
    ) { }

    private record EntityProfile(
            UUID id,
            String canonicalName,
            String normalizedName,
            String entityType,
            Map<String, String> aliases,
            List<EvidenceAnchor> mentions,
            Map<UUID, NeighborStats> neighbors,
            List<RelationshipAnchor> relationshipEvidence
    ) { }

    private static final class NeighborStats {
        private final Set<UUID> documents;
        private int evidenceCount;

        private NeighborStats(Set<UUID> documents, int evidenceCount) {
            this.documents = documents;
            this.evidenceCount = evidenceCount;
        }

        Set<UUID> documents() { return documents; }
        void increment() { evidenceCount++; }
    }

    private record EvidenceAnchor(
            String anchorType,
            UUID anchorId,
            UUID entityId,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId,
            String excerpt
    ) { }

    private record RelationshipAnchor(
            UUID id,
            UUID relationshipId,
            UUID sourceEntityId,
            UUID targetEntityId,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId,
            String excerpt
    ) {
        EvidenceAnchor asEvidence(UUID entityId) {
            return new EvidenceAnchor(
                    "RELATIONSHIP_EVIDENCE", id, entityId, documentId,
                    revisionId, childChunkId, sourceSpanId, excerpt
            );
        }
    }

    private record Signal(
            String code,
            String strength,
            String explanation,
            Double numericValue
    ) { }

    private static final class CandidateDraft {
        private final String type;
        private final String action;
        private final List<UUID> entityIds;
        private final String targetName;
        private final String targetType;
        private final List<String> suggestedAliases;
        private final List<Signal> signals;
        private final List<EvidenceAnchor> evidence;
        private final String key;
        private int rank;

        private CandidateDraft(
                String type, String action, List<UUID> entityIds,
                String targetName, String targetType,
                List<String> suggestedAliases, List<Signal> signals,
                List<EvidenceAnchor> evidence, String key
        ) {
            this.type = type;
            this.action = action;
            this.entityIds = entityIds;
            this.targetName = targetName;
            this.targetType = targetType;
            this.suggestedAliases = suggestedAliases;
            this.signals = signals;
            this.evidence = evidence;
            this.key = key;
        }

        String type() { return type; }
        String action() { return action; }
        List<UUID> entityIds() { return entityIds; }
        String targetName() { return targetName; }
        String targetType() { return targetType; }
        List<String> suggestedAliases() { return suggestedAliases; }
        List<Signal> signals() { return signals; }
        List<EvidenceAnchor> evidence() { return evidence; }
        String key() { return key; }
        int rank() { return rank; }
        void rank(int value) { rank = value; }
        int hardSignals() {
            return (int) signals.stream().filter(signal ->
                    "HARD".equals(signal.strength())).count();
        }
    }

    private record Pair(UUID left, UUID right) implements Comparable<Pair> {
        private Pair {
            if (left.compareTo(right) > 0) {
                UUID swap = left;
                left = right;
                right = swap;
            }
        }

        @Override
        public int compareTo(Pair other) {
            int leftOrder = left.compareTo(other.left);
            return leftOrder == 0 ? right.compareTo(other.right) : leftOrder;
        }
    }

    private record ClusterPair(
            Map.Entry<String, List<EvidenceAnchor>> left,
            Map.Entry<String, List<EvidenceAnchor>> right
    ) { }

    private record BaseCandidate(
            UUID id, String type, String action,
            String targetName, String targetType,
            List<String> suggestedAliases,
            int evidenceCount, int sourceDocumentCount, int rank,
            Instant createdAt, String status, int version, Instant updatedAt
    ) { }

    private record EntityCandidateRow(
            UUID candidateId,
            ResolutionEntityView view
    ) { }

    private record SignalRow(
            UUID candidateId,
            GraphResolutionCandidateSignalView view
    ) { }

    private record CandidateContext(SnapshotRow snapshot) { }
    private record StateRow(String status, int version) { }
    private record CandidateCursor(int rank, UUID id) { }
}
