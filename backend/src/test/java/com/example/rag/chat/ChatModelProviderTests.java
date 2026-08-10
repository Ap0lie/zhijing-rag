package com.example.rag.chat;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChatModelProviderTests {

    @Test
    void deepSeekV4DisablesThinking() {
        executeAndVerify(
                "DEEPSEEK-V4-Flash",
                containsString("\"thinking\":{\"type\":\"disabled\"}")
        );
    }

    @Test
    void otherOpenAiCompatibleModelsKeepDefaultRequest() {
        executeAndVerify("other-model", not(containsString("\"thinking\"")));
    }

    @Test
    void verificationQuestionsReceiveGroundedCorrectionInstructions() {
        executeAndVerify(
                "other-model",
                allOf(
                        containsString("核验、纠错或“是否有原文依据”类问题不能继承问题中的前提"),
                        containsString("所提供原文未记载该说法"),
                        containsString("不得扩大成“任何资料都不存在该事实”")
                )
        );
    }

    @Test
    void requestsAndParsesCitationBoundDirectAnswer() {
        UUID citationId = UUID.randomUUID();
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setBaseUrl("http://models/v1");
        properties.getLlm().setModel("other-model");
        properties.getLlm().setLocalEndpoint(true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://models/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("纯英文问题的 directAnswer 必须使用英文"),
                        containsString("directAnswerCitationIds"),
                        containsString("directAnswer 不替代 segments")
                )))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [{
                            "message": {
                              "content": "{\\"segments\\":[{\\"text\\":\\"The answer is Paris.\\",\\"citationIds\\":[\\"%s\\"],\\"memoryIds\\":[]}],\\"refusalReason\\":null,\\"directAnswer\\":\\"Paris\\",\\"directAnswerCitationIds\\":[\\"%s\\"]}"
                            }
                          }]
                        }
                        """.formatted(citationId, citationId),
                        MediaType.APPLICATION_JSON
                ));
        ChatModelProvider provider = new OpenAiCompatibleChatModelProvider(
                builder.build(),
                new ObjectMapper(),
                properties
        );

        ChatModelProvider.ModelAnswer answer = provider.answer(
                "What is the capital?",
                List.of()
        );

        assertThat(answer.directAnswer()).isEqualTo("Paris");
        assertThat(answer.directAnswerCitationIds())
                .containsExactly(citationId);
        assertThat(answer.segments()).singleElement()
                .satisfies(segment -> assertThat(segment.citationIds())
                        .containsExactly(citationId));
        server.verify();
    }

    @Test
    void twoArgumentModelAnswerKeepsFakeProviderCompatibility() {
        ChatModelProvider.ModelAnswer answer =
                new ChatModelProvider.ModelAnswer(List.of(), "declined");

        assertThat(answer.directAnswer()).isNull();
        assertThat(answer.directAnswerCitationIds()).isEmpty();
    }

    @Test
    void reportsLengthLimitedResponsesAsTruncated() {
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setBaseUrl("http://models/v1");
        properties.getLlm().setModel("deepseek-v4-flash");
        properties.getLlm().setLocalEndpoint(true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://models/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [{
                            "finish_reason": "length",
                            "message": {"content": "{\\"segments\\":["}
                          }]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        ChatModelProvider provider = new OpenAiCompatibleChatModelProvider(
                builder.build(),
                new ObjectMapper(),
                properties
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> provider.answer("What happened?", List.of())
        );

        assertThat(exception.code()).isEqualTo("LLM_OUTPUT_TRUNCATED");
        server.verify();
    }

    private void executeAndVerify(
            String model,
            org.hamcrest.Matcher<String> bodyMatcher
    ) {
        ChatProperties properties = new ChatProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setBaseUrl("http://models/v1");
        properties.getLlm().setModel(model);
        properties.getLlm().setLocalEndpoint(true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://models/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("\"model\":\"" + model + "\""),
                        bodyMatcher
                )))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [{
                            "message": {
                              "content": "{\\"segments\\":[],\\"refusalReason\\":\\"insufficient evidence\\"}"
                            }
                          }]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        ChatModelProvider provider = new OpenAiCompatibleChatModelProvider(
                builder.build(),
                new ObjectMapper(),
                properties
        );

        provider.answer("What is supported?", List.of());

        server.verify();
    }
}
