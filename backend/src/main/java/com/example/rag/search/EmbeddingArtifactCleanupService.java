package com.example.rag.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class EmbeddingArtifactCleanupService {

    private static final Logger log =
            LoggerFactory.getLogger(EmbeddingArtifactCleanupService.class);

    private final EmbeddingCacheService cache;

    EmbeddingArtifactCleanupService(EmbeddingCacheService cache) {
        this.cache = cache;
    }

    @Scheduled(
            fixedDelayString = "${rag.search.embedding-artifact-cleanup-delay:PT30S}",
            initialDelayString = "${rag.search.embedding-artifact-cleanup-delay:PT30S}"
    )
    void cleanup() {
        try {
            cache.cleanup();
        } catch (RuntimeException exception) {
            log.warn("Embedding artifact cleanup failed", exception);
        }
    }
}
