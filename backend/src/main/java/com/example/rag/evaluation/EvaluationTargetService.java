package com.example.rag.evaluation;

import com.example.rag.chat.QueryIntelligenceProfileService;
import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.AnswerProfileView;
import com.example.rag.evaluation.EvaluationContracts.SubjectType;
import com.example.rag.evaluation.EvaluationContracts.TargetView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class EvaluationTargetService {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final AnswerProfileService answerProfiles;
    private final QueryIntelligenceProfileService queryProfiles;
    private final EvaluationProperties properties;

    EvaluationTargetService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Environment environment,
            AnswerProfileService answerProfiles,
            QueryIntelligenceProfileService queryProfiles,
            EvaluationProperties properties
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.answerProfiles = answerProfiles;
        this.queryProfiles = queryProfiles;
        this.properties = properties;
    }

    @Transactional
    List<TargetView> targets() {
        List<UUID> current = new ArrayList<>();
        Map<String, Object> active = activeSnapshot();
        for (SubjectType type : SubjectType.values()) {
            current.add(persist(type, "ACTIVE", active).id());
        }
        readyIndexSnapshots(active).forEach(snapshot ->
                current.add(persist(
                        SubjectType.RETRIEVAL, "READY", snapshot
                ).id())
        );
        readyGraphSnapshots(active).forEach(snapshot ->
                current.add(persist(
                        SubjectType.LOCAL_GRAPH, "READY", snapshot
                ).id())
        );
        readyGlobalSnapshots(active).forEach(snapshot ->
                current.add(persist(
                        SubjectType.GLOBAL_GRAPH, "READY", snapshot
                ).id())
        );
        readyAnswerSnapshots(active).forEach(snapshot ->
                current.add(persist(
                        SubjectType.ANSWER_CITATION, "READY", snapshot
                ).id())
        );
        readyQuerySnapshots(active).forEach(snapshot -> {
            current.add(persist(
                    SubjectType.MULTI_TURN, "READY", snapshot
            ).id());
            current.add(persist(
                    SubjectType.INTENT, "READY", snapshot
            ).id());
        });
        return current.stream()
                .distinct()
                .map(this::target)
                .sorted((left, right) -> {
                    int type = left.subjectType().compareTo(right.subjectType());
                    if (type != 0) {
                        return type;
                    }
                    int readiness = Integer.compare(
                            "READY".equals(left.readinessStatus()) ? 0 : 1,
                            "READY".equals(right.readinessStatus()) ? 0 : 1
                    );
                    if (readiness != 0) {
                        return readiness;
                    }
                    int kind = left.targetKind().compareTo(right.targetKind());
                    return kind != 0
                            ? kind
                            : left.targetKey().compareTo(right.targetKey());
                })
                .toList();
    }

    TargetView target(UUID id) {
        return jdbc.query(
                """
                SELECT id, target_key, subject_type, target_kind,
                       snapshot::TEXT, snapshot_hash, readiness_status,
                       blocked_reason, created_at
                FROM evaluation_targets
                WHERE id = ?
                """,
                this::targetRow,
                id
        ).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "EVALUATION_TARGET_NOT_FOUND",
                "Evaluation Target 不存在"
        ));
    }

    private TargetView persist(
            SubjectType type,
            String kind,
            Map<String, Object> source
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>(source);
        snapshot.put("targetKind", kind);
        String blocked = blockedReason(type, snapshot);
        String readiness = blocked == null
                ? "READY" : "BLOCKED_PREREQUISITE";
        String payload = json(snapshot);
        String hash = sha256(payload);
        String key = "phase11d:" + type.name().toLowerCase()
                + ":" + kind.toLowerCase() + ":" + hash.substring(0, 24);
        UUID id = UUID.nameUUIDFromBytes(
                ("evaluation-target:" + key).getBytes(StandardCharsets.UTF_8)
        );
        jdbc.update(
                """
                INSERT INTO evaluation_targets (
                    id, target_key, subject_type, target_kind,
                    snapshot, snapshot_hash, readiness_status,
                    blocked_reason
                ) VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?)
                ON CONFLICT (target_key) DO NOTHING
                """,
                id, key, type.name(), kind, payload, hash,
                readiness, blocked
        );
        TargetView stored = target(id);
        if (!stored.snapshotHash().equals(hash)
                || stored.subjectType() != type
                || !stored.targetKind().equals(kind)) {
            throw new IllegalStateException(
                    "Evaluation Target definition changed: " + key
            );
        }
        return stored;
    }

    private Map<String, Object> activeSnapshot() {
        Map<String, Object> snapshot = baseSnapshot();
        row(
                """
                SELECT publication.profile_version,
                       manifest.index_generation, manifest.index_name,
                       manifest.index_config_version
                FROM retrieval_publications publication
                LEFT JOIN index_manifests manifest
                  ON manifest.status = 'ACTIVE'
                WHERE publication.singleton_id = 1
                ORDER BY manifest.index_generation DESC
                LIMIT 1
                """,
                rs -> {
                    snapshot.put(
                            "retrievalProfileVersion",
                            rs.getString("profile_version")
                    );
                    snapshot.put(
                            "indexGeneration",
                            rs.getObject("index_generation", Long.class)
                    );
                    snapshot.put("indexName", rs.getString("index_name"));
                    snapshot.put(
                            "indexConfigVersion",
                            rs.getString("index_config_version")
                    );
                }
        );
        snapshot.put(
                "graphRetrievalProfileVersion",
                scalar("""
                        SELECT profile_version
                        FROM graph_retrieval_publications
                        WHERE singleton_id = 1
                        """)
        );
        row(
                """
                SELECT publication.graph_generation,
                       manifest.graph_config_version
                FROM graph_publications publication
                JOIN graph_manifests manifest
                  ON manifest.graph_generation =
                     publication.graph_generation
                WHERE publication.singleton_id = 1
                """,
                rs -> {
                    snapshot.put(
                            "graphGeneration",
                            rs.getObject("graph_generation", Long.class)
                    );
                    snapshot.put(
                            "graphConfigVersion",
                            rs.getString("graph_config_version")
                    );
                }
        );
        row(
                """
                SELECT publication.global_generation,
                       manifest.global_config_version,
                       manifest.source_graph_generation,
                       manifest.index_name
                FROM global_graph_publications publication
                JOIN global_graph_manifests manifest
                  ON manifest.global_generation =
                     publication.global_generation
                WHERE publication.singleton_id = 1
                """,
                rs -> {
                    snapshot.put(
                            "globalGeneration",
                            rs.getObject("global_generation", Long.class)
                    );
                    snapshot.put(
                            "globalConfigVersion",
                            rs.getString("global_config_version")
                    );
                    snapshot.put(
                            "globalSourceGraphGeneration",
                            rs.getObject(
                                    "source_graph_generation", Long.class
                            )
                    );
                    snapshot.put(
                            "globalIndexName",
                            rs.getString("index_name")
                    );
                }
        );
        applyAnswer(snapshot, answerProfiles.active());
        var queryProfile = queryProfiles.active();
        snapshot.put(
                "queryProfileVersion",
                queryProfile == null ? null : queryProfile.version()
        );
        snapshot.put(
                "queryProfileRuntimeMatched",
                queryProfiles.matchesRuntime(queryProfile)
        );
        return snapshot;
    }

    private Map<String, Object> baseSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("realExecutionEnabled", properties.realEnabled());
        snapshot.put("pipelineVersion", environment.getProperty(
                "rag.pipeline.pipeline-version", "unknown"
        ));
        snapshot.put("parserVersion", environment.getProperty(
                "rag.pipeline.parser-version", "unknown"
        ));
        snapshot.put("chunkerVersion", environment.getProperty(
                "rag.pipeline.chunker-version", "unknown"
        ));
        snapshot.put("embeddingModel", environment.getProperty(
                "rag.models.embedding.model", "disabled"
        ));
        snapshot.put("embeddingRevision", environment.getProperty(
                "rag.models.embedding.revision", "unknown"
        ));
        snapshot.put("rerankModel", environment.getProperty(
                "rag.models.rerank.model", "disabled"
        ));
        snapshot.put("rerankRevision", environment.getProperty(
                "rag.models.rerank.revision", "unknown"
        ));
        snapshot.put(
                "answerOrchestration",
                "phase12c-stategraph-v1"
        );
        snapshot.put("documentFormatContract", "document-formats-v6");
        snapshot.put("sourceLocatorContract", "parsed-package-v3");
        snapshot.put("memoryContractVersion", "phase14-structured-memory-v1");
        snapshot.put(
                "evaluationSuiteVersion",
                "phase18d-" + MultiformatReleaseService.VERSION
        );
        snapshot.put(
                "multiformatLocatorClosure",
                multiformatLocatorClosure()
        );
        snapshot.put("benchmarkProtocol", Map.of(
                "version", "phase18d-observed-v1",
                "warmupRunsPerCase", 1,
                "measurementRunsPerCase", 1,
                "percentiles", List.of("p50", "p95", "max"),
                "qualityThresholdsBlocking", false
        ));
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> hardware = new LinkedHashMap<>();
        hardware.put("javaVersion", System.getProperty("java.version"));
        hardware.put("osName", System.getProperty("os.name"));
        hardware.put("osArch", System.getProperty("os.arch"));
        hardware.put("availableProcessors", runtime.availableProcessors());
        hardware.put("maxMemoryBytes", runtime.maxMemory());
        snapshot.put("runtimeHardware", hardware);
        return snapshot;
    }

    private Map<String, Object> multiformatLocatorClosure() {
        List<Map<String, Object>> formats = jdbc.query(
                """
                SELECT fact.document_format, fact.document_id,
                       fact.revision_id, fact.acl_version,
                       fact.locator_hash
                FROM evaluation_multiformat_case_facts fact
                JOIN evaluation_dataset_versions version
                  ON version.id = fact.dataset_version_id
                 AND version.version = ?
                JOIN documents document
                  ON document.id = fact.document_id
                 AND document.current_revision_id = fact.revision_id
                 AND document.deleted_at IS NULL
                 AND document.acl_version = fact.acl_version
                JOIN document_revisions revision
                  ON revision.id = fact.revision_id
                 AND revision.document_id = fact.document_id
                 AND revision.status = 'READY'
                 AND revision.content_hash = fact.file_sha256
                JOIN chunks child
                  ON child.id = fact.child_chunk_id
                 AND child.document_id = fact.document_id
                 AND child.revision_id = fact.revision_id
                 AND child.chunk_type = 'CHILD'
                 AND child.searchable = TRUE
                JOIN source_spans span
                  ON span.id = fact.source_span_id
                 AND span.chunk_id = fact.child_chunk_id
                 AND span.document_id = fact.document_id
                 AND span.revision_id = fact.revision_id
                JOIN source_locator_projection locator
                  ON locator.source_kind = 'SOURCE_SPAN'
                 AND locator.source_id = fact.source_span_id
                 AND locator.locator_kind = fact.locator_kind
                 AND locator.source_label = fact.source_label
                ORDER BY fact.document_format
                """,
                (rs, row) -> Map.of(
                        "documentFormat",
                        rs.getString("document_format"),
                        "documentId",
                        rs.getObject("document_id", UUID.class).toString(),
                        "revisionId",
                        rs.getObject("revision_id", UUID.class).toString(),
                        "aclVersion", rs.getLong("acl_version"),
                        "locatorHash", rs.getString("locator_hash")
                ),
                MultiformatReleaseService.VERSION
        );
        return Map.of(
                "datasetVersion", MultiformatReleaseService.VERSION,
                "readyFormats", formats.size(),
                "closureHash", sha256(json(formats))
        );
    }

    private void applyAnswer(
            Map<String, Object> snapshot,
            AnswerProfileView profile
    ) {
        var runtime = answerProfiles.runtime();
        snapshot.put(
                "llmModel",
                runtime.enabled() ? runtime.modelId() : "disabled"
        );
        snapshot.put("llmRevision", runtime.modelRevision());
        snapshot.put(
                "answerProfileVersion",
                profile == null ? null : profile.version()
        );
        snapshot.put(
                "answerProfileRuntimeMatched",
                profile != null && answerProfiles.matchesRuntime(profile)
        );
        if (profile != null) {
            snapshot.put("answerProfile", Map.ofEntries(
                    Map.entry("modelProvider", profile.modelProvider()),
                    Map.entry("modelId", profile.modelId()),
                    Map.entry("modelRevision", profile.modelRevision()),
                    Map.entry(
                            "endpointIdentity",
                            profile.endpointIdentity()
                    ),
                    Map.entry("promptVersion", profile.promptVersion()),
                    Map.entry(
                            "orchestrationVersion",
                            profile.orchestrationVersion()
                    ),
                    Map.entry("timeoutMs", profile.timeoutMs()),
                    Map.entry(
                            "maxOutputTokens",
                            profile.maxOutputTokens()
                    ),
                    Map.entry(
                            "remoteEvidenceAllowed",
                            profile.remoteEvidenceAllowed()
                    ),
                    Map.entry(
                            "remoteMemoryAllowed",
                            profile.remoteMemoryAllowed()
                    )
            ));
        } else {
            snapshot.remove("answerProfile");
        }
    }

    private List<Map<String, Object>> readyIndexSnapshots(
            Map<String, Object> active
    ) {
        return jdbc.query(
                """
                SELECT index_generation, index_name, index_config_version
                FROM index_manifests
                WHERE status = 'READY'
                ORDER BY index_generation
                """,
                (rs, row) -> {
                    Map<String, Object> snapshot =
                            new LinkedHashMap<>(active);
                    snapshot.put(
                            "indexGeneration",
                            rs.getLong("index_generation")
                    );
                    snapshot.put("indexName", rs.getString("index_name"));
                    snapshot.put(
                            "indexConfigVersion",
                            rs.getString("index_config_version")
                    );
                    return snapshot;
                }
        );
    }

    private List<Map<String, Object>> readyGraphSnapshots(
            Map<String, Object> active
    ) {
        return jdbc.query(
                """
                SELECT graph_generation, graph_config_version
                FROM graph_manifests
                WHERE status = 'READY'
                ORDER BY graph_generation
                """,
                (rs, row) -> {
                    Map<String, Object> snapshot =
                            new LinkedHashMap<>(active);
                    snapshot.put(
                            "graphGeneration",
                            rs.getLong("graph_generation")
                    );
                    snapshot.put(
                            "graphConfigVersion",
                            rs.getString("graph_config_version")
                    );
                    return snapshot;
                }
        );
    }

    private List<Map<String, Object>> readyGlobalSnapshots(
            Map<String, Object> active
    ) {
        return jdbc.query(
                """
                SELECT global_generation, global_config_version,
                       source_graph_generation, index_name
                FROM global_graph_manifests
                WHERE status = 'READY'
                ORDER BY global_generation
                """,
                (rs, row) -> {
                    Map<String, Object> snapshot =
                            new LinkedHashMap<>(active);
                    snapshot.put(
                            "globalGeneration",
                            rs.getLong("global_generation")
                    );
                    snapshot.put(
                            "globalConfigVersion",
                            rs.getString("global_config_version")
                    );
                    snapshot.put(
                            "globalSourceGraphGeneration",
                            rs.getLong("source_graph_generation")
                    );
                    snapshot.put(
                            "globalIndexName",
                            rs.getString("index_name")
                    );
                    return snapshot;
                }
        );
    }

    private List<Map<String, Object>> readyAnswerSnapshots(
            Map<String, Object> active
    ) {
        return answerProfiles.profiles().stream()
                .filter(profile -> !profile.published())
                .filter(answerProfiles::matchesRuntime)
                .map(profile -> {
                    Map<String, Object> snapshot =
                            new LinkedHashMap<>(active);
                    applyAnswer(snapshot, profile);
                    return snapshot;
                })
                .toList();
    }

    private List<Map<String, Object>> readyQuerySnapshots(
            Map<String, Object> active
    ) {
        return queryProfiles.profiles().stream()
                .filter(profile -> !profile.published())
                .filter(queryProfiles::matchesRuntime)
                .map(profile -> {
                    Map<String, Object> snapshot =
                            new LinkedHashMap<>(active);
                    snapshot.put("queryProfileVersion", profile.version());
                    snapshot.put("queryProfileRuntimeMatched", true);
                    return snapshot;
                })
                .toList();
    }

    private String blockedReason(
            SubjectType type,
            Map<String, Object> snapshot
    ) {
        if (type == SubjectType.PARSER) {
            return "Parser 代表性样本尚未绑定本地 Revision；真实评测按计划最后执行";
        }
        if (!Boolean.TRUE.equals(snapshot.get("realExecutionEnabled"))) {
            return "真实评测执行开关未开启";
        }
        if (missing(
                snapshot,
                "retrievalProfileVersion",
                "indexGeneration",
                "indexName",
                "indexConfigVersion"
        )) {
            return "缺少可执行 Retrieval Profile 或 Index Generation";
        }
        return switch (type) {
            case RETRIEVAL -> null;
            case LOCAL_GRAPH -> missing(
                    snapshot,
                    "graphRetrievalProfileVersion",
                    "graphGeneration",
                    "graphConfigVersion"
            ) ? "缺少可执行 Graph Generation 或 Graph Retrieval Profile"
                    : null;
            case GLOBAL_GRAPH -> missing(
                    snapshot,
                    "globalGeneration",
                    "globalConfigVersion",
                    "globalSourceGraphGeneration",
                    "globalIndexName"
            ) ? "缺少可执行 Global Generation" : null;
            case ANSWER_CITATION ->
                    "disabled".equals(snapshot.get("llmModel"))
                            || snapshot.get("answerProfileVersion") == null
                            || !Boolean.TRUE.equals(snapshot.get(
                            "answerProfileRuntimeMatched"
                    ))
                            ? "LLM 或 AnswerProfile 未配置或与运行时不一致"
                            : null;
            case MULTI_TURN, INTENT ->
                    "disabled".equals(snapshot.get("llmModel"))
                            || snapshot.get("queryProfileVersion") == null
                            || !Boolean.TRUE.equals(snapshot.get(
                            "queryProfileRuntimeMatched"
                    ))
                            ? "LLM 或 QueryIntelligenceProfile 未配置或与运行时不一致"
                            : null;
            case MULTIFORMAT_RELEASE -> multiformatBlockedReason(snapshot);
            case PARSER -> throw new IllegalStateException(
                    "PARSER readiness is handled before runtime prerequisites"
            );
        };
    }

    private String multiformatBlockedReason(Map<String, Object> snapshot) {
        if (missing(
                snapshot,
                "graphRetrievalProfileVersion",
                "graphGeneration",
                "graphConfigVersion",
                "globalGeneration",
                "globalConfigVersion",
                "globalSourceGraphGeneration",
                "globalIndexName",
                "answerProfileVersion"
        ) || !Boolean.TRUE.equals(snapshot.get(
                "answerProfileRuntimeMatched"
        ))) {
            return "多格式发布缺少 Graph/Global/Answer 冻结版本";
        }
        Integer ready = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT fact.document_format)
                FROM evaluation_multiformat_case_facts fact
                JOIN documents document
                  ON document.id = fact.document_id
                 AND document.current_revision_id = fact.revision_id
                 AND document.deleted_at IS NULL
                 AND document.acl_version = fact.acl_version
                JOIN document_revisions revision
                  ON revision.id = fact.revision_id
                 AND revision.document_id = fact.document_id
                 AND revision.status = 'READY'
                 AND revision.content_hash = fact.file_sha256
                JOIN chunks child
                  ON child.id = fact.child_chunk_id
                 AND child.document_id = fact.document_id
                 AND child.revision_id = fact.revision_id
                 AND child.chunk_type = 'CHILD'
                 AND child.searchable = TRUE
                JOIN source_spans span
                  ON span.id = fact.source_span_id
                 AND span.chunk_id = fact.child_chunk_id
                 AND span.document_id = fact.document_id
                 AND span.revision_id = fact.revision_id
                JOIN source_locator_projection locator
                  ON locator.source_kind = 'SOURCE_SPAN'
                 AND locator.source_id = fact.source_span_id
                 AND locator.locator_kind = fact.locator_kind
                 AND locator.source_label = fact.source_label
                JOIN evaluation_dataset_versions version
                  ON version.id = fact.dataset_version_id
                 AND version.version = ?
                """,
                Integer.class, MultiformatReleaseService.VERSION
        );
        return ready != null && ready == 8
                ? null
                : MultiformatReleaseService.VERSION
                + " 未形成八格式当前 Locator 闭包";
    }

    private static boolean missing(
            Map<String, Object> snapshot,
            String... keys
    ) {
        for (String key : keys) {
            Object value = snapshot.get(key);
            if (value == null || value instanceof String text && text.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private TargetView targetRow(ResultSet rs, int row) throws SQLException {
        return new TargetView(
                rs.getObject("id", UUID.class),
                rs.getString("target_key"),
                SubjectType.valueOf(rs.getString("subject_type")),
                rs.getString("target_kind"),
                objectMap(rs.getString("snapshot")),
                rs.getString("snapshot_hash"),
                rs.getString("readiness_status"),
                rs.getString("blocked_reason"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private Object scalar(String sql) {
        List<Object> values = jdbc.query(
                sql,
                (rs, row) -> rs.getObject(1)
        );
        return values.isEmpty() ? null : values.getFirst();
    }

    private void row(String sql, SqlRowConsumer consumer) {
        jdbc.query(sql, rs -> {
            if (rs.next()) {
                consumer.accept(rs);
            }
            return null;
        });
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize Evaluation Target",
                    exception
            );
        }
    }

    private Map<String, Object> objectMap(String value) {
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to read Evaluation Target",
                    exception
            );
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @FunctionalInterface
    private interface SqlRowConsumer {
        void accept(ResultSet resultSet) throws SQLException;
    }
}
