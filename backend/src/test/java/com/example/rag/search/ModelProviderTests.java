package com.example.rag.search;

import com.example.rag.search.ModelServiceProperties.Endpoint;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelProviderTests {

    @Test
    void embeddingResponsePreservesRequestOrderAndValidatesEveryVector() {
        Endpoint properties = endpoint("embedding-model", 3);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://models");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://models/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(not(containsString("\"dimensions\""))))
                .andRespond(withSuccess(
                        """
                        {
                          "data": [
                            {"index": 0, "embedding": [0.1, 0.2, 0.3]},
                            {"index": 1, "embedding": [0.4, 0.5, 0.6]}
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var provider = new HttpEmbeddingProvider(properties, builder.build());

        assertThat(provider.embed(List.of("first", "second")))
                .containsExactly(
                        List.of(0.1d, 0.2d, 0.3d),
                        List.of(0.4d, 0.5d, 0.6d)
                );
        server.verify();
    }

    @Test
    void embeddingRejectsWrongOrderDimensionsAndZeroVectors() {
        assertInvalidEmbedding(
                """
                {"data":[
                  {"index":1,"embedding":[0.1,0.2,0.3]},
                  {"index":0,"embedding":[0.4,0.5,0.6]}
                ]}
                """
        );
        assertInvalidEmbedding(
                """
                {"data":[
                  {"index":0,"embedding":[0.1,0.2]},
                  {"index":1,"embedding":[0.4,0.5,0.6]}
                ]}
                """
        );
        assertInvalidEmbedding(
                """
                {"data":[
                  {"index":0,"embedding":[0.0,0.0,0.0]},
                  {"index":1,"embedding":[0.4,0.5,0.6]}
                ]}
                """
        );
    }

    @Test
    void rerankPreservesRankedResponseAndRejectsDuplicateIndices() {
        Endpoint properties = endpoint("rerank-model", null);
        RestClient.Builder validBuilder = RestClient.builder().baseUrl("http://models");
        MockRestServiceServer validServer = MockRestServiceServer.bindTo(validBuilder).build();
        validServer.expect(requestTo("http://models/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"results":[
                          {"index":1,"relevance_score":0.9},
                          {"index":0,"relevance_score":0.4}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON
                ));
        var valid = new HttpRerankProvider(properties, validBuilder.build());

        assertThat(valid.rerank("query", List.of("first", "second")))
                .containsExactly(new RerankScore(1, 0.9d), new RerankScore(0, 0.4d));
        validServer.verify();

        RestClient.Builder invalidBuilder = RestClient.builder().baseUrl("http://models");
        MockRestServiceServer invalidServer = MockRestServiceServer.bindTo(invalidBuilder).build();
        invalidServer.expect(requestTo("http://models/rerank"))
                .andRespond(withSuccess(
                        """
                        {"results":[
                          {"index":0,"relevance_score":0.9},
                          {"index":0,"relevance_score":0.4}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON
                ));
        var invalid = new HttpRerankProvider(properties, invalidBuilder.build());

        assertThatThrownBy(() -> invalid.rerank("query", List.of("first", "second")))
                .isInstanceOf(ModelResponseException.class);
        invalidServer.verify();
    }

    @Test
    void defaultEmbeddingConfigurationUsesThePinnedQwenRevisionAnd1024Dimensions() {
        var properties = new ModelServiceProperties();

        assertThat(properties.getEmbedding().getModel())
                .isEqualTo("Qwen/Qwen3-Embedding-0.6B");
        assertThat(properties.getEmbedding().getRevision())
                .isEqualTo("97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3");
        assertThat(properties.getEmbedding().getDimensions()).isEqualTo(1024);
        assertThat(properties.getRerank().getRevision())
                .isEqualTo("e61197ed45024b0ed8a2d74b80b4d909f1255473");
    }

    private static void assertInvalidEmbedding(String response) {
        Endpoint properties = endpoint("embedding-model", 3);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://models");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://models/v1/embeddings"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var provider = new HttpEmbeddingProvider(properties, builder.build());

        assertThatThrownBy(() -> provider.embed(List.of("first", "second")))
                .isInstanceOf(ModelResponseException.class);
        server.verify();
    }

    private static Endpoint endpoint(String model, Integer dimensions) {
        Endpoint endpoint = new Endpoint(
                "http://models",
                model,
                "0123456789abcdef",
                dimensions
        );
        endpoint.setEnabled(true);
        endpoint.setTimeout(Duration.ofSeconds(1));
        return endpoint;
    }
}
