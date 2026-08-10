package com.example.rag.search;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class EmbeddingCacheContracts {

    private EmbeddingCacheContracts() {
    }

    public record QueryCacheStats(
            long entries,
            long maxEntries,
            long hits,
            long misses,
            long evictions,
            long coalesced,
            long modelCalls,
            long savedModelCalls
    ) {
    }

    public record ArtifactCacheStats(
            long entries,
            long bytes,
            long maxBytes,
            long hits,
            long misses,
            long evictions,
            long corruptions,
            long modelCalls,
            long savedModelCalls
    ) {
    }

    public record ModelCacheStats(
            String providerKey,
            String model,
            String revision,
            int dimensions,
            long queryEntries,
            long artifactEntries,
            long artifactBytes
    ) {
    }

    public record EmbeddingCacheStatsResponse(
            QueryCacheStats query,
            ArtifactCacheStats artifacts,
            List<ModelCacheStats> models,
            Instant checkedAt
    ) {
    }

    public record ClearEmbeddingCacheRequest(
            @NotBlank @Size(max = 64) String providerKey,
            @NotBlank @Size(max = 255) String model,
            @NotBlank @Size(max = 255) String revision,
            @NotBlank @Pattern(regexp = "CLEAR") String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record ClearEmbeddingCacheResponse(
            long deletedArtifacts,
            long invalidatedQueryEntries,
            long freedBytes
    ) {
    }
}
