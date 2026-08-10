package com.example.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "security_version", nullable = false)
    private long securityVersion = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserEntity() {
    }

    public UserEntity(String username, String passwordHash, UserRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getSecurityVersion() {
        return securityVersion;
    }

    public void changeRole(UserRole role) {
        if (this.role != role) {
            securityVersion++;
        }
        this.role = role;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            securityVersion++;
        }
        this.enabled = enabled;
    }

    public void changeSecurity(UserRole role, boolean enabled) {
        if (this.role != role || this.enabled != enabled) {
            securityVersion++;
        }
        this.role = role;
        this.enabled = enabled;
    }

    public void replacePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        securityVersion++;
    }
}
