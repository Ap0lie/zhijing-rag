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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_units")
public class SourceUnitEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision_id", nullable = false)
    private DocumentRevisionEntity revision;

    @Column(name = "unit_order", nullable = false)
    private int unitOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_kind", nullable = false, length = 32)
    private SourceUnitKind unitKind;

    @Column(name = "stable_address", nullable = false, length = 500)
    private String stableAddress;

    @Column(name = "canonical_text", nullable = false, columnDefinition = "text")
    private String canonicalText;

    @Column(name = "canonical_text_hash", nullable = false, length = 64)
    private String canonicalTextHash;

    @Column(name = "normalization_version", nullable = false, length = 64)
    private String normalizationVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_metadata", nullable = false, columnDefinition = "jsonb")
    private String labelMetadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SourceUnitEntity() {
    }

    public SourceUnitEntity(
            UUID id,
            DocumentRevisionEntity revision,
            int unitOrder,
            SourceUnitKind unitKind,
            String stableAddress,
            String canonicalText,
            String canonicalTextHash,
            String normalizationVersion,
            String labelMetadata
    ) {
        this.id = id;
        this.documentId = revision.getDocument().getId();
        this.revision = revision;
        this.unitOrder = unitOrder;
        this.unitKind = unitKind;
        this.stableAddress = stableAddress;
        this.canonicalText = canonicalText;
        this.canonicalTextHash = canonicalTextHash;
        this.normalizationVersion = normalizationVersion;
        this.labelMetadata = labelMetadata;
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

    public int getUnitOrder() {
        return unitOrder;
    }

    public SourceUnitKind getUnitKind() {
        return unitKind;
    }

    public String getStableAddress() {
        return stableAddress;
    }

    public String getCanonicalText() {
        return canonicalText;
    }

    public String getCanonicalTextHash() {
        return canonicalTextHash;
    }

    public String getNormalizationVersion() {
        return normalizationVersion;
    }

    public String getLabelMetadata() {
        return labelMetadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
