package com.example.rag.graph;

import com.example.rag.graph.CommunityDetectionClient.CommunityEdge;
import com.example.rag.graph.GlobalGraphContracts.ArtifactSource;
import com.example.rag.graph.GlobalGraphContracts.BuildResult;
import com.example.rag.graph.GlobalGraphContracts.ClaimFact;
import com.example.rag.graph.GlobalGraphContracts.EvidenceAnchor;
import com.example.rag.graph.GlobalGraphContracts.GlobalConfig;
import com.example.rag.graph.GlobalGraphContracts.ReportFact;
import com.example.rag.graph.GlobalReportProvider.Claim;
import com.example.rag.graph.GlobalReportProvider.CommunityInput;
import com.example.rag.graph.GlobalReportProvider.EvidenceInput;
import com.example.rag.graph.GlobalReportProvider.Report;
import com.example.rag.graph.GraphExtractionProvider.ExtractedRelationship;
import com.example.rag.graph.GraphExtractionProvider.ExtractionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GlobalGraphAssembler {

    private static final int MAX_REPORTS = 100;
    private static final int MAX_CLAIMS_PER_REPORT = 8;

    private final ObjectMapper json;
    private final CommunityDetectionClient communities;
    private final GlobalReportProvider reports;

    GlobalGraphAssembler(
            ObjectMapper json,
            CommunityDetectionClient communities,
            GlobalReportProvider reports
    ) {
        this.json = json;
        this.communities = communities;
        this.reports = reports;
    }

    BuildResult assemble(
            long generation,
            GlobalConfig config,
            List<ArtifactSource> artifacts,
            List<EvidenceAnchor> anchors
    ) {
        Map<AnchorKey, List<EvidenceAnchor>> anchorsByText =
                new LinkedHashMap<>();
        anchors.stream()
                .sorted(Comparator
                        .comparing(EvidenceAnchor::childChunkId)
                        .thenComparing(EvidenceAnchor::id))
                .forEach(anchor -> anchorsByText.computeIfAbsent(
                        new AnchorKey(
                                anchor.childChunkId(),
                                anchor.evidenceText().trim()
                        ),
                        ignored -> new ArrayList<>()
                ).add(anchor));

        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, PublicRelationship> relationships =
                new LinkedHashMap<>();
        for (ArtifactSource artifact : artifacts) {
            ExtractionResult extracted = parse(artifact.outputJson());
            for (ExtractedRelationship relationship :
                    list(extracted.relationships())) {
                if (relationship == null
                        || relationship.childId() == null
                        || blank(relationship.sourceCanonicalName())
                        || blank(relationship.targetCanonicalName())
                        || blank(relationship.relationshipType())
                        || blank(relationship.evidenceText())) {
                    continue;
                }
                List<EvidenceAnchor> matches = anchorsByText.getOrDefault(
                        new AnchorKey(
                                relationship.childId(),
                                relationship.evidenceText().trim()
                        ),
                        List.of()
                );
                EvidenceAnchor anchor = matches.stream()
                        .filter(item -> item.documentId().equals(
                                artifact.documentId()
                        ))
                        .filter(item -> item.revisionId().equals(
                                artifact.revisionId()
                        ))
                        .findFirst()
                        .orElse(null);
                if (anchor == null) {
                    continue;
                }
                String source = GraphAssembler.normalize(
                        relationship.sourceCanonicalName()
                );
                String target = GraphAssembler.normalize(
                        relationship.targetCanonicalName()
                );
                if (source.isBlank()
                        || target.isBlank()
                        || source.equals(target)) {
                    continue;
                }
                labels.putIfAbsent(
                        source,
                        relationship.sourceCanonicalName().trim()
                );
                labels.putIfAbsent(
                        target,
                        relationship.targetCanonicalName().trim()
                );
                String type = GraphAssembler.normalizeType(
                        relationship.relationshipType()
                );
                String key = source + "|" + target + "|" + type
                        + "|" + anchor.id();
                relationships.putIfAbsent(
                        key,
                        new PublicRelationship(
                                source,
                                target,
                                type,
                                anchor
                        )
                );
            }
        }
        if (relationships.isEmpty()) {
            throw new GlobalReportException(
                    "GLOBAL_PUBLIC_GRAPH_EMPTY",
                    "当前 ALL_USERS 子图没有可发布的关系 Evidence"
            );
        }

        Map<String, Integer> assignment = detectCommunities(
                config,
                labels.keySet(),
                relationships.values()
        );
        Map<Integer, List<PublicRelationship>> byCommunity =
                new LinkedHashMap<>();
        relationships.values().stream()
                .sorted(Comparator
                        .comparing(PublicRelationship::source)
                        .thenComparing(PublicRelationship::target)
                        .thenComparing(PublicRelationship::type)
                        .thenComparing(item -> item.anchor().id()))
                .forEach(relationship -> {
                    Integer community = assignment.get(
                            relationship.source()
                    );
                    if (community != null) {
                        byCommunity.computeIfAbsent(
                                community,
                                ignored -> new ArrayList<>()
                        ).add(relationship);
                    }
                });

        List<Map.Entry<Integer, List<PublicRelationship>>> selected =
                byCommunity.entrySet().stream()
                        .sorted(Comparator
                                .<Map.Entry<Integer,
                                        List<PublicRelationship>>>
                                        comparingInt(entry ->
                                        entry.getValue().size())
                                .reversed()
                                .thenComparingInt(Map.Entry::getKey))
                        .limit(MAX_REPORTS)
                        .toList();
        List<ReportFact> facts = new ArrayList<>();
        long modelCalls = 0;
        for (Map.Entry<Integer, List<PublicRelationship>> entry :
                selected) {
            List<EvidenceInput> inputs = entry.getValue().stream()
                    .map(relationship -> input(relationship, labels))
                    .toList();
            Report report = reports.summarize(new CommunityInput(
                    entry.getKey(),
                    inputs
            ));
            modelCalls++;
            ReportFact fact = validated(
                    generation,
                    entry.getKey(),
                    report,
                    entry.getValue()
            );
            if (fact != null) {
                facts.add(fact);
            }
        }
        if (facts.isEmpty()) {
            throw new GlobalReportException(
                    "GLOBAL_REPORTS_EMPTY",
                    "公共 Community 没有生成可验证的 Evidence-backed Claim"
            );
        }
        return new BuildResult(List.copyOf(facts), modelCalls);
    }

    private Map<String, Integer> detectCommunities(
            GlobalConfig config,
            Set<String> nodeKeys,
            java.util.Collection<PublicRelationship> relationships
    ) {
        List<String> orderedKeys = nodeKeys.stream().sorted().toList();
        Map<String, String> anonymous = new LinkedHashMap<>();
        List<String> nodes = new ArrayList<>();
        for (int index = 0; index < orderedKeys.size(); index++) {
            String value = "n" + index;
            nodes.add(value);
            anonymous.put(orderedKeys.get(index), value);
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        relationships.forEach(relationship -> {
            String source = anonymous.get(relationship.source());
            String target = anonymous.get(relationship.target());
            if (source == null || target == null) {
                return;
            }
            String key = source.compareTo(target) <= 0
                    ? source + "|" + target
                    : target + "|" + source;
            weights.merge(key, 1.0, Double::sum);
        });
        List<CommunityEdge> edges = weights.entrySet().stream()
                .map(entry -> {
                    String[] endpoints = entry.getKey().split("\\|", 2);
                    return new CommunityEdge(
                            endpoints[0],
                            endpoints[1],
                            entry.getValue()
                    );
                })
                .toList();
        Map<String, Integer> detected = communities.detect(
                nodes,
                edges,
                config.communityAlgorithmVersion(),
                config.communitySeed(),
                config.communityResolution()
        );
        Map<String, Integer> result = new LinkedHashMap<>();
        anonymous.forEach((key, value) -> result.put(key, detected.get(value)));
        return Map.copyOf(result);
    }

    private ReportFact validated(
            long generation,
            int community,
            Report report,
            List<PublicRelationship> relationships
    ) {
        if (report == null
                || blank(report.title())
                || blank(report.summary())
                || list(report.claims()).isEmpty()
                || list(report.claims()).size() > MAX_CLAIMS_PER_REPORT) {
            return null;
        }
        Map<UUID, EvidenceAnchor> allowed = new LinkedHashMap<>();
        relationships.forEach(relationship ->
                allowed.put(relationship.anchor().id(), relationship.anchor())
        );
        UUID reportId = uuid(
                "global-report",
                generation + "|" + community
        );
        List<ClaimFact> claims = new ArrayList<>();
        Set<UUID> usedEvidence = new LinkedHashSet<>();
        int order = 0;
        for (Claim claim : list(report.claims())) {
            if (claim == null || blank(claim.text())) {
                continue;
            }
            List<EvidenceAnchor> evidence = list(claim.evidenceIds()).stream()
                    .distinct()
                    .map(allowed::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (evidence.isEmpty()
                    || evidence.size() != list(claim.evidenceIds()).stream()
                    .distinct()
                    .count()) {
                continue;
            }
            UUID claimId = uuid(
                    "global-claim",
                    reportId + "|" + order + "|" + claim.text().trim()
            );
            claims.add(new ClaimFact(
                    claimId,
                    order++,
                    concise(claim.text(), 2_000),
                    evidence
            ));
            evidence.forEach(item -> usedEvidence.add(item.id()));
        }
        if (claims.isEmpty() || usedEvidence.isEmpty()) {
            return null;
        }
        String title = concise(report.title(), 300);
        String summary = concise(report.summary(), 2_000);
        String searchText = title + "\n" + summary + "\n"
                + String.join(
                "\n",
                claims.stream().map(ClaimFact::text).toList()
        );
        return new ReportFact(
                reportId,
                community,
                title,
                summary,
                searchText,
                GraphAssembler.sha256(searchText),
                tokenCount(searchText),
                List.copyOf(claims)
        );
    }

    private static EvidenceInput input(
            PublicRelationship relationship,
            Map<String, String> labels
    ) {
        EvidenceAnchor anchor = relationship.anchor();
        return new EvidenceInput(
                anchor.id(),
                labels.get(relationship.source()),
                labels.get(relationship.target()),
                relationship.type(),
                anchor.evidenceText(),
                anchor.documentTitle(),
                anchor.startPage(),
                anchor.endPage()
        );
    }

    private ExtractionResult parse(String value) {
        try {
            return json.readValue(value, ExtractionResult.class);
        } catch (JsonProcessingException exception) {
            throw new GlobalReportException(
                    "GLOBAL_ARTIFACT_INVALID",
                    "公共子图包含无效的抽取 Artifact",
                    exception
            );
        }
    }

    private static UUID uuid(String namespace, String value) {
        return UUID.nameUUIDFromBytes(
                (namespace + "|" + value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String concise(String value, int maximum) {
        String result = value == null ? "" : value.trim();
        return result.substring(0, Math.min(result.length(), maximum));
    }

    private static int tokenCount(String value) {
        return Math.max(1, value.codePointCount(0, value.length()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private record AnchorKey(UUID childId, String text) {
    }

    private record PublicRelationship(
            String source,
            String target,
            String type,
            EvidenceAnchor anchor
    ) {
    }
}
