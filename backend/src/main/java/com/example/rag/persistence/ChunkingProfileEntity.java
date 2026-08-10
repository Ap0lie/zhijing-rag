package com.example.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "chunking_profiles")
public class ChunkingProfileEntity {

    @Id
    @Column(length = 64)
    private String version;

    @Column(name = "parent_max_tokens", nullable = false)
    private int parentMaxTokens;

    @Column(name = "child_max_tokens", nullable = false)
    private int childMaxTokens;

    @Column(name = "child_overlap_tokens", nullable = false)
    private int childOverlapTokens;

    @Column(name = "token_counter_version", nullable = false, length = 64)
    private String tokenCounterVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChunkingProfileEntity() {
    }

    public ChunkingProfileEntity(
            String version,
            int parentMaxTokens,
            int childMaxTokens,
            int childOverlapTokens,
            String tokenCounterVersion
    ) {
        this.version = version;
        this.parentMaxTokens = parentMaxTokens;
        this.childMaxTokens = childMaxTokens;
        this.childOverlapTokens = childOverlapTokens;
        this.tokenCounterVersion = tokenCounterVersion;
    }

    public String getVersion() {
        return version;
    }

    public int getParentMaxTokens() {
        return parentMaxTokens;
    }

    public int getChildMaxTokens() {
        return childMaxTokens;
    }

    public int getChildOverlapTokens() {
        return childOverlapTokens;
    }

    public String getTokenCounterVersion() {
        return tokenCounterVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
