package com.example.rag.pipeline;

import com.example.rag.pipeline.PipelineJobLeaseService.ClaimedJob;
import com.example.rag.pipeline.parser.ParseQuarantineException;
import com.example.rag.pipeline.parser.ParsedDocument;
import com.example.rag.persistence.PipelineStage;
import com.example.rag.search.SearchIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "rag.pipeline", name = "worker-enabled", havingValue = "true")
public class PipelineWorker {

    private static final Logger log = LoggerFactory.getLogger(PipelineWorker.class);

    private final PipelineJobLeaseService leases;
    private final PipelineArtifactService artifacts;
    private final ObjectProvider<SearchIndexService> searchIndex;
    private final PipelineProperties properties;
    private final PipelineWorkerHealthService workerHealth;

    public PipelineWorker(
            PipelineJobLeaseService leases,
            PipelineArtifactService artifacts,
            ObjectProvider<SearchIndexService> searchIndex,
            PipelineProperties properties,
            PipelineWorkerHealthService workerHealth
    ) {
        this.leases = leases;
        this.artifacts = artifacts;
        this.searchIndex = searchIndex;
        this.properties = properties;
        this.workerHealth = workerHealth;
    }

    @Scheduled(fixedDelayString = "${rag.pipeline.poll-interval:PT1S}")
    public void poll() {
        try {
            workerHealth.publishCapabilities();
            leases.claimNext().ifPresent(this::process);
        } catch (RuntimeException exception) {
            log.error("Pipeline worker poll failed", exception);
        }
    }

    private void process(ClaimedJob job) {
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        try (ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("pipeline-heartbeat").factory()
        ); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long heartbeatMs = properties.heartbeatInterval().toMillis();
            heartbeat.scheduleAtFixedRate(
                    () -> {
                        try {
                            workerHealth.publishCapabilities();
                            if (!leases.heartbeat(job.id(), job.attempt())) {
                                leaseValid.set(false);
                            }
                        } catch (RuntimeException exception) {
                            leaseValid.set(false);
                            log.warn("Heartbeat failed for pipeline job {}", job.id(), exception);
                        }
                    },
                    heartbeatMs,
                    heartbeatMs,
                    TimeUnit.MILLISECONDS
            );

            var future = executor.submit(() -> {
                process(job, leaseValid);
                return null;
            });
            try {
                future.get(properties.taskTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                leases.failOrRetry(
                        job.id(),
                        job.attempt(),
                        "TASK_TIMEOUT",
                        job.stage() + " processing exceeded " + properties.taskTimeout().toSeconds() + " seconds"
                );
                log.error("Pipeline job {} timed out; terminating the isolated worker", job.id());
                Runtime.getRuntime().halt(70);
                return;
            } catch (ExecutionException exception) {
                handleFailure(job, exception.getCause());
                return;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            leases.failOrRetry(
                    job.id(), job.attempt(), "WORKER_INTERRUPTED", "Pipeline worker was interrupted"
            );
        } catch (RuntimeException exception) {
            leases.failOrRetry(
                    job.id(), job.attempt(), "PIPELINE_ERROR", concise(exception.getMessage())
            );
            log.error("Pipeline job {} failed", job.id(), exception);
        }
    }

    private void process(ClaimedJob job, AtomicBoolean leaseValid)
            throws IOException, ParseQuarantineException, ParserProcessingException {
        if (job.stage() == PipelineStage.INDEX) {
            if (leaseValid.get()) {
                searchIndex.getObject().index(job);
            }
            return;
        }

        ParsedDocument parsed = artifacts.parse(job);
        if (leaseValid.get() && !artifacts.replaceArtifacts(job, parsed)) {
            log.info("Discarded result for pipeline job {} after its lease was lost", job.id());
        }
    }

    private void handleFailure(ClaimedJob job, Throwable failure) {
        if (job.stage() == PipelineStage.PARSE && failure instanceof ParseQuarantineException quarantine) {
            leases.quarantine(
                    job.id(), job.attempt(), quarantine.reason().name(), concise(quarantine.getMessage())
            );
            return;
        }
        if (job.stage() == PipelineStage.PARSE && failure instanceof ParserProcessingException parserFailure) {
            leases.failTerminal(job.id(), job.attempt(), parserFailure.code(), concise(parserFailure.getMessage()));
            return;
        }
        leases.failOrRetry(job.id(), job.attempt(), "PIPELINE_ERROR", concise(failure.getMessage()));
        log.error("Pipeline job {} failed", job.id(), failure);
    }

    private static String concise(String value) {
        return value == null || value.isBlank() ? "Pipeline processing failed" : value;
    }
}
