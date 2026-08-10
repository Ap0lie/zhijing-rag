package com.example.rag.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class UserAccessContracts {

    private UserAccessContracts() {
    }

    public record AccessSummary(
            boolean platformAccess,
            long publicDocuments,
            long ownedDocuments,
            long explicitGrants,
            long totalDocuments
    ) {
        public static AccessSummary empty() {
            return new AccessSummary(false, 0, 0, 0, 0);
        }
    }

    public record UserAccessView(UserResponse user, AccessSummary access) {
    }

    public record DocumentGrantView(
            UUID documentId,
            String title,
            String visibility,
            UUID ownerUserId,
            String ownerUsername,
            long aclVersion,
            String accessSource,
            boolean granted,
            boolean editable
    ) {
    }

    public record DocumentGrantPage(
            UUID userId,
            int page,
            int size,
            long total,
            List<DocumentGrantView> items
    ) {
    }

    public record DocumentGrantChange(
            @NotNull UUID documentId,
            boolean granted,
            @Positive long expectedAclVersion
    ) {
    }

    public record DocumentGrantUpdateRequest(
            @NotEmpty @Size(max = 100) List<@Valid DocumentGrantChange> changes,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey,
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotBlank String confirmation,
            @Positive long expectedUserSecurityVersion
    ) {
    }

    public record DocumentGrantUpdateResult(
            UserAccessView user,
            List<UUID> changedDocumentIds,
            boolean replayed
    ) {
    }
}
