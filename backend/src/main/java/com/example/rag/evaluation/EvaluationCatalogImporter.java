package com.example.rag.evaluation;

import com.example.rag.evaluation.EvaluationContracts.CaseSeed;
import com.example.rag.evaluation.EvaluationContracts.DatasetSeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class EvaluationCatalogImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            EvaluationCatalogImporter.class
    );

    private static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry(
                    "retrieval-golden", "Retrieval Golden",
                    "公开中英检索候选与确定性 Evidence 契约",
                    "retrieval-golden-v2", "RETRIEVAL", "PROJECT",
                    List.of("retrieval-golden/v2/dataset.json")
            ),
            new CatalogEntry(
                    "graph-local-golden", "Local Graph Golden",
                    "2WikiMultiHopQA、MultiHop-RAG 与 XRAG 中文候选",
                    "graph-local-golden-v1", "LOCAL_GRAPH", "MIXED",
                    List.of(
                            "graph-local-golden/v1/manifest.json",
                            "graph-local-golden/v1/2wiki.json",
                            "graph-local-golden/v1/multihop-rag.json",
                            "graph-local-golden/v1/xrag-zh.json"
                    )
            ),
            new CatalogEntry(
                    "graph-global-golden", "Global Graph Golden",
                    "Phase 10R 已固化并映射的公开跨文档候选",
                    "graph-global-golden-v1", "GLOBAL_GRAPH", "MIXED",
                    List.of(
                            "graph-global-golden/v1/phase10r-corpus-manifest.json"
                    )
            ),
            new CatalogEntry(
                    "answer-citation-golden", "Answer & Citation Golden",
                    "QASPER 与 GaRAGe 单轮回答和引用公开候选",
                    "answer-citation-public-v1", "ANSWER_CITATION", "MIXED",
                    List.of(
                            "answer-citation-golden/v1/manifest.json",
                            "answer-citation-golden/v1/qasper.json",
                            "answer-citation-golden/v1/garage.json"
                    )
            ),
            new CatalogEntry(
                    "hotpotqa-answer-golden", "HotpotQA Answer & Citation",
                    "HotpotQA distractor validation 的可解释英文多跳问答候选",
                    "hotpotqa-answer-citation-v1", "ANSWER_CITATION",
                    "CC-BY-SA-4.0",
                    List.of("hotpotqa-golden/v1/dataset.json")
            ),
            new CatalogEntry(
                    "hotpotqa-answer-golden", "HotpotQA Answer & Citation",
                    "HotpotQA distractor validation 的可解释英文多跳问答候选",
                    "hotpotqa-answer-citation-v2", "ANSWER_CITATION",
                    "CC-BY-SA-4.0",
                    List.of("hotpotqa-golden/v2/dataset.json")
            ),
            new CatalogEntry(
                    "hotpotqa-retrieval-golden", "HotpotQA Hybrid Retrieval",
                    "HotpotQA distractor validation 的 Hybrid 多跳召回对照",
                    "hotpotqa-retrieval-v1", "RETRIEVAL",
                    "CC-BY-SA-4.0",
                    List.of("hotpotqa-golden/v1/dataset.json")
            ),
            new CatalogEntry(
                    "hotpotqa-retrieval-golden", "HotpotQA Hybrid Retrieval",
                    "HotpotQA distractor validation 的 Hybrid 多跳召回对照",
                    "hotpotqa-retrieval-v2", "RETRIEVAL",
                    "CC-BY-SA-4.0",
                    List.of("hotpotqa-golden/v2/dataset.json")
            ),
            new CatalogEntry(
                    "hotpotqa-local-graph-golden", "HotpotQA Local Graph",
                    "HotpotQA distractor validation 的 Local GraphRAG 多跳召回对照",
                    "hotpotqa-local-graph-v1", "LOCAL_GRAPH",
                    "CC-BY-SA-4.0",
                    List.of("hotpotqa-golden/v1/dataset.json")
            ),
            new CatalogEntry(
                    "hotpotqa-local-graph-golden", "HotpotQA Local Graph",
                    "HotpotQA distractor validation 的 Local GraphRAG 多跳召回对照",
                    "hotpotqa-local-graph-v2", "LOCAL_GRAPH",
                    "CC-BY-SA-4.0",
                    List.of("hotpotqa-golden/v2/dataset.json")
            ),
            new CatalogEntry(
                    "graph-local-release", "Local Graph Release",
                    "已映射公开 Evidence 的 Local GraphRAG 发布门禁",
                    "graph-local-release-v1", "LOCAL_GRAPH", "ODC-BY-1.0",
                    List.of("release-component-golden/v1/cases.json")
            ),
            new CatalogEntry(
                    "answer-citation-release", "Answer & Citation Release",
                    "已映射公开 Evidence 的回答、引用与拒答发布门禁",
                    "answer-citation-release-v4", "ANSWER_CITATION", "ODC-BY-1.0",
                    List.of("release-component-golden/v1/answer-cases-v4.json")
            ),
            new CatalogEntry(
                    "query-intent-golden", "Query Intent Golden",
                    "Phase 12C 请求级 HYBRID、Local 与 Global 路由契约",
                    "query-intent-golden-v1", "INTENT", "PROJECT",
                    List.of("query-intelligence-golden/v1/intent.json")
            ),
            new CatalogEntry(
                    "query-multiturn-golden", "Query Multi-turn Golden",
                    "Phase 12C owner-scoped 历史、Rewrite 与共享预算契约",
                    "query-multiturn-golden-v1", "MULTI_TURN", "PROJECT",
                    List.of("query-intelligence-golden/v1/multi-turn.json")
            ),
            new CatalogEntry(
                    "parser-golden", "Parser & Structure Golden",
                    "Phase 13C PDFBox、MinerU、表格坐标、隔离与中断恢复契约",
                    "parser-golden-v2", "PARSER", "PROJECT",
                    List.of("parser-golden/v2/cases.json")
            )
    );

    private final EvaluationService evaluations;
    private final EvaluationProperties properties;
    private final ObjectMapper objectMapper;

    EvaluationCatalogImporter(
            EvaluationService evaluations,
            EvaluationProperties properties,
            ObjectMapper objectMapper
    ) {
        this.evaluations = evaluations;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    void importCatalog() {
        if (!properties.catalogImportEnabled()) {
            return;
        }
        for (CatalogEntry entry : CATALOG) {
            List<String> missingResources = entry.resources().stream()
                    .filter(path -> !new ClassPathResource(path).exists())
                    .toList();
            if (!missingResources.isEmpty()) {
                LOGGER.info(
                        "Skipping optional evaluation catalog {} because " +
                                "its local resources are not bundled: {}",
                        entry.version(), missingResources
                );
                continue;
            }
            importEntry(entry);
        }
    }

    private void importEntry(CatalogEntry entry) {
        try {
            List<ResourceDocument> resources = new ArrayList<>();
            for (String path : entry.resources()) {
                byte[] bytes = new ClassPathResource(path)
                        .getInputStream().readAllBytes();
                resources.add(new ResourceDocument(
                        path, bytes, objectMapper.readTree(bytes)
                ));
            }
            String sourceHash = combinedHash(resources);
            UUID datasetId = stableId("dataset:" + entry.key());
            UUID versionId = stableId(
                    "dataset-version:" + entry.key() + ":" + entry.version()
            );
            String manifest = objectMapper.writeValueAsString(Map.of(
                    "resources", resources.stream().map(resource -> Map.of(
                            "path", resource.path(),
                            "sha256", sha256(resource.bytes())
                    )).toList()
            ));
            DatasetSeed dataset = new DatasetSeed(
                    datasetId, entry.key(), entry.title(), entry.description(),
                    versionId, entry.version(), "phase11a-v1", entry.caseType(),
                    entry.version(), entry.license(), sourceHash, manifest
            );
            evaluations.seed(dataset, cases(entry, versionId, resources));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to import evaluation catalog " + entry.key(),
                    exception
            );
        }
    }

    private List<CaseSeed> cases(
            CatalogEntry entry,
            UUID versionId,
            List<ResourceDocument> resources
    ) throws IOException {
        List<CaseSeed> seeds = new ArrayList<>();
        for (ResourceDocument resource : resources) {
            JsonNode root = resource.json();
            JsonNode candidates = candidates(root);
            if (!candidates.isArray()) {
                continue;
            }
            String slice = slice(resource.path());
            int position = 0;
            for (JsonNode candidate : candidates) {
                JsonNode actual = candidate.has("case")
                        ? candidate.path("case") : candidate;
                String question = question(actual);
                if (question.isBlank()) {
                    question = question(candidate);
                }
                if (question.isBlank()) {
                    continue;
                }
                String sourceId = identifier(actual);
                if (sourceId.isBlank()) {
                    sourceId = identifier(candidate);
                }
                String key = slice + ":" + (
                        sourceId.isBlank()
                                ? sha256(question.getBytes(StandardCharsets.UTF_8))
                                .substring(0, 24)
                                : sourceId
                );
                if (key.length() > 160) {
                    key = key.substring(0, 135) + ":" +
                            sha256(key.getBytes(StandardCharsets.UTF_8))
                                    .substring(0, 24);
                }
                List<String> requirements = evidenceKeys(candidate);
                String mapping = switch (entry.caseType()) {
                    case "RETRIEVAL", "INTENT", "PARSER" -> "NOT_REQUIRED";
                    case "GLOBAL_GRAPH" -> requirements.isEmpty()
                            || "xrag".equals(candidate.path("sourceDataset").asText())
                            ? "UNMAPPED" : "MAPPED";
                    case "MULTI_TURN" -> requirements.isEmpty()
                            ? "UNMAPPED" : "MAPPED";
                    case "LOCAL_GRAPH", "ANSWER_CITATION" ->
                            entry.key().endsWith("-release")
                                    && !requirements.isEmpty()
                                    ? "MAPPED" : "UNMAPPED";
                    default -> "UNMAPPED";
                };
                JsonNode inputNode = actual.path("input");
                Map<String, Object> input = inputNode.isObject()
                        ? objectMapper.convertValue(inputNode, Map.class)
                        : Map.of("query", question);
                JsonNode expectedNode = actual.path("expected").isObject()
                        ? actual.path("expected") : expected(actual);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("sourceResource", resource.path());
                metadata.put("sourcePosition", position++);
                if (candidate.has("source")) {
                    metadata.put("sourceDataset", candidate.path("source").asText());
                }
                if (candidate.has("intent")) {
                    metadata.put("intent", candidate.path("intent").asText());
                }
                seeds.add(new CaseSeed(
                        stableId("case:" + entry.version() + ":" + key),
                        versionId, key, language(root, actual, question),
                        entry.caseType(),
                        objectMapper.writeValueAsString(input),
                        objectMapper.writeValueAsString(expectedNode),
                        mapping,
                        objectMapper.writeValueAsString(requirements),
                        objectMapper.writeValueAsString(metadata)
                ));
            }
        }
        return seeds;
    }

    private static JsonNode candidates(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        if (root.path("queries").isArray()) {
            return root.path("queries");
        }
        if (root.path("cases").isArray()) {
            return root.path("cases");
        }
        return MissingNode.getInstance();
    }

    private static String question(JsonNode node) {
        for (String name : List.of("query", "question")) {
            if (node.path(name).isTextual()) {
                return node.path(name).asText().strip();
            }
        }
        if (node.path("qa").path("question").isTextual()) {
            return node.path("qa").path("question").asText().strip();
        }
        JsonNode turns = node.path("input").path("turns");
        if (turns.isArray() && !turns.isEmpty()) {
            return turns.get(turns.size() - 1).asText("").strip();
        }
        if (node.path("input").path("query").isTextual()) {
            return node.path("input").path("query").asText().strip();
        }
        return "";
    }

    private static String identifier(JsonNode node) {
        for (String name : List.of(
                "id", "_id", "caseKey", "question_id", "sourceCaseId"
        )) {
            if (node.path(name).isValueNode()) {
                return node.path(name).asText().strip();
            }
        }
        if (node.path("qa").path("question_id").isValueNode()) {
            return node.path("qa").path("question_id").asText().strip();
        }
        return "";
    }

    private static JsonNode expected(JsonNode node) {
        if (node.has("answer")) {
            return JsonNodeFactory.instance.objectNode()
                    .set("answer", node.path("answer"));
        }
        if (node.has("answers")) {
            return JsonNodeFactory.instance.objectNode()
                    .set("answers", node.path("answers"));
        }
        if (node.path("qa").has("answers")) {
            return JsonNodeFactory.instance.objectNode().set(
                    "answers", node.path("qa").path("answers")
            );
        }
        if (node.has("evidenceGroups")) {
            var expected = JsonNodeFactory.instance.objectNode();
            expected.put("answerable", node.path("answerable").asBoolean());
            expected.set("evidenceGroups", node.path("evidenceGroups"));
            return expected;
        }
        return JsonNodeFactory.instance.objectNode();
    }

    private static List<String> evidenceKeys(JsonNode candidate) {
        List<String> keys = new ArrayList<>();
        for (JsonNode evidence : candidate.path("evidenceRefs")) {
            String key = evidence.path("evidenceKey").asText();
            if (!key.isBlank()) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    private static String language(
            JsonNode root,
            JsonNode node,
            String question
    ) {
        if (node.path("language").isTextual()) {
            return node.path("language").asText();
        }
        if (root.path("language").isTextual()) {
            return root.path("language").asText();
        }
        return question.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.HAN
        ) ? "zh" : "en";
    }

    private static String slice(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        return filename.substring(0, filename.lastIndexOf('.'))
                .replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private static String combinedHash(List<ResourceDocument> resources) {
        MessageDigest digest = digest();
        for (ResourceDocument resource : resources) {
            digest.update(resource.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(resource.bytes());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest().digest(value));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record CatalogEntry(
            String key,
            String title,
            String description,
            String version,
            String caseType,
            String license,
            List<String> resources
    ) {
    }

    private record ResourceDocument(
            String path,
            byte[] bytes,
            JsonNode json
    ) {
    }
}
