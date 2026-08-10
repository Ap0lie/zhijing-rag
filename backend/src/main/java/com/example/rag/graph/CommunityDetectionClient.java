package com.example.rag.graph;

import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CommunityDetectionClient {

    private final RestClient client;

    CommunityDetectionClient(RestClient client) {
        this.client = client;
    }

    Map<String, Integer> detect(
            List<String> nodes,
            List<CommunityEdge> edges,
            String expectedVersion,
            long seed,
            double resolution
    ) {
        if (nodes.isEmpty()) {
            return Map.of();
        }
        CommunityResponse response;
        try {
            response = client.post()
                    .uri("/community-detection")
                    .body(new CommunityRequest(
                            nodes,
                            edges,
                            seed,
                            resolution
                    ))
                    .retrieve()
                    .body(CommunityResponse.class);
        } catch (RuntimeException exception) {
            throw new CommunityDetectionException(
                    "GRAPH_COMMUNITY_UNAVAILABLE",
                    "Leiden Community 服务暂时不可用",
                    exception
            );
        }
        if (response == null
                || !"leidenalg".equals(response.algorithm())
                || !expectedVersion.equals(response.version())
                || response.assignments() == null
                || response.assignments().size() != nodes.size()) {
            throw invalid();
        }
        Set<String> expectedNodes = new HashSet<>(nodes);
        Map<String, Integer> assignments = new LinkedHashMap<>();
        for (CommunityAssignment assignment : response.assignments()) {
            if (assignment == null
                    || !expectedNodes.contains(assignment.node())
                    || assignment.community() < 0
                    || assignments.putIfAbsent(
                    assignment.node(),
                    assignment.community()
            ) != null) {
                throw invalid();
            }
        }
        if (assignments.size() != nodes.size()) {
            throw invalid();
        }
        return Map.copyOf(assignments);
    }

    record CommunityEdge(String source, String target, double weight) {
    }

    private record CommunityRequest(
            List<String> nodes,
            List<CommunityEdge> edges,
            long seed,
            double resolution
    ) {
    }

    private record CommunityResponse(
            String algorithm,
            String version,
            List<CommunityAssignment> assignments
    ) {
    }

    private record CommunityAssignment(String node, int community) {
    }

    private static CommunityDetectionException invalid() {
        return new CommunityDetectionException(
                "GRAPH_COMMUNITY_RESPONSE_INVALID",
                "Leiden Community 服务返回了无效结果"
        );
    }
}

final class CommunityDetectionException extends RuntimeException {

    private final String code;

    CommunityDetectionException(String code, String message) {
        super(message);
        this.code = code;
    }

    CommunityDetectionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
