package com.example.rag.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chunks")
public class ChunkEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id", nullable = false)
    private DocumentRevisionEntity revision;

    @Column(name = "parent_chunk_id")
    private UUID parentChunkId;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type", nullable = false, length = 8)
    private ChunkType chunkType;

    @Column(name = "chunk_order", nullable = false)
    private int chunkOrder;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "heading_path", nullable = false, columnDefinition = "text")
    private String headingPath;

    @Column(name = "start_block_order", nullable = false)
    private int startBlockOrder;

    @Column(name = "end_block_order", nullable = false)
    private int endBlockOrder;

    @Column(name = "character_count", nullable = false)
    private int characterCount;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "token_counter_version", nullable = false, length = 64)
    private String tokenCounterVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunking_profile_version", nullable = false)
    private ChunkingProfileEntity chunkingProfile;

    @Column(name = "parser_version", nullable = false, length = 64)
    private String parserVersion;

    @Column(name = "chunker_version", nullable = false, length = 64)
    private String chunkerVersion;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false)
    private boolean searchable;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChunkEntity() {
    }

    public ChunkEntity(
            UUID id,
            DocumentRevisionEntity revision,
            UUID parentChunkId,
            ChunkType chunkType,
            int chunkOrder,
            String text,
            String headingPath,
            int startBlockOrder,
            int endBlockOrder,
            int tokenCount,
            String tokenCounterVersion,
            ChunkingProfileEntity chunkingProfile,
            String parserVersion,
            String chunkerVersion,
            String contentHash
    ) {
        this.id = id;
        this.documentId = revision.getDocument().getId();
        this.revision = revision;
        this.parentChunkId = parentChunkId;
        this.chunkType = chunkType;
        this.chunkOrder = chunkOrder;
        this.text = text;
        this.headingPath = headingPath;
        this.startBlockOrder = startBlockOrder;
        this.endBlockOrder = endBlockOrder;
        this.characterCount = text.codePointCount(0, text.length());
        this.tokenCount = tokenCount;
        this.tokenCounterVersion = tokenCounterVersion;
        this.chunkingProfile = chunkingProfile;
        this.parserVersion = parserVersion;
        this.chunkerVersion = chunkerVersion;
        this.contentHash = contentHash;
        this.searchable = chunkType == ChunkType.CHILD;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public DocumentRevisionEntity getRevision() {
        return revision;
    }

    public UUID getParentChunkId() {
        return parentChunkId;
    }

    public ChunkType getChunkType() {
        return chunkType;
    }

    public int getChunkOrder() {
        return chunkOrder;
    }

    public String getText() {
        return text;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public int getStartBlockOrder() {
        return startBlockOrder;
    }

    public int getEndBlockOrder() {
        return endBlockOrder;
    }

    public int getCharacterCount() {
        return characterCount;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public String getTokenCounterVersion() {
        return tokenCounterVersion;
    }

    public ChunkingProfileEntity getChunkingProfile() {
        return chunkingProfile;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getChunkerVersion() {
        return chunkerVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public boolean isSearchable() {
        return searchable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
