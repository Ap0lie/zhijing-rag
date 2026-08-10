package com.example.rag.pipeline;

import com.example.rag.persistence.PipelineStage;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("rag.pipeline")
public record PipelineProperties(
        boolean workerEnabled,
        String workerId,
        PipelineStage workerStage,
        Duration pollInterval,
        Duration leaseDuration,
        Duration heartbeatInterval,
        Duration taskTimeout,
        int maxAttempts,
        String pipelineVersion,
        String parserVersion,
        String chunkerVersion,
        Chunking chunkingProfile
) {
    public PipelineProperties {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("rag.pipeline.worker-id must contain 1-128 characters");
        }
        if (workerStage != PipelineStage.PARSE && workerStage != PipelineStage.INDEX) {
            throw new IllegalArgumentException("rag.pipeline.worker-stage must be PARSE or INDEX");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                || leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()
                || taskTimeout == null || taskTimeout.isNegative() || taskTimeout.isZero()) {
            throw new IllegalArgumentException("Pipeline durations must be positive");
        }
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("Pipeline heartbeat must be shorter than its lease");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("rag.pipeline.max-attempts must be positive");
        }
        if (pipelineVersion == null || pipelineVersion.isBlank()
                || parserVersion == null || parserVersion.isBlank()
                || chunkerVersion == null || chunkerVersion.isBlank()
                || chunkingProfile == null) {
            throw new IllegalArgumentException("Pipeline versions and chunking profile are required");
        }
    }

    public record Chunking(
            String version,
            int parentMaxTokens,
            int childMaxTokens,
            int overlapTokens,
            String tokenCounterVersion
    ) {
        public Chunking {
            if (version == null || version.isBlank() || tokenCounterVersion == null || tokenCounterVersion.isBlank()
                    || parentMaxTokens < 1 || childMaxTokens < 1 || childMaxTokens > parentMaxTokens
                    || overlapTokens < 0 || overlapTokens >= childMaxTokens) {
                throw new IllegalArgumentException("Invalid rag.pipeline.chunking-profile");
            }
        }
    }
}
