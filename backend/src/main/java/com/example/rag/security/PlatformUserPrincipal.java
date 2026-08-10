package com.example.rag.security;

import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class PlatformUserPrincipal implements UserDetails {

    private final UUID id;
    private final String username;
    private final String passwordHash;
    private final UserRole role;
    private final boolean enabled;

    private PlatformUserPrincipal(
            UUID id,
            String username,
            String passwordHash,
            UserRole role,
            boolean enabled
    ) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public static PlatformUserPrincipal from(UserEntity user) {
        return new PlatformUserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRole(),
                user.isEnabled()
        );
    }

    public UUID id() {
        return id;
    }

    public UserRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof PlatformUserPrincipal principal && id.equals(principal.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
