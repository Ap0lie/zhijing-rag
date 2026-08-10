package com.example.rag.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GraphProperties.class)
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class GraphConfiguration {

    @Bean
    GraphExtractionProvider graphExtractionProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            GraphProperties properties
    ) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                properties.getExtraction().getTimeout()
        );
        requestFactory.setReadTimeout(
                properties.getExtraction().getTimeout()
        );
        RestClient client = builder
                .baseUrl(properties.getExtraction().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new OpenAiCompatibleGraphExtractionProvider(
                client,
                objectMapper,
                properties
        );
    }

    @Bean
    GlobalReportProvider globalReportProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            GraphProperties properties
    ) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                properties.getExtraction().getTimeout()
        );
        requestFactory.setReadTimeout(
                properties.getExtraction().getTimeout()
        );
        RestClient client = builder
                .baseUrl(properties.getExtraction().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new OpenAiCompatibleGlobalReportProvider(
                client,
                objectMapper,
                properties
        );
    }

    @Bean
    CommunityDetectionClient communityDetectionClient(
            RestClient.Builder builder,
            GraphProperties properties
    ) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                properties.getCommunity().getTimeout()
        );
        requestFactory.setReadTimeout(
                properties.getCommunity().getTimeout()
        );
        RestClient client = builder
                .baseUrl(properties.getCommunity().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new CommunityDetectionClient(client);
    }

    @Bean
    GraphAssembler graphAssembler(
            CommunityDetectionClient communities,
            GraphProperties properties
    ) {
        return new GraphAssembler(communities, properties);
    }
}
