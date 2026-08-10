package com.example.rag.evaluation;

import com.example.rag.evaluation.EvaluationContracts.ClaimedRun;
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
        prefix = "rag.evaluation",
        name = "worker-enabled",
        havingValue = "true"
)
class EvaluationWorker {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(EvaluationWorker.class);

    private final EvaluationService evaluations;
    private final EvaluationObservabilityService observability;
    private final EvaluationDrillService drills;
    private final EvaluationProperties properties;

    EvaluationWorker(
            EvaluationService evaluations,
            EvaluationObservabilityService observability,
            EvaluationDrillService drills,
            EvaluationProperties properties
    ) {
        this.evaluations = evaluations;
        this.observability = observability;
        this.drills = drills;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${rag.evaluation.poll-interval:PT1S}")
    void poll() {
        try {
            if (!observability.evaluationMayClaim()) {
                return;
            }
            var drill = drills.claim();
            if (drill.isPresent()) {
                drills.process(drill.get());
                return;
            }
            evaluations.claim().ifPresent(this::process);
        } catch (RuntimeException exception) {
            LOGGER.error("Evaluation worker poll failed", exception);
        }
    }

    private void process(ClaimedRun run) {
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        try (ScheduledExecutorService heartbeat =
                     Executors.newSingleThreadScheduledExecutor(
                             Thread.ofPlatform()
                                     .daemon()
                                     .name("evaluation-heartbeat")
                                     .factory()
                     )) {
            long heartbeatMs = properties.heartbeatInterval().toMillis();
            heartbeat.scheduleAtFixedRate(
                    () -> {
                        try {
                            if (!evaluations.heartbeat(run)) {
                                leaseValid.set(false);
                            }
                        } catch (RuntimeException exception) {
                            leaseValid.set(false);
                            LOGGER.warn(
                                    "Evaluation Run {} heartbeat failed",
                                    run.id(), exception
                            );
                        }
                    },
                    heartbeatMs,
                    heartbeatMs,
                    TimeUnit.MILLISECONDS
            );
            while (leaseValid.get()
                    && !evaluations.cancellationRequested(run)) {
                if (!observability.evaluationMayClaim()) {
                    evaluations.yieldToOnlineChat(run);
                    return;
                }
                var next = evaluations.nextCase(run);
                if (next.isEmpty()) {
                    break;
                }
                if (!evaluations.completeCase(run, next.get())) {
                    leaseValid.set(false);
                }
            }
            if (leaseValid.get()) {
                evaluations.finish(run);
            }
        } catch (RuntimeException exception) {
            evaluations.fail(run, exception);
            LOGGER.error("Evaluation Run {} failed", run.id(), exception);
        }
    }
}
