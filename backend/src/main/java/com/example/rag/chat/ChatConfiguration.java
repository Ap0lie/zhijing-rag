package com.example.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ChatConfiguration {

    @Bean
    ChatModelProvider chatModelProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            ChatProperties properties
    ) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getLlm().getTimeout());
        requestFactory.setReadTimeout(properties.getLlm().getTimeout());
        RestClient client = builder
                .baseUrl(properties.getLlm().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new OpenAiCompatibleChatModelProvider(client, objectMapper, properties);
    }

    @Bean
    ChatQueryPlanner chatQueryPlanner(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            ChatProperties properties
    ) {
        return new OpenAiCompatibleChatQueryPlanner(
                builder, objectMapper, properties
        );
    }

    @Bean(destroyMethod = "close")
    @Qualifier("chatExecutor")
    ExecutorService chatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
