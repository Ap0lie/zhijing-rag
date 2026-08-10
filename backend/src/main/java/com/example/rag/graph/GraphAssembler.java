package com.example.rag.graph;

import com.example.rag.graph.CommunityDetectionClient.CommunityEdge;
import com.example.rag.graph.GraphBuildContracts.AdjacencyFact;
import com.example.rag.graph.GraphBuildContracts.AliasFact;
import com.example.rag.graph.GraphBuildContracts.AliasEvidenceFact;
import com.example.rag.graph.GraphBuildContracts.ChildSource;
import com.example.rag.graph.GraphBuildContracts.CommunityClaimFact;
import com.example.rag.graph.GraphBuildContracts.CommunityFact;
import com.example.rag.graph.GraphBuildContracts.CommunityMemberFact;
import com.example.rag.graph.GraphBuildContracts.EntityFact;
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
import com.example.rag.graph.GraphExtractionProvider.ExtractedEntity;
import com.example.rag.graph.GraphExtractionProvider.ExtractedMention;
import com.example.rag.graph.GraphExtractionProvider.ExtractedRelationship;
import com.example.rag.graph.GraphExtractionProvider.ExtractionResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class GraphAssembler {

    private static final Pattern MULTIPLE_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TYPE_CHARACTER = Pattern.compile("[^A-Z0-9_]");

    private final CommunityDetectionClient communities;
    private final GraphProperties properties;

    GraphAssembler(
            CommunityDetectionClient communities,
            GraphProperties properties
    ) {
        this.communities = communities;
        this.properties = properties;
    }

    GraphBuild assemble(
            long generation,
            GraphConfig config,
            List<SourceDocument> documents,
            Map<UUID, ParentExtraction> extractions,
            List<ResolutionRule> rules,
            long cacheHits,
            long modelCalls
    ) {
        Map<String, MutableEntity> entityByKey = new LinkedHashMap<>();
        Map<String, MutableRelationship> relationshipByKey =
                new LinkedHashMap<>();

        for (SourceDocument document : documents) {
            for (ParentSource parent : document.parents()) {
                ParentExtraction extraction = extractions.get(parent.id());
                if (extraction == null) {
                    throw new IllegalStateException(
                            "Missing graph extraction for parent " + parent.id()
                    );
                }
                assembleParent(
                        generation,
                        document,
                        parent,
                        safe(extraction.result()),
                        rules,
                        entityByKey,
                        relationshipByKey
                );
                if (entityByKey.size() > properties.getMaxEntities()
                        || relationshipByKey.size()
                        > properties.getMaxRelationships()) {
                    throw new GraphBuildException(
                            "GRAPH_BUILD_LIMIT_EXCEEDED",
                            "Graph Generation 超过实体或关系安全上限"
                    );
                }
            }
        }

        List<EntityFact> entities = entityByKey.values().stream()
                .map(MutableEntity::fact)
                .toList();
        List<AliasFact> aliases = entityByKey.values().stream()
                .flatMap(entity -> entity.aliases.values().stream().map(alias ->
                        new AliasFact(entity.id, alias.value(), alias.normalized())
                ))
                .toList();
        List<AliasEvidenceFact> aliasEvidence = entityByKey.values().stream()
                .flatMap(entity -> entity.aliasEvidence.entrySet().stream()
                        .flatMap(entry -> entry.getValue().stream().map(mentionId ->
                                new AliasEvidenceFact(
                                        entity.id,
                                        entry.getKey(),
                                        mentionId
                                )
                        )))
                .toList();
        List<MentionFact> mentions = entityByKey.values().stream()
                .flatMap(entity -> entity.mentions.values().stream())
                .toList();
        List<RelationshipFact> relationships = relationshipByKey.values().stream()
                .map(MutableRelationship::fact)
                .toList();
        List<RelationshipEvidenceFact> evidence =
                relationshipByKey.values().stream()
                        .flatMap(relationship ->
                                relationship.evidence.values().stream())
                        .toList();
        List<AdjacencyFact> adjacency = relationships.stream()
                .flatMap(relationship -> List.of(
                        new AdjacencyFact(
                                relationship.sourceEntityId(),
                                relationship.targetEntityId(),
                                relationship.id(),
                                "OUT",
                                1.0
                        ),
                        new AdjacencyFact(
                                relationship.targetEntityId(),
                                relationship.sourceEntityId(),
                                relationship.id(),
                                "IN",
                                1.0
                        )
                ).stream())
                .toList();

        CommunityBuild communityBuild = communities(
                generation,
                config,
                entityByKey,
                relationshipByKey
        );
        List<ProjectionFact> projections = documents.stream()
                .map(document -> projection(document, extractions))
                .toList();
        return new GraphBuild(
                generation,
                entities,
                aliases,
                aliasEvidence,
                mentions,
                relationships,
                evidence,
                adjacency,
                communityBuild.communities(),
                communityBuild.members(),
                communityBuild.claims(),
                projections,
                cacheHits,
                modelCalls
        );
    }

    private void assembleParent(
            long generation,
            SourceDocument document,
            ParentSource parent,
            ExtractionResult result,
            List<ResolutionRule> rules,
            Map<String, MutableEntity> entityByKey,
            Map<String, MutableRelationship> relationshipByKey
    ) {
        Map<UUID, ChildSource> childById = new LinkedHashMap<>();
        parent.children().forEach(child -> childById.put(child.id(), child));
        Map<String, String> localEntityByName = new LinkedHashMap<>();
        Set<String> ambiguousNames = new LinkedHashSet<>();

        for (ExtractedEntity extracted : list(result.entities())) {
            ValidEntity valid = validEntity(extracted, childById);
            if (valid == null) {
                continue;
            }
            ResolvedIdentity identity = resolve(valid, rules);
            MutableEntity entity = entityByKey.computeIfAbsent(
                    identity.key(),
                    key -> new MutableEntity(
                            uuid("entity", generation, key),
                            identity.canonicalName(),
                            identity.normalizedName(),
                            identity.entityType(),
                            concise(extracted.description(), 2000)
                    )
            );
            List<UUID> mentionIds = new ArrayList<>();
            for (ValidMention mention : valid.mentions()) {
                String mentionKey = entity.id + "|" + mention.child().id()
                        + "|" + mention.start() + "|" + mention.end();
                MentionFact fact = entity.mentions.computeIfAbsent(
                        mentionKey,
                        ignored -> new MentionFact(
                        uuid("mention", generation, mentionKey),
                        entity.id,
                        document.documentId(),
                        document.revisionId(),
                        parent.id(),
                        mention.child().id(),
                        mention.span().id(),
                        mention.surfaceText(),
                        mention.start(),
                        mention.end()
                ));
                mentionIds.add(fact.id());
            }
            entity.addAlias(identity.canonicalName(), mentionIds);
            entity.addAlias(extracted.canonicalName(), mentionIds);
            list(extracted.aliases()).forEach(alias ->
                    entity.addAlias(alias, mentionIds));
            String localName = normalize(extracted.canonicalName());
            String previous = localEntityByName.putIfAbsent(
                    localName,
                    identity.key()
            );
            if (previous != null && !previous.equals(identity.key())) {
                ambiguousNames.add(localName);
            }
        }

        for (ExtractedRelationship extracted : list(result.relationships())) {
            String sourceName = normalize(extracted.sourceCanonicalName());
            String targetName = normalize(extracted.targetCanonicalName());
            if (ambiguousNames.contains(sourceName)
                    || ambiguousNames.contains(targetName)) {
                continue;
            }
            String sourceKey = localEntityByName.get(sourceName);
            String targetKey = localEntityByName.get(targetName);
            if (sourceKey == null
                    || targetKey == null
                    || sourceKey.equals(targetKey)) {
                continue;
            }
            ChildSource child = childById.get(extracted.childId());
            String evidenceText = trimmed(extracted.evidenceText(), 2000);
            AnchoredRange anchor = child == null
                    ? null
                    : anchoredRange(child, evidenceText);
            if (child == null
                    || evidenceText.isEmpty()
                    || anchor == null) {
                continue;
            }
            String relationshipType = normalizeType(
                    extracted.relationshipType()
            );
            if (relationshipType.isEmpty()) {
                continue;
            }
            MutableEntity source = entityByKey.get(sourceKey);
            MutableEntity target = entityByKey.get(targetKey);
            String relationshipKey = source.id + "|" + target.id
                    + "|" + relationshipType;
            MutableRelationship relationship =
                    relationshipByKey.computeIfAbsent(
                            relationshipKey,
                            ignored -> new MutableRelationship(
                                    uuid(
                                            "relationship",
                                            generation,
                                            relationshipKey
                                    ),
                                    source.id,
                                    target.id,
                                    relationshipType,
                                    concise(extracted.description(), 2000)
                            )
                    );
            String evidenceHash = sha256(evidenceText);
            String evidenceKey = relationship.id + "|" + child.id()
                    + "|" + evidenceHash;
            relationship.evidence.putIfAbsent(
                    evidenceKey,
                    new RelationshipEvidenceFact(
                            uuid("relationship-evidence", generation, evidenceKey),
                            relationship.id,
                            document.documentId(),
                            document.revisionId(),
                            parent.id(),
                            child.id(),
                            anchor.span().id(),
                            evidenceText,
                            evidenceHash,
                            anchor.start(),
                            anchor.end()
                    )
            );
        }
    }

    private CommunityBuild communities(
            long generation,
            GraphConfig config,
            Map<String, MutableEntity> entityByKey,
            Map<String, MutableRelationship> relationshipByKey
    ) {
        List<Map.Entry<String, MutableEntity>> orderedEntities =
                entityByKey.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
        Map<UUID, String> anonymousByEntity = new LinkedHashMap<>();
        List<String> nodes = new ArrayList<>();
        for (int index = 0; index < orderedEntities.size(); index++) {
            String anonymous = "n" + index;
            nodes.add(anonymous);
            anonymousByEntity.put(
                    orderedEntities.get(index).getValue().id,
                    anonymous
            );
        }
        Map<String, Double> edgeWeights = new LinkedHashMap<>();
        for (MutableRelationship relationship : relationshipByKey.values()) {
            String source = anonymousByEntity.get(relationship.sourceEntityId);
            String target = anonymousByEntity.get(relationship.targetEntityId);
            if (source == null || target == null) {
                continue;
            }
            String edgeKey = source.compareTo(target) <= 0
                    ? source + "|" + target
                    : target + "|" + source;
            edgeWeights.merge(edgeKey, 1.0, Double::sum);
        }
        List<CommunityEdge> edges = edgeWeights.entrySet().stream()
                .map(entry -> {
                    String[] endpoints = entry.getKey().split("\\|", 2);
                    return new CommunityEdge(
                            endpoints[0],
                            endpoints[1],
                            entry.getValue()
                    );
                })
                .toList();
        Map<String, Integer> assignment = communities.detect(
                nodes,
                edges,
                config.communityAlgorithmVersion(),
                config.communitySeed(),
                config.communityResolution()
        );

        Map<Integer, List<MutableEntity>> entitiesByCommunity =
                new LinkedHashMap<>();
        for (MutableEntity entity : entityByKey.values()) {
            Integer key = assignment.get(anonymousByEntity.get(entity.id));
            if (key == null) {
                throw new IllegalStateException(
                        "Community assignment missing for entity " + entity.id
                );
            }
            entitiesByCommunity.computeIfAbsent(
                    key,
                    ignored -> new ArrayList<>()
            ).add(entity);
        }

        List<CommunityFact> communityFacts = new ArrayList<>();
        List<CommunityMemberFact> members = new ArrayList<>();
        Map<UUID, UUID> communityByEntity = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<MutableEntity>> entry :
                entitiesByCommunity.entrySet()) {
            int key = entry.getKey();
            List<MutableEntity> values = entry.getValue().stream()
                    .sorted(Comparator.comparing(entity ->
                            entity.normalizedName))
                    .toList();
            UUID communityId = uuid(
                    "community",
                    generation,
                    Integer.toString(key)
            );
            String title = values.stream()
                    .limit(3)
                    .map(entity -> entity.canonicalName)
                    .reduce((left, right) -> left + " · " + right)
                    .orElse("Community " + key);
            long relationCount = relationshipByKey.values().stream()
                    .filter(relationship -> values.stream().anyMatch(entity ->
                            entity.id.equals(relationship.sourceEntityId)))
                    .count();
            communityFacts.add(new CommunityFact(
                    communityId,
                    key,
                    title,
                    values.size() + " 个实体 · "
                            + relationCount + " 条关系",
                    values.size()
            ));
            for (int index = 0; index < values.size(); index++) {
                MutableEntity entity = values.get(index);
                communityByEntity.put(entity.id, communityId);
                members.add(new CommunityMemberFact(
                        communityId,
                        entity.id,
                        index
                ));
            }
        }

        List<CommunityClaimFact> claims = new ArrayList<>();
        for (MutableRelationship relationship : relationshipByKey.values()) {
            UUID communityId = communityByEntity.get(
                    relationship.sourceEntityId
            );
            if (communityId == null) {
                continue;
            }
            MutableEntity source = entityById(
                    entityByKey,
                    relationship.sourceEntityId
            );
            MutableEntity target = entityById(
                    entityByKey,
                    relationship.targetEntityId
            );
            String claimText = relationship.description == null
                    || relationship.description.isBlank()
                    ? source.canonicalName + " "
                    + relationship.relationshipType + " "
                    + target.canonicalName
                    : relationship.description;
            for (RelationshipEvidenceFact evidence :
                    relationship.evidence.values()) {
                String key = communityId + "|" + evidence.id();
                claims.add(new CommunityClaimFact(
                        uuid("community-claim", generation, key),
                        communityId,
                        relationship.id,
                        evidence.id(),
                        claimText
                ));
            }
        }
        return new CommunityBuild(
                List.copyOf(communityFacts),
                List.copyOf(members),
                List.copyOf(claims)
        );
    }

    private ProjectionFact projection(
            SourceDocument document,
            Map<UUID, ParentExtraction> extractions
    ) {
        List<UUID> artifactIds = new ArrayList<>();
        StringBuilder input = new StringBuilder()
                .append(document.documentId())
                .append('|')
                .append(document.revisionId())
                .append('|')
                .append(document.aclVersion());
        for (ParentSource parent : document.parents()) {
            ParentExtraction extraction = extractions.get(parent.id());
            if (extraction == null) {
                throw new IllegalStateException(
                        "Missing projection extraction for parent " + parent.id()
                );
            }
            artifactIds.add(extraction.artifactId());
            input.append('|').append(extraction.inputHash());
        }
        return new ProjectionFact(
                document.documentId(),
                document.revisionId(),
                document.aclVersion(),
                sha256(input.toString()),
                List.copyOf(artifactIds)
        );
    }

    private ValidEntity validEntity(
            ExtractedEntity extracted,
            Map<UUID, ChildSource> childById
    ) {
        if (extracted == null) {
            return null;
        }
        String canonicalName = trimmed(extracted.canonicalName(), 300);
        String normalizedName = normalize(canonicalName);
        String entityType = normalizeType(extracted.entityType());
        if (canonicalName.isEmpty()
                || normalizedName.isEmpty()
                || entityType.isEmpty()) {
            return null;
        }
        List<ValidMention> mentions = new ArrayList<>();
        for (ExtractedMention mention : list(extracted.mentions())) {
            if (mention == null || mention.childId() == null) {
                continue;
            }
            ChildSource child = childById.get(mention.childId());
            String surface = trimmed(mention.surfaceText(), 2000);
            AnchoredRange anchor = child == null
                    ? null
                    : anchoredRange(child, surface);
            if (child == null
                    || surface.isEmpty()
                    || anchor == null) {
                continue;
            }
            mentions.add(new ValidMention(
                    child,
                    anchor.span(),
                    surface,
                    anchor.start(),
                    anchor.end()
            ));
        }
        if (mentions.isEmpty()) {
            return null;
        }
        return new ValidEntity(
                canonicalName,
                normalizedName,
                entityType,
                List.copyOf(mentions),
                list(extracted.aliases())
        );
    }

    private ResolvedIdentity resolve(
            ValidEntity entity,
            List<ResolutionRule> rules
    ) {
        ResolvedIdentity identity = new ResolvedIdentity(
                entity.entityType() + "|" + entity.normalizedName(),
                entity.canonicalName(),
                entity.normalizedName(),
                entity.entityType()
        );
        Set<String> surfaceForms = new LinkedHashSet<>();
        surfaceForms.add(entity.normalizedName());
        entity.mentions().forEach(mention ->
                surfaceForms.add(normalize(mention.surfaceText())));
        entity.aliases().forEach(alias -> surfaceForms.add(normalize(alias)));

        for (ResolutionRule rule : rules) {
            if (!rule.sourceEntityKeys().contains(identity.key())) {
                continue;
            }
            if ("SPLIT".equals(rule.action())
                    && rule.matchAliases().stream()
                    .map(GraphAssembler::normalize)
                    .noneMatch(surfaceForms::contains)) {
                continue;
            }
            identity = new ResolvedIdentity(
                    rule.targetEntityType()
                            + "|" + rule.targetNormalizedName(),
                    rule.targetCanonicalName(),
                    rule.targetNormalizedName(),
                    rule.targetEntityType()
            );
        }
        return identity;
    }

    private static MutableEntity entityById(
            Map<String, MutableEntity> entities,
            UUID id
    ) {
        return entities.values().stream()
                .filter(entity -> entity.id.equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static AnchoredRange anchoredRange(
            ChildSource child,
            String text
    ) {
        if (text.isEmpty()) {
            return null;
        }
        int from = 0;
        while (from < child.text().length()) {
            int start = child.text().indexOf(text, from);
            if (start < 0) {
                return null;
            }
            int end = start + text.length();
            for (SpanSource span : child.spans()) {
                if (start >= span.chunkStartOffset()
                        && end <= span.chunkEndOffset()) {
                    return new AnchoredRange(start, end, span);
                }
            }
            from = start + 1;
        }
        return null;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        return MULTIPLE_WHITESPACE.matcher(normalized).replaceAll(" ");
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static UUID uuid(String namespace, long generation, String value) {
        return UUID.nameUUIDFromBytes(
                (namespace + "|" + generation + "|" + value)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    static String normalizeType(String value) {
        if (value == null) {
            return "";
        }
        String normalized = TYPE_CHARACTER.matcher(
                value.trim().toUpperCase(Locale.ROOT)
        ).replaceAll("_");
        String bounded = normalized.substring(
                0,
                Math.min(normalized.length(), 64)
        );
        return bounded.codePoints().anyMatch(Character::isLetterOrDigit)
                ? bounded
                : "";
    }

    private static String trimmed(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), max));
    }

    private static String concise(String value, int max) {
        String result = trimmed(value, max);
        return result.isEmpty() ? null : result;
    }

    private static ExtractionResult safe(ExtractionResult result) {
        return result == null
                ? new ExtractionResult(List.of(), List.of())
                : result;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    record ParentExtraction(
            UUID artifactId,
            String inputHash,
            ExtractionResult result
    ) {
    }

    private record ValidMention(
            ChildSource child,
            SpanSource span,
            String surfaceText,
            int start,
            int end
    ) {
    }

    private record AnchoredRange(
            int start,
            int end,
            SpanSource span
    ) {
    }

    private record ValidEntity(
            String canonicalName,
            String normalizedName,
            String entityType,
            List<ValidMention> mentions,
            List<String> aliases
    ) {
    }

    private record ResolvedIdentity(
            String key,
            String canonicalName,
            String normalizedName,
            String entityType
    ) {
    }

    private record AliasValue(String value, String normalized) {
    }

    private record CommunityBuild(
            List<CommunityFact> communities,
            List<CommunityMemberFact> members,
            List<CommunityClaimFact> claims
    ) {
    }

    private static final class MutableEntity {

        private final UUID id;
        private final String canonicalName;
        private final String normalizedName;
        private final String entityType;
        private final String description;
        private final Map<String, AliasValue> aliases = new LinkedHashMap<>();
        private final Map<String, Set<UUID>> aliasEvidence =
                new LinkedHashMap<>();
        private final Map<String, MentionFact> mentions = new LinkedHashMap<>();

        private MutableEntity(
                UUID id,
                String canonicalName,
                String normalizedName,
                String entityType,
                String description
        ) {
            this.id = id;
            this.canonicalName = canonicalName;
            this.normalizedName = normalizedName;
            this.entityType = entityType;
            this.description = description;
        }

        private void addAlias(String value, List<UUID> mentionIds) {
            String alias = trimmed(value, 300);
            String normalized = normalize(alias);
            if (!alias.isEmpty() && !normalized.isEmpty()) {
                aliases.putIfAbsent(normalized, new AliasValue(alias, normalized));
                aliasEvidence.computeIfAbsent(
                        normalized,
                        ignored -> new LinkedHashSet<>()
                ).addAll(mentionIds);
            }
        }

        private EntityFact fact() {
            return new EntityFact(
                    id,
                    canonicalName,
                    normalizedName,
                    entityType,
                    description
            );
        }
    }

    private static final class MutableRelationship {

        private final UUID id;
        private final UUID sourceEntityId;
        private final UUID targetEntityId;
        private final String relationshipType;
        private final String description;
        private final Map<String, RelationshipEvidenceFact> evidence =
                new LinkedHashMap<>();

        private MutableRelationship(
                UUID id,
                UUID sourceEntityId,
                UUID targetEntityId,
                String relationshipType,
                String description
        ) {
            this.id = id;
            this.sourceEntityId = sourceEntityId;
            this.targetEntityId = targetEntityId;
            this.relationshipType = relationshipType;
            this.description = description;
        }

        private RelationshipFact fact() {
            return new RelationshipFact(
                    id,
                    sourceEntityId,
                    targetEntityId,
                    relationshipType,
                    description
            );
        }
    }
}
