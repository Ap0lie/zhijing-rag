package com.example.rag.memory;

import com.example.rag.document.SourceLocatorResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MemoryContracts {

    private MemoryContracts() {
    }

    public record MemorySettingsView(
            boolean enabled,
            boolean suggestionEnabled,
            long version,
            Instant updatedAt
    ) {
    }

    public record UpdateMemorySettingsRequest(
            boolean enabled,
            boolean suggestionEnabled,
            @Min(0) long expectedVersion
    ) {
    }

    public record MemorySourceInput(
            @NotBlank
            @Pattern(regexp = "CHAT_SESSION|CHAT_MESSAGE|DOCUMENT_SPAN")
            String sourceType,
            UUID chatSessionId,
            UUID chatMessageId,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId
    ) {
    }

    public record CreateMemoryRequest(
            @NotBlank
            @Pattern(regexp = "USER_PREFERENCE|USER_FACT|SESSION_SUMMARY|DOCUMENT_FACT")
            String memoryType,
            @NotBlank @Size(max = 160) String memoryKey,
            @NotBlank @Size(max = 1200) String content,
            boolean candidate,
            @Future Instant expiresAt,
            @NotNull @Size(max = 20) List<@Valid MemorySourceInput> sources
    ) {
    }

    public record ReplaceMemoryRequest(
            @NotBlank @Size(max = 160) String memoryKey,
            @NotBlank @Size(max = 1200) String content,
            @Future Instant expiresAt,
            @NotNull @Size(max = 20) List<@Valid MemorySourceInput> sources,
            @Size(max = 500) String reason
    ) {
    }

    public record MemoryActionRequest(
            @Size(max = 500) String reason
    ) {
    }

    public record ForgetMemoryRequest(
            @NotBlank
            @Pattern(regexp = "FORGET_MEMORY")
            String confirmation,
            @Size(max = 500) String reason
    ) {
    }

    public record MemoryItemView(
            UUID id,
            String memoryType,
            String memoryKey,
            String content,
            String status,
            int versionNumber,
            String origin,
            UUID supersedesMemoryId,
            int sourceCount,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record MemorySourceView(
            UUID id,
            String sourceType,
            UUID chatSessionId,
            UUID chatMessageId,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId,
            Instant sourceDeletedAt,
            Instant createdAt,
            String documentFormat,
            SourceLocatorResponse sourceLocator,
            String sourceLabel
    ) {
        public MemorySourceView(
                UUID id,
                String sourceType,
                UUID chatSessionId,
                UUID chatMessageId,
                UUID documentId,
                UUID revisionId,
                UUID childChunkId,
                UUID sourceSpanId,
                Instant sourceDeletedAt,
                Instant createdAt
        ) {
            this(
                    id,
                    sourceType,
                    chatSessionId,
                    chatMessageId,
                    documentId,
                    revisionId,
                    childChunkId,
                    sourceSpanId,
                    sourceDeletedAt,
                    createdAt,
                    null,
                    null,
                    null
            );
        }
    }

    public record MemoryEventView(
            long id,
            String eventType,
            UUID relatedMemoryId,
            String reason,
            Instant createdAt
    ) {
    }

    public record UserProfileEntry(
            UUID memoryId,
            String key,
            String value,
            int versionNumber
    ) {
    }

    public record UserProfileView(
            boolean memoryEnabled,
            List<UserProfileEntry> preferences
    ) {
    }

    public record AdminMemorySummaryView(
            long userSettings,
            long enabledUsers,
            long suggestionEnabledUsers,
            Map<String, Long> itemsByType,
            Map<String, Long> itemsByStatus
    ) {
    }
}
