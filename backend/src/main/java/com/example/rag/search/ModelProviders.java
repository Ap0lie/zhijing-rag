package com.example.rag.search;

import com.example.rag.search.ModelServiceProperties.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

interface EmbeddingProvider {

    ModelDescriptor descriptor();

    List<List<Double>> embed(List<String> inputs);

    void health();
}

interface RerankProvider {

    ModelDescriptor descriptor();

    List<RerankScore> rerank(String query, List<String> documents);

    void health();
}

record ModelDescriptor(
        boolean enabled,
        String model,
        String revision,
        Integer dimensions
) {
}

record RerankScore(int index, double score) {
}

final class HttpEmbeddingProvider implements EmbeddingProvider {

    private final Endpoint properties;
    private final RestClient client;

    HttpEmbeddingProvider(Endpoint properties) {
        this(properties, ModelHttpClientFactory.create(properties));
    }

    HttpEmbeddingProvider(Endpoint properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public ModelDescriptor descriptor() {
        return ModelHttpClientFactory.descriptor(properties);
    }

    @Override
    public List<List<Double>> embed(List<String> inputs) {
        requireEnabled();
        if (inputs == null || inputs.isEmpty() || inputs.stream().anyMatch(ModelProviders::blank)) {
            throw new IllegalArgumentException("Embedding inputs must be non-empty");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getModel());
        request.put("input", inputs);
        request.put("encoding_format", "float");
        JsonNode response = client.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        return embeddings(response, inputs.size(), requiredDimensions());
    }

    @Override
    public void health() {
        requireEnabled();
        client.get().uri("/health").retrieve().toBodilessEntity();
    }

    private int requiredDimensions() {
        Integer dimensions = properties.getDimensions();
        if (dimensions == null || dimensions < 1) {
            throw new IllegalStateException("Embedding dimensions must be configured");
        }
        return dimensions;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Embedding service is disabled");
        }
    }

    private static List<List<Double>> embeddings(
            JsonNode response,
            int expectedCount,
            int expectedDimensions
    ) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.size() != expectedCount) {
            throw invalidResponse("Embedding response count does not match the request");
        }
        List<List<Double>> result = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            JsonNode item = data.get(index);
            if (!item.path("index").canConvertToInt() || item.path("index").asInt() != index) {
                throw invalidResponse("Embedding response order is invalid");
            }
            JsonNode values = item.path("embedding");
            if (!values.isArray() || values.size() != expectedDimensions) {
                throw invalidResponse("Embedding vector dimensions are invalid");
            }
            boolean nonZero = false;
            List<Double> vector = new ArrayList<>(expectedDimensions);
            for (JsonNode value : values) {
                if (!value.isNumber()) {
                    throw invalidResponse("Embedding vector contains a non-number");
                }
                double number = value.asDouble();
                if (!Double.isFinite(number)) {
                    throw invalidResponse("Embedding vector contains a non-finite number");
                }
                nonZero |= number != 0.0d;
                vector.add(number);
            }
            if (!nonZero) {
                throw invalidResponse("Embedding vector must not be all zero");
            }
            result.add(List.copyOf(vector));
        }
        return List.copyOf(result);
    }

    private static ModelResponseException invalidResponse(String message) {
        return new ModelResponseException(message);
    }
}

final class HttpRerankProvider implements RerankProvider {

    private final Endpoint properties;
    private final RestClient client;

    HttpRerankProvider(Endpoint properties) {
        this(properties, ModelHttpClientFactory.create(properties));
    }

    HttpRerankProvider(Endpoint properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public ModelDescriptor descriptor() {
        return ModelHttpClientFactory.descriptor(properties);
    }

    @Override
    public List<RerankScore> rerank(String query, List<String> documents) {
        requireEnabled();
        if (ModelProviders.blank(query)
                || documents == null
                || documents.isEmpty()
                || documents.stream().anyMatch(ModelProviders::blank)) {
            throw new IllegalArgumentException("Rerank query and documents must be non-empty");
        }
        JsonNode response = client.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", properties.getModel(),
                        "query", query,
                        "documents", documents,
                        "top_n", documents.size()
                ))
                .retrieve()
                .body(JsonNode.class);
        return scores(response, documents.size());
    }

    @Override
    public void health() {
        requireEnabled();
        client.get().uri("/health").retrieve().toBodilessEntity();
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Rerank service is disabled");
        }
    }

    private static List<RerankScore> scores(JsonNode response, int expectedCount) {
        JsonNode results = response == null ? null : response.path("results");
        if (results == null || !results.isArray() || results.size() != expectedCount) {
            throw new ModelResponseException("Rerank response count does not match the request");
        }
        Set<Integer> seen = new HashSet<>();
        List<RerankScore> scores = new ArrayList<>(expectedCount);
        double previousScore = Double.POSITIVE_INFINITY;
        for (JsonNode item : results) {
            JsonNode indexNode = item.path("index");
            JsonNode scoreNode = item.path("relevance_score");
            if (!indexNode.canConvertToInt() || !scoreNode.isNumber()) {
                throw new ModelResponseException("Rerank response item is invalid");
            }
            int index = indexNode.asInt();
            double score = scoreNode.asDouble();
            if (index < 0
                    || index >= expectedCount
                    || !seen.add(index)
                    || !Double.isFinite(score)
                    || score > previousScore) {
                throw new ModelResponseException("Rerank response indices or scores are invalid");
            }
            scores.add(new RerankScore(index, score));
            previousScore = score;
        }
        if (seen.size() != expectedCount) {
            throw new ModelResponseException("Rerank response is incomplete");
        }
        return List.copyOf(scores);
    }
}

final class ModelHttpClientFactory {

    private ModelHttpClientFactory() {
    }

    static RestClient create(Endpoint properties) {
        URI baseUrl;
        try {
            baseUrl = URI.create(properties.getBaseUrl());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Model service base URL is invalid", exception);
        }
        if (!Set.of("http", "https").contains(baseUrl.getScheme()) || baseUrl.getHost() == null) {
            throw new IllegalStateException("Model service base URL must use HTTP or HTTPS");
        }
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("Model service timeout must be positive");
        }
        if (ModelProviders.blank(properties.getModel())
                || ModelProviders.blank(properties.getRevision())) {
            throw new IllegalStateException("Model and revision must be configured");
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .build();
    }

    static ModelDescriptor descriptor(Endpoint properties) {
        return new ModelDescriptor(
                properties.isEnabled(),
                properties.getModel(),
                properties.getRevision(),
                properties.getDimensions()
        );
    }
}

final class ModelProviders {

    private ModelProviders() {
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

final class ModelResponseException extends RuntimeException {

    ModelResponseException(String message) {
        super(message);
    }
}
