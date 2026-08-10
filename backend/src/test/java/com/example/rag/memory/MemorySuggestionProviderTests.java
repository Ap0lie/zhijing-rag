package com.example.rag.memory;

import com.example.rag.chat.ChatProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MemorySuggestionProviderTests {

    @Test
    void extractsOnlyExplicitSupportedUserMemory() {
        ChatProperties chat = new ChatProperties();
        chat.getLlm().setEnabled(true);
        chat.getLlm().setBaseUrl("http://models/v1");
        chat.getLlm().setModel("deepseek-chat");
        chat.getLlm().setLocalEndpoint(true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://models/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("以后回答请先给结论"),
                        not(containsString("assistant answer"))
                )))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [{
                            "message": {
                              "content": "{\\"suggestions\\":[{\\"memoryType\\":\\"USER_PREFERENCE\\",\\"memoryKey\\":\\"回答顺序\\",\\"content\\":\\"回答时先给结论\\"},{\\"memoryType\\":\\"DOCUMENT_FACT\\",\\"memoryKey\\":\\"非法\\",\\"content\\":\\"不能保存\\"},{\\"memoryType\\":\\"USER_PREFERENCE\\",\\"memoryKey\\":\\"回答顺序\\",\\"content\\":\\"重复项\\"}]}"
                            }
                          }]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        MemorySuggestionProvider provider =
                new OpenAiCompatibleMemorySuggestionProvider(
                        builder.build(),
                        new ObjectMapper(),
                        chat,
                        new MemorySuggestionProperties(
                                false,
                                "test-worker",
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(45),
                                3,
                                3,
                                "extractor-v1",
                                "prompt-v1"
                        )
                );

        var suggestions = provider.suggest(
                provider.snapshot(),
                "以后回答请先给结论"
        );

        assertThat(suggestions).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.memoryType())
                    .isEqualTo("USER_PREFERENCE");
            assertThat(suggestion.memoryKey()).isEqualTo("回答顺序");
            assertThat(suggestion.content()).isEqualTo("回答时先给结论");
        });
        server.verify();
    }

    @Test
    void rejectsRemoteSuggestionWhenMemoryPermissionIsDisabled() {
        ChatProperties chat = enabledChat("https://api.example/v1", false);
        MemorySuggestionProvider provider = provider(
                RestClient.builder().build(),
                chat
        );

        assertThatThrownBy(() -> provider.suggest(
                provider.snapshot(),
                "以后回答请先给结论"
        ))
                .isInstanceOfSatisfying(
                        MemorySuggestionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(
                                        "MEMORY_SUGGESTION_REMOTE_NOT_ALLOWED"
                                )
                );
    }

    @Test
    void rejectsCredentialBeforeCallingModel() {
        ChatProperties chat = enabledChat("http://models/v1", true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        MemorySuggestionProvider provider = provider(builder.build(), chat);

        assertThatThrownBy(() -> provider.suggest(
                provider.snapshot(),
                "请记住我的 api_key=sk-secret-value"
        )).isInstanceOfSatisfying(
                MemorySuggestionException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("MEMORY_SUGGESTION_INPUT_REJECTED")
        );
        server.verify();
    }

    @Test
    void rejectsFrozenSnapshotMismatchBeforeCallingModel() {
        ChatProperties chat = enabledChat("http://models/v1", true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        MemorySuggestionProvider provider = provider(builder.build(), chat);
        var frozen = provider.snapshot();
        chat.getLlm().setModel("changed-model");

        assertThatThrownBy(() -> provider.suggest(
                frozen,
                "以后回答请先给结论"
        )).isInstanceOfSatisfying(
                MemorySuggestionException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("MEMORY_SUGGESTION_RUNTIME_MISMATCH")
        );
        server.verify();
    }

    private static ChatProperties enabledChat(
            String baseUrl,
            boolean localEndpoint
    ) {
        ChatProperties chat = new ChatProperties();
        chat.getLlm().setEnabled(true);
        chat.getLlm().setBaseUrl(baseUrl);
        chat.getLlm().setModel("deepseek-chat");
        chat.getLlm().setLocalEndpoint(localEndpoint);
        return chat;
    }

    private static MemorySuggestionProvider provider(
            RestClient client,
            ChatProperties chat
    ) {
        return new OpenAiCompatibleMemorySuggestionProvider(
                client,
                new ObjectMapper(),
                chat,
                new MemorySuggestionProperties(
                        false,
                        "test-worker",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(45),
                        3,
                        3,
                        "extractor-v1",
                        "prompt-v1"
                )
        );
    }
}
