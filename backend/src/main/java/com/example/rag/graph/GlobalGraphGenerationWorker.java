package com.example.rag.graph;

import com.example.rag.graph.GlobalGraphContracts.ClaimedGeneration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "global-worker-enabled",
        havingValue = "true"
)
@ConditionalOnProperty(
        prefix = "rag.search",
        name = "enabled",
        havingValue = "true"
)
class GlobalGraphGenerationWorker {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalGraphGenerationWorker.class);

    private final GlobalGraphGenerationService generations;
    private final GraphProperties properties;

    GlobalGraphGenerationWorker(
            GlobalGraphGenerationService generations,
            GraphProperties properties
    ) {
        this.generations = generations;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${rag.graph.poll-interval:PT1S}")
    void poll() {
        generations.cleanupExpired();
        generations.claim().ifPresent(this::process);
    }

    private void process(ClaimedGeneration claim) {
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        try (ScheduledExecutorService heartbeat =
                     Executors.newSingleThreadScheduledExecutor(
                             Thread.ofPlatform()
                                     .daemon()
                                     .name("global-graph-heartbeat")
                                     .factory()
                     )) {
            long heartbeatMs = properties.getHeartbeatInterval().toMillis();
            heartbeat.scheduleAtFixedRate(
                    () -> {
                        try {
                            if (!generations.heartbeat(claim)) {
                                leaseValid.set(false);
                            }
                        } catch (RuntimeException exception) {
                            leaseValid.set(false);
                            LOGGER.warn(
                                    "Global Generation {} heartbeat failed",
                                    claim.generation(),
                                    exception
                            );
                        }
                    },
                    heartbeatMs,
                    heartbeatMs,
                    TimeUnit.MILLISECONDS
            );
            generations.build(claim, leaseValid);
        } catch (RuntimeException exception) {
            generations.fail(claim, exception);
            LOGGER.error(
                    "Global Generation {} build failed",
                    claim.generation(),
                    exception
            );
        }
    }
}
