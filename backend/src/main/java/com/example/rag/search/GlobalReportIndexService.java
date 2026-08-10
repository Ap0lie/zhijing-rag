package com.example.rag.search;

import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Service
@ConditionalOnProperty(
        prefix = "rag.search",
        name = "enabled",
        havingValue = "true"
)
public class GlobalReportIndexService {

    private final OpenSearchGateway openSearch;
    private final RetrievalConfigurationRepository configurations;
    private final EmbeddingCacheService embeddings;
    private final ExecutorService executor;

    GlobalReportIndexService(
            OpenSearchGateway openSearch,
            RetrievalConfigurationRepository configurations,
            EmbeddingCacheService embeddings,
            @Qualifier("searchBranchExecutor") ExecutorService executor
    ) {
        this.openSearch = openSearch;
        this.configurations = configurations;
        this.embeddings = embeddings;
        this.executor = executor;
    }

    public BuildResult build(
            String indexName,
            String indexConfigVersion,
            List<ReportDocument> reports
    ) {
        IndexConfigView config = configurations.indexConfig(
                indexConfigVersion
        );
        openSearch.deleteIndex(indexName);
        openSearch.createIndex(indexName, indexDefinition(config));
        List<List<Double>> vectors = List.of();
        if (config.vectorEnabled()) {
            vectors = embeddings.embedGlobalReports(
                    config,
                    reports.stream()
                            .map(report -> new ChildEmbeddingInput(
                                    report.contentHash(),
                                    report.searchText()
                            ))
                            .toList()
            );
        }
        List<Map<String, Object>> documents =
                new ArrayList<>(reports.size());
        for (int index = 0; index < reports.size(); index++) {
            ReportDocument report = reports.get(index);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("reportId", report.id().toString());
            document.put("globalGeneration", report.globalGeneration());
            document.put("communityKey", report.communityKey());
            document.put("title", report.title());
            document.put("summary", report.summary());
            document.put("text", report.searchText());
            if (config.vectorEnabled()) {
                document.put("embedding", vectors.get(index));
            }
            documents.add(document);
        }
        openSearch.bulk(indexName, "reportId", documents);
        openSearch.refresh(indexName);
        long count = openSearch.count(
                indexName,
                Map.of("match_all", Map.of())
        );
        if (count != reports.size()) {
            throw new IllegalStateException(
                    "Global report index count does not match the build"
            );
        }
        return new BuildResult(count, config.vectorEnabled() ? count : 0);
    }

    public SearchResult search(
            String indexName,
            String indexConfigVersion,
            String query,
            int bm25TopK,
            int vectorTopK,
            int rankConstant
    ) {
        IndexConfigView config = configurations.indexConfig(
                indexConfigVersion
        );
        Future<JsonNode> bm25Future = executor.submit(() ->
                openSearch.search(indexName, bm25Body(query, bm25TopK))
        );
        Future<JsonNode> vectorFuture = null;
        JsonNode vector = null;
        String degradationCode = null;
        if (config.vectorEnabled() && vectorTopK > 0) {
            vectorFuture = executor.submit(() -> {
                List<Double> queryVector = embeddings.embedQuery(
                        config,
                        query
                );
                return openSearch.search(
                        indexName,
                        vectorBody(queryVector, vectorTopK)
                );
            });
        } else if (vectorTopK > 0) {
            degradationCode = "GLOBAL_VECTOR_INDEX_UNAVAILABLE";
        }
        JsonNode bm25;
        try {
            bm25 = await(bm25Future);
        } catch (RuntimeException exception) {
            if (vectorFuture != null) {
                vectorFuture.cancel(true);
            }
            throw exception;
        }
        if (vectorFuture != null) {
            try {
                vector = await(vectorFuture);
            } catch (RuntimeException exception) {
                degradationCode = "GLOBAL_VECTOR_UNAVAILABLE";
            }
        }
        Map<UUID, MutableRank> ranks = new LinkedHashMap<>();
        add(ranks, bm25, true);
        add(ranks, vector, false);
        List<RankedReport> ordered = ranks.values().stream()
                .map(item -> item.freeze(rankConstant))
                .sorted(Comparator
                        .comparingDouble(RankedReport::score)
                        .reversed()
                        .thenComparing(item -> item.reportId().toString()))
                .toList();
        return new SearchResult(ordered, degradationCode);
    }

    public void delete(String indexName) {
        openSearch.deleteIndex(indexName);
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Global report search was interrupted",
                    exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Global report search failed",
                    cause
            );
        }
    }

    private static void add(
            Map<UUID, MutableRank> ranks,
            JsonNode response,
            boolean bm25
    ) {
        if (response == null) {
            return;
        }
        int rank = 0;
        for (JsonNode hit : response.path("hits").path("hits")) {
            rank++;
            UUID id;
            try {
                id = UUID.fromString(
                        hit.path("_source").path("reportId").asText()
                );
            } catch (IllegalArgumentException exception) {
                continue;
            }
            MutableRank value = ranks.computeIfAbsent(
                    id,
                    MutableRank::new
            );
            if (bm25) {
                value.bm25Rank = rank;
            } else {
                value.vectorRank = rank;
            }
        }
    }

    private static Map<String, Object> bm25Body(
            String query,
            int topK
    ) {
        return Map.of(
                "size", topK,
                "_source", List.of("reportId"),
                "query", Map.of(
                        "multi_match", Map.of(
                                "query", query,
                                "fields", List.of(
                                        "title^4",
                                        "title.english^4",
                                        "summary^2",
                                        "summary.english^2",
                                        "text",
                                        "text.english"
                                ),
                                "type", "best_fields",
                                "operator", "or"
                        )
                )
        );
    }

    private static Map<String, Object> vectorBody(
            List<Double> vector,
            int topK
    ) {
        return Map.of(
                "size", topK,
                "_source", List.of("reportId"),
                "query", Map.of(
                        "knn", Map.of(
                                "embedding", Map.of(
                                        "vector", vector,
                                        "k", topK
                                )
                        )
                )
        );
    }

    private static Map<String, Object> indexDefinition(
            IndexConfigView config
    ) {
        Map<String, Object> bilingual = Map.of(
                "type", "text",
                "analyzer", "cjk",
                "fields", Map.of(
                        "english",
                        Map.of("type", "text", "analyzer", "english")
                )
        );
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportId", Map.of("type", "keyword"));
        fields.put("globalGeneration", Map.of("type", "long"));
        fields.put("communityKey", Map.of("type", "integer"));
        fields.put("title", bilingual);
        fields.put("summary", bilingual);
        fields.put("text", bilingual);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        if (config.vectorEnabled()) {
            settings.put("knn", true);
            fields.put("embedding", Map.of(
                    "type", "knn_vector",
                    "dimension", config.vectorDimensions(),
                    "method", Map.of(
                            "name", "hnsw",
                            "engine", "lucene",
                            "space_type", spaceType(config.distance()),
                            "parameters", Map.of(
                                    "m", config.hnswM(),
                                    "ef_construction",
                                    config.hnswEfConstruction()
                            )
                    )
            ));
        }
        return Map.of(
                "settings", settings,
                "mappings", Map.of(
                        "dynamic", "strict",
                        "properties", fields
                )
        );
    }

    private static String spaceType(String distance) {
        return switch (distance) {
            case "COSINE" -> "cosinesimil";
            case "L2" -> "l2";
            case "INNER_PRODUCT" -> "innerproduct";
            default -> throw new IllegalArgumentException(
                    "Unsupported vector distance: " + distance
            );
        };
    }

    public record ReportDocument(
            UUID id,
            long globalGeneration,
            int communityKey,
            String title,
            String summary,
            String searchText,
            String contentHash
    ) {
    }

    public record BuildResult(
            long indexedReportCount,
            long validVectorCount
    ) {
    }

    public record RankedReport(
            UUID reportId,
            Integer bm25Rank,
            Integer vectorRank,
            double score
    ) {
    }

    public record SearchResult(
            List<RankedReport> reports,
            String degradationCode
    ) {
    }

    private static final class MutableRank {

        private final UUID id;
        private Integer bm25Rank;
        private Integer vectorRank;

        private MutableRank(UUID id) {
            this.id = id;
        }

        private RankedReport freeze(int constant) {
            double score = 0.0;
            if (bm25Rank != null) {
                score += 1.0 / (constant + bm25Rank);
            }
            if (vectorRank != null) {
                score += 1.0 / (constant + vectorRank);
            }
            return new RankedReport(
                    id,
                    bm25Rank,
                    vectorRank,
                    score
            );
        }
    }
}
