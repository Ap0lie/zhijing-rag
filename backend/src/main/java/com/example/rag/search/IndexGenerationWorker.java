package com.example.rag.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "rag.search",
        name = "generation-worker-enabled",
        havingValue = "true"
)
class IndexGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexGenerationWorker.class);

    private final IndexGenerationService generations;

    IndexGenerationWorker(IndexGenerationService generations) {
        this.generations = generations;
    }

    @Scheduled(fixedDelayString = "${rag.search.generation-poll-delay-ms:1000}")
    void poll() {
        generations.claim().ifPresent(claim -> {
            try {
                generations.build(claim);
            } catch (RuntimeException exception) {
                generations.fail(claim, exception);
                log.error(
                        "Index generation {} build failed",
                        claim.generation(),
                        exception
                );
            }
        });
    }
}
