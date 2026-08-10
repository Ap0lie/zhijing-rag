package com.example.rag.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class SearchProjectionSynchronizer {

    private final SearchIndexService indexes;
    private final IndexGenerationService generations;

    SearchProjectionSynchronizer(
            SearchIndexService indexes,
            IndexGenerationService generations
    ) {
        this.indexes = indexes;
        this.generations = generations;
    }

    @Scheduled(
            fixedDelayString = "${rag.search.projection-delay-ms:10000}",
            initialDelayString = "${rag.search.projection-delay-ms:10000}"
    )
    void synchronize() {
        indexes.synchronizeProjections();
        generations.maintain();
    }
}
