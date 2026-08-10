package com.example.rag.graph;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class GraphRetrievalContracts {

    private GraphRetrievalContracts() {
    }

    public record CreateProfileRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String version,
            @NotNull @Min(1) @Max(5) Integer seedLimit,
            @NotNull @Min(1) @Max(2) Integer maxHops,
            @NotNull @Min(1) @Max(20) Integer entityLimit,
            @NotNull @Min(1) @Max(40) Integer edgeLimit,
            @NotNull @Min(1) @Max(30) Integer graphChildLimit,
            @NotNull @DecimalMin("0.001") @DecimalMax("4.0")
            Double graphWeight,
            @NotNull @Min(0) @Max(900) Integer graphContextTokenBudget,
            @NotNull @Min(0) @Max(15) Integer graphContextPercent,
            @NotNull @Min(50) @Max(1000) Integer statementTimeoutMs,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Pattern(regexp = "CREATE") String confirmation
    ) {
    }

    public record ReleaseProfileRequest(
            @NotBlank @Size(max = 64) String profileVersion,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank String confirmation
    ) {
    }

    public record ProfileView(
            String version,
            int seedLimit,
            int maxHops,
            int entityLimit,
            int edgeLimit,
            int graphChildLimit,
            double graphWeight,
            int graphContextTokenBudget,
            int graphContextPercent,
            int statementTimeoutMs,
            String reason,
            Instant createdAt
    ) {
    }

    public record PublicationView(
            String profileVersion,
            long publicationEventId,
            Instant publishedAt
    ) {
    }

    public record ConfigurationResponse(
            PublicationView currentPublication,
            Long activeGraphGeneration,
            List<ProfileView> profiles
    ) {
    }
}
