package com.example.rag.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties({
        SearchProperties.class,
        ModelServiceProperties.class
})
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class SearchConfiguration {

    @Bean
    RestClient openSearchRestClient(RestClient.Builder builder, SearchProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getRequestTimeout());
        requestFactory.setReadTimeout(properties.getRequestTimeout());
        return builder
                .baseUrl(properties.getEndpoint())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean(destroyMethod = "close")
    @Qualifier("searchBranchExecutor")
    ExecutorService searchBranchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    EmbeddingProvider embeddingProvider(ModelServiceProperties properties) {
        return new HttpEmbeddingProvider(properties.getEmbedding());
    }

    @Bean
    RerankProvider rerankProvider(ModelServiceProperties properties) {
        return new HttpRerankProvider(properties.getRerank());
    }
}
