package com.example.rag.search;

import com.example.rag.projection.GenerationRecoveryProgress;
import com.example.rag.projection.ProjectionClosureStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IndexGenerationContracts {

    private IndexGenerationContracts() {
    }

    public record StartIndexBuildRequest(
            @NotBlank @Size(max = 64) String indexConfigVersion,
            @NotBlank @Pattern(regexp = "BUILD") String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record PublishGenerationRequest(
            @Min(1) long indexGeneration,
            @NotBlank @Size(max = 64) String profileVersion,
            @NotBlank @Pattern(regexp = "PUBLISH") String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record RollbackGenerationRequest(
            @Min(1) long indexGeneration,
            @NotBlank @Size(max = 64) String profileVersion,
            @NotBlank @Pattern(regexp = "ROLLBACK") String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record IndexGenerationsResponse(
            Long activeGeneration,
            List<IndexGenerationView> generations
    ) {
    }

    public record IndexGenerationView(
            UUID id,
            long indexGeneration,
            String indexName,
            String indexConfigVersion,
            String status,
            long expectedDocumentCount,
            long expectedChunkCount,
            long indexedChunkCount,
            long validVectorCount,
            double vectorCoverage,
            boolean readyCheckPassed,
            ProjectionClosureStatus closure,
            GenerationRecoveryProgress recovery,
            int buildAttempt,
            String failureCode,
            String failureReason,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant retentionUntil,
            Instant updatedAt
    ) {
    }
}
