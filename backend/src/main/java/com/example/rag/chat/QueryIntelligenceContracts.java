package com.example.rag.chat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class QueryIntelligenceContracts {

    private QueryIntelligenceContracts() {
    }

    public record CreateProfileRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String version,
            boolean enabled,
            @NotBlank @Size(max = 64) String plannerProvider,
            @NotBlank @Size(max = 160) String plannerModel,
            @NotBlank @Size(max = 160) String plannerRevision,
            @NotBlank @Size(max = 64) String promptVersion,
            @NotBlank @Size(max = 64) String schemaVersion,
            @NotBlank
            @Pattern(regexp = "CONSERVATIVE_UTF8|MODEL_TOKENIZER")
            String tokenCounterType,
            @NotBlank @Size(max = 64) String tokenCounterVersion,
            @Min(1024) @Max(1048576) int modelContextTokens,
            @Min(1) @Max(12) int historyMessageLimit,
            @Min(64) @Max(2048) int historyTokenBudget,
            @Min(1) @Max(20) int historyContextPercent,
            @Min(1) @Max(3) int maxSubQueries,
            @Min(1) @Max(2) int maxRetrievalRounds,
            @Min(0) @Max(2) int plannerCallLimit,
            @Min(100) @Max(30000) int timeoutMs,
            @NotBlank @Pattern(regexp = "ORIGINAL_QUERY")
            String fallbackMode,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record ProfileView(
            String version,
            boolean enabled,
            String plannerProvider,
            String plannerModel,
            String plannerRevision,
            String promptVersion,
            String schemaVersion,
            String tokenCounterType,
            String tokenCounterVersion,
            int modelContextTokens,
            int historyMessageLimit,
            int historyTokenBudget,
            int historyContextPercent,
            int maxSubQueries,
            int maxRetrievalRounds,
            int plannerCallLimit,
            int timeoutMs,
            String fallbackMode,
            String reason,
            boolean published,
            Instant createdAt
    ) {
        int effectiveHistoryTokenBudget() {
            return Math.min(
                    historyTokenBudget,
                    Math.floorDiv(
                            modelContextTokens * historyContextPercent,
                            100
                    )
            );
        }
    }

    public record RuntimeView(
            boolean llmEnabled,
            String plannerProvider,
            String plannerModel,
            String plannerRevision,
            String promptVersion,
            String schemaVersion,
            String supportedCounterType,
            String supportedCounterVersion
    ) {
    }

    public record PublicationRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String profileVersion,
            @NotNull UUID intentRunId,
            @NotNull UUID multiTurnRunId,
            @NotBlank @Pattern(regexp = "PUBLISH_QUERY_PROFILE")
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record RollbackRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
            String profileVersion,
            @NotBlank @Pattern(regexp = "ROLLBACK_QUERY_PROFILE")
            String confirmation,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record PublicationEventView(
            long eventId,
            String profileVersion,
            String previousProfileVersion,
            UUID intentRunId,
            UUID multiTurnRunId,
            String action,
            String reason,
            Instant createdAt
    ) {
    }
}
