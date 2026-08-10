package com.example.rag.search;

import com.example.rag.common.ApiException;
import com.example.rag.search.RetrievalConfigurationContracts.ActiveManifestView;
import com.example.rag.search.RetrievalConfigurationContracts.CreateRetrievalProfileRequest;
import com.example.rag.search.RetrievalConfigurationContracts.CurrentPublicationView;
import com.example.rag.search.RetrievalConfigurationContracts.GoldenBaselineView;
import com.example.rag.search.RetrievalConfigurationContracts.GoldenSliceView;
import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalConfigurationResponse;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalMode;
import com.example.rag.search.RetrievalConfigurationContracts.RetrievalProfileView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class RetrievalConfigurationRepository {

    private final JdbcTemplate jdbc;
    private final SearchProperties searchProperties;
    private final ObjectMapper json;

    RetrievalConfigurationRepository(
            JdbcTemplate jdbc,
            SearchProperties searchProperties,
            ObjectMapper json
    ) {
        this.jdbc = jdbc;
        this.searchProperties = searchProperties;
        this.json = json;
    }

    @Transactional(readOnly = true)
    RetrievalConfigurationResponse configuration() {
        return new RetrievalConfigurationResponse(
                currentPublication().orElse(null),
                activeManifest().orElse(null),
                indexConfigs(),
                profiles(),
                goldenBaseline()
        );
    }

    @Transactional
    RetrievalProfileView create(CreateRetrievalProfileRequest request) {
        validate(request);
        String version = request.version().trim();
        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO retrieval_profiles (
                        version, mode, default_page_size, max_page_size,
                        bm25_top_k, vector_top_k, rrf_rank_constant,
                        rerank_top_k, evidence_top_k, parent_token_budget
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING version, mode, default_page_size, max_page_size,
                              bm25_top_k, vector_top_k, rrf_rank_constant,
                              rerank_top_k, evidence_top_k, parent_token_budget, created_at
                    """,
                    (resultSet, rowNumber) -> profile(resultSet),
                    version,
                    request.mode().name(),
                    request.defaultPageSize(),
                    request.maxPageSize(),
                    request.bm25TopK(),
                    request.vectorTopK(),
                    request.rrfRankConstant(),
                    request.rerankTopK(),
                    request.evidenceTopK(),
                    request.parentTokenBudget()
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "RETRIEVAL_PROFILE_VERSION_EXISTS",
                    "Retrieval Profile 版本已存在",
                    exception
            );
        }
    }

    @Transactional(readOnly = true)
    String currentProfileVersion() {
        return currentPublication()
                .map(CurrentPublicationView::profileVersion)
                .orElseThrow(RetrievalConfigurationRepository::configurationUnavailable);
    }

    @Transactional(readOnly = true)
    RetrievalProfileView currentProfile() {
        return profile(currentProfileVersion());
    }

    @Transactional(readOnly = true)
    RetrievalProfileView profile(String version) {
        return jdbc.query(
                """
                SELECT version, mode, default_page_size, max_page_size,
                       bm25_top_k, vector_top_k, rrf_rank_constant,
                       rerank_top_k, evidence_top_k, parent_token_budget, created_at
                FROM retrieval_profiles
                WHERE version = ?
                """,
                (resultSet, rowNumber) -> profile(resultSet),
                version
        ).stream().findFirst().orElseThrow(RetrievalConfigurationRepository::configurationUnavailable);
    }

    @Transactional(readOnly = true)
    IndexConfigView indexConfigForRebuild() {
        Optional<IndexConfigView> active = jdbc.query(
                """
                SELECT config.version, config.schema_version, config.analyzer,
                       config.embedding_provider_key,
                       config.embedding_input_format_version,
                       config.embedding_normalization_version,
                       config.embedding_model, config.embedding_revision,
                       config.vector_dimensions, config.distance,
                       config.hnsw_m, config.hnsw_ef_construction, config.created_at
                FROM index_manifests manifest
                JOIN index_configs config
                  ON config.version = manifest.index_config_version
                WHERE manifest.index_alias = ? AND manifest.status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> indexConfig(resultSet),
                searchProperties.getIndexAlias()
        ).stream().findFirst();
        if (active.isPresent()) {
            return active.get();
        }
        List<IndexConfigView> bootstrapCandidates = jdbc.query(
                """
                SELECT version, schema_version, analyzer,
                       embedding_provider_key, embedding_input_format_version,
                       embedding_normalization_version, embedding_model,
                       embedding_revision, vector_dimensions, distance,
                       hnsw_m, hnsw_ef_construction, created_at
                FROM index_configs
                WHERE embedding_model IS NULL
                ORDER BY created_at DESC, version DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> indexConfig(resultSet)
        );
        if (bootstrapCandidates.isEmpty()) {
            throw configurationUnavailable();
        }
        return bootstrapCandidates.getFirst();
    }

    @Transactional(readOnly = true)
    IndexConfigView indexConfig(String version) {
        return jdbc.query(
                """
                SELECT version, schema_version, analyzer,
                       embedding_provider_key, embedding_input_format_version,
                       embedding_normalization_version, embedding_model,
                       embedding_revision, vector_dimensions, distance,
                       hnsw_m, hnsw_ef_construction, created_at
                FROM index_configs
                WHERE version = ?
                """,
                (resultSet, rowNumber) -> indexConfig(resultSet),
                version
        ).stream().findFirst().orElseThrow(RetrievalConfigurationRepository::configurationUnavailable);
    }

    private Optional<CurrentPublicationView> currentPublication() {
        return jdbc.query(
                """
                SELECT profile_version, publication_event_id, published_at
                FROM retrieval_publications
                WHERE singleton_id = 1
                """,
                (resultSet, rowNumber) -> new CurrentPublicationView(
                        resultSet.getString("profile_version"),
                        resultSet.getLong("publication_event_id"),
                        resultSet.getTimestamp("published_at").toInstant()
                )
        ).stream().findFirst();
    }

    private Optional<ActiveManifestView> activeManifest() {
        return jdbc.query(
                """
                SELECT index_generation, index_name, index_config_version, status
                FROM index_manifests
                WHERE index_alias = ? AND status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> new ActiveManifestView(
                        resultSet.getLong("index_generation"),
                        resultSet.getString("index_name"),
                        resultSet.getString("index_config_version"),
                        resultSet.getString("status")
                ),
                searchProperties.getIndexAlias()
        ).stream().findFirst();
    }

    private List<IndexConfigView> indexConfigs() {
        return jdbc.query(
                """
                SELECT version, schema_version, analyzer,
                       embedding_provider_key, embedding_input_format_version,
                       embedding_normalization_version, embedding_model,
                       embedding_revision, vector_dimensions, distance,
                       hnsw_m, hnsw_ef_construction, created_at
                FROM index_configs
                ORDER BY created_at, version
                """,
                (resultSet, rowNumber) -> indexConfig(resultSet)
        );
    }

    private List<RetrievalProfileView> profiles() {
        return jdbc.query(
                """
                SELECT version, mode, default_page_size, max_page_size,
                       bm25_top_k, vector_top_k, rrf_rank_constant,
                       rerank_top_k, evidence_top_k, parent_token_budget, created_at
                FROM retrieval_profiles
                ORDER BY created_at, version
                """,
                (resultSet, rowNumber) -> profile(resultSet)
        );
    }

    private GoldenBaselineView goldenBaseline() {
        var resource = new ClassPathResource("retrieval-golden/v2/baseline.json");
        try (var input = resource.getInputStream()) {
            JsonNode root = json.readTree(input);
            String datasetVersion = root.path("datasetVersion").asText("");
            String status = root.path("status").asText("");
            int caseCount = root.path("caseCount").asInt(0);
            List<GoldenSliceView> slices = slices(root.path("slices"));
            if (datasetVersion.isBlank()
                    || caseCount < 40
                    || status.isBlank()
                    || slices.isEmpty()) {
                throw new IOException("Golden baseline metadata is incomplete");
            }
            return new GoldenBaselineView(
                    datasetVersion,
                    caseCount,
                    status,
                    instant(root.path("generatedAt")),
                    root.path("reportAvailable").asBoolean(false),
                    slices
            );
        } catch (IOException | DateTimeParseException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GOLDEN_BASELINE_UNAVAILABLE",
                    "检索评测基线暂时不可用",
                    exception
            );
        }
    }

    private static List<GoldenSliceView> slices(JsonNode source) throws IOException {
        List<GoldenSliceView> result = new ArrayList<>();
        if (source.isObject()) {
            source.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                int count = value.isObject()
                        ? value.path("caseCount").asInt(0)
                        : value.asInt(0);
                Double hit = value.isObject() && value.hasNonNull("candidateHitAt50")
                        ? value.path("candidateHitAt50").asDouble()
                        : null;
                result.add(new GoldenSliceView(entry.getKey(), count, hit));
            });
        } else if (source.isArray()) {
            for (JsonNode value : source) {
                result.add(new GoldenSliceView(
                        value.path("name").asText(""),
                        value.path("caseCount").asInt(0),
                        value.hasNonNull("candidateHitAt50")
                                ? value.path("candidateHitAt50").asDouble()
                                : null
                ));
            }
        }
        if (result.stream().anyMatch(slice -> slice.name().isBlank() || slice.caseCount() < 1)) {
            throw new IOException("Golden baseline slices are invalid");
        }
        return List.copyOf(result);
    }

    private static Instant instant(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank()
                ? Instant.parse(value.asText())
                : null;
    }

    private static IndexConfigView indexConfig(ResultSet resultSet) throws SQLException {
        return new IndexConfigView(
                resultSet.getString("version"),
                resultSet.getString("schema_version"),
                resultSet.getString("analyzer"),
                resultSet.getString("embedding_provider_key"),
                resultSet.getString("embedding_input_format_version"),
                resultSet.getString("embedding_normalization_version"),
                resultSet.getString("embedding_model"),
                resultSet.getString("embedding_revision"),
                resultSet.getObject("vector_dimensions", Integer.class),
                resultSet.getString("distance"),
                resultSet.getObject("hnsw_m", Integer.class),
                resultSet.getObject("hnsw_ef_construction", Integer.class),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static RetrievalProfileView profile(ResultSet resultSet) throws SQLException {
        return new RetrievalProfileView(
                resultSet.getString("version"),
                RetrievalMode.valueOf(resultSet.getString("mode")),
                resultSet.getInt("default_page_size"),
                resultSet.getInt("max_page_size"),
                resultSet.getInt("bm25_top_k"),
                resultSet.getInt("vector_top_k"),
                resultSet.getInt("rrf_rank_constant"),
                resultSet.getInt("rerank_top_k"),
                resultSet.getInt("evidence_top_k"),
                resultSet.getInt("parent_token_budget"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static void validate(CreateRetrievalProfileRequest request) {
        int candidates = request.bm25TopK() + request.vectorTopK();
        int finalCandidates = request.rerankTopK() > 0 ? request.rerankTopK() : candidates;
        boolean modeValid = request.mode() == RetrievalMode.BM25
                ? request.vectorTopK() == 0
                : request.vectorTopK() > 0;
        if (request.defaultPageSize() > request.maxPageSize()
                || !modeValid
                || request.rerankTopK() > candidates
                || request.evidenceTopK() > finalCandidates) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "RETRIEVAL_PROFILE_INVALID",
                    "Retrieval Profile 参数组合无效"
            );
        }
    }

    private static ApiException configurationUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RETRIEVAL_CONFIGURATION_UNAVAILABLE",
                "检索配置暂时不可用"
        );
    }
}
