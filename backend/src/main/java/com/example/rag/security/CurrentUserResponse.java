package com.example.rag.security;

import com.example.rag.persistence.UserRole;

import java.util.UUID;

public record CurrentUserResponse(UUID id, String username, UserRole role) {

    public static CurrentUserResponse from(PlatformUserPrincipal principal) {
        return new CurrentUserResponse(principal.id(), principal.getUsername(), principal.role());
    }
}
