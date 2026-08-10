package com.example.rag.graph;

import com.example.rag.graph.GraphExtractionProvider.ChildEvidence;
import com.example.rag.graph.GraphExtractionProvider.ExtractionInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GraphExtractionProviderTests {

    @Test
    void promptV3RequestsExplicitTechnicalConceptRelationships() {
        GraphProperties properties = new GraphProperties();
        properties.getExtraction().setEnabled(true);
        properties.getExtraction().setBaseUrl("http://models/v1");
        properties.getExtraction().setModel("test-model");
        properties.getExtraction().setRevision("test-revision");
        properties.getExtraction().setLocalEndpoint(true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://models/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("技术组件、机制、数据对象、能力和流程阶段"),
                        containsString("短技术文档"),
                        containsString("X supports Y"),
                        containsString("主语、谓语和宾语"),
                        containsString("最多输出 6 个实体和 6 条最直接的关系"),
                        containsString("直接输出紧凑单行 JSON"),
                        containsString("\"response_format\":{\"type\":\"json_object\"}"),
                        not(containsString("\"thinking\"")),
                        containsString("Subsystem supports capability.")
                )))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [{
                            "message": {
                              "content": "{\\"entities\\":[],\\"relationships\\":[]}"
                            }
                          }]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        GraphExtractionProvider provider =
                new OpenAiCompatibleGraphExtractionProvider(
                        builder.build(),
                        new ObjectMapper(),
                        properties
                );

        var result = provider.extract(new ExtractionInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Technical note",
                List.of("Overview"),
                "Subsystem supports capability.",
                List.of(new ChildEvidence(
                        UUID.randomUUID(),
                        List.of("Overview"),
                        "Subsystem supports capability."
                ))
        ));

        assertThat(provider.descriptor().promptVersion())
                .isEqualTo("phase8-graph-prompt-v3");
        assertThat(result.entities()).isEmpty();
        assertThat(result.relationships()).isEmpty();
        server.verify();
    }

    @Test
    void deepSeekV4DisablesThinking() {
        GraphProperties properties = new GraphProperties();
        properties.getExtraction().setEnabled(true);
        properties.getExtraction().setBaseUrl("http://models/v1");
        properties.getExtraction().setModel("DeepSeek-V4-Flash");
        properties.getExtraction().setRevision("test-revision");
        properties.getExtraction().setLocalEndpoint(true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://models/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("\"model\":\"DeepSeek-V4-Flash\""),
                        containsString("\"thinking\":{\"type\":\"disabled\"}")
                )))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [{
                            "message": {
                              "content": "{\\"entities\\":[],\\"relationships\\":[]}"
                            }
                          }]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        GraphExtractionProvider provider =
                new OpenAiCompatibleGraphExtractionProvider(
                        builder.build(),
                        new ObjectMapper(),
                        properties
                );

        provider.extract(new ExtractionInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Technical note",
                List.of(),
                "Subsystem supports capability.",
                List.of(new ChildEvidence(
                        UUID.randomUUID(),
                        List.of(),
                        "Subsystem supports capability."
                ))
        ));

        server.verify();
    }
}
