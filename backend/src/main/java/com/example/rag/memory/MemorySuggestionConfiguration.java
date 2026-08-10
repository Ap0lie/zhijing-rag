package com.example.rag.memory;

import com.example.rag.chat.ChatProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(MemorySuggestionProperties.class)
class MemorySuggestionConfiguration {

    @Bean
    MemorySuggestionProvider memorySuggestionProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            ChatProperties chatProperties,
            MemorySuggestionProperties suggestionProperties
    ) {
        if (suggestionProperties.leaseDuration().compareTo(
                chatProperties.getLlm().getTimeout().plusSeconds(5)
        ) <= 0) {
            throw new IllegalArgumentException(
                    "Memory suggestion leaseDuration must exceed "
                            + "the LLM timeout by more than 5 seconds"
            );
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                chatProperties.getLlm().getTimeout()
        );
        requestFactory.setReadTimeout(chatProperties.getLlm().getTimeout());
        RestClient client = builder
                .baseUrl(chatProperties.getLlm().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new OpenAiCompatibleMemorySuggestionProvider(
                client,
                objectMapper,
                chatProperties,
                suggestionProperties
        );
    }

    @Bean(destroyMethod = "close")
    ExecutorService memorySuggestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
