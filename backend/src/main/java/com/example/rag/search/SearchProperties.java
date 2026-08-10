package com.example.rag.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("rag.search")
public class SearchProperties {

    private boolean enabled;
    private String endpoint = "http://localhost:9200";
    private String indexAlias = "rag-child-chunks";
    private String indexPrefix = "rag-child-chunks";
    private int bulkSize = 500;
    private int embeddingBatchSize = 32;
    private int embeddingIndexConcurrency = 2;
    private int embeddingQueryCacheMaxEntries = 2_048;
    private Duration embeddingQueryCacheTtl = Duration.ofMinutes(10);
    private long embeddingArtifactMaxBytes = 2L * 1024 * 1024 * 1024;
    private Duration embeddingArtifactRetention = Duration.ofDays(30);
    private Duration embeddingArtifactCleanupDelay = Duration.ofSeconds(30);
    private Duration embeddingArtifactTouchInterval = Duration.ofHours(1);
    private Duration requestTimeout = Duration.ofMillis(2500);
    private int modelFailureThreshold = 3;
    private Duration modelCircuitBreakDuration = Duration.ofSeconds(30);
    private boolean generationWorkerEnabled;
    private String generationWorkerId = "generation-worker";
    private Duration generationLeaseDuration = Duration.ofMinutes(2);
    private Duration generationRetention = Duration.ofHours(24);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = required(endpoint, "endpoint");
    }

    public String getIndexAlias() {
        return indexAlias;
    }

    public void setIndexAlias(String indexAlias) {
        this.indexAlias = required(indexAlias, "index-alias");
    }

    public String getIndexPrefix() {
        return indexPrefix;
    }

    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = required(indexPrefix, "index-prefix");
    }

    public int getBulkSize() {
        return bulkSize;
    }

    public void setBulkSize(int bulkSize) {
        if (bulkSize < 1 || bulkSize > 2_000) {
            throw new IllegalArgumentException("rag.search.bulk-size must be between 1 and 2000");
        }
        this.bulkSize = bulkSize;
    }

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        if (embeddingBatchSize < 1 || embeddingBatchSize > 256) {
            throw new IllegalArgumentException(
                    "rag.search.embedding-batch-size must be between 1 and 256"
            );
        }
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public int getEmbeddingIndexConcurrency() {
        return embeddingIndexConcurrency;
    }

    public void setEmbeddingIndexConcurrency(int value) {
        if (value < 1 || value > 4) {
            throw new IllegalArgumentException(
                    "rag.search.embedding-index-concurrency must be between 1 and 4"
            );
        }
        embeddingIndexConcurrency = value;
    }

    public int getEmbeddingQueryCacheMaxEntries() {
        return embeddingQueryCacheMaxEntries;
    }

    public void setEmbeddingQueryCacheMaxEntries(int value) {
        if (value < 1 || value > 16_384) {
            throw new IllegalArgumentException(
                    "rag.search.embedding-query-cache-max-entries must be between 1 and 16384"
            );
        }
        embeddingQueryCacheMaxEntries = value;
    }

    public Duration getEmbeddingQueryCacheTtl() {
        return embeddingQueryCacheTtl;
    }

    public void setEmbeddingQueryCacheTtl(Duration value) {
        embeddingQueryCacheTtl = positive(value, "embedding-query-cache-ttl");
    }

    public long getEmbeddingArtifactMaxBytes() {
        return embeddingArtifactMaxBytes;
    }

    public void setEmbeddingArtifactMaxBytes(long value) {
        if (value < 1) {
            throw new IllegalArgumentException(
                    "rag.search.embedding-artifact-max-bytes must be positive"
            );
        }
        embeddingArtifactMaxBytes = value;
    }

    public Duration getEmbeddingArtifactRetention() {
        return embeddingArtifactRetention;
    }

    public void setEmbeddingArtifactRetention(Duration value) {
        embeddingArtifactRetention = positive(value, "embedding-artifact-retention");
    }

    public Duration getEmbeddingArtifactCleanupDelay() {
        return embeddingArtifactCleanupDelay;
    }

    public void setEmbeddingArtifactCleanupDelay(Duration value) {
        embeddingArtifactCleanupDelay = positive(value, "embedding-artifact-cleanup-delay");
    }

    public Duration getEmbeddingArtifactTouchInterval() {
        return embeddingArtifactTouchInterval;
    }

    public void setEmbeddingArtifactTouchInterval(Duration value) {
        embeddingArtifactTouchInterval = positive(value, "embedding-artifact-touch-interval");
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = positive(requestTimeout, "request-timeout");
    }

    public int getModelFailureThreshold() {
        return modelFailureThreshold;
    }

    public void setModelFailureThreshold(int modelFailureThreshold) {
        if (modelFailureThreshold < 1 || modelFailureThreshold > 100) {
            throw new IllegalArgumentException(
                    "rag.search.model-failure-threshold must be between 1 and 100"
            );
        }
        this.modelFailureThreshold = modelFailureThreshold;
    }

    public Duration getModelCircuitBreakDuration() {
        return modelCircuitBreakDuration;
    }

    public void setModelCircuitBreakDuration(Duration modelCircuitBreakDuration) {
        this.modelCircuitBreakDuration = positive(
                modelCircuitBreakDuration,
                "model-circuit-break-duration"
        );
    }

    public boolean isGenerationWorkerEnabled() {
        return generationWorkerEnabled;
    }

    public void setGenerationWorkerEnabled(boolean generationWorkerEnabled) {
        this.generationWorkerEnabled = generationWorkerEnabled;
    }

    public String getGenerationWorkerId() {
        return generationWorkerId;
    }

    public void setGenerationWorkerId(String generationWorkerId) {
        this.generationWorkerId = required(generationWorkerId, "generation-worker-id");
    }

    public Duration getGenerationLeaseDuration() {
        return generationLeaseDuration;
    }

    public void setGenerationLeaseDuration(Duration generationLeaseDuration) {
        this.generationLeaseDuration = positive(generationLeaseDuration, "generation-lease-duration");
    }

    public Duration getGenerationRetention() {
        return generationRetention;
    }

    public void setGenerationRetention(Duration generationRetention) {
        this.generationRetention = positive(generationRetention, "generation-retention");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rag.search." + name + " is required");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("rag.search." + name + " must be positive");
        }
        return value;
    }
}
