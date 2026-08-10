package com.example.rag.search;

import com.example.rag.search.ModelServiceContracts.ModelServiceHealth;
import com.example.rag.search.ModelServiceContracts.ModelServicesHealthResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/admin/model-services")
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class AdminModelServiceController {

    private final EmbeddingProvider embedding;
    private final RerankProvider rerank;

    AdminModelServiceController(EmbeddingProvider embedding, RerankProvider rerank) {
        this.embedding = embedding;
        this.rerank = rerank;
    }

    @GetMapping("/health")
    ModelServicesHealthResponse health() {
        CompletableFuture<ModelServiceHealth> embeddingHealth = CompletableFuture.supplyAsync(
                () -> check("EMBEDDING", embedding.descriptor(), embedding::health)
        );
        CompletableFuture<ModelServiceHealth> rerankHealth = CompletableFuture.supplyAsync(
                () -> check("RERANK", rerank.descriptor(), rerank::health)
        );
        return new ModelServicesHealthResponse(List.of(
                embeddingHealth.join(),
                rerankHealth.join()
        ));
    }

    private static ModelServiceHealth check(
            String type,
            ModelDescriptor descriptor,
            Runnable health
    ) {
        Instant checkedAt = Instant.now();
        if (!descriptor.enabled()) {
            return new ModelServiceHealth(
                    type,
                    "DISABLED",
                    descriptor.model(),
                    descriptor.revision(),
                    descriptor.dimensions(),
                    null,
                    checkedAt,
                    null
            );
        }
        long started = System.nanoTime();
        try {
            health.run();
            return new ModelServiceHealth(
                    type,
                    "UP",
                    descriptor.model(),
                    descriptor.revision(),
                    descriptor.dimensions(),
                    elapsedMillis(started),
                    checkedAt,
                    null
            );
        } catch (RuntimeException exception) {
            return new ModelServiceHealth(
                    type,
                    "DOWN",
                    descriptor.model(),
                    descriptor.revision(),
                    descriptor.dimensions(),
                    elapsedMillis(started),
                    checkedAt,
                    exception instanceof ModelResponseException
                            ? "MODEL_RESPONSE_INVALID"
                            : "MODEL_UNAVAILABLE"
            );
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
