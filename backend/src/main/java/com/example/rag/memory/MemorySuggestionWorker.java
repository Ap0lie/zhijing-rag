package com.example.rag.memory;

import com.example.rag.memory.MemorySuggestionService.ClaimedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnBean(MemorySuggestionService.class)
@ConditionalOnProperty(
        prefix = "rag.memory.suggestion",
        name = "worker-enabled",
        havingValue = "true"
)
class MemorySuggestionWorker {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MemorySuggestionWorker.class);

    private final MemorySuggestionService jobs;
    private final MemorySuggestionProvider provider;
    private final ExecutorService executor;
    private final AtomicBoolean busy = new AtomicBoolean();

    MemorySuggestionWorker(
            MemorySuggestionService jobs,
            MemorySuggestionProvider provider,
            @Qualifier("memorySuggestionExecutor") ExecutorService executor
    ) {
        this.jobs = jobs;
        this.provider = provider;
        this.executor = executor;
    }

    @Scheduled(
            fixedDelayString = "${rag.memory.suggestion.poll-interval:PT1S}"
    )
    void poll() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.submit(this::processOne);
        } catch (RejectedExecutionException rejected) {
            busy.set(false);
            LOGGER.warn("Memory suggestion executor rejected scheduled work");
        }
    }

    private void processOne() {
        ClaimedJob claim;
        try {
            jobs.reconcile();
            claim = jobs.claim();
        } catch (RuntimeException exception) {
            LOGGER.error("Memory suggestion claim failed", exception);
            busy.set(false);
            return;
        }
        if (claim == null) {
            busy.set(false);
            return;
        }
        try {
            if (!jobs.heartbeat(claim)) {
                throw leaseLost();
            }
            var suggestions = provider.suggest(
                    claim.snapshot(),
                    jobs.input(claim)
            );
            if (!jobs.heartbeat(claim)) {
                throw leaseLost();
            }
            jobs.complete(claim, suggestions);
        } catch (RuntimeException exception) {
            jobs.fail(claim, exception);
            LOGGER.warn(
                    "Memory suggestion Job {} attempt {} failed: {}",
                    claim.id(),
                    claim.attemptCount(),
                    exception.getMessage()
            );
        } finally {
            busy.set(false);
        }
    }

    private static MemorySuggestionException leaseLost() {
        return new MemorySuggestionException(
                "MEMORY_SUGGESTION_LEASE_LOST",
                "记忆建议任务租约已失效"
        );
    }
}
