package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.governance.GovernanceEventService;
import com.example.rag.graph.GraphApiContracts.CreateResolutionRuleRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionEntityView;
import com.example.rag.graph.GraphApiContracts.ResolutionImpact;
import com.example.rag.graph.GraphApiContracts.ResolutionNotice;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewResponse;
import com.example.rag.graph.GraphBuildContracts.GraphConfig;
import com.example.rag.graph.GraphBuildContracts.ResolutionRule;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GraphResolutionService {

    private static final String CREATE_ACTION =
            "GRAPH_RESOLUTION_RULE_CREATE";
    private static final int PREVIEW_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final GraphGenerationRepository generations;
    private final GovernanceEventService governance;

    GraphResolutionService(
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

    ResolutionRulePreviewResponse preview(
            ResolutionRulePreviewRequest request,
            PlatformUserPrincipal actor
    ) {
        ResolutionDraft draft = draft(request);
        ManifestState manifest = manifest(request.graphGeneration(), false);
        requirePreviewable(manifest);
        if (!manifest.configVersion().equals(draft.baseConfigVersion())) {
            throw conflict(
                    "GRAPH_RULE_BASE_CONFIG_MISMATCH",
                    "基础 GraphConfig 与所选 Generation 不一致"
            );
        }

        List<EntitySelection> entities = selections(
                manifest.generation(), draft.sourceEntityIds()
        );
        requireAllEntities(draft.sourceEntityIds(), entities);
        GraphConfig base = generations.config(draft.baseConfigVersion());
        ImpactCounts impact = impact(
                manifest.generation(), draft.sourceEntityIds()
        );
        List<ResolutionNotice> blockers = blockers(
                manifest, base, draft, entities
        );
        List<ResolutionNotice> warnings = warnings(entities);
        String currentSourceSetHash = generations.currentSourceSetHash();
        if (!manifest.sourceSetHash().equals(currentSourceSetHash)) {
            blockers.add(new ResolutionNotice(
                    "GRAPH_GENERATION_STALE",
                    "当前文档 Revision 或权限已变化，请先重建并选择追平后的 Generation"
            ));
        }

        UUID token = null;
        Instant expiresAt = null;
        if (blockers.isEmpty()) {
            token = UUID.randomUUID();
            expiresAt = Instant.now().plus(PREVIEW_MINUTES, ChronoUnit.MINUTES);
            insertPreview(
                    token, actor.id(), manifest, draft, entities,
                    impact, warnings, expiresAt
            );
        }
        return response(
                token, expiresAt, manifest, draft, entities,
                impact, blockers, warnings
        );
    }

    GraphConfig create(
            CreateResolutionRuleRequest request,
            PlatformUserPrincipal actor,
            String newConfigVersion
    ) {
        String reason = GovernanceEventService.normalizeReason(request.reason());
        String idempotencyKey = required(request.idempotencyKey(),
                "idempotencyKey");
        String requestHash = governance.requestHash(
                request.previewToken() + "|" + newConfigVersion + "|" + reason
        );
        governance.lockIdempotency(actor, CREATE_ACTION, idempotencyKey);
        String existingHash = governance.existingRequestHash(
                actor, CREATE_ACTION, idempotencyKey
        );
        if (existingHash != null) {
            if (!existingHash.equals(requestHash)) {
                throw conflict(
                        "GRAPH_RULE_IDEMPOTENCY_CONFLICT",
                        "该幂等键已用于不同的实体消歧操作"
                );
            }
            String existingVersion = governance.existingObjectId(
                    actor, CREATE_ACTION, idempotencyKey
            );
            return generations.config(existingVersion);
        }

        PreviewRow preview = lockPreview(request.previewToken());
        if (!preview.actorId().equals(actor.id())) {
            throw previewInvalid();
        }
        if (!preview.expiresAt().isAfter(Instant.now())) {
            throw conflict(
                    "GRAPH_RULE_PREVIEW_EXPIRED",
                    "影响预览已过期，请重新预检"
            );
        }
        if (preview.consumedAt() != null) {
            throw conflict(
                    "GRAPH_RULE_PREVIEW_CONSUMED",
                    "影响预览已被使用，请刷新规则状态"
            );
        }

        ManifestState manifest = manifest(preview.generation(), true);
        requirePreviewable(manifest);
        if (!manifest.configVersion().equals(preview.baseConfigVersion())
                || !manifest.sourceSetHash().equals(preview.sourceSetHash())
                || !manifest.sourceSetHash().equals(
                        generations.currentSourceSetHash()
                )) {
            throw conflict(
                    "GRAPH_RULE_PREVIEW_STALE",
                    "Generation、Revision 或权限事实已变化，请重新预检"
            );
        }

        ResolutionDraft draft = new ResolutionDraft(
                preview.baseConfigVersion(),
                preview.action(),
                preview.sourceEntityIds(),
                preview.matchAliases(),
                preview.targetCanonicalName(),
                preview.targetNormalizedName(),
                preview.targetEntityType()
        );
        List<EntitySelection> entities = selections(
                manifest.generation(), draft.sourceEntityIds()
        );
        requireAllEntities(draft.sourceEntityIds(), entities);
        GraphConfig base = generations.config(draft.baseConfigVersion());
        List<ResolutionNotice> blockers = blockers(
                manifest, base, draft, entities
        );
        if (!blockers.isEmpty()) {
            throw conflict(
                    blockers.getFirst().code(),
                    blockers.getFirst().message()
            );
        }
        ImpactCounts impact = impact(
                manifest.generation(), draft.sourceEntityIds()
        );
        String currentFactHash = factHash(
                manifest, draft, entities, impact
        );
        if (!preview.factHash().equals(currentFactHash)) {
            throw conflict(
                    "GRAPH_RULE_PREVIEW_STALE",
                    "实体、关系或 Evidence 已变化，请重新预检"
            );
        }

        GraphConfig config = generations.createResolutionConfig(
                base,
                newConfigVersion,
                manifest.generation(),
                manifest.sourceSetHash(),
                draft.action(),
                draft.sourceEntityIds(),
                draft.matchAliases(),
                draft.targetCanonicalName(),
                draft.targetNormalizedName(),
                draft.targetEntityType(),
                reason,
                actor.id()
        );
        int consumed = jdbc.update(
                """
                UPDATE graph_resolution_previews
                   SET consumed_at = CURRENT_TIMESTAMP,
                       consumed_config_version = ?,
                       consumed_idempotency_key = ?
                 WHERE token = ? AND consumed_at IS NULL
                """,
                config.version(), idempotencyKey, preview.token()
        );
        if (consumed != 1) {
            throw conflict(
                    "GRAPH_RULE_PREVIEW_CONSUMED",
                    "影响预览已被其他请求使用"
            );
        }
        governance.append(
                "GRAPH",
                CREATE_ACTION,
                actor,
                "GRAPH_CONFIG",
                config.version(),
                config.version(),
                Map.of(
                        "generation", manifest.generation(),
                        "baseConfigVersion", preview.baseConfigVersion(),
                        "sourceSetHash", preview.sourceSetHash()
                ),
                Map.of(
                        "action", preview.action(),
                        "sourceEntityCount", preview.sourceEntityIds().size(),
                        "configVersion", config.version(),
                        "ruleSetVersion", config.resolutionRuleSetVersion()
                ),
                reason,
                idempotencyKey,
                requestHash
        );
        return config;
    }

    void requirePreviewMatches(
            UUID previewToken,
            ResolutionRulePreviewRequest expected,
            PlatformUserPrincipal actor
    ) {
        ResolutionDraft draft = draft(expected);
        PreviewRow preview = lockPreview(previewToken);
        if (!preview.actorId().equals(actor.id())) {
            throw previewInvalid();
        }
        if (!preview.expiresAt().isAfter(Instant.now())) {
            throw conflict(
                    "GRAPH_RULE_PREVIEW_EXPIRED",
                    "影响预览已过期，请重新预检"
            );
        }
        if (preview.consumedAt() != null) {
            throw conflict(
                    "GRAPH_RULE_PREVIEW_CONSUMED",
                    "影响预览已被使用，请重新预检"
            );
        }
        boolean matches = preview.generation() == expected.graphGeneration()
                && preview.baseConfigVersion().equals(draft.baseConfigVersion())
                && preview.action().equals(draft.action())
                && preview.sourceEntityIds().equals(draft.sourceEntityIds())
                && preview.matchAliases().equals(draft.matchAliases())
                && preview.targetNormalizedName().equals(
                draft.targetNormalizedName()
        )
                && preview.targetEntityType().equals(draft.targetEntityType());
        if (!matches) {
            throw conflict(
                    "GRAPH_PROPOSAL_PREVIEW_MISMATCH",
                    "影响预览与当前 Proposal Revision 不一致，请重新预检"
            );
        }
    }

    private ResolutionRulePreviewResponse response(
            UUID token,
            Instant expiresAt,
            ManifestState manifest,
            ResolutionDraft draft,
            List<EntitySelection> entities,
            ImpactCounts impact,
            List<ResolutionNotice> blockers,
            List<ResolutionNotice> warnings
    ) {
        return new ResolutionRulePreviewResponse(
                token,
                expiresAt,
                manifest.generation(),
                manifest.status(),
                manifest.configVersion(),
                manifest.sourceSetHash(),
                draft.action(),
                entities.stream().map(EntitySelection::view).toList(),
                new ResolutionImpact(
                        impact.mentions(),
                        impact.sourceSpans(),
                        impact.relationships(),
                        impact.relationshipEvidence(),
                        impact.communities(),
                        impact.documents(),
                        "NOT_AVAILABLE",
                        "离线图无法可靠推算未来查询命中范围"
                ),
                List.copyOf(blockers),
                List.copyOf(warnings)
        );
    }

    private ResolutionDraft draft(ResolutionRulePreviewRequest request) {
        String action = required(request.action(), "action")
                .toUpperCase(Locale.ROOT);
        List<UUID> ids = request.sourceEntityIds();
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw badRequest(
                    "GRAPH_RULE_SOURCE_DUPLICATE",
                    "来源实体不能重复选择"
            );
        }
        if ("MERGE".equals(action) && ids.size() < 2) {
            throw badRequest(
                    "GRAPH_MERGE_SOURCE_COUNT_INVALID",
                    "MERGE 需要选择 2-20 个来源实体"
            );
        }
        if ("SPLIT".equals(action) && ids.size() != 1) {
            throw badRequest(
                    "GRAPH_SPLIT_SOURCE_COUNT_INVALID",
                    "SPLIT 只能选择一个来源实体"
            );
        }
        List<String> aliases = request.matchAliases() == null
                ? List.of()
                : request.matchAliases().stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
        if ("SPLIT".equals(action) && aliases.isEmpty()) {
            throw badRequest(
                    "GRAPH_SPLIT_ALIASES_REQUIRED",
                    "SPLIT 需要选择至少一个当前 Alias"
            );
        }
        String targetName = required(
                request.targetCanonicalName(), "targetCanonicalName"
        );
        String targetNormalized = GraphAssembler.normalize(targetName);
        String targetType = GraphAssembler.normalizeType(
                request.targetEntityType()
        );
        if (targetNormalized.isEmpty() || targetType.isEmpty()) {
            throw badRequest(
                    "GRAPH_RULE_TARGET_INVALID",
                    "目标实体名称或类型无效"
            );
        }
        return new ResolutionDraft(
                required(request.baseConfigVersion(), "baseConfigVersion"),
                action,
                List.copyOf(ids),
                aliases,
                targetName,
                targetNormalized,
                targetType
        );
    }

    private List<ResolutionNotice> blockers(
            ManifestState manifest,
            GraphConfig base,
            ResolutionDraft draft,
            List<EntitySelection> entities
    ) {
        List<ResolutionNotice> blockers = new ArrayList<>();
        Set<String> selectedKeys = entities.stream()
                .map(EntitySelection::entityKey)
                .collect(java.util.stream.Collectors.toSet());
        boolean overlaps = generations.rules(base.resolutionRuleSetVersion())
                .stream()
                .map(ResolutionRule::sourceEntityKeys)
                .flatMap(List::stream)
                .anyMatch(selectedKeys::contains);
        if (overlaps) {
            blockers.add(new ResolutionNotice(
                    "GRAPH_RULE_SOURCE_ALREADY_GOVERNED",
                    "至少一个来源实体已被基础配置中的规则处理，请先核对规则顺序"
            ));
        }
        if ("SPLIT".equals(draft.action())) {
            Set<String> aliases = entities.getFirst().view().aliases().stream()
                    .map(GraphAssembler::normalize)
                    .collect(java.util.stream.Collectors.toSet());
            boolean invalid = draft.matchAliases().stream()
                    .map(GraphAssembler::normalize)
                    .anyMatch(alias -> !aliases.contains(alias));
            if (invalid) {
                blockers.add(new ResolutionNotice(
                        "GRAPH_SPLIT_ALIAS_STALE",
                        "拆分 Alias 已不存在或不属于所选实体，请重新选择"
                ));
            }
        }
        if (!manifest.configVersion().equals(base.version())) {
            blockers.add(new ResolutionNotice(
                    "GRAPH_RULE_BASE_CONFIG_MISMATCH",
                    "所选 Generation 与基础 GraphConfig 不一致"
            ));
        }
        return blockers;
    }

    private List<ResolutionNotice> warnings(List<EntitySelection> entities) {
        List<ResolutionNotice> warnings = new ArrayList<>();
        if (entities.stream().map(item -> item.view().entityType())
                .distinct().count() > 1) {
            warnings.add(new ResolutionNotice(
                    "GRAPH_RULE_ENTITY_TYPE_MIXED",
                    "来源实体类型不同，请确认目标类型能够表达合并后的语义"
            ));
        }
        return warnings;
    }

    private void insertPreview(
            UUID token,
            UUID actorId,
            ManifestState manifest,
            ResolutionDraft draft,
            List<EntitySelection> entities,
            ImpactCounts impact,
            List<ResolutionNotice> warnings,
            Instant expiresAt
    ) {
        jdbc.update(
                """
                INSERT INTO graph_resolution_previews (
                    token, actor_user_id, graph_generation,
                    base_config_version, source_set_hash, action,
                    source_entity_ids, source_entity_keys, match_aliases,
                    target_canonical_name, target_normalized_name,
                    target_entity_type, fact_hash,
                    mention_count, source_span_count, relationship_count,
                    relationship_evidence_count, community_count,
                    document_count, warnings, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                          CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """,
                token,
                actorId,
                manifest.generation(),
                draft.baseConfigVersion(),
                manifest.sourceSetHash(),
                draft.action(),
                json(draft.sourceEntityIds()),
                json(entities.stream().map(EntitySelection::entityKey).toList()),
                json(draft.matchAliases()),
                draft.targetCanonicalName(),
                draft.targetNormalizedName(),
                draft.targetEntityType(),
                factHash(manifest, draft, entities, impact),
                impact.mentions(),
                impact.sourceSpans(),
                impact.relationships(),
                impact.relationshipEvidence(),
                impact.communities(),
                impact.documents(),
                json(warnings),
                java.sql.Timestamp.from(expiresAt)
        );
    }

    private PreviewRow lockPreview(UUID token) {
        return jdbc.query(
                """
                SELECT token, actor_user_id, graph_generation,
                       base_config_version, source_set_hash, action,
                       source_entity_ids::text, source_entity_keys::text,
                       match_aliases::text, target_canonical_name,
                       target_normalized_name, target_entity_type, fact_hash,
                       expires_at, consumed_at
                FROM graph_resolution_previews
                WHERE token = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new PreviewRow(
                        resultSet.getObject("token", UUID.class),
                        resultSet.getObject("actor_user_id", UUID.class),
                        resultSet.getLong("graph_generation"),
                        resultSet.getString("base_config_version"),
                        resultSet.getString("source_set_hash"),
                        resultSet.getString("action"),
                        uuidList(resultSet.getString("source_entity_ids")),
                        stringList(resultSet.getString("source_entity_keys")),
                        stringList(resultSet.getString("match_aliases")),
                        resultSet.getString("target_canonical_name"),
                        resultSet.getString("target_normalized_name"),
                        resultSet.getString("target_entity_type"),
                        resultSet.getString("fact_hash"),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        resultSet.getTimestamp("consumed_at") == null
                                ? null
                                : resultSet.getTimestamp("consumed_at").toInstant()
                ),
                token
        ).stream().findFirst().orElseThrow(this::previewInvalid);
    }

    private ManifestState manifest(long generation, boolean lock) {
        String suffix = lock ? " FOR SHARE" : "";
        return jdbc.query(
                """
                SELECT graph_generation, graph_config_version,
                       status, source_set_hash
                FROM graph_manifests
                WHERE graph_generation = ?
                """ + suffix,
                (resultSet, rowNumber) -> new ManifestState(
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

    private void requirePreviewable(ManifestState manifest) {
        if (!Set.of("ACTIVE", "READY").contains(manifest.status())) {
            throw conflict(
                    "GRAPH_GENERATION_NOT_PREVIEWABLE",
                    "只有 ACTIVE 或 READY Generation 可以创建实体消歧预览"
            );
        }
    }

    private List<EntitySelection> selections(
            long generation,
            List<UUID> entityIds
    ) {
        String ids = json(entityIds);
        List<EntitySelection> found = jdbc.query(
                """
                WITH selected AS (
                    SELECT value::uuid AS entity_id
                    FROM jsonb_array_elements_text(CAST(? AS jsonb))
                ),
                valid_mentions AS (
                    SELECT mention.*
                    FROM graph_entity_mentions mention
                    JOIN selected ON selected.entity_id = mention.entity_id
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
                ),
                valid_evidence AS (
                    SELECT evidence.*
                    FROM graph_relationship_evidence evidence
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
                    WHERE evidence.graph_generation = ?
                )
                SELECT entity.id, entity.canonical_name, entity.entity_type,
                       entity.entity_type || '|' || entity.normalized_name AS entity_key,
                       (SELECT count(*) FROM valid_mentions mention
                         WHERE mention.entity_id = entity.id) AS mention_count,
                       (SELECT count(DISTINCT relationship.id)
                          FROM graph_relationships relationship
                          JOIN valid_evidence evidence
                            ON evidence.relationship_id = relationship.id
                         WHERE relationship.graph_generation = entity.graph_generation
                           AND (relationship.source_entity_id = entity.id
                             OR relationship.target_entity_id = entity.id)) AS relationship_count,
                       (SELECT count(*)
                          FROM graph_relationships relationship
                          JOIN valid_evidence evidence
                            ON evidence.relationship_id = relationship.id
                         WHERE relationship.graph_generation = entity.graph_generation
                           AND (relationship.source_entity_id = entity.id
                             OR relationship.target_entity_id = entity.id)) AS evidence_count,
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
                       ), ARRAY[]::text[]) AS aliases
                FROM graph_entities entity
                JOIN selected ON selected.entity_id = entity.id
                WHERE entity.graph_generation = ?
                  AND EXISTS (
                    SELECT 1 FROM valid_mentions mention
                    WHERE mention.entity_id = entity.id
                  )
                ORDER BY entity.entity_type, entity.normalized_name, entity.id
                """,
                (resultSet, rowNumber) -> {
                    List<String> aliases = textArray(resultSet.getArray("aliases"));
                    return new EntitySelection(
                            new ResolutionEntityView(
                                    resultSet.getObject("id", UUID.class),
                                    resultSet.getString("canonical_name"),
                                    resultSet.getString("entity_type"),
                                    aliases,
                                    resultSet.getInt("mention_count"),
                                    resultSet.getInt("relationship_count"),
                                    resultSet.getInt("evidence_count")
                            ),
                            resultSet.getString("entity_key")
                    );
                },
                ids, generation, generation, generation
        );
        Map<UUID, EntitySelection> byId = new LinkedHashMap<>();
        found.forEach(item -> byId.put(item.view().id(), item));
        return entityIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .toList();
    }

    private ImpactCounts impact(long generation, List<UUID> entityIds) {
        return jdbc.queryForObject(
                """
                WITH selected AS (
                    SELECT value::uuid AS entity_id
                    FROM jsonb_array_elements_text(CAST(? AS jsonb))
                ),
                valid_mentions AS (
                    SELECT mention.*
                    FROM graph_entity_mentions mention
                    JOIN selected ON selected.entity_id = mention.entity_id
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
                ),
                valid_evidence AS (
                    SELECT evidence.*
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
                      AND EXISTS (
                        SELECT 1
                        FROM selected
                        WHERE selected.entity_id =
                              relationship.source_entity_id
                           OR selected.entity_id =
                              relationship.target_entity_id
                      )
                ),
                affected_spans AS (
                    SELECT source_span_id FROM valid_mentions
                    UNION
                    SELECT source_span_id FROM valid_evidence
                ),
                affected_documents AS (
                    SELECT document_id FROM valid_mentions
                    UNION
                    SELECT document_id FROM valid_evidence
                )
                SELECT
                    (SELECT count(*) FROM valid_mentions) AS mention_count,
                    (SELECT count(*) FROM affected_spans) AS source_span_count,
                    (SELECT count(DISTINCT relationship_id)
                       FROM valid_evidence) AS relationship_count,
                    (SELECT count(*) FROM valid_evidence) AS evidence_count,
                    (SELECT count(DISTINCT member.community_id)
                       FROM graph_community_members member
                       JOIN selected ON selected.entity_id = member.entity_id
                      WHERE member.graph_generation = ?) AS community_count,
                    (SELECT count(*) FROM affected_documents) AS document_count
                """,
                (resultSet, rowNumber) -> new ImpactCounts(
                        resultSet.getInt("mention_count"),
                        resultSet.getInt("source_span_count"),
                        resultSet.getInt("relationship_count"),
                        resultSet.getInt("evidence_count"),
                        resultSet.getInt("community_count"),
                        resultSet.getInt("document_count")
                ),
                json(entityIds), generation, generation, generation
        );
    }

    private String factHash(
            ManifestState manifest,
            ResolutionDraft draft,
            List<EntitySelection> entities,
            ImpactCounts impact
    ) {
        StringBuilder value = new StringBuilder()
                .append(manifest.generation()).append('|')
                .append(manifest.configVersion()).append('|')
                .append(manifest.sourceSetHash()).append('|')
                .append(draft.action()).append('|')
                .append(draft.targetNormalizedName()).append('|')
                .append(draft.targetEntityType()).append('|')
                .append(String.join(",", draft.matchAliases())).append('|')
                .append(impact.mentions()).append('|')
                .append(impact.sourceSpans()).append('|')
                .append(impact.relationships()).append('|')
                .append(impact.relationshipEvidence()).append('|')
                .append(impact.communities()).append('|')
                .append(impact.documents());
        entities.forEach(entity -> value.append('|')
                .append(entity.view().id()).append(':')
                .append(entity.entityKey()).append(':')
                .append(entity.view().mentionCount()).append(':')
                .append(entity.view().relationshipCount()).append(':')
                .append(entity.view().relationshipEvidenceCount()).append(':')
                .append(String.join(",", entity.view().aliases())));
        return GraphAssembler.sha256(value.toString());
    }

    private void requireAllEntities(
            List<UUID> expected,
            List<EntitySelection> actual
    ) {
        if (actual.size() != expected.size()) {
            throw conflict(
                    "GRAPH_RULE_SOURCE_STALE",
                    "至少一个来源实体不存在、不可见或 Projection 已过期"
            );
        }
    }

    private static List<String> textArray(Array value) throws SQLException {
        if (value == null) {
            return List.of();
        }
        return List.of((String[]) value.getArray());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Graph resolution value cannot be serialized",
                    exception
            );
        }
    }

    private List<String> stringList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored graph resolution preview is invalid",
                    exception
            );
        }
    }

    private List<UUID> uuidList(String value) {
        return stringList(value).stream().map(UUID::fromString).toList();
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw badRequest(
                    "GRAPH_FIELD_REQUIRED",
                    field + " 不能为空"
            );
        }
        return normalized;
    }

    private ApiException previewInvalid() {
        return conflict(
                "GRAPH_RULE_PREVIEW_INVALID",
                "影响预览不存在或已失效"
        );
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private record ManifestState(
            long generation,
            String configVersion,
            String status,
            String sourceSetHash
    ) {
    }

    private record ResolutionDraft(
            String baseConfigVersion,
            String action,
            List<UUID> sourceEntityIds,
            List<String> matchAliases,
            String targetCanonicalName,
            String targetNormalizedName,
            String targetEntityType
    ) {
    }

    private record EntitySelection(
            ResolutionEntityView view,
            String entityKey
    ) {
    }

    private record ImpactCounts(
            int mentions,
            int sourceSpans,
            int relationships,
            int relationshipEvidence,
            int communities,
            int documents
    ) {
    }

    private record PreviewRow(
            UUID token,
            UUID actorId,
            long generation,
            String baseConfigVersion,
            String sourceSetHash,
            String action,
            List<UUID> sourceEntityIds,
            List<String> sourceEntityKeys,
            List<String> matchAliases,
            String targetCanonicalName,
            String targetNormalizedName,
            String targetEntityType,
            String factHash,
            Instant expiresAt,
            Instant consumedAt
    ) {
    }
}
