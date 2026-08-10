package com.example.rag.document;

import com.example.rag.persistence.DocumentVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record DocumentAclUpdateRequest(
        @NotBlank @Size(max = 500) String title,
        @NotNull DocumentVisibility visibility,
        List<UUID> grantedUserIds,
        @Positive long expectedAclVersion,
        @Size(max = 500) String reason
) {
    public List<UUID> safeGrantedUserIds() {
        return grantedUserIds == null ? List.of() : grantedUserIds;
    }
}
