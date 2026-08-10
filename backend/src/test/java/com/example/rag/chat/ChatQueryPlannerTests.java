package com.example.rag.chat;

import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchService.Coverage;
import com.example.rag.search.SearchService.QueryExecutionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatQueryPlannerTests {

    @Test
    void explicitHybridKeepsExplicitReason() throws Exception {
        try (Fixture fixture = fixture("""
                {"standaloneQuery":"question","queries":["question"],
                 "routeMode":"HYBRID","routeReasonCode":"RELATION"}
                """)) {
            var plan = fixture.planner().initial(
                    "question", List.of(), GraphMode.HYBRID,
                    profile(), policy()
            );

            assertThat(plan.routedMode()).isEqualTo(GraphMode.HYBRID);
            assertThat(plan.routeReasonCode()).isEqualTo("EXPLICIT");
            assertThat(fixture.requestBody()).contains(
                    "\\\"requestedMode\\\":\\\"HYBRID\\\""
            );
        }
    }

    @Test
    void refinementUsesOnlyBudgetAndAttemptFacts() throws Exception {
        try (Fixture fixture = fixture("""
                {"queries":["Bridge Entity next hop"]}
                """)) {
            var plan = fixture.planner().refine(
                    "question",
                    List.of(),
                    List.of("question"),
                    new Coverage(30, 2),
                    profile(),
                    policy()
            );

            assertThat(plan.queries()).containsExactly(
                    "Bridge Entity next hop"
            );
            assertThat(fixture.requestBody())
                    .contains("\\\"authorizedCandidateCount\\\":30")
                    .contains("\\\"remainingSlots\\\":2")
                    .doesNotContain("evidenceHints");
        }
    }

    private static Fixture fixture(String content) throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );
        byte[] response = response(content).getBytes(StandardCharsets.UTF_8);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json"
            );
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
        );
        properties.getLlm().setModel("planner-model");
        properties.getLlm().setRemoteEvidenceAllowed(true);
        return new Fixture(
                new OpenAiCompatibleChatQueryPlanner(
                        RestClient.builder(), new ObjectMapper(), properties
                ),
                server,
                requestBody
        );
    }

    private static ProfileView profile() {
        ProfileView profile = mock(ProfileView.class);
        when(profile.enabled()).thenReturn(true);
        when(profile.promptVersion()).thenReturn(
                QueryIntelligenceProfileService.PROMPT_VERSION
        );
        when(profile.schemaVersion()).thenReturn(
                QueryIntelligenceProfileService.SCHEMA_VERSION
        );
        when(profile.maxSubQueries()).thenReturn(3);
        when(profile.plannerCallLimit()).thenReturn(2);
        return profile;
    }

    private static QueryExecutionPolicy policy() {
        return QueryExecutionPolicy.start(3, 2, 2, 5_000);
    }

    private static String response(String content) {
        return "{\"choices\":[{\"message\":{\"content\":"
                + quote(content.strip()) + "}}]}";
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            OpenAiCompatibleChatQueryPlanner planner,
            HttpServer server,
            AtomicReference<String> body
    ) implements AutoCloseable {

        String requestBody() {
            return body.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
