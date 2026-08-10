package com.example.rag.search;

import com.example.rag.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class OpenSearchGateway {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final RestClient client;
    private final ObjectMapper json;

    OpenSearchGateway(RestClient openSearchRestClient, ObjectMapper json) {
        this.client = openSearchRestClient;
        this.json = json;
    }

    void createIndex(String name, Map<String, Object> body) {
        exchange(() -> client.put().uri("/{index}", name).body(body).retrieve().toBodilessEntity());
    }

    void deleteIndex(String name) {
        try {
            client.delete().uri("/{index}", name).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw unavailable(exception);
            }
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    void bulk(String index, List<Map<String, Object>> documents) {
        bulk(index, "chunkId", documents);
    }

    void bulk(
            String index,
            String idField,
            List<Map<String, Object>> documents
    ) {
        if (documents.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        try {
            for (Map<String, Object> document : documents) {
                Object documentId = document.get(idField);
                if (documentId == null) {
                    throw new IllegalArgumentException(
                            "OpenSearch bulk document is missing " + idField
                    );
                }
                body.append(json.writeValueAsString(Map.of(
                        "index", Map.of(
                                "_index", index,
                                "_id", documentId
                        )
                ))).append('\n');
                body.append(json.writeValueAsString(document)).append('\n');
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to encode OpenSearch bulk request", exception);
        }
        JsonNode response = exchange(() -> client.post()
                .uri("/_bulk")
                .contentType(NDJSON)
                .body(body.toString())
                .retrieve()
                .body(JsonNode.class));
        if (response == null || response.path("errors").asBoolean()) {
            throw unavailable(new IllegalStateException(firstBulkError(response)));
        }
    }

    void refresh(String index) {
        exchange(() -> client.post().uri("/{index}/_refresh", index).retrieve().toBodilessEntity());
    }

    JsonNode search(String alias, Map<String, Object> body) {
        JsonNode response = exchange(() -> client.post()
                .uri("/{index}/_search", alias)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        if (response == null) {
            throw unavailable(new IllegalStateException("OpenSearch returned no response"));
        }
        return response;
    }

    long count(String index, Map<String, Object> query) {
        JsonNode response = exchange(() -> client.post()
                .uri("/{index}/_count", index)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query))
                .retrieve()
                .body(JsonNode.class));
        if (response == null || !response.path("count").canConvertToLong()) {
            throw unavailable(new IllegalStateException("OpenSearch returned an invalid count"));
        }
        return response.path("count").asLong();
    }

    void switchAlias(String alias, String index) {
        List<Map<String, Object>> actions = new ArrayList<>();
        try {
            JsonNode existing = client.get().uri("/_alias/{alias}", alias).retrieve().body(JsonNode.class);
            if (existing != null) {
                existing.fieldNames().forEachRemaining(name ->
                        actions.add(Map.of("remove", Map.of("index", name, "alias", alias))));
            }
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw unavailable(exception);
            }
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
        actions.add(Map.of("add", Map.of("index", index, "alias", alias)));
        exchange(() -> client.post()
                .uri("/_aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", actions))
                .retrieve()
                .toBodilessEntity());
    }

    boolean aliasPointsTo(String alias, String index) {
        try {
            JsonNode existing = client.get()
                    .uri("/_alias/{alias}", alias)
                    .retrieve()
                    .body(JsonNode.class);
            return existing != null && existing.size() == 1 && existing.has(index);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return false;
            }
            throw unavailable(exception);
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    long updateProjection(
            String index,
            String documentId,
            String revisionId,
            String title,
            long aclVersion,
            String visibility,
            String ownerId,
            List<String> grantedUserIds
    ) {
        Map<String, Object> params = Map.of(
                "title", title,
                "aclVersion", aclVersion,
                "visibility", visibility,
                "ownerUserId", ownerId,
                "grantedUserIds", grantedUserIds
        );
        String source = """
                ctx._source.title = params.title;
                ctx._source.documentTitle = params.title;
                ctx._source.aclVersion = params.aclVersion;
                ctx._source.visibility = params.visibility;
                ctx._source.ownerUserId = params.ownerUserId;
                ctx._source.grantedUserIds = params.grantedUserIds;
                ctx._source.accessProjectionKey =
                    ctx._source.documentId + ':' + ctx._source.revisionId + ':' + params.aclVersion;
                """;
        return updateByQuery(
                index,
                documentRevisionQuery(documentId, revisionId),
                Map.of("source", source, "lang", "painless", "params", params)
        ).path("updated").asLong();
    }

    long deleteDocument(String index, String documentId) {
        return deleteByQuery(index, documentQuery(documentId));
    }

    long deleteRevision(String index, String revisionId) {
        return deleteByQuery(index, termQuery("revisionId", revisionId));
    }

    long deleteNonCurrentRevisions(String index, String documentId, String revisionId) {
        Map<String, Object> query = Map.of(
                "bool", Map.of(
                        "filter", List.of(termQuery("documentId", documentId)),
                        "must_not", List.of(termQuery("revisionId", revisionId))
                )
        );
        return deleteByQuery(index, query);
    }

    private long deleteByQuery(String index, Map<String, Object> query) {
        JsonNode response = exchange(() -> client.post()
                .uri("/{index}/_delete_by_query?refresh=true", index)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query))
                .retrieve()
                .body(JsonNode.class));
        return validateByQuery(response).path("deleted").asLong();
    }

    private JsonNode updateByQuery(
            String index,
            Map<String, Object> query,
            Map<String, Object> script
    ) {
        JsonNode response = exchange(() -> client.post()
                .uri("/{index}/_update_by_query?refresh=true", index)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "script", script))
                .retrieve()
                .body(JsonNode.class));
        return validateByQuery(response);
    }

    private static JsonNode validateByQuery(JsonNode response) {
        if (response == null
                || response.path("timed_out").asBoolean()
                || response.path("version_conflicts").asLong() > 0
                || !response.path("failures").isEmpty()) {
            throw unavailable(new IllegalStateException("OpenSearch projection update was incomplete"));
        }
        return response;
    }

    private static Map<String, Object> documentQuery(String documentId) {
        return termQuery("documentId", documentId);
    }

    private static Map<String, Object> documentRevisionQuery(
            String documentId,
            String revisionId
    ) {
        return Map.of("bool", Map.of("filter", List.of(
                termQuery("documentId", documentId),
                termQuery("revisionId", revisionId)
        )));
    }

    private static Map<String, Object> termQuery(String field, String value) {
        return Map.of("term", Map.of(field, value));
    }

    private <T> T exchange(Request<T> request) {
        try {
            return request.execute();
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    private static ApiException unavailable(Throwable cause) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SEARCH_UNAVAILABLE",
                "搜索服务暂时不可用",
                cause
        );
    }

    private static String firstBulkError(JsonNode response) {
        if (response == null) {
            return "OpenSearch bulk request returned no response";
        }
        for (JsonNode item : response.path("items")) {
            JsonNode error = item.path("index").path("error");
            if (!error.isMissingNode()) {
                return error.path("reason").asText("OpenSearch bulk request failed");
            }
        }
        return "OpenSearch bulk request failed";
    }

    @FunctionalInterface
    private interface Request<T> {
        T execute();
    }
}
