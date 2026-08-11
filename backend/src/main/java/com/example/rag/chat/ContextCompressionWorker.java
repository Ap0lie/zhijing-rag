package com.example.rag.chat;

import com.example.rag.chat.ContextCompressionService.ClaimedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = {"enabled", "context-compression.worker-enabled"},
        havingValue = "true",
        matchIfMissing = true
)
class ContextCompressionWorker {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ContextCompressionWorker.class);

    private final ContextCompressionService compression;
    private final ExecutorService executor;
    private final AtomicBoolean busy = new AtomicBoolean();

    ContextCompressionWorker(
            ContextCompressionService compression,
            @Qualifier("contextCompressionExecutor") ExecutorService executor
    ) {
        this.compression = compression;
        this.executor = executor;
    }

    @Scheduled(
            fixedDelayString =
                    "${rag.chat.context-compression.poll-interval:PT1S}"
    )
    void poll() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.submit(this::processOne);
        } catch (RejectedExecutionException rejected) {
            busy.set(false);
            LOGGER.warn("Context compression executor rejected work");
        }
    }

    private void processOne() {
        ClaimedJob claim = null;
        try {
            claim = compression.claim();
            if (claim == null) {
                return;
            }
            if (!compression.heartbeat(claim)) {
                throw new ContextCompressionException(
                        "CONTEXT_SUMMARY_LEASE_LOST",
                        "摘要任务租约已失效"
                );
            }
            compression.process(claim);
        } catch (RuntimeException exception) {
            if (claim != null) {
                compression.fail(claim, exception);
                LOGGER.warn(
                        "Context summary Job {} attempt {} failed: {}",
                        claim.id(), claim.attemptCount(), exception.getMessage()
                );
            } else {
                LOGGER.error("Context summary claim failed", exception);
            }
        } finally {
            busy.set(false);
        }
    }
}
