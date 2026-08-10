package com.example.rag.user;

import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRole;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        UserRole role,
        boolean enabled,
        Instant createdAt,
        long securityVersion,
        UserAccessContracts.AccessSummary accessSummary
) {
    static UserResponse from(UserEntity user) {
        return from(user, UserAccessContracts.AccessSummary.empty());
    }

    static UserResponse from(UserEntity user, UserAccessContracts.AccessSummary accessSummary) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt(),
                user.getSecurityVersion(),
                accessSummary
        );
    }
}
