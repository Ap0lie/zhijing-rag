package com.example.rag.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class RetrievalConfigurationContracts {

    private RetrievalConfigurationContracts() {
    }

    public enum RetrievalMode {
        BM25,
        HYBRID
    }

    public record CreateRetrievalProfileRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String version,
            @NotNull RetrievalMode mode,
            @NotNull @Min(1) @Max(100) Integer defaultPageSize,
            @NotNull @Min(1) @Max(100) Integer maxPageSize,
            @NotNull @Min(1) @Max(200) Integer bm25TopK,
            @NotNull @Min(0) @Max(200) Integer vectorTopK,
            @NotNull @Min(1) @Max(1000) Integer rrfRankConstant,
            @NotNull @Min(0) @Max(200) Integer rerankTopK,
            @NotNull @Min(1) @Max(50) Integer evidenceTopK,
            @NotNull @Min(0) @Max(6000) Integer parentTokenBudget
    ) {
    }

    public record RetrievalConfigurationResponse(
            CurrentPublicationView currentPublication,
            ActiveManifestView activeManifest,
            List<IndexConfigView> indexConfigs,
            List<RetrievalProfileView> profiles,
            GoldenBaselineView goldenBaseline
    ) {
    }

    public record CurrentPublicationView(
            String profileVersion,
            long publicationEventId,
            Instant publishedAt
    ) {
    }

    public record ActiveManifestView(
            long indexGeneration,
            String indexName,
            String indexConfigVersion,
            String status
    ) {
    }

    public record IndexConfigView(
            String version,
            String schemaVersion,
            String analyzer,
            String embeddingProviderKey,
            String embeddingInputFormatVersion,
            String embeddingNormalizationVersion,
            String embeddingModel,
            String embeddingRevision,
            Integer vectorDimensions,
            String distance,
            Integer hnswM,
            Integer hnswEfConstruction,
            Instant createdAt
    ) {
        boolean vectorEnabled() {
            return embeddingModel != null;
        }
    }

    public record RetrievalProfileView(
            String version,
            RetrievalMode mode,
            int defaultPageSize,
            int maxPageSize,
            int bm25TopK,
            int vectorTopK,
            int rrfRankConstant,
            int rerankTopK,
            int evidenceTopK,
            int parentTokenBudget,
            Instant createdAt
    ) {
    }

    public record GoldenBaselineView(
            String datasetVersion,
            int caseCount,
            String status,
            Instant generatedAt,
            boolean reportAvailable,
            List<GoldenSliceView> slices
    ) {
    }

    public record GoldenSliceView(
            String name,
            int caseCount,
            Double candidateHitAt50
    ) {
    }
}
