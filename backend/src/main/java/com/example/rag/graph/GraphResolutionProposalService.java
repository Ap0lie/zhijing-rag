package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.governance.GovernanceEventService;
import com.example.rag.graph.GraphApiContracts.CreateResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.CreateResolutionRuleRequest;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalConflictView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalDetail;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalEventView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalPage;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalRevisionView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalSummary;
import com.example.rag.graph.GraphApiContracts.MaterializeResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionEntityView;
import com.example.rag.graph.GraphApiContracts.ResolutionImpact;
import com.example.rag.graph.GraphApiContracts.ResolutionNotice;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewResponse;
import com.example.rag.graph.GraphApiContracts.ReviseResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.WithdrawResolutionProposalRequest;
import com.example.rag.graph.GraphBuildContracts.GraphConfig;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GraphResolutionProposalService {

    private static final Set<String> EDITABLE = Set.of(
            "DRAFT", "READY", "CONFLICTED", "STALE"
    );
    private static final String CREATE = "GRAPH_PROPOSAL_CREATE";
    private static final String REVISE = "GRAPH_PROPOSAL_REVISE";
    private static final String WITHDRAW = "GRAPH_PROPOSAL_WITHDRAW";
    private static final String MATERIALIZE = "GRAPH_PROPOSAL_MATERIALIZE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final GraphGenerationRepository generations;
    private final GraphResolutionService resolutions;
    private final GovernanceEventService governance;

    GraphResolutionProposalService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            GraphGenerationRepository generations,
            GraphResolutionService resolutions,
            GovernanceEventService governance
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.generations = generations;
        this.resolutions = resolutions;
        this.governance = governance;
    }

    @Transactional
    GraphResolutionProposalDetail create(
            CreateResolutionProposalRequest request,
            PlatformUserPrincipal actor
    ) {
        String reason = GovernanceEventService.normalizeReason(request.reason());
        String idempotencyKey = required(request.idempotencyKey(), "idempotencyKey");
        Draft draft = draft(
                request.graphGeneration(), request.baseConfigVersion(),
                request.action(), request.sourceEntityIds(), request.matchAliases(),
                request.targetCanonicalName(), request.targetEntityType()
        );
        String requestHash = governance.requestHash(
                draft.hashInput() + "|" + request.candidateId() + "|" + reason
        );
        governance.lockIdempotency(actor, CREATE, idempotencyKey);
        String existingHash = governance.existingRequestHash(actor, CREATE, idempotencyKey);
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw conflict("GRAPH_PROPOSAL_IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的 Proposal");
            }
            return detail(UUID.fromString(governance.existingObjectId(
                    actor, CREATE, idempotencyKey
            )), actor, false);
        }
        requireCandidate(request.candidateId());
        Assessment assessment = assess(draft, actor);
        UUID proposalId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO graph_resolution_proposals (
                    id, origin_candidate_id, status,
                    base_graph_generation, base_graph_config_version,
                    current_revision_number, version, created_by
                ) VALUES (?, ?, ?, ?, ?, 1, 1, ?)
                """,
                proposalId, request.candidateId(), assessment.status(),
                draft.graphGeneration(), draft.baseConfigVersion(), actor.id()
        );
        insertRevision(proposalId, 1, null, draft, assessment, actor.id(), reason);
        appendEvent(proposalId, "CREATED", actor.id(), 1, null,
                assessment.status(), 1, reason, Map.of());
        reconcileConflicts(draft.baseConfigVersion(), actor.id(), reason);
        governance.append(
                "GRAPH", CREATE, actor, "GRAPH_PROPOSAL",
                proposalId.toString(), draft.targetCanonicalName(), Map.of(),
                Map.of("status", assessment.status(), "revision", 1,
                        "baseConfigVersion", draft.baseConfigVersion()),
                reason, idempotencyKey, requestHash
        );
        return detail(proposalId, actor, false);
    }

    @Transactional
    GraphResolutionProposalDetail revise(
            UUID proposalId,
            ReviseResolutionProposalRequest request,
            PlatformUserPrincipal actor
    ) {
        String reason = GovernanceEventService.normalizeReason(request.reason());
        String idempotencyKey = required(request.idempotencyKey(), "idempotencyKey");
        ProposalRow proposal = proposal(proposalId, true);
        requireExpected(proposal, request.expectedRevision(), request.expectedVersion());
        if (!EDITABLE.contains(proposal.status())) {
            throw conflict("GRAPH_PROPOSAL_IMMUTABLE",
                    "已撤回、已物化或已应用的 Proposal 不能修改，请创建纠正规则");
        }
        Draft draft = draft(
                proposal.baseGeneration(), proposal.baseConfigVersion(),
                request.action(), request.sourceEntityIds(), request.matchAliases(),
                request.targetCanonicalName(), request.targetEntityType()
        );
        String requestHash = governance.requestHash(
                proposalId + "|" + proposal.version() + "|"
                        + draft.hashInput() + "|" + reason
        );
        governance.lockIdempotency(actor, REVISE, idempotencyKey);
        String existingHash = governance.existingRequestHash(actor, REVISE, idempotencyKey);
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw conflict("GRAPH_PROPOSAL_IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的 Proposal 修订");
            }
            return detail(proposalId, actor, false);
        }
        Assessment assessment = assess(draft, actor);
        int nextRevision = proposal.currentRevision() + 1;
        int nextVersion = proposal.version() + 1;
        insertRevision(proposalId, nextRevision, proposal.currentRevision(),
                draft, assessment, actor.id(), reason);
        int updated = jdbc.update(
                """
                UPDATE graph_resolution_proposals
                   SET current_revision_number = ?, status = ?,
                       version = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND current_revision_number = ? AND version = ?
                """,
                nextRevision, assessment.status(), nextVersion, proposalId,
                proposal.currentRevision(), proposal.version()
        );
        if (updated != 1) {
            throw versionConflict();
        }
        appendEvent(proposalId, "REVISED", actor.id(), nextRevision,
                proposal.status(), assessment.status(), nextVersion, reason, Map.of());
        reconcileConflicts(proposal.baseConfigVersion(), actor.id(), reason);
        governance.append(
                "GRAPH", REVISE, actor, "GRAPH_PROPOSAL",
                proposalId.toString(), draft.targetCanonicalName(),
                Map.of("status", proposal.status(),
                        "revision", proposal.currentRevision()),
                Map.of("status", assessment.status(), "revision", nextRevision),
                reason, idempotencyKey, requestHash
        );
        return detail(proposalId, actor, false);
    }

    @Transactional
    GraphResolutionProposalDetail withdraw(
            UUID proposalId,
            WithdrawResolutionProposalRequest request,
            PlatformUserPrincipal actor
    ) {
        if (!"WITHDRAW_RESOLUTION_PROPOSAL".equals(request.confirmation())) {
            throw badRequest("GRAPH_PROPOSAL_CONFIRMATION_INVALID", "确认字段不正确");
        }
        String reason = GovernanceEventService.normalizeReason(request.reason());
        String idempotencyKey = required(request.idempotencyKey(), "idempotencyKey");
        ProposalRow proposal = proposal(proposalId, true);
        requireExpected(proposal, request.expectedRevision(), request.expectedVersion());
        String requestHash = governance.requestHash(
                proposalId + "|" + proposal.version() + "|WITHDRAW|" + reason
        );
        governance.lockIdempotency(actor, WITHDRAW, idempotencyKey);
        String existingHash = governance.existingRequestHash(actor, WITHDRAW, idempotencyKey);
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw conflict("GRAPH_PROPOSAL_IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的撤回操作");
            }
            return detail(proposalId, actor, false);
        }
        if (!EDITABLE.contains(proposal.status())) {
            throw conflict("GRAPH_PROPOSAL_WITHDRAW_NOT_ALLOWED",
                    "只有尚未物化的 Proposal 可以撤回");
        }
        int nextVersion = proposal.version() + 1;
        if (jdbc.update(
                """
                UPDATE graph_resolution_proposals
                   SET status = 'WITHDRAWN', version = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND version = ?
                """,
                nextVersion, proposalId, proposal.version()
        ) != 1) {
            throw versionConflict();
        }
        jdbc.update("DELETE FROM graph_resolution_proposal_conflicts WHERE proposal_id = ? OR conflicting_proposal_id = ?",
                proposalId, proposalId);
        appendEvent(proposalId, "WITHDRAWN", actor.id(),
                proposal.currentRevision(), proposal.status(), "WITHDRAWN",
                nextVersion, reason, Map.of());
        reconcileConflicts(proposal.baseConfigVersion(), actor.id(), reason);
        governance.append(
                "GRAPH", WITHDRAW, actor, "GRAPH_PROPOSAL",
                proposalId.toString(), proposalId.toString(),
                Map.of("status", proposal.status()), Map.of("status", "WITHDRAWN"),
                reason, idempotencyKey, requestHash
        );
        return detail(proposalId, actor, false);
    }

    @Transactional
    GraphResolutionProposalDetail materialize(
            UUID proposalId,
            MaterializeResolutionProposalRequest request,
            PlatformUserPrincipal actor
    ) {
        if (!"MATERIALIZE_RESOLUTION_PROPOSAL".equals(request.confirmation())) {
            throw badRequest("GRAPH_PROPOSAL_CONFIRMATION_INVALID", "确认字段不正确");
        }
        String reason = GovernanceEventService.normalizeReason(request.reason());
        String idempotencyKey = required(request.idempotencyKey(), "idempotencyKey");
        String requestHash = governance.requestHash(
                proposalId + "|" + request.expectedRevision() + "|"
                        + request.previewToken() + "|" + request.newConfigVersion()
                        + "|" + reason
        );
        governance.lockIdempotency(actor, MATERIALIZE, idempotencyKey);
        String existingHash = governance.existingRequestHash(
                actor, MATERIALIZE, idempotencyKey
        );
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw conflict("GRAPH_PROPOSAL_IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的物化操作");
            }
            return detail(proposalId, actor, false);
        }
        ProposalRow proposal = proposal(proposalId, true);
        requireExpected(proposal, request.expectedRevision(), request.expectedVersion());
        if (!"READY".equals(proposal.status())) {
            throw conflict("GRAPH_PROPOSAL_NOT_READY",
                    "Proposal 存在冲突、已过期或尚未准备好，不能物化");
        }
        RevisionRow revision = revision(proposalId, proposal.currentRevision());
        ResolutionRulePreviewRequest expected = previewRequest(proposal, revision);
        resolutions.requirePreviewMatches(request.previewToken(), expected, actor);
        GraphConfig config = resolutions.create(
                new CreateResolutionRuleRequest(
                        request.previewToken(), request.newConfigVersion(),
                        "APPLY_NEXT_BUILD", reason, idempotencyKey
                ),
                actor,
                request.newConfigVersion().trim()
        );
        int nextVersion = proposal.version() + 1;
        if (jdbc.update(
                """
                UPDATE graph_resolution_proposals
                   SET status = 'MATERIALIZED', version = ?,
                       materialized_config_version = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND version = ? AND status = 'READY'
                """,
                nextVersion, config.version(), proposalId, proposal.version()
        ) != 1) {
            throw versionConflict();
        }
        appendEvent(proposalId, "MATERIALIZED", actor.id(),
                proposal.currentRevision(), proposal.status(), "MATERIALIZED",
                nextVersion, reason,
                Map.of("graphConfigVersion", config.version()));
        governance.append(
                "GRAPH", MATERIALIZE, actor, "GRAPH_PROPOSAL",
                proposalId.toString(), revision.targetCanonicalName(),
                Map.of("status", proposal.status()),
                Map.of("status", "MATERIALIZED",
                        "graphConfigVersion", config.version()),
                reason, idempotencyKey, requestHash
        );
        return detail(proposalId, actor, false);
    }

    @Transactional
    void markApplied(String configVersion, long generation, UUID actorId) {
        List<ProposalRow> matches = jdbc.query(
                proposalSelect() + " WHERE proposal.status = 'MATERIALIZED' AND proposal.materialized_config_version = ? FOR UPDATE",
                (rs, row) -> proposalRow(rs), configVersion
        );
        for (ProposalRow proposal : matches) {
            int nextVersion = proposal.version() + 1;
            if (jdbc.update(
                    """
                    UPDATE graph_resolution_proposals
                       SET status = 'APPLIED', version = ?,
                           applied_graph_generation = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND version = ? AND status = 'MATERIALIZED'
                    """,
                    nextVersion, generation, proposal.id(), proposal.version()
            ) == 1) {
                appendEvent(proposal.id(), "APPLIED", actorId,
                        proposal.currentRevision(), "MATERIALIZED", "APPLIED",
                        nextVersion, "候选 Generation 已冻结该 GraphConfig",
                        Map.of("graphGeneration", generation));
            }
        }
    }

    @Transactional
    GraphResolutionProposalPage page(
            String status,
            int page,
            int size,
            PlatformUserPrincipal actor
    ) {
        reconcileStale(actor.id());
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isEmpty() && !Set.of(
                "DRAFT", "READY", "CONFLICTED", "STALE", "WITHDRAWN",
                "MATERIALIZED", "APPLIED"
        ).contains(normalizedStatus)) {
            throw badRequest("GRAPH_PROPOSAL_STATUS_INVALID", "Proposal 状态筛选无效");
        }
        String where = normalizedStatus.isEmpty() ? "" : " WHERE proposal.status = ?";
        Object[] args = normalizedStatus.isEmpty()
                ? new Object[]{size, page * size}
                : new Object[]{normalizedStatus, size, page * size};
        List<ProposalRow> rows = jdbc.query(
                proposalSelect() + where + " ORDER BY CASE proposal.status WHEN 'CONFLICTED' THEN 0 WHEN 'STALE' THEN 1 WHEN 'READY' THEN 2 WHEN 'DRAFT' THEN 3 ELSE 4 END, proposal.updated_at DESC, proposal.id LIMIT ? OFFSET ?",
                (rs, row) -> proposalRow(rs), args
        );
        long total = normalizedStatus.isEmpty()
                ? jdbc.queryForObject("SELECT count(*) FROM graph_resolution_proposals", Long.class)
                : jdbc.queryForObject("SELECT count(*) FROM graph_resolution_proposals WHERE status = ?", Long.class, normalizedStatus);
        return new GraphResolutionProposalPage(
                page, size, total,
                rows.stream().map(this::summary).toList()
        );
    }

    @Transactional
    GraphResolutionProposalDetail detail(
            UUID proposalId,
            PlatformUserPrincipal actor,
            boolean reconcile
    ) {
        if (reconcile) {
            reconcileStale(actor.id());
        }
        ProposalRow proposal = proposal(proposalId, false);
        List<GraphResolutionProposalRevisionView> revisions = jdbc.query(
                revisionSelect() + " WHERE revision.proposal_id = ? ORDER BY revision.revision_number DESC",
                (rs, row) -> revisionRow(rs), proposalId
        ).stream().map(this::revisionView).toList();
        List<GraphResolutionProposalEventView> events = events(proposalId);
        return new GraphResolutionProposalDetail(summary(proposal), revisions, events);
    }

    @Transactional(readOnly = true)
    List<GraphResolutionProposalEventView> events(UUID proposalId) {
        proposal(proposalId, false);
        return jdbc.query(
                """
                SELECT id, event_type, revision_number, previous_status,
                       next_status, proposal_version, reason, created_at
                FROM graph_resolution_proposal_events
                WHERE proposal_id = ? ORDER BY id DESC
                """,
                (rs, row) -> new GraphResolutionProposalEventView(
                        rs.getLong("id"), rs.getString("event_type"),
                        rs.getInt("revision_number"), rs.getString("previous_status"),
                        rs.getString("next_status"), rs.getInt("proposal_version"),
                        rs.getString("reason"), rs.getTimestamp("created_at").toInstant()
                ), proposalId
        );
    }

    private Assessment assess(Draft draft, PlatformUserPrincipal actor) {
        ResolutionRulePreviewResponse preview = resolutions.preview(
                new ResolutionRulePreviewRequest(
                        draft.graphGeneration(), draft.baseConfigVersion(),
                        draft.action(), draft.sourceEntityIds(), draft.matchAliases(),
                        draft.targetCanonicalName(), draft.targetEntityType()
                ), actor
        );
        String status;
        if (preview.blockers().isEmpty()) {
            status = "READY";
        } else if (preview.blockers().stream().map(ResolutionNotice::code)
                .anyMatch(code -> code.contains("STALE")
                        || code.contains("GENERATION"))) {
            status = "STALE";
        } else {
            status = "CONFLICTED";
        }
        return new Assessment(status, preview);
    }

    private Draft draft(
            long generation,
            String baseConfig,
            String action,
            List<UUID> sourceIds,
            List<String> aliases,
            String targetName,
            String targetType
    ) {
        List<UUID> ids = sourceIds == null ? List.of() : sourceIds.stream()
                .distinct().sorted().toList();
        List<String> normalizedAliases = aliases == null ? List.of() : aliases.stream()
                .map(String::trim).filter(value -> !value.isEmpty())
                .distinct().sorted().toList();
        return new Draft(
                generation, required(baseConfig, "baseConfigVersion"),
                required(action, "action").toUpperCase(Locale.ROOT), ids,
                normalizedAliases, required(targetName, "targetCanonicalName"),
                required(targetType, "targetEntityType")
        );
    }

    private void insertRevision(
            UUID proposalId,
            int revisionNumber,
            Integer supersedes,
            Draft draft,
            Assessment assessment,
            UUID actorId,
            String reason
    ) {
        ResolutionRulePreviewResponse preview = assessment.preview();
        List<String> entityKeys = preview.entities().stream()
                .map(entity -> entity.entityType() + "|"
                        + GraphAssembler.normalize(entity.canonicalName()))
                .sorted().toList();
        jdbc.update(
                """
                INSERT INTO graph_resolution_proposal_revisions (
                    proposal_id, revision_number, id,
                    supersedes_revision_number, action, source_set_hash,
                    source_entity_ids, source_entity_keys, match_aliases,
                    target_canonical_name, target_normalized_name,
                    target_entity_type, mention_count, source_span_count,
                    relationship_count, relationship_evidence_count,
                    community_count, document_count, blockers, warnings,
                    created_by, reason
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                          CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                """,
                proposalId, revisionNumber, UUID.randomUUID(), supersedes,
                draft.action(), preview.sourceSetHash(), json(draft.sourceEntityIds()),
                json(entityKeys), json(draft.matchAliases()),
                draft.targetCanonicalName(),
                GraphAssembler.normalize(draft.targetCanonicalName()),
                GraphAssembler.normalizeType(draft.targetEntityType()),
                preview.impact().mentionCount(), preview.impact().sourceSpanCount(),
                preview.impact().relationshipCount(),
                preview.impact().relationshipEvidenceCount(),
                preview.impact().communityCount(), preview.impact().documentCount(),
                json(preview.blockers()), json(preview.warnings()), actorId, reason
        );
        int order = 0;
        for (ResolutionEntityView entity : preview.entities()) {
            jdbc.update(
                    """
                    INSERT INTO graph_resolution_proposal_revision_entities (
                        proposal_id, revision_number, graph_generation,
                        entity_id, entity_order, canonical_name, entity_type,
                        aliases, mention_count, relationship_count,
                        relationship_evidence_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                    """,
                    proposalId, revisionNumber, draft.graphGeneration(), entity.id(),
                    order++, entity.canonicalName(), entity.entityType(),
                    json(entity.aliases()), entity.mentionCount(),
                    entity.relationshipCount(), entity.relationshipEvidenceCount()
            );
        }
    }

    private void reconcileConflicts(String baseConfig, UUID actorId, String reason) {
        List<ProposalRow> active = jdbc.query(
                proposalSelect() + " WHERE proposal.base_graph_config_version = ? AND proposal.status IN ('DRAFT','READY','CONFLICTED','STALE') ORDER BY proposal.id FOR UPDATE",
                (rs, row) -> proposalRow(rs), baseConfig
        );
        if (active.isEmpty()) return;
        jdbc.update(
                "DELETE FROM graph_resolution_proposal_conflicts WHERE proposal_id IN (SELECT id FROM graph_resolution_proposals WHERE base_graph_config_version = ? AND status IN ('DRAFT','READY','CONFLICTED','STALE'))",
                baseConfig
        );
        Map<UUID, RevisionRow> revisions = active.stream().collect(Collectors.toMap(
                ProposalRow::id,
                item -> revision(item.id(), item.currentRevision()),
                (left, right) -> left,
                LinkedHashMap::new
        ));
        for (int leftIndex = 0; leftIndex < active.size(); leftIndex++) {
            ProposalRow left = active.get(leftIndex);
            RevisionRow leftRevision = revisions.get(left.id());
            for (int rightIndex = leftIndex + 1; rightIndex < active.size(); rightIndex++) {
                ProposalRow right = active.get(rightIndex);
                RevisionRow rightRevision = revisions.get(right.id());
                boolean overlaps = leftRevision.sourceEntityIds().stream()
                        .anyMatch(rightRevision.sourceEntityIds()::contains);
                if (overlaps) {
                    addConflict(left, right, "SOURCE_ENTITY_OVERLAP",
                            "多条待生效规则包含相同来源实体，请保留一个明确方案");
                }
                if (leftRevision.sourceEntityKeys().equals(rightRevision.sourceEntityKeys())
                        && leftRevision.action().equals(rightRevision.action())) {
                    addConflict(left, right, "DUPLICATE_RULE",
                            "同一基础配置已有来源集合与操作相同的 Proposal");
                }
                if (leftRevision.targetNormalizedName().equals(
                        rightRevision.targetNormalizedName())
                        && !leftRevision.targetEntityType().equals(
                        rightRevision.targetEntityType())) {
                    addConflict(left, right, "TARGET_TYPE_CONFLICT",
                            "目标名称相同但实体类型不同，请统一目标语义");
                }
            }
        }
        Set<UUID> conflicted = jdbc.query(
                "SELECT DISTINCT proposal_id FROM graph_resolution_proposal_conflicts WHERE proposal_id IN (SELECT id FROM graph_resolution_proposals WHERE base_graph_config_version = ?)",
                (rs, row) -> rs.getObject(1, UUID.class), baseConfig
        ).stream().collect(Collectors.toSet());
        for (ProposalRow proposal : active) {
            RevisionRow revision = revisions.get(proposal.id());
            String desired = conflicted.contains(proposal.id())
                    ? "CONFLICTED" : baseStatus(revision.blockers());
            if (!proposal.status().equals(desired)) {
                int nextVersion = proposal.version() + 1;
                jdbc.update(
                        "UPDATE graph_resolution_proposals SET status = ?, version = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?",
                        desired, nextVersion, proposal.id(), proposal.version()
                );
                appendEvent(proposal.id(), desired, actorId,
                        proposal.currentRevision(), proposal.status(), desired,
                        nextVersion, reason, Map.of("automatic", true));
            }
        }
    }

    private void addConflict(
            ProposalRow left,
            ProposalRow right,
            String code,
            String message
    ) {
        insertConflict(left.id(), left.currentRevision(), right.id(), code, message);
        insertConflict(right.id(), right.currentRevision(), left.id(), code, message);
    }

    private void insertConflict(
            UUID proposalId,
            int revision,
            UUID other,
            String code,
            String message
    ) {
        jdbc.update(
                """
                INSERT INTO graph_resolution_proposal_conflicts (
                    proposal_id, revision_number, conflicting_proposal_id,
                    conflict_code, message
                ) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """,
                proposalId, revision, other, code, message
        );
    }

    private void reconcileStale(UUID actorId) {
        String currentHash = generations.currentSourceSetHash();
        List<ProposalRow> rows = jdbc.query(
                proposalSelect() + " JOIN graph_manifests manifest ON manifest.graph_generation = proposal.base_graph_generation WHERE proposal.status IN ('DRAFT','READY','CONFLICTED') AND (manifest.status NOT IN ('ACTIVE','READY') OR manifest.source_set_hash <> ?)",
                (rs, row) -> proposalRow(rs), currentHash
        );
        for (ProposalRow proposal : rows) {
            int nextVersion = proposal.version() + 1;
            if (jdbc.update(
                    "UPDATE graph_resolution_proposals SET status = 'STALE', version = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ?",
                    nextVersion, proposal.id(), proposal.version()
            ) == 1) {
                appendEvent(proposal.id(), "STALE", actorId,
                        proposal.currentRevision(), proposal.status(), "STALE",
                        nextVersion, "Revision 或 ACL 变化使 Proposal 事实过期",
                        Map.of());
            }
        }
    }

    private GraphResolutionProposalSummary summary(ProposalRow proposal) {
        RevisionRow revision = revision(proposal.id(), proposal.currentRevision());
        return new GraphResolutionProposalSummary(
                proposal.id(), proposal.candidateId(), proposal.status(),
                proposal.version(), proposal.currentRevision(),
                proposal.baseGeneration(), proposal.baseConfigVersion(),
                proposal.materializedConfigVersion(), proposal.appliedGeneration(),
                revision.action(), revision.entities(), revision.matchAliases(),
                revision.targetCanonicalName(), revision.targetEntityType(),
                revision.impact(), revision.blockers(), revision.warnings(),
                conflicts(proposal.id(), proposal.currentRevision()),
                proposal.createdBy(), proposal.createdAt(), proposal.updatedAt(),
                nextStep(proposal.status())
        );
    }

    private GraphResolutionProposalRevisionView revisionView(RevisionRow revision) {
        return new GraphResolutionProposalRevisionView(
                revision.id(), revision.revision(), revision.supersedesRevision(),
                revision.action(), revision.entities(), revision.matchAliases(),
                revision.targetCanonicalName(), revision.targetEntityType(),
                revision.impact(), revision.blockers(), revision.warnings(),
                revision.reason(), revision.createdBy(), revision.createdAt()
        );
    }

    private List<GraphResolutionProposalConflictView> conflicts(
            UUID proposalId,
            int revision
    ) {
        return jdbc.query(
                """
                SELECT conflicting_proposal_id, conflict_code, message
                FROM graph_resolution_proposal_conflicts
                WHERE proposal_id = ? AND revision_number = ?
                ORDER BY conflict_code, conflicting_proposal_id
                """,
                (rs, row) -> new GraphResolutionProposalConflictView(
                        rs.getObject("conflicting_proposal_id", UUID.class),
                        rs.getString("conflict_code"), rs.getString("message")
                ), proposalId, revision
        );
    }

    private RevisionRow revision(UUID proposalId, int revision) {
        return jdbc.query(
                revisionSelect() + " WHERE revision.proposal_id = ? AND revision.revision_number = ?",
                (rs, row) -> revisionRow(rs), proposalId, revision
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "GRAPH_PROPOSAL_REVISION_NOT_FOUND",
                "找不到 Proposal Revision"
        ));
    }

    private RevisionRow revisionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID proposalId = rs.getObject("proposal_id", UUID.class);
        int revision = rs.getInt("revision_number");
        List<ResolutionEntityView> entities = jdbc.query(
                """
                SELECT entity_id, canonical_name, entity_type, aliases::text,
                       mention_count, relationship_count,
                       relationship_evidence_count
                FROM graph_resolution_proposal_revision_entities
                WHERE proposal_id = ? AND revision_number = ?
                ORDER BY entity_order
                """,
                (entityRs, row) -> new ResolutionEntityView(
                        entityRs.getObject("entity_id", UUID.class),
                        entityRs.getString("canonical_name"),
                        entityRs.getString("entity_type"),
                        strings(entityRs.getString("aliases")),
                        entityRs.getInt("mention_count"),
                        entityRs.getInt("relationship_count"),
                        entityRs.getInt("relationship_evidence_count")
                ), proposalId, revision
        );
        Integer supersedes = rs.getObject("supersedes_revision_number") == null
                ? null : rs.getInt("supersedes_revision_number");
        return new RevisionRow(
                proposalId, rs.getObject("id", UUID.class), revision, supersedes,
                rs.getString("action"), strings(rs.getString("source_entity_ids"))
                .stream().map(UUID::fromString).toList(),
                strings(rs.getString("source_entity_keys")),
                strings(rs.getString("match_aliases")),
                rs.getString("target_canonical_name"),
                rs.getString("target_normalized_name"),
                rs.getString("target_entity_type"), entities,
                new ResolutionImpact(
                        rs.getInt("mention_count"), rs.getInt("source_span_count"),
                        rs.getInt("relationship_count"),
                        rs.getInt("relationship_evidence_count"),
                        rs.getInt("community_count"), rs.getInt("document_count"),
                        "NOT_AVAILABLE", "离线图无法可靠推算未来查询命中范围"
                ),
                notices(rs.getString("blockers")), notices(rs.getString("warnings")),
                rs.getString("reason"), rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private ProposalRow proposal(UUID id, boolean lock) {
        return jdbc.query(
                proposalSelect() + " WHERE proposal.id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> proposalRow(rs), id
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "GRAPH_PROPOSAL_NOT_FOUND",
                "找不到实体治理 Proposal"
        ));
    }

    private ProposalRow proposalRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProposalRow(
                rs.getObject("id", UUID.class),
                rs.getObject("origin_candidate_id", UUID.class),
                rs.getString("status"), rs.getInt("version"),
                rs.getInt("current_revision_number"),
                rs.getLong("base_graph_generation"),
                rs.getString("base_graph_config_version"),
                rs.getString("materialized_config_version"),
                rs.getObject("applied_graph_generation") == null
                        ? null : rs.getLong("applied_graph_generation"),
                rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private String proposalSelect() {
        return """
                SELECT proposal.id, proposal.origin_candidate_id,
                       proposal.status, proposal.version,
                       proposal.current_revision_number,
                       proposal.base_graph_generation,
                       proposal.base_graph_config_version,
                       proposal.materialized_config_version,
                       proposal.applied_graph_generation,
                       actor.username AS created_by_name,
                       proposal.created_at, proposal.updated_at
                FROM graph_resolution_proposals proposal
                JOIN users actor ON actor.id = proposal.created_by
                """;
    }

    private String revisionSelect() {
        return """
                SELECT revision.proposal_id, revision.revision_number,
                       revision.id, revision.supersedes_revision_number,
                       revision.action, revision.source_entity_ids::text,
                       revision.source_entity_keys::text,
                       revision.match_aliases::text,
                       revision.target_canonical_name,
                       revision.target_normalized_name,
                       revision.target_entity_type, revision.mention_count,
                       revision.source_span_count, revision.relationship_count,
                       revision.relationship_evidence_count,
                       revision.community_count, revision.document_count,
                       revision.blockers::text, revision.warnings::text,
                       revision.reason, actor.username AS created_by_name,
                       revision.created_at
                FROM graph_resolution_proposal_revisions revision
                JOIN users actor ON actor.id = revision.created_by
                """;
    }

    private ResolutionRulePreviewRequest previewRequest(
            ProposalRow proposal,
            RevisionRow revision
    ) {
        return new ResolutionRulePreviewRequest(
                proposal.baseGeneration(), proposal.baseConfigVersion(),
                revision.action(), revision.sourceEntityIds(),
                revision.matchAliases(), revision.targetCanonicalName(),
                revision.targetEntityType()
        );
    }

    private void requireCandidate(UUID candidateId) {
        if (candidateId == null) return;
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM graph_resolution_candidates candidate
                JOIN graph_resolution_candidate_snapshots snapshot
                  ON snapshot.id = candidate.snapshot_id
                 AND snapshot.status = 'READY'
                JOIN graph_resolution_candidate_states state
                  ON state.candidate_id = candidate.id
                 AND state.status = 'ACTIVE'
                WHERE candidate.id = ?
                """,
                Integer.class, candidateId
        );
        if (count == null || count != 1) {
            throw conflict("GRAPH_PROPOSAL_CANDIDATE_STALE",
                    "候选已失效或不可用，请重新核对当前实体");
        }
    }

    private void appendEvent(
            UUID proposalId,
            String type,
            UUID actorId,
            int revision,
            String previous,
            String next,
            int version,
            String reason,
            Map<String, ?> details
    ) {
        jdbc.update(
                """
                INSERT INTO graph_resolution_proposal_events (
                    proposal_id, event_type, actor_user_id, revision_number,
                    previous_status, next_status, proposal_version,
                    reason, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                proposalId, type, actorId, revision, previous, next, version,
                reason, json(details)
        );
    }

    private void requireExpected(
            ProposalRow proposal,
            int revision,
            int version
    ) {
        if (proposal.currentRevision() != revision || proposal.version() != version) {
            throw versionConflict();
        }
    }

    private String baseStatus(List<ResolutionNotice> blockers) {
        if (blockers.isEmpty()) return "READY";
        return blockers.stream().map(ResolutionNotice::code)
                .anyMatch(code -> code.contains("STALE")
                        || code.contains("GENERATION"))
                ? "STALE" : "CONFLICTED";
    }

    private String nextStep(String status) {
        return switch (status) {
            case "READY" -> "重新预检后物化";
            case "CONFLICTED" -> "核对冲突并追加修订";
            case "STALE" -> "选择追平后的 Generation 并创建修订";
            case "DRAFT" -> "补全并预检";
            case "WITHDRAWN" -> "只读；如需纠正请创建新 Proposal";
            case "MATERIALIZED" -> "使用该 GraphConfig 创建候选 Generation";
            case "APPLIED" -> "等待独立构建与发布门禁";
            default -> "核对状态";
        };
    }

    private List<ResolutionNotice> notices(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Proposal notices are invalid", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Proposal list is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Proposal value cannot be serialized", exception);
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw badRequest("GRAPH_PROPOSAL_FIELD_REQUIRED", field + " 不能为空");
        }
        return normalized;
    }

    private static ApiException versionConflict() {
        return conflict("GRAPH_PROPOSAL_VERSION_CONFLICT",
                "Proposal 已被其他操作更新，请刷新后重试");
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private record Draft(
            long graphGeneration,
            String baseConfigVersion,
            String action,
            List<UUID> sourceEntityIds,
            List<String> matchAliases,
            String targetCanonicalName,
            String targetEntityType
    ) {
        String hashInput() {
            return graphGeneration + "|" + baseConfigVersion + "|" + action
                    + "|" + sourceEntityIds + "|" + matchAliases + "|"
                    + GraphAssembler.normalize(targetCanonicalName) + "|"
                    + GraphAssembler.normalizeType(targetEntityType);
        }
    }

    private record Assessment(String status, ResolutionRulePreviewResponse preview) {
    }

    private record ProposalRow(
            UUID id,
            UUID candidateId,
            String status,
            int version,
            int currentRevision,
            long baseGeneration,
            String baseConfigVersion,
            String materializedConfigVersion,
            Long appliedGeneration,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record RevisionRow(
            UUID proposalId,
            UUID id,
            int revision,
            Integer supersedesRevision,
            String action,
            List<UUID> sourceEntityIds,
            List<String> sourceEntityKeys,
            List<String> matchAliases,
            String targetCanonicalName,
            String targetNormalizedName,
            String targetEntityType,
            List<ResolutionEntityView> entities,
            ResolutionImpact impact,
            List<ResolutionNotice> blockers,
            List<ResolutionNotice> warnings,
            String reason,
            String createdBy,
            Instant createdAt
    ) {
    }
}
